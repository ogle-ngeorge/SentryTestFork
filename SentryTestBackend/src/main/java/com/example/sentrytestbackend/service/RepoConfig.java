package com.example.sentrytestbackend.service;

import com.example.sentrytestbackend.util.BitbucketUrlParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;
import java.util.List;

/**
 * Enhanced repository configuration that supports dynamic Bitbucket URL parsing
 * and stack trace filtering based on project roots.
 * 
 * Supports both legacy format and new Bitbucket URL format:
 * - Legacy: repoUrl + branch + srcRoot (separate fields)
 * - New: Complete Bitbucket URL (automatically parsed)
 */
public class RepoConfig {
    private final String projectName;
    private final String projectRoot;
    private final String repoUrl;
    private final String branch;
    private final String srcRoot;
    
    // Parsed Bitbucket URL components (if applicable)
    private final BitbucketUrlParser.BitbucketUrlComponents bitbucketComponents;
    
    // For dynamic file discovery (injected when needed)
    private BitbucketCodeFetcher bitbucketCodeFetcher;
    
    // Configurable source roots for path mapping
    private final String[] sourceRoots;
    
    /**
     * Injects BitbucketCodeFetcher for dynamic file discovery
     */
    public void setBitbucketCodeFetcher(BitbucketCodeFetcher bitbucketCodeFetcher) {
        this.bitbucketCodeFetcher = bitbucketCodeFetcher;
    }

    /**
     * Legacy constructor for backward compatibility
     */
    public RepoConfig(String repoUrl, String branch, String srcRoot, String projectRoot) {
        this(null, projectRoot, repoUrl, branch, srcRoot, null, null);
    }
    
    /**
     * Enhanced constructor with Bitbucket URL support
     * 
     * @param projectName Name of the project (e.g., "sentrytestbackend")
     * @param projectRoot Java package root for filtering (e.g., "com.example.sentrytestbackend")
     * @param repoUrl Repository URL (legacy format)
     * @param branch Branch name (legacy format)
     * @param srcRoot Source root path (legacy format)
     * @param bitbucketUrl Complete Bitbucket URL (new format, overrides legacy if present)
     * @param sourceRoots Configurable source roots for intelligent path mapping
     */
    public RepoConfig(String projectName, String projectRoot, String repoUrl, String branch, String srcRoot, String bitbucketUrl, String[] sourceRoots) {
        this.projectName = projectName;
        this.projectRoot = projectRoot;
        
        // If Bitbucket URL is provided, parse it and use those components
        if (bitbucketUrl != null && !bitbucketUrl.trim().isEmpty()) {
            this.bitbucketComponents = BitbucketUrlParser.parseUrl(bitbucketUrl);
            if (this.bitbucketComponents != null) {
                // Use parsed components from Bitbucket URL
                this.repoUrl = this.bitbucketComponents.getBaseRepoUrl();
                this.branch = this.bitbucketComponents.getBranch();
                this.srcRoot = this.bitbucketComponents.getNormalizedSourcePath();
                System.out.println(String.format("[RepoConfig] Parsed Bitbucket URL for %s: workspace=%s, repo=%s, branch=%s, srcRoot='%s'", 
                    projectName, this.bitbucketComponents.getWorkspace(), this.bitbucketComponents.getRepository(), 
                    this.branch, this.srcRoot));
            } else {
                // Fallback to legacy values if URL parsing fails
                System.err.println("[RepoConfig] Failed to parse Bitbucket URL: " + bitbucketUrl + ", using legacy config");
                this.repoUrl = repoUrl;
                this.branch = branch;
                this.srcRoot = srcRoot;
            }
        } else {
            // Use legacy format
            this.bitbucketComponents = null;
        this.repoUrl = repoUrl;
        this.branch = branch;
        this.srcRoot = srcRoot;
        }
        
        // Store configurable source roots (with default fallback)
        if (sourceRoots != null && sourceRoots.length > 0) {
            this.sourceRoots = sourceRoots;
        } else {
            // Default source roots if none provided
            this.sourceRoots = new String[]{
                "app/src/main/java/", "app/src/main/kotlin/", 
                "src/main/java/", "src/main/kotlin/",
                "app/src/", "src/", "main/java/", "main/kotlin/"
            };
        }
    }

    // Getters
    public String getProjectName() { return projectName; }
    public String getProjectRoot() { return projectRoot; }
    public String getRepoUrl() { return repoUrl; }
    public String getBranch() { return branch; }
    public String getSrcRoot() { return srcRoot; }
    
