package com.example.sentrytestbackend.controller;

import com.example.sentrytestbackend.service.BitbucketPrService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/**
 * REST controller for automating Bitbucket pull request creation using Gemini AI JSON output.
 *
 * Example endpoint:
 * POST http://localhost:8081/api/bitbucket-pr/create
 *   (Body: Gemini JSON PR structure)
 *
 * Bitbucket API workflow:
 * 1. Create branch: POST /2.0/repositories/{workspace}/{repo_slug}/refs/branches
 * 2. Commit code:   POST /2.0/repositories/{workspace}/{repo_slug}/src
 * 3. Create PR:     POST /2.0/repositories/{workspace}/{repo_slug}/pullrequests
 */
@RestController
@RequestMapping("/api/bitbucket-pr")
public class BitbucketPrController {

    @Autowired
    private BitbucketPrService bitbucketPrService;

    /**
     * Creates a Bitbucket pull request from Gemini's JSON output.
     *
     * Endpoint: POST /api/bitbucket-pr/create
     * Example: http://localhost:8081/api/bitbucket-pr/create
     *
     * Workflow:
     *   1. Create branch (Bitbucket API: POST /2.0/repositories/{workspace}/{repo_slug}/refs/branches)
     *   2. Commit code changes (Bitbucket API: POST /2.0/repositories/{workspace}/{repo_slug}/src)
     *   3. Create pull request (Bitbucket API: POST /2.0/repositories/{workspace}/{repo_slug}/pullrequests)
     *
     * @param geminiJson The JSON structure from Gemini containing PR details and code changes.
     * @return The result or link to the created PR, or error message.
     */
    @PostMapping("/create")
    public ResponseEntity<?> createPrFromGemini(@RequestBody Map<String, Object> geminiJson) {
        try {
            // 1. Create branch
            // 2. Commit code changes
            // 3. Create pull request
            // (All handled in BitbucketPrService.createPullRequestFromGeminiJson)
            String prResult = bitbucketPrService.createPullRequestFromGeminiJson(geminiJson);
            return ResponseEntity.ok(Map.of("result", prResult));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
}