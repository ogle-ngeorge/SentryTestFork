package com.example.sentrytestbackend.service;

/**
 * Simple POJO holding repository configuration used to build code links
 * and filter stack traces.
 */
public class RepoConfig {
    private final String repoUrl;
    private final String branch;
    private final String srcRoot;
    private final String projectRoot;

    public RepoConfig(String repoUrl, String branch, String srcRoot, String projectRoot) {
        this.repoUrl = repoUrl;
        this.branch = branch;
        this.srcRoot = srcRoot;
        this.projectRoot = projectRoot;
    }

    public String getRepoUrl() { return repoUrl; }
    public String getBranch() { return branch; }
    public String getSrcRoot() { return srcRoot; }
    public String getProjectRoot() { return projectRoot; }
}