    /**
     * Returns the Bitbucket workspace if URL was parsed, null otherwise
     */
    public String getWorkspace() {
        return bitbucketComponents != null ? bitbucketComponents.getWorkspace() : null;
    }
    
    /**
     * Returns the repository name if URL was parsed, null otherwise
     */
    public String getRepositoryName() {
        return bitbucketComponents != null ? bitbucketComponents.getRepository() : null;
    }
    
    /**
     * Returns whether this config was created from a parsed Bitbucket URL
     */
    public boolean hasParsedBitbucketUrl() {
        return bitbucketComponents != null;
    }
    
    /**
     * Returns the original Bitbucket URL components if available
     */
    public BitbucketUrlParser.BitbucketUrlComponents getBitbucketComponents() {
        return bitbucketComponents;
    }
    
    /**
     * Builds a file URL using DYNAMIC DISCOVERY FIRST approach:
     * 1. Try dynamic file discovery (search APIs) - NO ASSUMPTIONS about structure
     * 2. Only fall back to static path building if dynamic discovery fails
     */
    public String buildFileUrl(String modulePackage, String filename, int lineNumber) {
        // PREFER DYNAMIC DISCOVERY - let the search APIs find the file
        if (bitbucketCodeFetcher != null && bitbucketComponents != null) {
            System.out.println("[RepoConfig] Using DYNAMIC DISCOVERY first for: " + filename);
            String dynamicUrl = buildFileUrlWithDynamicDiscovery(modulePackage, filename, lineNumber);
            
            if (dynamicUrl != null) {
                System.out.println("[RepoConfig] ✅ Dynamic discovery successful: " + dynamicUrl);
                return dynamicUrl;
            } else {
                System.out.println("[RepoConfig] ⚠️ Dynamic discovery failed, falling back to static building");
            }
        }
        
        // FALLBACK: Try static URL building (assumes directory structure)
        String staticUrl = null;
        if (bitbucketComponents != null) {
            staticUrl = buildEnhancedFileUrl(modulePackage, filename, lineNumber);
        } else {
            staticUrl = buildLegacyFileUrl(modulePackage, filename, lineNumber);
        }
        
        if (staticUrl != null) {
            System.out.println("[RepoConfig] ⚠️ Using static URL (may be incorrect): " + staticUrl);
            return staticUrl;
        }
        
        System.err.println("[RepoConfig] ❌ Both dynamic and static URL building failed for: " + filename);
        return null;
    }
    
