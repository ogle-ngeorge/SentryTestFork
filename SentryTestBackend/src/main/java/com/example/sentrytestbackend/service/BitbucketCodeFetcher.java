package com.example.sentrytestbackend.service;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

@Service
public class BitbucketCodeFetcher {

    /**
     * Bitbucket repo configuration. Set these via @Value or config for deployment.
     * Example:
     *   bitbucket.repo.url=https://bitbucket.org/<workspace>/<repo>
     *   bitbucket.repo.branch=main
     *   bitbucket.repo.srcRoot=src/main/java/
     */
    @Value("${bitbucket.repo.url}")
    private String bitbucketRepoUrl;
    @Value("${bitbucket.repo.branch}")
    private String bitbucketRepoBranch;
    @Value("${bitbucket.repo.srcRoot}")
    private String bitbucketRepoSrcRoot;
    @Value("${bitbucket.api.token}")
    private String bitbucketApiToken;
    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Builds a Bitbucket link for a given module, filename, and line number.
     * Used to map stack frames to source code in Bitbucket.
     * @param module Java package/module name (e.g. com.example.service)
     * @param filename Source file name (e.g. MyService.java)
     * @param lineno Line number in the file
     * @return Bitbucket URL to the file and line
     */
    public String buildBitbucketLink(String module, String filename, int lineno) {
        int lastDot = module.lastIndexOf('.');
        String packagePath = lastDot != -1 ? module.substring(0, lastDot).replace('.', '/') : "";
        String srcRoot = bitbucketRepoSrcRoot;
        if (!srcRoot.endsWith("/")) srcRoot += "/";
        if (!packagePath.isEmpty() && !packagePath.endsWith("/")) packagePath += "/";
        String fullPath = srcRoot + packagePath + filename;

        // Remove consecutive duplicate segments in the path (e.g., src/main/src/main/...)
        String[] parts = fullPath.split("/");
        StringBuilder dedupedPath = new StringBuilder();
        String prev = null;
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (!part.equals(prev)) {
                if (dedupedPath.length() > 0) dedupedPath.append("/");
                dedupedPath.append(part);
            }
            prev = part;
        }

