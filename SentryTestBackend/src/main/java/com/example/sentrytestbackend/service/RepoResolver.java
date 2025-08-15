package com.example.sentrytestbackend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;

/**
 * Enhanced repository resolver that dynamically reads project configurations from application.properties.
 * 
 * Supports project-specific configurations in the format:
 * - project.{projectName}.root=com.example.package
 * - project.{projectName}.bitbucket.url=https://bitbucket.org/workspace/repo/src/branch/path/
 * - project.{projectName}.source.path=src/main/java/
 * 
 * Example configuration for BlueFletch EMS Auth:
 * project.bf-ems-auth.root=com.example.bfemsauth
 * project.bf-ems-auth.bitbucket.url=https://bitbucket.org/bluefletch/bf-ems-auth4/src/master/
 * project.bf-ems-auth.source.path=src/main/java/
 */
@Service
public class RepoResolver {
    
    private final Environment environment;
    
    // Default/legacy configuration
    // NO FALLBACK CONFIGURATION - ALL PROJECTS MUST BE EXPLICITLY CONFIGURED
    // Remove all @Value annotations to force explicit project configuration

    private final Map<String, RepoConfig> cachedConfigs = new HashMap<>();
    
    // Configurable source roots for path mapping
    private String[] sourceRoots;
    
    @Autowired
    private BitbucketCodeFetcher bitbucketCodeFetcher;

    public RepoResolver(Environment environment) {
        this.environment = environment;
    }
    
    /**
     * Builds a file URL with intelligent fallback strategy
     * This method coordinates between static URL building and dynamic search
     */
    public String buildFileUrlWithFallback(String project, String modulePackage, String filename, int lineNumber) {
        RepoConfig config = resolve(project);
        if (config == null) {
            System.err.println("[RepoResolver] No configuration found for project: " + project);
            return null;
        }
        
        // Try static URL building first
        String staticUrl = config.buildFileUrl(modulePackage, filename, lineNumber);
        
        // If we have Bitbucket components and the static URL might be incorrect, try search
        if (config.hasParsedBitbucketUrl() && staticUrl != null) {
            // For now, trust the static URL but log the search capability
            System.out.println("[RepoResolver] Static URL built: " + staticUrl);
            
            // Optionally validate and fallback to search (can be enabled later)
            if (shouldUseDynamicDiscovery(filename)) {
                String searchUrl = tryDynamicFileDiscovery(config, modulePackage, filename, lineNumber);
                if (searchUrl != null) {
                    System.out.println("[RepoResolver] Dynamic discovery found alternative: " + searchUrl);
                    return searchUrl; // Use discovered URL instead
                }
            }
        }
        
        return staticUrl;
    }
    
    /**
     * Determines if dynamic discovery should be attempted
     */
    private boolean shouldUseDynamicDiscovery(String filename) {
        // Enable for specific file types or when configured
        return filename != null && (filename.endsWith(".kt") || filename.endsWith(".java"));
    }
    
    /**
     * Uses BitbucketCodeFetcher to dynamically discover file location
     */
    private String tryDynamicFileDiscovery(RepoConfig config, String modulePackage, String filename, int lineNumber) {
        if (!config.hasParsedBitbucketUrl()) {
            return null;
        }
        
        String workspace = config.getWorkspace();
        String packageName = null;
        
        // Extract package name for search
        if (modulePackage != null && !modulePackage.isEmpty()) {
            int lastDot = modulePackage.lastIndexOf('.');
            if (lastDot != -1) {
                packageName = modulePackage.substring(0, lastDot);
            }
        }
        
        System.out.println("[RepoResolver] Attempting dynamic discovery: workspace=" + workspace + ", filename=" + filename + ", package=" + packageName);
        
        try {
            // DELEGATE TO RepoConfig's dynamic discovery method
            return config.buildFileUrlWithDynamicDiscovery(modulePackage, filename, lineNumber);
            
        } catch (Exception e) {
            System.err.println("[RepoResolver] Dynamic discovery failed: " + e.getMessage());
            return null;
        }
    }

    @PostConstruct
    public void init() {
        System.out.println("[RepoResolver] Initializing with EXPLICIT configuration requirement");
        System.out.println("[RepoResolver] NO FALLBACK configuration - all projects must be explicitly configured");
        
        // Load configurable source roots
        String sourceRootsProperty = environment.getProperty("path.mapping.source.roots", 
            "app/src/main/java/,app/src/main/kotlin/,src/main/java/,src/main/kotlin/,app/src/,src/,main/java/,main/kotlin/");
        this.sourceRoots = sourceRootsProperty.split(",");
        
        // Trim whitespace from each root
        for (int i = 0; i < sourceRoots.length; i++) {
            sourceRoots[i] = sourceRoots[i].trim();
        }
        
        System.out.println("[RepoResolver] Loaded " + sourceRoots.length + " configurable source roots: " + String.join(", ", sourceRoots));
        
        // Pre-load some known configurations to cache them
        loadProjectConfig("sentry-demo-app");
        loadProjectConfig("sentrytestbackend");
        loadProjectConfig("android");
        loadProjectConfig("bf-ems-auth");
        
        System.out.println("[RepoResolver] Cached " + cachedConfigs.size() + " project configurations");
    }