    /**
     * Uses Bitbucket search API to find the actual file location dynamically
     */
    private String buildUrlWithFileDiscovery(String modulePackage, String filename, int lineNumber) {
        if (bitbucketComponents == null) {
            return null; // Can't do dynamic discovery without workspace info
        }
        
        try {
            // Extract package name for search
            String packageName = null;
            if (modulePackage != null && !modulePackage.isEmpty()) {
                int lastDot = modulePackage.lastIndexOf('.');
                if (lastDot != -1) {
                    packageName = modulePackage.substring(0, lastDot);
                }
            }
            
            // Search for the file in the workspace
            List<String> foundPaths = searchFileInWorkspace(bitbucketComponents.getWorkspace(), filename, packageName);
            
            if (!foundPaths.isEmpty()) {
                // Use the first found path to build URL
                String filePath = foundPaths.get(0);
                String fileUrl = String.format("https://bitbucket.org/%s/%s/src/%s/%s", 
                    bitbucketComponents.getWorkspace(), 
                    bitbucketComponents.getRepository(), 
                    bitbucketComponents.getBranch(), 
                    filePath);
                
                if (lineNumber > 0) {
                    fileUrl += "#lines-" + lineNumber;
                }
                
                System.out.println("[RepoConfig] Discovered file path via search: " + filePath);
                return fileUrl;
            }
            
        } catch (Exception e) {
            System.err.println("[RepoConfig] Dynamic file discovery failed: " + e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Validates if a URL returns a successful response
     */
    private boolean isValidUrl(String url) {
        try {
            // For now, do basic URL structure validation
            // In production, you might want to make HEAD request to check if file exists
            return url != null && url.startsWith("https://bitbucket.org/");
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Simplified file search - delegates to BitbucketCodeFetcher if available
     */
    private List<String> searchFileInWorkspace(String workspace, String filename, String packageName) {
        try {
            // Build search query
            String searchQuery = filename;
            if (packageName != null && !packageName.isEmpty()) {
                String packagePath = packageName.replace('.', '/');
                searchQuery = filename + " path:" + packagePath;
            }
            
            String searchUrl = String.format(
                "https://api.bitbucket.org/2.0/workspaces/%s/search/code?search_query=%s",
                workspace, 
                java.net.URLEncoder.encode(searchQuery, "UTF-8")
            );
            
            // Note: This is a simplified implementation
            // In production, you'd inject BitbucketCodeFetcher or make this method accept it as parameter
            System.out.println("[RepoConfig] Would search with URL: " + searchUrl);
            System.out.println("[RepoConfig] Search query: " + searchQuery);
            
            // For now, return empty list - this should be enhanced with actual API call
            return List.of();
            
        } catch (Exception e) {
            System.err.println("[RepoConfig] Search failed: " + e.getMessage());
            return List.of();
        }
    }
    
    /**
     * Strips the package root and maps 
     */
    private String buildEnhancedFileUrl(String modulePackage, String filename, int lineNumber) {
        System.out.println("[RepoConfig] INTELLIGENT PATH MAPPING - modulePackage: " + modulePackage + ", filename: " + filename);
        
        // Step 1: Convert Java module to file path
        String stackTracePath = "";
        if (modulePackage != null && !modulePackage.isEmpty()) {
            // Extract package from module (remove class name)
            int lastDot = modulePackage.lastIndexOf('.');
            if (lastDot != -1) {
                String packageOnly = modulePackage.substring(0, lastDot);
                stackTracePath = packageOnly.replace('.', '/');
            }
        }
        
        // Add filename to create full path
        if (filename != null) {
            stackTracePath = stackTracePath.isEmpty() ? filename : stackTracePath + "/" + filename;
        }
        
        // Step 2: Stripping logic
        String relativePath = stripToRepoRelativePath(stackTracePath);
        if (relativePath == null) {
            System.err.println("[RepoConfig] Could not map stack trace path to repo: " + stackTracePath);
            return null;
        }
        
        // Step 3: Build clean Bitbucket URL
        String fileUrl = String.format("https://bitbucket.org/%s/%s/src/%s/%s", 
            bitbucketComponents.getWorkspace(), 
            bitbucketComponents.getRepository(), 
            bitbucketComponents.getBranch(), 
            relativePath);
        
        // Step 4: Add line number anchor
        if (lineNumber > 0) {
            fileUrl += "#lines-" + lineNumber;
        }
        
        System.out.println(String.format("[RepoConfig] INTELLIGENT MAPPING: stackTrace='%s' -> relative='%s' -> URL='%s'", 
            stackTracePath, relativePath, fileUrl));
        
        return fileUrl;
    }
    
    /**
     * Finds the project root in the stack trace path and strips everything before it
     */
    private String stripToRepoRelativePath(String stackTracePath) {
        // Normalize path separators (Windows \ vs Unix /)
        String normalizedPath = stackTracePath.replace('\\', '/');
        
        // Use configurable source roots from application.properties
        System.out.println("[RepoConfig] Trying " + this.sourceRoots.length + " configured source roots: " + String.join(", ", this.sourceRoots));
        
        // Try each configured source root
        for (String root : this.sourceRoots) {
            int index = normalizedPath.indexOf(root);
            if (index != -1) {
                String relativePath = normalizedPath.substring(index);
                System.out.println("[RepoConfig] ROOT MATCH: found '" + root + "' in path, using: " + relativePath);
                return relativePath;
            }
        }
        
        // Fallback: PROJECT ROOT MATCHING (for Java package-based stack traces)
        if (projectRoot != null && !projectRoot.isEmpty()) {
            String projectRootPath = projectRoot.replace('.', '/');
            
            // Check if the normalized path starts with or contains the project root
            if (normalizedPath.startsWith(projectRootPath) || normalizedPath.contains(projectRootPath)) {
                // Use configured srcRoot OR infer common Android/Java roots
                String configuredSrcRoot = (srcRoot != null && !srcRoot.isEmpty()) ? 
                    (srcRoot.endsWith("/") ? srcRoot : srcRoot + "/") : inferSourceRoot();
                
                // Build final path: srcRoot + full package path
                String relativePath = configuredSrcRoot + normalizedPath;
                
                // Clean up duplicate slashes
                relativePath = relativePath.replaceAll("/+", "/");
                
                System.out.println("[RepoConfig] PROJECT ROOT MATCH: srcRoot='" + configuredSrcRoot + "' + path='" + normalizedPath + "' = " + relativePath);
                return relativePath;
            }
        }
        
        // Last resort: assume standard Android structure
        if (normalizedPath.contains(".kt") || normalizedPath.contains(".java")) {
            String inferredRoot = inferSourceRoot();
            String relativePath = inferredRoot + normalizedPath;
            relativePath = relativePath.replaceAll("/+", "/");
            
            System.out.println("[RepoConfig] INFERRED ROOT: using '" + inferredRoot + "' + path='" + normalizedPath + "' = " + relativePath);
            return relativePath;
        }
        
        System.err.println("[RepoConfig] NO ROOT MATCH found for path: " + normalizedPath);
        return null;
    }
    
    /**
     * CHATGPT'S INTELLIGENT ROOT INFERENCE
     * Infers the most likely source root based on project context
     */
    private String inferSourceRoot() {
        // Android projects typically use app/src/main/java/ or app/src/main/kotlin/
        if (projectName != null && (projectName.contains("android") || projectName.contains("demo-app"))) {
            return "app/src/main/java/";
        }
        
        // Standard Java projects use src/main/java/
        return "src/main/java/";
    }
    
    /**
     * Legacy file URL building method
     */
    private String buildLegacyFileUrl(String modulePackage, String filename, int lineNumber) {
        // Convert module package to path
        String packagePath = "";
        if (modulePackage != null && !modulePackage.isEmpty()) {
            int lastDot = modulePackage.lastIndexOf('.');
            if (lastDot != -1) {
                String packageOnly = modulePackage.substring(0, lastDot);
                packagePath = packageOnly.replace('.', '/') + "/";
            }
        }
        
        // Build path: srcRoot + packagePath + filename
        String srcRootNormalized = (srcRoot != null && !srcRoot.isEmpty()) ? 
            (srcRoot.endsWith("/") ? srcRoot : srcRoot + "/") : "";
        String fullPath = srcRootNormalized + packagePath + (filename != null ? filename : "");
        
        // Build URL
        String fileUrl = repoUrl + "/src/" + branch + "/" + fullPath;
        
        // Add line number
        if (lineNumber > 0) {
            fileUrl += "#lines-" + lineNumber;
        }
        
        return fileUrl;
    }
    
    /**
     * SIMPLIFIED DYNAMIC URL BUILDING (FUTURE APPROACH)
     * Configuration ends at /src/ and uses dynamic file discovery for the rest
     * This eliminates the need to configure exact source paths
     * 
     * @param modulePackage Java module (e.g., com.example.service.UserService)
     * @param filename Source filename (e.g., UserService.java)
     * @param lineNumber Line number
     * @return Complete Bitbucket URL or null if discovery fails
     */
    public String buildFileUrlWithDynamicDiscovery(String modulePackage, String filename, int lineNumber) {
        if (bitbucketComponents == null || bitbucketCodeFetcher == null) {
            System.err.println("[RepoConfig] Dynamic discovery requires parsed Bitbucket URL and BitbucketCodeFetcher injection");
            return null;
        }
        
        // Extract package name for search
        String packageName = null;
        if (modulePackage != null && !modulePackage.isEmpty()) {
            int lastDot = modulePackage.lastIndexOf('.');
            if (lastDot != -1) {
                packageName = modulePackage.substring(0, lastDot);
            }
        }
        
        System.out.println("[RepoConfig] Starting dynamic discovery: workspace=" + bitbucketComponents.getWorkspace() + 
                           ", repo=" + bitbucketComponents.getRepository() + 
                           ", branch=" + bitbucketComponents.getBranch() + 
                           ", filename=" + filename + 
                           ", package=" + packageName);
        
        // Use enhanced file discovery
        String discoveredPath = bitbucketCodeFetcher.discoverFileLocation(
            bitbucketComponents.getWorkspace(),
            bitbucketComponents.getRepository(), 
            bitbucketComponents.getBranch(),
            filename,
            packageName
        );
        
        if (discoveredPath != null) {
            // Build complete URL with discovered path
            String fileUrl = String.format("https://bitbucket.org/%s/%s/src/%s/%s", 
                bitbucketComponents.getWorkspace(), 
                bitbucketComponents.getRepository(), 
                bitbucketComponents.getBranch(), 
                discoveredPath);
            
            if (lineNumber > 0) {
                fileUrl += "#lines-" + lineNumber;
            }
            
            System.out.println("[RepoConfig] Dynamic discovery successful: " + fileUrl);
            return fileUrl;
        } else {
            System.err.println("[RepoConfig] Dynamic discovery failed for: " + filename);
            return null;
        }
    }
    
    @Override
    public String toString() {
        if (bitbucketComponents != null) {
            return String.format("RepoConfig{project='%s', root='%s', %s}", 
                projectName, projectRoot, bitbucketComponents);
        } else {
            return String.format("RepoConfig{project='%s', root='%s', url='%s', branch='%s', srcRoot='%s'}", 
                projectName, projectRoot, repoUrl, branch, srcRoot);
        }
    }
}


