package com.example.sentrytestbackend.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for parsing Bitbucket URLs and extracting components.
 * Supports URLs like: https://bitbucket.org/workspace/repo/src/branch/path/
 * 
 * Example usage:
 * - URL: https://bitbucket.org/bluefletch/bf-ems-auth4/src/master/
 * - Workspace: bluefletch
 * - Repository: bf-ems-auth4  
 * - Branch: master
 * - Source Path: (empty - root level)
 */
public class BitbucketUrlParser {
    
    // Pattern to match Bitbucket URLs: https://bitbucket.org/{workspace}/{repo}/src/{branch}/{path}
    private static final Pattern BITBUCKET_URL_PATTERN = Pattern.compile(
        "^https?://bitbucket\\.org/([^/]+)/([^/]+)/src/([^/]+)/?(.*)$"
    );
    
    /**
     * Parsed components of a Bitbucket URL
     */
    public static class BitbucketUrlComponents {
        private final String workspace;
        private final String repository;
        private final String branch;
        private final String sourcePath;
        private final String originalUrl;
        
        public BitbucketUrlComponents(String workspace, String repository, String branch, String sourcePath, String originalUrl) {
            this.workspace = workspace;
            this.repository = repository;
            this.branch = branch;
            this.sourcePath = sourcePath;
            this.originalUrl = originalUrl;
        }
        
        public String getWorkspace() { return workspace; }
        public String getRepository() { return repository; }
        public String getBranch() { return branch; }
        public String getSourcePath() { return sourcePath; }
        public String getOriginalUrl() { return originalUrl; }
        
        /**
         * Returns the base repository URL without src/branch/path
         * Example: https://bitbucket.org/workspace/repo
         */
        public String getBaseRepoUrl() {
            return String.format("https://bitbucket.org/%s/%s", workspace, repository);
        }
        
        /**
         * Returns normalized source path (ensures trailing slash if not empty)
         */
        public String getNormalizedSourcePath() {
            if (sourcePath == null || sourcePath.trim().isEmpty()) {
                return "";
            }
            String normalized = sourcePath.trim();
            return normalized.endsWith("/") ? normalized : normalized + "/";
        }
        
        @Override
        public String toString() {
            return String.format("BitbucketUrl{workspace='%s', repo='%s', branch='%s', path='%s'}", 
                workspace, repository, branch, sourcePath);
        }
    }
    
    /**
     * Parses a Bitbucket URL and extracts its components
     * 
     * @param url The Bitbucket URL to parse
     * @return Parsed components or null if URL is invalid
     */
    public static BitbucketUrlComponents parseUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        
        Matcher matcher = BITBUCKET_URL_PATTERN.matcher(url.trim());
        if (!matcher.matches()) {
            System.err.println("[BitbucketUrlParser] Invalid Bitbucket URL format: " + url);
            return null;
        }
        
        String workspace = matcher.group(1);
        String repository = matcher.group(2);
        String branch = matcher.group(3);
        String sourcePath = matcher.group(4); // Can be empty
        
        System.out.println(String.format("[BitbucketUrlParser] Parsed URL: workspace=%s, repo=%s, branch=%s, path=%s", 
            workspace, repository, branch, sourcePath));
        
        return new BitbucketUrlComponents(workspace, repository, branch, sourcePath, url);
    }
    
    /**
     * Validates if a URL is a valid Bitbucket URL
     */
    public static boolean isValidBitbucketUrl(String url) {
        return parseUrl(url) != null;
    }
    
    /**
     * Builds a complete Bitbucket file URL from components and file path
     * 
     * @param components The parsed URL components
     * @param modulePackage Java package (e.g., com.example.service)
     * @param filename Java filename (e.g., MyService.java)
     * @param lineNumber Line number (optional, -1 for no line)
     * @return Complete Bitbucket URL to the file
     */
    public static String buildFileUrl(BitbucketUrlComponents components, String modulePackage, String filename, int lineNumber) {
        if (components == null) {
            return null;
        }
        
        // Convert Java package to file path (com.example.service -> com/example/service/)
        String packagePath = "";
        if (modulePackage != null && !modulePackage.isEmpty()) {
            // Extract package from module (remove class name if present)
            int lastDot = modulePackage.lastIndexOf('.');
            if (lastDot != -1) {
                String packageOnly = modulePackage.substring(0, lastDot);
                packagePath = packageOnly.replace('.', '/') + "/";
            }
        }
        
        // Build the complete file path: sourcePath + packagePath + filename
        String sourcePath = components.getNormalizedSourcePath();
        String fullPath = sourcePath + packagePath + (filename != null ? filename : "");
        
        // Remove any duplicate slashes
        fullPath = fullPath.replaceAll("/+", "/");
        if (fullPath.startsWith("/")) {
            fullPath = fullPath.substring(1);
        }
        
        // Build the complete URL
        String fileUrl = String.format("https://bitbucket.org/%s/%s/src/%s/%s", 
            components.getWorkspace(), components.getRepository(), components.getBranch(), fullPath);
        
        // Add line number anchor if specified
        if (lineNumber > 0) {
            fileUrl += "#lines-" + lineNumber;
        }
        
        return fileUrl;
    }
    
    /**
     * Example usage and testing method
     */
    public static void main(String[] args) {
        // Test various URL formats
        String[] testUrls = {
            "https://bitbucket.org/bluefletch/bf-ems-auth4/src/master/",
            "https://bitbucket.org/sentry-codespace-api/stacktrace/src/main/",
            "https://bitbucket.org/workspace/repo/src/develop/src/main/java/",
            "https://bitbucket.org/workspace/repo/src/feature-branch/app/src/main/kotlin/"
        };
        
        for (String url : testUrls) {
            System.out.println("\nTesting URL: " + url);
            BitbucketUrlComponents components = parseUrl(url);
            if (components != null) {
                System.out.println("  " + components);
                System.out.println("  Base URL: " + components.getBaseRepoUrl());
                System.out.println("  Normalized Path: '" + components.getNormalizedSourcePath() + "'");
                
                // Test file URL building
                String fileUrl = buildFileUrl(components, "com.example.service.UserService", "UserService.java", 42);
                System.out.println("  Example file URL: " + fileUrl);
            } else {
                System.out.println("  INVALID URL");
            }
        }
    }
}
