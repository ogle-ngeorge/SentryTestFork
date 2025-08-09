package com.example.sentrytestbackend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;

/**
 * Resolves repository configuration per Sentry project.
 * Defaults to the backend repo, but allows project-specific overrides (e.g., Android demo app).
 */
@Service
public class RepoResolver {
    @Value("${bitbucket.repo.url}")
    private String defaultRepoUrl;
    @Value("${bitbucket.repo.branch}")
    private String defaultBranch;
    @Value("${bitbucket.repo.srcRoot}")
    private String defaultSrcRoot;
    @Value("${stacktrace.project.root}")
    private String defaultProjectRoot;

    private final Map<String, RepoConfig> overrides = new HashMap<>();

    @PostConstruct
    public void init() {
        // Android demo app mapping
        RepoConfig demoAndroid = new RepoConfig(
                "https://bitbucket.org/sentry-codespace-api/sentry-demo-app",
                "main",
                "app/src/main/java/",
                "com.example.demologinapp"
        );
        overrides.put("sentry-demo", demoAndroid);
        overrides.put("sentry-demo-app", demoAndroid);
    }

    public RepoConfig resolve(String project) {
        RepoConfig override = overrides.get(project);
        if (override != null) {
            return override;
        }
        return new RepoConfig(defaultRepoUrl, defaultBranch, defaultSrcRoot, defaultProjectRoot);
    }
}