        String bitbucketUrl = bitbucketRepoUrl + "/src/" + bitbucketRepoBranch + "/" + dedupedPath.toString();
        if (lineno != -1) {
            bitbucketUrl += "#lines-" + lineno;
        }
        // Only collapse double slashes after protocol, never in 'https://'
        bitbucketUrl = bitbucketUrl.replaceFirst("^(https?:)/+", "$1//");
        bitbucketUrl = bitbucketUrl.replaceAll("(?<!:)//+", "/");
        return bitbucketUrl;
    }

    /**
     * Fetches a code snippet from Bitbucket for a given file and line number, using the commit as of the error timestamp.
     * @param bitbucketLink Bitbucket file link in the format: https://bitbucket.org/{workspace}/{repo}/src/{branch}/{path}#lines-{line}
     * @param context Number of lines of context before and after the error line
     * @param errorTimestamp ISO8601 timestamp of the error occurrence
     * @return Code snippet as a String, or error message if link is invalid
     */
    public String mapToBitbucketCode(String bitbucketLink, int context, String errorTimestamp) {
        Pattern pattern = Pattern.compile(
            "bitbucket.org/([^/]+)/([^/]+)/src/([^/]+)/(.+?)#lines-(\\d+)");
        Matcher matcher = pattern.matcher(bitbucketLink);

        if (!matcher.find()) {
            System.out.println("[DEBUG] Invalid Bitbucket link: " + bitbucketLink);
            return "Invalid Bitbucket link";
        }

        String workspace = matcher.group(1);
        String repo = matcher.group(2);
        String branch = matcher.group(3);
        String filePath = matcher.group(4);
        int lineNumber = Integer.parseInt(matcher.group(5));

        // Get commit hash for file as of errorTimestamp
        String commitHash = getCommitHashForDate(workspace, repo, filePath, errorTimestamp);
        String ref = (commitHash != null && !commitHash.isEmpty()) ? commitHash : branch;

        String apiUrl = String.format(
            "https://api.bitbucket.org/2.0/repositories/%s/%s/src/%s/%s",
            workspace, repo, ref, filePath
        );
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + bitbucketApiToken);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(apiUrl, HttpMethod.GET, entity, String.class);
            System.out.println("[DEBUG] Bitbucket API URL: " + apiUrl);
            System.out.println("[DEBUG] Bitbucket API HTTP status: " + response.getStatusCodeValue());
            String fileContent = response.getBody();
            String[] lines = fileContent.split("\n");

            int start = Math.max(0, lineNumber - context);
            int end = Math.min(lines.length, lineNumber + context);

            StringBuilder snippet = new StringBuilder();
            for (int i = start; i < end; i++) {
                snippet.append((i + 1)).append(": ").append(lines[i]).append("\n");
            }
            return snippet.toString();
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            System.out.println("[DEBUG] Bitbucket API URL: " + apiUrl);
            System.out.println("[DEBUG] Bitbucket API HTTP status: " + e.getStatusCode().value());
            System.out.println("[DEBUG] Bitbucket API error body: " + e.getResponseBodyAsString());
            return "Bitbucket file not found for one or more frames.";
        } catch (Exception e) {
            System.out.println("[DEBUG] Bitbucket API URL: " + apiUrl);
            System.out.println("[DEBUG] Bitbucket API error: " + e.getMessage());
            return "Error fetching Bitbucket code: " + e.getMessage();
        }
    }

    /**
     * Maps a Sentry stack trace containing Bitbucket links to code snippets for each frame.
     * @param stackTrace Sentry stack trace with Bitbucket links
     * @param context Number of lines of context before and after the error line
     * @param errorTimestamp ISO8601 timestamp of the error occurrence
     * @return Combined code snippets for all Bitbucket links in the stack trace
     */
    public String getBitbucketCodeFromStackTrace(String stackTrace, int context, String errorTimestamp) {
        StringBuilder allSnippets = new StringBuilder();
        Pattern linkPattern = Pattern.compile("https://bitbucket.org/[^\\s\\]]+#lines-\\d+");
        Matcher matcher = linkPattern.matcher(stackTrace);
        while (matcher.find()) {
            String bitbucketLink = matcher.group();
            // Only fetch code for files in your project root
            if (bitbucketLink.contains("SentryTestBackend/")) {
                String snippet = mapToBitbucketCode(bitbucketLink, context, errorTimestamp);
                allSnippets.append("Snippet for: ").append(bitbucketLink).append("\n");
                allSnippets.append(snippet).append("\n\n");
            }
        }
        if (allSnippets.length() == 0) {
            return "No Bitbucket links found in stack trace.";
        }
        return allSnippets.toString();
    }

    /**
     * Fetches the commit hash for a file in Bitbucket as of a specific ISO8601 date.
     * @param workspace Bitbucket workspace (user or team)
     * @param repo Bitbucket repository name
     * @param filePath Path to the file in the repository
     * @param untilIsoDate ISO8601 timestamp to search for the latest commit before this date
     * @return Commit hash as a String, or null if not found
     */
    public String getCommitHashForDate(String workspace, String repo, String filePath, String untilIsoDate) {
        try {
            String apiUrl = String.format(
                "https://api.bitbucket.org/2.0/repositories/%s/%s/commits?path=%s&q=date<=\"%s\"&pagelen=1",
                workspace, repo, filePath, untilIsoDate
            );
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + bitbucketApiToken);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(apiUrl, HttpMethod.GET, entity, String.class);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode commits = mapper.readTree(response.getBody()).path("values");
            if (commits.isArray() && commits.size() > 0) {
                return commits.get(0).path("hash").asText();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
