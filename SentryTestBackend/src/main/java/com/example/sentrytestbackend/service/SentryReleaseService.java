package com.example.sentrytestbackend.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sentry.Sentry;

import java.util.HashMap;
import java.util.Map;

/**
 * Service for creating and managing Sentry releases programmatically
 * Integrates with Bitbucket API to get commit hashes and creates corresponding Sentry releases
 */
@Service
public class SentryReleaseService {

    @Autowired
    private RestTemplate restTemplate;
    
    @Autowired
    private BitbucketCodeFetcher bitbucketCodeFetcher;

    // Sentry API Configuration
    @Value("${sentry.api.url}")
    private String sentryBaseUrl;
    
    @Value("${sentry.organization.id}")
    private String sentryOrgId;
    
    @Value("${sentry.default.project}")
    private String sentryDefaultProject;
    
    @Value("${sentry.api.token}")
    private String sentryApiToken;

    // Bitbucket repository reference for commit linking
    @Value("${bitbucket.workspace}")
    private String bitbucketWorkspace;
    
    @Value("${bitbucket.repo.name}")
    private String bitbucketRepoName;

    /**
     * Creates or ensures a Sentry release exists for the current commit
     * Gets commit hash from Bitbucket API and creates corresponding Sentry release
     * @return The commit hash/release version, or null if failed
     */
    public String createOrEnsureSentryRelease() {
        try {
            // 1. Get current commit from Bitbucket API
            String commitHash = bitbucketCodeFetcher.getCurrentCommitFromDetectedBranch();
            if (commitHash == null || commitHash.isEmpty()) {
                System.err.println("[SentryRelease] ERROR: Could not get commit hash from Bitbucket");
                return null;
            }

            System.out.println("[SentryRelease] Using commit hash from Bitbucket: " + commitHash);

            // 2. Check if release already exists
            if (checkReleaseExists(commitHash)) {
                System.out.println("[SentryRelease] Release already exists for commit: " + commitHash);
                return commitHash;
            }

            // 3. Create new Sentry release
            boolean created = createSentryRelease(commitHash);
            if (created) {
                System.out.println("[SentryRelease] Successfully created release for commit: " + commitHash);
                return commitHash;
            } else {
                System.err.println("[SentryRelease] ERROR: Failed to create Sentry release for commit: " + commitHash);
                return null;
            }

        } catch (Exception e) {
            System.err.println("[SentryRelease] ERROR: Exception during release creation: " + e.getMessage());
            Sentry.captureException(e);
            return null;
        }
    }

    /**
     * Checks if a Sentry release already exists for the given commit hash
     * @param commitHash The commit hash to check
     * @return true if release exists, false otherwise
     */
    private boolean checkReleaseExists(String commitHash) {
        try {
            String url = String.format("%s/api/0/organizations/%s/releases/%s/",
                sentryBaseUrl, sentryOrgId, commitHash);

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + sentryApiToken);
            headers.set("Content-Type", "application/json");

            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            
            // If we get 200, release exists
            if (response.getStatusCode().is2xxSuccessful()) {
                System.out.println("[SentryRelease] Release exists: " + commitHash);
                return true;
            }

        } catch (Exception e) {
            // 404 means release doesn't exist, which is expected
            System.out.println("[SentryRelease] Release does not exist yet: " + commitHash);
        }

        return false;
    }

    /**
     * Creates a new Sentry release with commit association
     * @param commitHash The commit hash to create a release for
     * @return true if successful, false otherwise
     */
    private boolean createSentryRelease(String commitHash) {
        try {
            String url = String.format("%s/api/0/organizations/%s/releases/",
                sentryBaseUrl, sentryOrgId);

            // Build request payload
            Map<String, Object> payload = new HashMap<>();
            payload.put("version", commitHash);
            payload.put("projects", new String[]{sentryDefaultProject});
            
            // Add commit reference for linking
            Map<String, Object> ref = new HashMap<>();
            ref.put("repository", bitbucketWorkspace + "/" + bitbucketRepoName);
            ref.put("commit", commitHash);
            payload.put("refs", new Map[]{ref});

            ObjectMapper mapper = new ObjectMapper();
            String jsonPayload = mapper.writeValueAsString(payload);

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + sentryApiToken);
            headers.set("Content-Type", "application/json");

            HttpEntity<String> entity = new HttpEntity<>(jsonPayload, headers);

            System.out.println("[SentryRelease] Creating release with payload: " + jsonPayload);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                System.out.println("[SentryRelease] Successfully created release: " + commitHash);
                return true;
            } else {
                System.err.println("[SentryRelease] ERROR: Failed to create release. Status: " + response.getStatusCode());
                System.err.println("[SentryRelease] Response: " + response.getBody());
                return false;
            }

        } catch (Exception e) {
            System.err.println("[SentryRelease] ERROR: Exception creating release: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Gets the current active release (commit hash) for error tracking
     * This can be called by controllers to get the current commit for error association
     * @return Current commit hash or null if not available
     */
    public String getCurrentReleaseForErrors() {
        // Try to get existing commit from Bitbucket
        String commitHash = bitbucketCodeFetcher.getCurrentCommitFromDetectedBranch();
        
        if (commitHash != null && !commitHash.isEmpty()) {
            System.out.println("[SentryRelease] Current release for errors: " + commitHash);
            return commitHash;
        } else {
            System.err.println("[SentryRelease] ERROR: No current release available for error tracking");
            return null;
        }
    }

    /**
     * Initializes Sentry release on application startup
     * Call this from a startup listener or controller to ensure release exists
     */
    public void initializeReleaseOnStartup() {
        System.out.println("[SentryRelease] Initializing Sentry release on application startup...");
        String release = createOrEnsureSentryRelease();
        
        if (release != null) {
            System.out.println("[SentryRelease] Application initialized with release: " + release);
            
            // Set the release in Sentry context for automatic association
            Sentry.configureScope(scope -> {
                scope.setTag("release", release);
                scope.setTag("commit_hash", release);
            });
            
        } else {
            System.err.println("[SentryRelease] WARNING: Application started without Sentry release initialization");
        }
    }
}