package com.example.sentrytestbackend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.util.MultiValueMap;
import org.springframework.util.LinkedMultiValueMap;

import java.util.*;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

/**
 * Service for automating Bitbucket pull request creation using Gemini AI JSON output.
 * Handles branch creation, file patching, committing, and PR creation via Bitbucket REST API.
 */
@Service
public class BitbucketPrService {
    @Value("${bitbucket.workspace}")
    private String defaultWorkspace;
    @Value("${bitbucket.repo.name}")
    private String defaultRepoSlug;
    @Value("${bitbucket.repo.branch}")
    private String defaultMainBranch;
    @Value("${bitbucket.api.email}")
    private String bitbucketApiEmail;
    @Value("${bitbucket.api.token}")
    private String apiToken;
    @Value("${bitbucket.sentry-demo-app.api.token:}")
    private String sentryDemoAppApiToken;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private RepoResolver repoResolver;

    private static class RepoTarget {
        final String workspace;
        final String repoSlug;
        final String mainBranch;
        RepoTarget(String workspace, String repoSlug, String mainBranch) {
            this.workspace = workspace;
            this.repoSlug = repoSlug;
            this.mainBranch = mainBranch;
        }
    }

    private RepoTarget resolveTarget(String project) {
        try {
            RepoConfig repo = repoResolver.resolve(project != null ? project : "");
            String repoUrl = repo.getRepoUrl();
            // Parse https://bitbucket.org/{workspace}/{repo}
            String[] parts = repoUrl.replace("https://", "").replace("http://", "").split("/");
            String workspace = parts.length > 1 ? parts[1] : defaultWorkspace;
            String repoSlug = parts.length > 2 ? parts[2] : defaultRepoSlug;
            String branch = repo.getBranch() != null ? repo.getBranch() : defaultMainBranch;
            return new RepoTarget(workspace, repoSlug, branch);
        } catch (Exception e) {
            return new RepoTarget(defaultWorkspace, defaultRepoSlug, defaultMainBranch);
        }
    }

    /**
     * Orchestrates the creation of a Bitbucket pull request from Gemini's JSON output.
     * @param geminiJson The JSON structure from Gemini containing PR details and code changes.
     * @return The result or link to the created PR.
     * @throws Exception if any Bitbucket API call fails or file patching fails.
     */
    public String createPullRequestFromGeminiJson(Map<String, Object> geminiJson, String project) throws Exception {
        RepoTarget target = resolveTarget(project);
        RepoConfig repoConfig = repoResolver.resolve(project != null ? project : "");
        Map<String, Object> pr = (Map<String, Object>) geminiJson.get("pull_request");
        if (pr == null) throw new IllegalArgumentException("No pull_request in Gemini JSON");
        String branchName = generateBranchName((String) pr.get("title"));
        String commitMessage = (String) pr.get("commit_message");
        List<Map<String, Object>> changes = (List<Map<String, Object>>) pr.get("changes");
        HttpHeaders authHeaders = buildAuthHeadersForProject(project);

        // 1. Get latest commit hash of main branch
        String mainHash = getMainBranchCommitHash(target.workspace, target.repoSlug, target.mainBranch, authHeaders);
        // 2. Create branch
        createBranch(target.workspace, target.repoSlug, branchName, mainHash, authHeaders);
        // 3. For each file, apply replacements and commit
        for (Map<String, Object> change : changes) {
            String filePath = (String) change.get("file");
            List<Map<String, Object>> replacements = (List<Map<String, Object>>) change.get("replacements");
            FileUpdateResult update = applyReplacementsToFileWithPathResolution(
                target.workspace, target.repoSlug, target.mainBranch,
                filePath, replacements, repoConfig, authHeaders
            );
            commitFileChange(target.workspace, target.repoSlug, branchName, commitMessage, update.path, update.updatedContent, authHeaders);
        }
        // 4. Create PR
        return createPullRequest(target.workspace, target.repoSlug, target.mainBranch, pr, branchName, authHeaders);
    }

    /**
     * Gets the latest commit hash of the main branch.
     * @return The commit hash string.
     * @throws Exception if the Bitbucket API call fails.
     */
    private String getMainBranchCommitHash(String workspace, String repoSlug, String mainBranch, HttpHeaders headers) throws Exception {
        String url = String.format("https://api.bitbucket.org/2.0/repositories/%s/%s/refs/branches/%s", workspace, repoSlug, mainBranch);
        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        Map<String, Object> body = response.getBody();
        if (body == null || !body.containsKey("target")) throw new RuntimeException("Failed to get main branch hash");
        Map<String, Object> target = (Map<String, Object>) body.get("target");
        return (String) target.get("hash");
    }

