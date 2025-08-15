package com.example.sentrytestbackend.service;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.List;
import java.util.ArrayList;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

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
    @Value("${bitbucket.api.email}")
    private String bitbucketApiEmail;
    @Value("${bitbucket.api.token}")
    private String bitbucketApiToken;
    @Value("${bitbucket.sentry-demo-app.api.token:}")
    private String sentryDemoAppApiToken;
    @Value("${bitbucket.workspace}")
    private String bitbucketWorkspace;
    @Value("${bitbucket.repo.name}")
    private String bitbucketRepoName;
    private final RestTemplate restTemplate = new RestTemplate();
    
    /**
     * Searches for a file in a Bitbucket workspace using the search API
     * This is useful when static URL building fails due to complex project structures
     * 
     * @param workspace Bitbucket workspace name
     * @param filename The filename to search for (e.g., MainActivity.kt)
     * @param packageName Optional Java package name for more precise matching
     * @return List of matching file paths, or empty list if none found
     */
    public List<String> searchFileInWorkspace(String workspace, String filename, String packageName) {
        try {
            // Build search query: filename + optional package constraint
            String searchQuery = filename;
            if (packageName != null && !packageName.isEmpty()) {
                // Convert package to path for better matching
                String packagePath = packageName.replace('.', '/');
                searchQuery = filename + " path:" + packagePath;
            }
            
            String searchUrl = String.format(
                "https://api.bitbucket.org/2.0/workspaces/%s/search/code?search_query=%s",
                workspace, 
                java.net.URLEncoder.encode(searchQuery, "UTF-8")
            );
            
            System.out.println("[BitbucketCodeFetcher] Searching for file: " + searchQuery + " in workspace: " + workspace);
            System.out.println("[BitbucketCodeFetcher] Search URL: " + searchUrl);
            
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", createBasicAuthHeaderForWorkspace(workspace));
            HttpEntity<String> entity = new HttpEntity<>(headers);
            
            ResponseEntity<String> response = restTemplate.exchange(searchUrl, HttpMethod.GET, entity, String.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return parseSearchResults(response.getBody(), filename);
            } else {
                System.err.println("[BitbucketCodeFetcher] Search failed with status: " + response.getStatusCode());
                return List.of();
            }
            
        } catch (Exception e) {
            System.err.println("[BitbucketCodeFetcher] Error searching for file: " + e.getMessage());
            return List.of();
        }
    }
    
    /**
     * Parses Bitbucket search API results to extract file paths
     */
    private List<String> parseSearchResults(String jsonResponse, String targetFilename) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(jsonResponse);
            JsonNode values = root.path("values");
            
            List<String> filePaths = new ArrayList<>();
            
            if (values.isArray()) {
                for (JsonNode result : values) {
                    JsonNode file = result.path("file");
                    String path = file.path("path").asText();
                    String name = file.path("name").asText();
                    
                    // Only include exact filename matches
                    if (targetFilename.equals(name)) {
                        filePaths.add(path);
                        System.out.println("[BitbucketCodeFetcher] Found matching file: " + path);
                    }
                }
            }
            
            System.out.println("[BitbucketCodeFetcher] Search found " + filePaths.size() + " matching files");
            return filePaths;
            
        } catch (Exception e) {
            System.err.println("[BitbucketCodeFetcher] Error parsing search results: " + e.getMessage());
            return List.of();
        }
    }
    
    /**
     * Searches for a file in a specific repository (more precise than workspace search)
     * This is the PREFERRED method for dynamic file discovery
     * 
     * @param workspace Bitbucket workspace name
     * @param repository Repository name
     * @param filename The filename to search for
     * @param packageName Optional package name for filtering
     * @return List of matching file paths with full repository paths
     */
    public List<String> searchFileInRepository(String workspace, String repository, String filename, String packageName) {
        try {
            // Build search query
            String searchQuery = filename;
            if (packageName != null && !packageName.isEmpty()) {
                String packagePath = packageName.replace('.', '/');
                searchQuery = filename + " path:" + packagePath;
            }
            
            String searchUrl = String.format(
                "https://api.bitbucket.org/2.0/repositories/%s/%s/search/code?search_query=%s",
                workspace, repository,
                java.net.URLEncoder.encode(searchQuery, "UTF-8")
            );
            
            System.out.println("[BitbucketCodeFetcher] Repository search: " + searchQuery + " in " + workspace + "/" + repository);
            System.out.println("[BitbucketCodeFetcher] Search URL: " + searchUrl);
            
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", createBasicAuthHeaderForWorkspace(workspace));
            HttpEntity<String> entity = new HttpEntity<>(headers);
            
            ResponseEntity<String> response = restTemplate.exchange(searchUrl, HttpMethod.GET, entity, String.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return parseSearchResults(response.getBody(), filename);
            } else {
                System.err.println("[BitbucketCodeFetcher] Repository search failed with status: " + response.getStatusCode());
                return List.of();
            }
            
        } catch (Exception e) {
            System.err.println("[BitbucketCodeFetcher] Error searching repository: " + e.getMessage());
            return List.of();
        }
    }
    
    /**
     * Browses repository directory structure to find files dynamically
     * Useful when search API fails or for discovering project structure
     * 
     * @param workspace Bitbucket workspace
     * @param repository Repository name  
     * @param branch Branch name
     * @param startPath Starting path (e.g., "" for root, "app/" for subdirectory)
     * @param targetFilename File to search for
     * @return List of full paths to matching files
     */
    public List<String> browseRepositoryForFile(String workspace, String repository, String branch, String startPath, String targetFilename) {
        try {
            String browsePath = startPath != null ? startPath : "";
            String browseUrl = String.format(
                "https://api.bitbucket.org/2.0/repositories/%s/%s/src/%s/%s",
                workspace, repository, branch, browsePath
            );
            
            System.out.println("[BitbucketCodeFetcher] Browsing repository path: " + browseUrl);
            
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", createBasicAuthHeaderForWorkspace(workspace));
            HttpEntity<String> entity = new HttpEntity<>(headers);
            
            ResponseEntity<String> response = restTemplate.exchange(browseUrl, HttpMethod.GET, entity, String.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return parseDirectoryContents(response.getBody(), workspace, repository, branch, browsePath, targetFilename);
            } else {
                System.err.println("[BitbucketCodeFetcher] Browse failed with status: " + response.getStatusCode());
                return List.of();
            }
            
        } catch (Exception e) {
            System.err.println("[BitbucketCodeFetcher] Error browsing repository: " + e.getMessage());
            return List.of();
        }
    }
    
    /**
     * Parses directory listing response and recursively searches for target file
     */
    private List<String> parseDirectoryContents(String jsonResponse, String workspace, String repository, String branch, String currentPath, String targetFilename) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(jsonResponse);
            JsonNode values = root.path("values");
            
            List<String> foundPaths = new ArrayList<>();
            
            if (values.isArray()) {
                for (JsonNode item : values) {
                    String type = item.path("type").asText();
                    String name = item.path("name").asText();
                    String path = item.path("path").asText();
                    
                    if ("commit_file".equals(type) && targetFilename.equals(name)) {
                        // Found the target file
                        foundPaths.add(path);
                        System.out.println("[BitbucketCodeFetcher] Found file via browsing: " + path);
                    } else if ("commit_directory".equals(type)) {
                        // Recursively search subdirectories (limited depth for performance)
                        int currentDepth = currentPath.split("/").length;
                        if (currentDepth < 10) { // Prevent infinite recursion
                            List<String> subResults = browseRepositoryForFile(workspace, repository, branch, path + "/", targetFilename);
                            foundPaths.addAll(subResults);
                        }
                    }
                }
            }
            
            return foundPaths;
            
        } catch (Exception e) {
            System.err.println("[BitbucketCodeFetcher] Error parsing directory contents: " + e.getMessage());
            return List.of();
        }
    }
    
    /**
     * ENHANCED FILE DISCOVERY - Combines multiple strategies for maximum success
     * 1. Try repository-specific search first (fastest, most accurate)
     * 2. Fall back to workspace search if needed
     * 3. Fall back to directory browsing if search fails
     * 
     * @param workspace Bitbucket workspace
     * @param repository Repository name
     * @param branch Branch name
     * @param filename Target filename
     * @param packageName Optional package for filtering
     * @return Best matching file path or null if not found
     */
    public String discoverFileLocation(String workspace, String repository, String branch, String filename, String packageName) {
        System.out.println("[BitbucketCodeFetcher] Starting enhanced file discovery for: " + filename);
        
        // Strategy 1: Repository-specific search (preferred)
        List<String> repoResults = searchFileInRepository(workspace, repository, filename, packageName);
        if (!repoResults.isEmpty()) {
            String bestMatch = repoResults.get(0); // Use first result
            System.out.println("[BitbucketCodeFetcher] Discovery successful via repository search: " + bestMatch);
            return bestMatch;
        }
        
        // Strategy 2: Workspace-wide search (fallback)
        List<String> workspaceResults = searchFileInWorkspace(workspace, filename, packageName);
        if (!workspaceResults.isEmpty()) {
            String bestMatch = workspaceResults.get(0);
            System.out.println("[BitbucketCodeFetcher] Discovery successful via workspace search: " + bestMatch);
            return bestMatch;
        }
        
        // Strategy 3: Directory browsing (last resort)
        List<String> browseResults = browseRepositoryForFile(workspace, repository, branch, "", filename);
        if (!browseResults.isEmpty()) {
            String bestMatch = browseResults.get(0);
            System.out.println("[BitbucketCodeFetcher] Discovery successful via browsing: " + bestMatch);
            return bestMatch;
        }
        
        System.err.println("[BitbucketCodeFetcher] Enhanced discovery failed for: " + filename);
        return null;
    }
    
    /**
     * Creates Basic Authentication header for Atlassian API
     * Format: Authorization: Basic base64(email:token)
     */
    private String createBasicAuthHeader() {
        String credentials = bitbucketApiEmail + ":" + bitbucketApiToken;
        String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encodedCredentials;
    }
    
    /**
     * Creates Basic Authentication header for specific workspace/repo
     * Uses workspace-specific token if available, otherwise uses default
     */
    private String createBasicAuthHeaderForWorkspace(String workspace) {
        String token = selectTokenForWorkspace(workspace);
        String credentials = bitbucketApiEmail + ":" + token;
        String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encodedCredentials;
    }
    
    /**
     * Creates Basic Authentication header for specific workspace and repository
     * Uses repository-specific token selection logic
     */
    private String createBasicAuthHeaderForWorkspaceRepo(String workspace, String repo) {
        String token = selectTokenForWorkspaceRepo(workspace, repo);
        String credentials = bitbucketApiEmail + ":" + token;
        String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encodedCredentials;
    }
    
    /**
     * Selects appropriate API token for a workspace
     */
    private String selectTokenForWorkspace(String workspace) {
        // Use workspace-specific token if available, otherwise use default
        if ("sentry-codespace-api".equals(workspace) && sentryDemoAppApiToken != null && !sentryDemoAppApiToken.isEmpty()) {
            return sentryDemoAppApiToken;
        }
        return bitbucketApiToken;
    }

    /**
     * Builds a Bitbucket link for a given module, filename, and line number.
     * Used to map stack frames to source code in Bitbucket.
     * @param module Java package/module name (e.g. com.example.service)
     * @param filename Source file name (e.g. MyService.java)
     * @param lineno Line number in the file
     * @return Bitbucket URL to the file and line
     */
    public String buildBitbucketLink(String module, String filename, int lineno) {
        return buildBitbucketLinkWithCommit(module, filename, lineno, null);
    }
    
    /**
     * Enhanced version that can use either commit hash or branch
     * @param module Java package/module name
     * @param filename Source file name
     * @param lineno Line number
     * @param commitHash Optional commit hash (if null, uses branch)
     * @return Bitbucket URL pointing to specific commit or branch
     */
    public String buildBitbucketLinkWithCommit(String module, String filename, int lineno, String commitHash) {
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

        // Use commit hash if provided, otherwise use branch
        String ref = (commitHash != null && !commitHash.isEmpty() && !"unknown".equals(commitHash)) 
            ? commitHash : bitbucketRepoBranch;
            
        System.out.println("[DEBUG] Link generation - commitHash: '" + commitHash + "', bitbucketRepoBranch: '" + bitbucketRepoBranch + "', final ref: '" + ref + "'");
        
        String bitbucketUrl = bitbucketRepoUrl + "/src/" + ref + "/" + dedupedPath.toString();
        System.out.println("[DEBUG] Before line number - URL: '" + bitbucketUrl + "'");
        
        if (lineno != -1) {
            bitbucketUrl += "#lines-" + lineno;
        }
        System.out.println("[DEBUG] Before cleanup - URL: '" + bitbucketUrl + "'");
        
        // Only collapse double slashes after protocol, never in 'https://'
        bitbucketUrl = bitbucketUrl.replaceFirst("^(https?:)/+", "$1//");
        bitbucketUrl = bitbucketUrl.replaceAll("(?<!:)//+", "/");
        System.out.println("[DEBUG] Final URL: '" + bitbucketUrl + "'");
        
        if (commitHash != null && !commitHash.isEmpty()) {
            System.out.println("[DEBUG] Generated commit-specific link: " + bitbucketUrl);
        }
        
        return bitbucketUrl;
    }

    /**
     * Builds a Bitbucket link against a specific repository configuration (per-project override).
     */
    public String buildBitbucketLinkWithCommitForRepo(String module, String filename, int lineno, RepoConfig repo, String commitHash) {
        int lastDot = module.lastIndexOf('.');
        String packagePath = lastDot != -1 ? module.substring(0, lastDot).replace('.', '/') : "";
        String srcRoot = repo.getSrcRoot();
        if (!srcRoot.endsWith("/")) srcRoot += "/";
        if (!packagePath.isEmpty() && !packagePath.endsWith("/")) packagePath += "/";
        String fullPath = srcRoot + packagePath + filename;

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

        String ref = (commitHash != null && !commitHash.isEmpty() && !"unknown".equals(commitHash)) ? commitHash : repo.getBranch();
        String bitbucketUrl = repo.getRepoUrl() + "/src/" + ref + "/" + dedupedPath.toString();
        if (lineno != -1) {
            bitbucketUrl += "#lines-" + lineno;
        }
        // Normalize
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
        String refFromLink = matcher.group(3);
        String filePath = matcher.group(4);
        int lineNumber = Integer.parseInt(matcher.group(5));

        // Respect explicit commit hashes in the incoming link; only resolve when it's a branch name
        String ref = refFromLink;
        boolean looksLikeCommit = refFromLink.matches("^[a-fA-F0-9]{7,40}$");
        if (!looksLikeCommit) {
            String commitHash = getCommitHashForDate(workspace, repo, filePath, errorTimestamp);
            if (commitHash != null && !commitHash.isEmpty()) {
                ref = commitHash;
            }
        }

        String apiUrl = String.format(
            "https://api.bitbucket.org/2.0/repositories/%s/%s/src/%s/%s",
            workspace, repo, ref, filePath
        );
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", createBasicAuthHeaderForWorkspaceRepo(workspace, repo));
        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(apiUrl, HttpMethod.GET, entity, String.class);
            System.out.println("[DEBUG] Bitbucket API URL: " + apiUrl);
            System.out.println("[DEBUG] Bitbucket API HTTP status: " + response.getStatusCodeValue());
            String fileContent = response.getBody();
            String[] lines = fileContent.split("\n");

            // Convert target lineNumber (1-based) to 0-based index
            int targetIdx = Math.max(0, lineNumber - 1);
            int startIdx = Math.max(0, targetIdx - context);
            int endExclusive = Math.min(lines.length, targetIdx + context + 1);

            StringBuilder snippet = new StringBuilder();
            for (int i = startIdx; i < endExclusive; i++) {
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
            if (bitbucketLink.contains(bitbucketRepoSrcRoot)) {
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
     * Variant that filters snippets using a provided srcRoot (per-project filtering).
     */
    public String getBitbucketCodeFromStackTrace(String stackTrace, int context, String errorTimestamp, String srcRootFilter) {
        StringBuilder allSnippets = new StringBuilder();
        Pattern linkPattern = Pattern.compile("https://bitbucket.org/[^\\s\\]]+#lines-\\d+");
        Matcher matcher = linkPattern.matcher(stackTrace);
        while (matcher.find()) {
            String bitbucketLink = matcher.group();
            if (srcRootFilter == null || srcRootFilter.isEmpty() || bitbucketLink.contains(srcRootFilter)) {
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
     * Gets the commit hash that was active at a specific timestamp using Bitbucket API.
     * Provides multiple strategies for finding the most accurate commit.
     */
    
    /**
     * Strategy 1: Get commit for specific file at timestamp
     * Finds the last commit that modified this file before the error occurred
     */
    public String getCommitHashForFileAtTime(String workspace, String repo, String filePath, String errorTimestamp) {
        try {
            // Get commits for this specific file, with date filtering
            String apiUrl = String.format(
                "https://api.bitbucket.org/2.0/repositories/%s/%s/commits?path=%s&pagelen=50",
                workspace, repo, filePath
            );
            
            System.out.println("[DEBUG] Getting commit for file: " + filePath + " at time: " + errorTimestamp);
            
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + selectTokenForWorkspaceRepo(workspace, repo));
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(apiUrl, HttpMethod.GET, entity, String.class);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode rootNode = mapper.readTree(response.getBody());
            JsonNode commits = rootNode.path("values");
            
            // Find the commit that was active at the error timestamp
            String bestCommit = findCommitByTimestamp(commits, errorTimestamp);
            if (bestCommit != null) {
                System.out.println("[DEBUG] Found file-specific commit: " + bestCommit);
                return bestCommit;
            }
            
        } catch (Exception e) {
            System.out.println("[DEBUG] File-specific commit lookup failed: " + e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Strategy 2: Get branch HEAD at specific timestamp
     * Finds what commit the branch was pointing to when the error occurred
     */
    public String getBranchCommitAtTime(String workspace, String repo, String branch, String errorTimestamp) {
        try {
            // Get all commits on branch, find the one active at timestamp
            String apiUrl = String.format(
                "https://api.bitbucket.org/2.0/repositories/%s/%s/commits/%s?pagelen=100",
                workspace, repo, branch
            );
            
            System.out.println("[DEBUG] Getting branch commit for: " + branch + " at time: " + errorTimestamp);
            
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + selectTokenForWorkspaceRepo(workspace, repo));
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(apiUrl, HttpMethod.GET, entity, String.class);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode rootNode = mapper.readTree(response.getBody());
            JsonNode commits = rootNode.path("values");
            
            String bestCommit = findCommitByTimestamp(commits, errorTimestamp);
            if (bestCommit != null) {
                System.out.println("[DEBUG] Found branch commit: " + bestCommit);
                return bestCommit;
            }
            
        } catch (Exception e) {
            System.out.println("[DEBUG] Branch commit lookup failed: " + e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Strategy 3: Smart commit detection with multiple approaches
     * Combines file-specific and branch-specific strategies
     */
    public String getSmartCommitForError(String workspace, String repo, String filePath, String errorTimestamp) {
        System.out.println("[DEBUG] Starting smart commit detection for error at: " + errorTimestamp);
        
        // Strategy 1: Try file-specific commit
        String fileCommit = getCommitHashForFileAtTime(workspace, repo, filePath, errorTimestamp);
        if (fileCommit != null) {
            System.out.println("[DEBUG] Using file-specific commit: " + fileCommit);
            return fileCommit;
        }
        
        // Strategy 2: Try branch commit at timestamp
        String branchCommit = getBranchCommitAtTime(workspace, repo, bitbucketRepoBranch, errorTimestamp);
        if (branchCommit != null) {
            System.out.println("[DEBUG] Using branch commit: " + branchCommit);
            return branchCommit;
        }
        
        // Strategy 3: Last resort - return null instead of fallback
        System.out.println("[DEBUG] No commit found for error timestamp");
        return null;
    }
    
    /**
     * Helper: Find the best commit based on timestamp comparison
     */
    private String findCommitByTimestamp(JsonNode commits, String errorTimestamp) {
        try {
            java.time.Instant errorTime = java.time.Instant.parse(errorTimestamp);
            
            for (JsonNode commit : commits) {
                String commitDateStr = commit.path("date").asText();
                java.time.Instant commitTime = java.time.Instant.parse(commitDateStr);
                
                // If this commit happened before or at the error time, it's a candidate
                if (!commitTime.isAfter(errorTime)) {
                    String commitHash = commit.path("hash").asText();
                    System.out.println("[DEBUG] Found commit " + commitHash + " at " + commitDateStr + " (before error)");
                    return commitHash;
                }
            }
        } catch (Exception e) {
            System.out.println("[DEBUG] Timestamp parsing failed: " + e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Legacy method - kept for backward compatibility
     */
    public String getCommitHashForDate(String workspace, String repo, String filePath, String untilIsoDate) {
        try {
            String apiUrl = String.format(
                "https://api.bitbucket.org/2.0/repositories/%s/%s/commits?path=%s&q=date<=\"%s\"&pagelen=1",
                workspace, repo, filePath, untilIsoDate
            );
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", createBasicAuthHeader());
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
    
    /**
     * Gets the current commit hash from Bitbucket API (branch HEAD)
     * This ensures we always use deployment-based commits, never local git
     */
    public String getCurrentCommitFromBitbucket() {
        // Use automatically detected branch instead of hardcoded one
        String currentBranch = getCurrentBranch();
        
        try {
            // Get current HEAD of the detected branch from Bitbucket
            String apiUrl = String.format(
                "https://api.bitbucket.org/2.0/repositories/%s/%s/commits/%s?pagelen=1",
                bitbucketWorkspace, bitbucketRepoName, currentBranch
            );
            
            System.out.println("[BitbucketCodeFetcher] Checking Bitbucket API for branch HEAD: " + currentBranch);
            
            HttpHeaders headers = new HttpHeaders();
            if (bitbucketApiToken != null && !bitbucketApiToken.isEmpty()) {
                headers.set("Authorization", createBasicAuthHeader());
            }
            HttpEntity<String> entity = new HttpEntity<>(headers);
            
            ResponseEntity<String> response = restTemplate.exchange(apiUrl, HttpMethod.GET, entity, String.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode rootNode = mapper.readTree(response.getBody());
                JsonNode commits = rootNode.path("values");
                
                if (commits.isArray() && commits.size() > 0) {
                    String commitHash = commits.get(0).path("hash").asText();
                    String shortCommit = commitHash.length() > 7 ? commitHash.substring(0, 7) : commitHash;
                    String commitDate = commits.get(0).path("date").asText();
                    System.out.println("[BitbucketCodeFetcher] Found branch HEAD: " + shortCommit + " on branch: " + currentBranch + " dated: " + commitDate);
                    return shortCommit;
                }
            }
        } catch (Exception e) {
            System.err.println("[BitbucketCodeFetcher] API error getting current commit: " + e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Centralized commit detection method for all controllers
     * Uses the same hybrid approach as SentryConfig for consistency
     */
    public String getCurrentCommitForErrorTracking() {
        // 1. Try BITBUCKET_COMMIT (set by Bitbucket Pipeline) - HIGHEST PRIORITY
        String envCommit = System.getenv("BITBUCKET_COMMIT");
        if (envCommit != null && !envCommit.isEmpty()) {
            String shortCommit = envCommit.substring(0, Math.min(7, envCommit.length()));
            System.out.println("[CommitTracking] Using pipeline commit: " + shortCommit);
            return shortCommit;
        }
        
        // 2. Try SENTRY_RELEASE (if manually set)
        String sentryRelease = System.getenv("SENTRY_RELEASE");
        if (sentryRelease != null && !sentryRelease.isEmpty() && !"unknown".equals(sentryRelease)) {
            System.out.println("[CommitTracking] Using SENTRY_RELEASE: " + sentryRelease);
            return sentryRelease;
        }
        
        // 3. BITBUCKET API: Get current commit from auto-detected branch (deployment-only)
        try {
            String bitbucketCommit = getCurrentCommitFromDetectedBranch();
            if (bitbucketCommit != null && !bitbucketCommit.isEmpty()) {
                System.out.println("[CommitTracking] Using Bitbucket API commit from detected branch: " + bitbucketCommit);
                return bitbucketCommit;
            }
        } catch (Exception e) {
            System.err.println("[CommitTracking] Bitbucket API commit lookup failed: " + e.getMessage());
        }
        
        // 4. Return null instead of fallback
        System.err.println("[CommitTracking] No commit found - configuration needed");
        return null;
    }
    
    /**
     * Automatically detects the current git branch
     * Uses environment variables from deployment pipeline, with git fallback
     */
    public String getCurrentBranch() {
        // 1. Try BITBUCKET_BRANCH (set by Bitbucket Pipeline)
        String envBranch = System.getenv("BITBUCKET_BRANCH");
        if (envBranch != null && !envBranch.isEmpty()) {
            System.out.println("[BranchDetection] Using pipeline branch: " + envBranch);
            return envBranch;
        }
        
        // 2. Try GIT_BRANCH (set by some CI systems)
        String gitBranch = System.getenv("GIT_BRANCH");
        if (gitBranch != null && !gitBranch.isEmpty()) {
            // Remove origin/ prefix if present
            String cleanBranch = gitBranch.replaceFirst("^origin/", "");
            System.out.println("[BranchDetection] Using GIT_BRANCH: " + cleanBranch);
            return cleanBranch;
        }
        
        // 3. Try Bitbucket API to get default branch
        try {
            String defaultBranch = getRepositoryDefaultBranch();
            if (defaultBranch != null && !defaultBranch.isEmpty()) {
                System.out.println("[BranchDetection] Using repository default branch: " + defaultBranch);
                return defaultBranch;
            }
        } catch (Exception e) {
            System.err.println("[BranchDetection] Failed to get default branch: " + e.getMessage());
        }
        
        // 4. Return configured branch
        String configuredBranch = bitbucketRepoBranch;
        System.out.println("[BranchDetection] Using configured branch: " + configuredBranch);
        return configuredBranch;
    }
    
    /**
     * Gets the repository's default branch from Bitbucket API
     */
    private String getRepositoryDefaultBranch() {
        try {
            String apiUrl = String.format("https://api.bitbucket.org/2.0/repositories/%s/%s", bitbucketWorkspace, bitbucketRepoName);
            
            HttpHeaders headers = new HttpHeaders();
            String token = selectTokenForWorkspaceRepo(bitbucketWorkspace, bitbucketRepoName);
            if (token != null && !token.isEmpty()) {
                headers.set("Authorization", createBasicAuthHeaderForWorkspaceRepo(bitbucketWorkspace, bitbucketRepoName));
            }
            HttpEntity<String> entity = new HttpEntity<>(headers);
            
            ResponseEntity<String> response = restTemplate.exchange(apiUrl, HttpMethod.GET, entity, String.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode rootNode = mapper.readTree(response.getBody());
                JsonNode mainBranch = rootNode.path("mainbranch");
                
                if (!mainBranch.isMissingNode()) {
                    return mainBranch.path("name").asText();
                }
            }
        } catch (Exception e) {
            System.err.println("[BranchDetection] API error getting default branch: " + e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Gets current commit from the automatically detected branch
     */
    public String getCurrentCommitFromDetectedBranch() {
        String currentBranch = getCurrentBranch();
        
        try {
            String apiUrl = String.format(
                "https://api.bitbucket.org/2.0/repositories/%s/%s/commits/%s?pagelen=1",
                bitbucketWorkspace, bitbucketRepoName, currentBranch
            );
            
            System.out.println("[BitbucketCodeFetcher] Checking branch: " + currentBranch + " for current commit");
            
            HttpHeaders headers = new HttpHeaders();
            String token = selectTokenForWorkspaceRepo(bitbucketWorkspace, bitbucketRepoName);
            if (token != null && !token.isEmpty()) {
                headers.set("Authorization", createBasicAuthHeaderForWorkspaceRepo(bitbucketWorkspace, bitbucketRepoName));
            }
            HttpEntity<String> entity = new HttpEntity<>(headers);
            
            ResponseEntity<String> response = restTemplate.exchange(apiUrl, HttpMethod.GET, entity, String.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode rootNode = mapper.readTree(response.getBody());
                JsonNode commits = rootNode.path("values");
                
                if (commits.isArray() && commits.size() > 0) {
                    String commitHash = commits.get(0).path("hash").asText();
                    String shortCommit = commitHash.length() > 7 ? commitHash.substring(0, 7) : commitHash;
                    String commitDate = commits.get(0).path("date").asText();
                    System.out.println("[BitbucketCodeFetcher] Found commit " + shortCommit + " on branch " + currentBranch + " dated: " + commitDate);
                    return shortCommit;
                }
            }
        } catch (Exception e) {
            System.err.println("[BitbucketCodeFetcher] API error getting commit from detected branch: " + e.getMessage());
        }
        
        return null;
    }

    /**
     * Gets the current commit hash for a specific repository/branch provided via RepoConfig.
     * This is used for per-project commit detection (e.g., Android demo app vs backend).
     */
    public String getCurrentCommitForRepo(RepoConfig repo) {
        if (repo == null || repo.getRepoUrl() == null || repo.getRepoUrl().isEmpty()) {
            return null;
        }

        // Parse https://bitbucket.org/{workspace}/{repo}
        String url = repo.getRepoUrl().replace("https://", "").replace("http://", "");
        String[] parts = url.split("/");
        String workspace = parts.length > 1 ? parts[1] : bitbucketWorkspace;
        String repoSlug = parts.length > 2 ? parts[2] : bitbucketRepoName;
        String branch = (repo.getBranch() != null && !repo.getBranch().isEmpty()) ? repo.getBranch() : bitbucketRepoBranch;

        try {
            String apiUrl = String.format(
                "https://api.bitbucket.org/2.0/repositories/%s/%s/commits/%s?pagelen=1",
                workspace, repoSlug, branch
            );

            HttpHeaders headers = new HttpHeaders();
            String token = selectTokenForWorkspaceRepo(workspace, repoSlug);
            if (token != null && !token.isEmpty()) {
                headers.set("Authorization", createBasicAuthHeaderForWorkspaceRepo(workspace, repoSlug));
            }
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(apiUrl, HttpMethod.GET, entity, String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode rootNode = mapper.readTree(response.getBody());
                JsonNode commits = rootNode.path("values");
                if (commits.isArray() && commits.size() > 0) {
                    String commitHash = commits.get(0).path("hash").asText();
                    return commitHash.length() > 7 ? commitHash.substring(0, 7) : commitHash;
                }
            }
        } catch (Exception e) {
            System.err.println("[BitbucketCodeFetcher] API error getting commit for repo: " + repo.getRepoUrl() + " - " + e.getMessage());
        }

        return null;
    }

    private String selectTokenForWorkspaceRepo(String workspace, String repo) {
        // Use the special token for sentry-demo-app repo; fallback to default
        if (repo != null && repo.equalsIgnoreCase("sentry-demo-app")) {
            if (sentryDemoAppApiToken != null && !sentryDemoAppApiToken.isEmpty()) {
                return sentryDemoAppApiToken;
            }
        }
        return bitbucketApiToken;
    }
}
