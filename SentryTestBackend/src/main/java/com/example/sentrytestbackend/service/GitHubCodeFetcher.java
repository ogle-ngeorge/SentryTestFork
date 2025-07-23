
package com.example.sentrytestbackend.service;

import java.util.regex.Pattern;
import java.util.regex.Matcher;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * Service for fetching code snippets from GitHub using the GitHub API.
 * Given a file path and line number, returns a snippet of code around that line.
 */
@Service
public class GitHubCodeFetcher {
    // Bitbucket code fetcher wrapper for compatibility with controller
    public String getBitbucketCode(String stackTrace) {
        // For now, just use the GitHub code fetcher logic
        return getGithubCode(stackTrace);
    }
    @Value("${github.api.token}")
    private String githubApiToken;
    private final RestTemplate restTemplate = new RestTemplate();

    // Testing Purposes // 
    public static void main(String[] args){
        GitHubCodeFetcher fetcher = new GitHubCodeFetcher();
        String stackTrace = "[https://github.com/DoubtfulCoder/SentryTest/blob/backend/SentryTestBackend/src/main/java/com/example/sentrytestbackend/controller/TestController.java#L100] at jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:104)";
        String result = fetcher.getGithubCode(stackTrace);
        System.out.println(result);
    }

    // GETTER METHODS //
    public String getGithubCode(String stackTrace){
        ArrayNode githubLinks = fetchGithubLinks(stackTrace);
        StringBuilder allCodeSnippets = new StringBuilder(); // Builds code into one large string

        for (int i = 0; i < githubLinks.size(); i++){
            JsonNode linkNode = githubLinks.get(i);
            String githubLink = linkNode.asText();
            try {
                String codeSnippet = mapToGithubCode(githubLink, 20); // 20 lines of code context
                allCodeSnippets.append("Snippet for: ").append(githubLink).append("\n").append(codeSnippet).append("\n\n");
            } catch (org.springframework.web.client.HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                // Ignore links that are not found
                continue;
            } else {
                throw e; // Rethrow other errors
            }
        }
    }
    return allCodeSnippets.toString();
}

    // HELPER METHODS //

    // Fetches and parses StackTrace w/ Github Links
    // Requires use of getMostRecentStackTraceWithGithubLinks() from StackTraceGenerator
    // Returns JSONode of Github Links
    private ArrayNode fetchGithubLinks(String stackTrace){

        ObjectMapper mapper = new ObjectMapper();
        ArrayNode linksArray = mapper.createArrayNode();

        //Regex to find Github links inside brackets
        Pattern pattern = Pattern.compile("\\[(https://github\\.com/[^\\]]+)\\]");
        Matcher matcher = pattern.matcher(stackTrace);

        while (matcher.find()){
            String link = matcher.group(1); // 1 returns the link inside the brackets
            linksArray.add(TextNode.valueOf(link));
        }
        return linksArray;
    }

    // Fetches code snippet from Github source code codebase
    // Uses links to see where to look in github
    // Context determines how many lines to return
    private String mapToGithubCode(String githubLink, int context){
        
        // Example: https://github.com/owner/repo/blob/branch/path/File.java#L20

        // Sets up a pattern to know where to look for Github links
        Pattern pattern = Pattern.compile(
            "github\\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.+?)#L(\\d+)");
        Matcher matcher = pattern.matcher(githubLink);

        if (!matcher.find()){
            return "Invalid Github link";
        }
        // Matches the values to group found in parenthesis in pattern
        String owner = matcher.group(1);
        String repo = matcher.group(2);
        String branch = matcher.group(3);
        String filePath = matcher.group(4);
        int lineNumber = Integer.parseInt(matcher.group(5));
        String apiUrl = String.format("https://api.github.com/repos/%s/%s/contents/%s?ref=%s", owner, repo, filePath, branch);

        // Sets up API endpoint to send to Github API to recieve code
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "token " + githubApiToken);
        headers.set("Accept", "application/vnd.github.v3.raw"); // Tells Github to give RAW file contents

        HttpEntity<String> entity = new HttpEntity<>(headers); // <-- Move this line here


        // GEt request to get code lines
        ResponseEntity<String> response = restTemplate.exchange(apiUrl, HttpMethod.GET, entity, String.class); 
        String fileContent = response.getBody();
        String[] lines = fileContent.split("\n");

        // Calculate start and end lines for context
        int start = Math.max(0, lineNumber - context); // Start at lines before error line
        int end = Math.min(lines.length, lineNumber + context); // End at lines after error line

        StringBuilder snippet = new StringBuilder();
        for (int i = start; i < end; i++){
            snippet.append((i + 1)).append(": ").append(lines[i]).append("\n");
        }
        return snippet.toString();
    }
}

