    /**
     * Creates a new branch in Bitbucket from the given commit hash.
     * @param branchName The name of the new branch.
     * @param targetHash The commit hash to branch from.
     */
    private void createBranch(String workspace, String repoSlug, String branchName, String targetHash, HttpHeaders headers) {
        String url = String.format("https://api.bitbucket.org/2.0/repositories/%s/%s/refs/branches", workspace, repoSlug);
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", branchName);
        Map<String, String> target = new HashMap<>();
        target.put("hash", targetHash);
        payload.put("target", target);
        headers.setContentType(MediaType.APPLICATION_JSON);
        restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(payload, headers), Map.class);
    }

    /**
     * Commits a file change to the given branch in Bitbucket.
     * @param branch The branch name.
     * @param commitMessage The commit message.
     * @param filePath The file path to update.
     * @param newContent The new file content.
     */
    private void commitFileChange(String workspace, String repoSlug, String branch, String commitMessage, String filePath, String newContent, HttpHeaders headers) {
        String url = String.format("https://api.bitbucket.org/2.0/repositories/%s/%s/src", workspace, repoSlug);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new org.springframework.util.LinkedMultiValueMap<>();
        body.add("branch", branch);
        body.add("message", commitMessage);
        body.add(filePath, newContent);
        restTemplate.postForEntity(url, new HttpEntity<>(body, headers), String.class);
    }

    /**
     * Creates a pull request in Bitbucket from the given branch to the main branch.
     * @param pr The PR details from Gemini JSON.
     * @param branchName The source branch name.
     * @return The PR result or link.
     */
    private String createPullRequest(String workspace, String repoSlug, String mainBranch, Map<String, Object> pr, String branchName, HttpHeaders headers) {
        String url = String.format("https://api.bitbucket.org/2.0/repositories/%s/%s/pullrequests", workspace, repoSlug);
        Map<String, Object> payload = new HashMap<>();
        payload.put("title", pr.get("title"));
        payload.put("description", pr.get("description"));
        Map<String, Object> source = new HashMap<>();
        source.put("branch", Collections.singletonMap("name", branchName));
        payload.put("source", source);
        Map<String, Object> destination = new HashMap<>();
        destination.put("branch", Collections.singletonMap("name", mainBranch));
        payload.put("destination", destination);
        payload.put("close_source_branch", true);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(payload, headers), Map.class);
        Map<String, Object> body = response.getBody();
        if (body != null && body.containsKey("links")) {
            try {
                Map<String, Object> links = (Map<String, Object>) body.get("links");
                Map<String, Object> html = (Map<String, Object>) links.get("html");
                String href = html != null ? (String) html.get("href") : null;
                if (href != null && !href.isEmpty()) {
                    return href;
                }
                // Fallback to self link
                Map<String, Object> self = (Map<String, Object>) links.get("self");
                String selfHref = self != null ? (String) self.get("href") : null;
                if (selfHref != null && !selfHref.isEmpty()) {
                    return selfHref;
                }
            } catch (Exception ignored) {
            }
        }
        return "PR created";
    }

    /**
     * Downloads a file from Bitbucket and applies the replacements from Gemini JSON.
     * @param filePath The file path in the repo.
     * @param replacements The list of replacements (start_line, end_line, replacement_code).
     * @return The updated file content as a string.
     * @throws Exception if file download or patching fails.
     */
    private String applyReplacementsToFile(String workspace, String repoSlug, String mainBranch, String filePath, List<Map<String, Object>> replacements, HttpHeaders headers) throws Exception {
        // 1. Download the file from Bitbucket
        String url = String.format("https://api.bitbucket.org/2.0/repositories/%s/%s/src/%s/%s", workspace, repoSlug, mainBranch, filePath);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        String content = response.getBody();
        if (content == null) throw new RuntimeException("Failed to fetch file content");
        // 2. Apply replacements (safe indexing, supports 0 to mean "insert at top")
        return applyReplacementsToContent(content, replacements);
    }

    /**
     * Holds the result of applying replacements to a file, including the resolved file path.
     */
    private static class FileUpdateResult {
        final String path;
        final String updatedContent;
        FileUpdateResult(String path, String updatedContent) {
            this.path = path;
            this.updatedContent = updatedContent;
        }
    }

    /**
     * Attempts to download the file using multiple candidate paths, then applies replacements.
     * Candidates:
     * - If filePath has '/', use as-is
     * - If bare filename, try srcRoot + filename
     * - If bare filename, also try srcRoot + projectRootPath + "/" + filename
     */
    private FileUpdateResult applyReplacementsToFileWithPathResolution(
            String workspace, String repoSlug, String mainBranch,
            String filePath, List<Map<String, Object>> replacements, RepoConfig repoConfig, HttpHeaders headers) throws Exception {

        List<String> candidates = new ArrayList<>();
        if (filePath != null && filePath.contains("/")) {
            candidates.add(filePath);
        } else {
            String base = normalizeFilePath(filePath, repoConfig); // srcRoot + filename
            candidates.add(base);
            String projectRoot = repoConfig != null ? repoConfig.getProjectRoot() : null;
            if (projectRoot != null && !projectRoot.isEmpty()) {
                String projectRootPath = projectRoot.replace('.', '/');
                String srcRoot = repoConfig.getSrcRoot();
                String normalizedRoot = (srcRoot != null && !srcRoot.isEmpty()) ? (srcRoot.endsWith("/") ? srcRoot : srcRoot + "/") : "";
                candidates.add(normalizedRoot + projectRootPath + "/" + filePath);
            }
        }

        for (String candidate : candidates) {
            String url = String.format("https://api.bitbucket.org/2.0/repositories/%s/%s/src/%s/%s", workspace, repoSlug, mainBranch, candidate);
            try {
                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
                String content = response.getBody();
                if (content == null) continue;
                String updated = applyReplacementsToContent(content, replacements);
                return new FileUpdateResult(candidate, updated);
            } catch (HttpClientErrorException.NotFound nf) {
                // Try next candidate
            }
        }

        throw new RuntimeException("No such file or directory: " + filePath);
    }

    /**
     * Applies a list of replacements to file content with safe bounds handling.
     * - Lines are treated as 1-based; 0 means insert at beginning.
     * - If end < start after normalization, perform insertion only.
     */
    private String applyReplacementsToContent(String content, List<Map<String, Object>> replacements) {
        List<String> lines = new ArrayList<>(Arrays.asList(content.split("\n", -1)));
        for (Map<String, Object> repl : replacements) {
            int startLine = ((Number) repl.get("start_line")).intValue();
            int endLine = ((Number) repl.get("end_line")).intValue();
            String replacement = (String) repl.get("replacement_code");

            // Normalize to 0-based indices; allow 0 to mean index 0
            int startIdx = Math.max(0, startLine - 1);
            int endIdx = Math.max(-1, endLine - 1);

            // Clamp to current bounds
            startIdx = Math.min(startIdx, lines.size());
            endIdx = Math.min(endIdx, lines.size() - 1);

            if (endIdx >= startIdx) {
                // Replace inclusive range [startIdx, endIdx]
                for (int i = endIdx; i >= startIdx; i--) {
                    lines.remove(i);
                }
                lines.add(startIdx, replacement);
            } else {
                // Pure insertion at startIdx
                lines.add(startIdx, replacement);
            }
        }
        return String.join("\n", lines);
    }

    /**
     * Creates Basic Authentication header for default API token
     * Format: Authorization: Basic base64(email:token)
     */
    private String createBasicAuthHeader() {
        String credentials = bitbucketApiEmail + ":" + apiToken;
        String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encodedCredentials;
    }
    
    /**
     * Creates Basic Authentication header for project-specific token
     */
    private String createBasicAuthHeaderForProject(String project) {
        String tokenToUse = selectTokenForProject(project);
        String credentials = bitbucketApiEmail + ":" + tokenToUse;
        String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encodedCredentials;
    }
    
    /**
     * Selects appropriate API token for a project
     */
    private String selectTokenForProject(String project) {
        if (project != null) {
            String normalized = project.trim().toLowerCase();
            if (("sentry-demo-app".equals(normalized) || "sentry-demo".equals(normalized)) && 
                sentryDemoAppApiToken != null && !sentryDemoAppApiToken.isEmpty()) {
                return sentryDemoAppApiToken;
            }
        }
        return apiToken;
    }

    /**
     * Gets the HTTP headers for Bitbucket API authentication using Basic Auth.
     * @return HttpHeaders with Authorization set.
     */
    private HttpHeaders buildAuthHeadersForProject(String project) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", createBasicAuthHeaderForProject(project));
        return headers;
    }

    /**
     * Generates a branch name from the PR title.
     * @param title The PR title.
     * @return A sanitized branch name string.
     */
    private String generateBranchName(String title) {
        String safeTitle = (title == null || title.isEmpty()) ? "change" : title;
        String slug = safeTitle
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        if (slug.isEmpty()) {
            slug = "update";
        }
        // Limit the slug portion to 30 characters so names stay short; prefix remains intact
        if (slug.length() > 30) {
            slug = slug.substring(0, 30);
        }
        String randomPrefix = String.format("%04d", new java.util.Random().nextInt(10000));
        return randomPrefix + "-ai-fix-" + slug;
    }

    /**
     * Convert Gemini's simple file names into repo-relative paths.
     * If the path lacks directories, prefix with the repo's srcRoot from RepoConfig.
     */
    private String normalizeFilePath(String filePath, RepoConfig repoConfig) {
        if (filePath == null || filePath.isEmpty()) return filePath;
        // If already looks like a path (contains '/'), return as-is
        if (filePath.contains("/")) return filePath;
        String srcRoot = repoConfig != null ? repoConfig.getSrcRoot() : null;
        if (srcRoot == null || srcRoot.isEmpty()) return filePath;
        String normalizedRoot = srcRoot.endsWith("/") ? srcRoot : srcRoot + "/";
        return normalizedRoot + filePath;
    }
}