    /**
     * Resolves repository configuration for a given Sentry project.
     * REQUIRES explicit configuration in application.properties.
     * Returns null if no configuration found - NO FALLBACKS.
     */
    public RepoConfig resolve(String project) {
        if (project == null || project.trim().isEmpty()) {
            System.err.println("[RepoResolver] EXPLICIT FAILURE: No project name provided");
            return null;
        }
        
        String normalizedProject = project.trim().toLowerCase();
        
        // Check cache first
        if (cachedConfigs.containsKey(normalizedProject)) {
            RepoConfig cached = cachedConfigs.get(normalizedProject);
            System.out.println("[RepoResolver] Using cached config for project: " + normalizedProject + " -> " + cached);
            return cached;
        }
        
        // Try to load from properties
        RepoConfig config = loadProjectConfig(normalizedProject);
        if (config != null) {
            cachedConfigs.put(normalizedProject, config);
            return config;
        }
        
        // EXPLICIT FAILURE - no fallbacks allowed
        System.err.println("[RepoResolver] EXPLICIT FAILURE: No configuration found for project: " + normalizedProject);
        System.err.println("[RepoResolver] Required properties:");
        System.err.println("[RepoResolver]   - project." + normalizedProject + ".root=com.example.package");
        System.err.println("[RepoResolver]   - project." + normalizedProject + ".bitbucket.url=https://bitbucket.org/workspace/repo/src/branch/");
        System.err.println("[RepoResolver]   - project." + normalizedProject + ".source.path=src/main/java/");
        return null;
    }
    
    /**
     * Loads project configuration from application.properties using the format:
     * project.{projectName}.root, project.{projectName}.bitbucket.url, etc.
     */
    private RepoConfig loadProjectConfig(String projectName) {
        if (projectName == null || projectName.trim().isEmpty()) {
            return null;
        }
        
        String normalizedName = projectName.trim().toLowerCase();
        String prefix = "project." + normalizedName + ".";
        
        // Read project-specific properties
        String projectRoot = environment.getProperty(prefix + "root");
        String bitbucketUrl = environment.getProperty(prefix + "bitbucket.url");
        String sourcePath = environment.getProperty(prefix + "source.path");
        String projectSourceRoots = environment.getProperty(prefix + "path.mapping.source.roots");
        
        // Only create config if at least project root is defined
        if (projectRoot == null || projectRoot.trim().isEmpty()) {
            System.out.println("[RepoResolver] No configuration found for project: " + normalizedName + " (missing " + prefix + "root)");
            return null;
        }
        
        // Determine which source roots to use (project-specific or global)
        String[] projectSourceRootsArray = sourceRoots; // Default to global
        if (projectSourceRoots != null && !projectSourceRoots.trim().isEmpty()) {
            projectSourceRootsArray = projectSourceRoots.split(",");
            // Trim whitespace from each root
            for (int i = 0; i < projectSourceRootsArray.length; i++) {
                projectSourceRootsArray[i] = projectSourceRootsArray[i].trim();
            }
            System.out.println(String.format("[RepoResolver] Using project-specific source roots: %s", String.join(", ", projectSourceRootsArray)));
        } else {
            System.out.println(String.format("[RepoResolver] Using global source roots: %s", String.join(", ", sourceRoots)));
        }
        
        System.out.println(String.format("[RepoResolver] Loading config for project: %s", normalizedName));
        System.out.println(String.format("[RepoResolver]   - root: %s", projectRoot));
        System.out.println(String.format("[RepoResolver]   - bitbucket.url: %s", bitbucketUrl));
        System.out.println(String.format("[RepoResolver]   - source.path: %s", sourcePath));
        System.out.println(String.format("[RepoResolver]   - source.roots: %s", String.join(", ", projectSourceRootsArray)));
        
        // Create RepoConfig - NO FALLBACK VALUES
        RepoConfig config = new RepoConfig(
            normalizedName,           // projectName
            projectRoot,              // projectRoot (e.g., com.example.package)
            null,                     // NO legacy repoUrl fallback
            null,                     // NO legacy branch fallback
            sourcePath,               // source path (must be explicitly provided)
            bitbucketUrl,             // bitbucketUrl (new format, parsed automatically)
            projectSourceRootsArray   // project-specific or global source roots
        );
        
        // INJECT BitbucketCodeFetcher for DYNAMIC DISCOVERY
        if (bitbucketCodeFetcher != null) {
            config.setBitbucketCodeFetcher(bitbucketCodeFetcher);
            System.out.println("[RepoResolver] ✅ Injected BitbucketCodeFetcher for dynamic discovery");
        } else {
            System.out.println("[RepoResolver] ⚠️ BitbucketCodeFetcher not available - dynamic discovery disabled");
        }
        
        System.out.println("[RepoResolver] Created config: " + config);
        return config;
    }
    
    // NO DEFAULT CONFIG METHOD - EXPLICIT CONFIGURATION REQUIRED
    
    /**
     * Returns all cached configurations (for debugging/monitoring)
     */
    public Map<String, RepoConfig> getCachedConfigs() {
        return new HashMap<>(cachedConfigs);
    }
    
    /**
     * Clears the configuration cache (useful for testing or dynamic reconfiguration)
     */
    public void clearCache() {
        cachedConfigs.clear();
        System.out.println("[RepoResolver] Configuration cache cleared");
    }
    
    /**
     * Manually adds a configuration to the cache (useful for testing)
     */
    public void addConfig(String projectName, RepoConfig config) {
        cachedConfigs.put(projectName.toLowerCase(), config);
        System.out.println("[RepoResolver] Manually added config for: " + projectName + " -> " + config);
    }
}


