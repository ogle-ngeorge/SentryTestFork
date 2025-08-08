package com.example.sentrytestbackend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.util.MultiValueMap;
import org.springframework.util.LinkedMultiValueMap;

import java.util.*;

/**
 * Service for automating Bitbucket pull request creation using Gemini AI JSON output.
 * Handles branch creation, file patching, committing, and PR creation via Bitbucket REST API.
 */
@Service
public class BitbucketPrService {
    @Value("${bitbucket.workspace}")
    private String workspace;
    @Value("${bitbucket.repo.name}")
    private String repoSlug;
    @Value("${bitbucket.repo.branch}")
    private String mainBranch;
    @Value("${bitbucket.api.token}")
    private String apiToken;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Orchestrates the creation of a Bitbucket pull request from Gemini's JSON output.
     * @param geminiJson The JSON structure from Gemini containing PR details and code changes.
     * @return The result or link to the created PR.
     * @throws Exception if any Bitbucket API call fails or file patching fails.
     */
    public String createPullRequestFromGeminiJson(Map<String, Object> geminiJson) throws Exception {
        Map<String, Object> pr = (Map<String, Object>) geminiJson.get("pull_request");
        if (pr == null) throw new IllegalArgumentException("No pull_request in Gemini JSON");
        String branchName = generateBranchName((String) pr.get("title"));
        String commitMessage = (String) pr.get("commit_message");
        List<Map<String, Object>> changes = (List<Map<String, Object>>) pr.get("changes");

        // 1. Get latest commit hash of main branch
        String mainHash = getMainBranchCommitHash();
        // 2. Create branch
        createBranch(branchName, mainHash);
        // 3. For each file, apply replacements and commit
        for (Map<String, Object> change : changes) {
            String filePath = (String) change.get("file");
            List<Map<String, Object>> replacements = (List<Map<String, Object>>) change.get("replacements");
            String updatedContent = applyReplacementsToFile(filePath, replacements);
            commitFileChange(branchName, commitMessage, filePath, updatedContent);
        }
        // 4. Create PR
        return createPullRequest(pr, branchName);
    }

    /**
     * Gets the latest commit hash of the main branch.
     * @return The commit hash string.
     * @throws Exception if the Bitbucket API call fails.
     */
    private String getMainBranchCommitHash() throws Exception {
        String url = String.format("https://api.bitbucket.org/2.0/repositories/%s/%s/refs/branches/%s", workspace, repoSlug, mainBranch);
        HttpHeaders headers = getAuthHeaders();
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
    private void createBranch(String branchName, String targetHash) {
        String url = String.format("https://api.bitbucket.org/2.0/repositories/%s/%s/refs/branches", workspace, repoSlug);
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", branchName);
        Map<String, String> target = new HashMap<>();
        target.put("hash", targetHash);
        payload.put("target", target);
        HttpHeaders headers = getAuthHeaders();
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
    private void commitFileChange(String branch, String commitMessage, String filePath, String newContent) {
        String url = String.format("https://api.bitbucket.org/2.0/repositories/%s/%s/src", workspace, repoSlug);
        HttpHeaders headers = getAuthHeaders();
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
    private String createPullRequest(Map<String, Object> pr, String branchName) {
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
        HttpHeaders headers = getAuthHeaders();
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
    private String applyReplacementsToFile(String filePath, List<Map<String, Object>> replacements) throws Exception {
        // 1. Download the file from Bitbucket
        String url = String.format("https://api.bitbucket.org/2.0/repositories/%s/%s/src/%s/%s", workspace, repoSlug, mainBranch, filePath);
        HttpHeaders headers = getAuthHeaders();
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        String content = response.getBody();
        if (content == null) throw new RuntimeException("Failed to fetch file content");
        // 2. Apply replacements
        List<String> lines = new ArrayList<>(Arrays.asList(content.split("\n", -1)));
        for (Map<String, Object> repl : replacements) {
            int start = (int) repl.get("start_line") - 1;
            int end = (int) repl.get("end_line") - 1;
            String replacement = (String) repl.get("replacement_code");
            if (start == end) {
                // Insert at start, do not delete any lines
                lines.add(start, replacement);
            } else {
                // Replace lines from start to end (inclusive)
                for (int i = end; i >= start; i--) {
                    lines.remove(i);
                }
                lines.add(start, replacement);
            }
        }
        return String.join("\n", lines);
    }

    /**
     * Gets the HTTP headers for Bitbucket API authentication.
     * @return HttpHeaders with Authorization set.
     */
    private HttpHeaders getAuthHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + apiToken);
        return headers;
    }

    /**
     * Generates a branch name from the PR title.
     * @param title The PR title.
     * @return A sanitized branch name string.
     */
    private String generateBranchName(String title) {
        return "ai-fix-" + title.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("-+", "-").replaceAll("^-|-$", "").substring(0, Math.min(30, title.length()));
    }
}
