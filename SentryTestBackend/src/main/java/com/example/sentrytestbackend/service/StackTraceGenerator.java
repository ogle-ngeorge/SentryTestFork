package com.example.sentrytestbackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import java.util.Set;

@Service
public class StackTraceGenerator {

    @Autowired
    private AIAnalysisService aiAnalysisService;

    @Autowired
    private SentryReleaseService sentryReleaseService;

    @Autowired
    private RepoResolver repoResolver;

    // Variables to connect to github Repo
    @Value("${github.repo.url}")
    private String githubRepo;
    
    @Value("${github.repo.branch}")
    private String branch;
    
    @Value("${github.repo.source-root}")
    private String srcRoot;

    @Value("${spring.application.name:}")
    private String springAppName;

    private String detectedAppName;

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        if (springAppName != null && !springAppName.isEmpty()) {
            detectedAppName = springAppName.toLowerCase();
        } else {
            try {
                detectedAppName = com.example.sentrytestbackend.SentryTestBackendApplication.class.getPackage().getName().toLowerCase();
            } catch (Exception e) {
                detectedAppName = "";
            }
        }
        System.out.println("[StackTrace] Detected application name for filtering: '" + detectedAppName + "'");
    }
    private static final Set<String> ANDROID_FRAMEWORK_PREFIXES = Set.of(
            "androidx.",
            "android.",
            "java.",
            "kotlin.",
            "kotlinx.",
            "dalvik.",
            "com.android.",
            "sun.",
            "org.jetbrains.kotlin"
    );

    private static boolean isAndroidFrameworkModule(String module) {
        if (module == null) return false;
        for (String p : ANDROID_FRAMEWORK_PREFIXES) {
            if (module.startsWith(p)) return true;
        }
        return false;
    }

    private static boolean isSyntheticFrame(String functionOrModule) {
        if (functionOrModule == null) return false;
        return functionOrModule.contains("$$ExternalSyntheticLambda")
                || functionOrModule.contains("$r8$lambda$")
                || functionOrModule.contains("$$Lambda$")
                || functionOrModule.contains("$$SyntheticClass")
                || functionOrModule.contains("D8$$SyntheticClass")
                || functionOrModule.contains("$$ExternalSynthetic");
    }

    private static boolean isObfuscatedFrame(JsonNode frame) {
        String function = frame.has("function") ? frame.path("function").asText("") : "";
        int lineno = frame.has("lineno") ? frame.path("lineno").asInt(-1) : (frame.has("lineNo") ? frame.path("lineNo").asInt(-1) : -1);
        if (lineno == 0) return true;
        return function.matches(".*\\$[a-zA-Z0-9_]{10,}.*") || function.matches("^[a-zA-Z]$");
    }

    private static boolean looksAndroid(JsonNode exception, String projectRoot) {
        JsonNode frames = exception.path("stacktrace").path("frames");
        if (frames.isArray()) {
            for (int i = frames.size() - 1; i >= 0; i--) {
                JsonNode frame = frames.get(i);
                String module = frame.path("module").asText("");
                String filename = frame.path("filename").asText("");
                // Heuristic: Kotlin .kt isn't enough for Android; require projectRoot hint if present
                if (module.contains("androidx.") || module.startsWith("android.")) return true;
                if (filename.endsWith(".kt") && projectRoot != null && projectRoot.startsWith("com.example.demologinapp")) return true;
            }
        }
        return projectRoot != null && projectRoot.startsWith("com.example.demologinapp");
    }

    /**
     * Auto-detects app type (Android vs Java backend) and builds a filtered stack trace string
     * with Bitbucket links using per-project repository mapping.
     */
    public String buildStackTraceStringAuto(JsonNode exception, BitbucketCodeFetcher bitbucketCodeFetcher, JsonNode eventData, String project) {
        RepoConfig repo = repoResolver.resolve(project);
        String commitHash = extractCommitHashFromEvent(eventData);
        if (commitHash == null || commitHash.isEmpty()) {
            // Try per-project commit from Bitbucket for the resolved repo
            try {
                String repoCommit = bitbucketCodeFetcher != null ? bitbucketCodeFetcher.getCurrentCommitForRepo(repo) : null;
                if (repoCommit != null && !repoCommit.isEmpty()) {
                    commitHash = repoCommit;
                }
            } catch (Exception ignored) {}
            // As a fallback, try creating/ensuring a Sentry release (may use default repo)
            if (commitHash == null || commitHash.isEmpty()) {
                try {
                    String newCommitHash = sentryReleaseService.createOrEnsureSentryRelease();
                    if (newCommitHash != null && !newCommitHash.isEmpty()) {
                        commitHash = newCommitHash;
                    }
                } catch (Exception ignored) {}
            }
        }

        boolean android = looksAndroid(exception, repo.getProjectRoot());
        return android
                ? buildAndroidStyleTrace(exception, bitbucketCodeFetcher, repo, commitHash)
                : buildBackendStyleTrace(exception, bitbucketCodeFetcher, repo, commitHash);
    }

    private String buildBackendStyleTrace(JsonNode exception, BitbucketCodeFetcher bitbucketCodeFetcher, RepoConfig repo, String commitHash) {
        String exceptionType = exception.has("type") ? exception.path("type").asText() : exception.path("name").asText("UnknownException");
        String exceptionValue = exception.has("value") ? exception.path("value").asText() : "";
        StringBuilder stackTrace = new StringBuilder();
        stackTrace.append(exceptionType);
        if (!exceptionValue.isEmpty()) stackTrace.append(": ").append(exceptionValue);
        stackTrace.append("\n");

        JsonNode frames = exception.path("stacktrace").path("frames");
        if (frames.isArray()) {
            for (int i = frames.size() - 1; i >= 0; i--) {
                JsonNode frame = frames.get(i);
                String module = frame.path("module").asText("");
                String function = frame.path("function").asText("");
                String filename = frame.path("filename").asText("UnknownFile.java");
                int lineno = frame.has("lineno") ? frame.path("lineno").asInt(-1) : (frame.has("lineNo") ? frame.path("lineNo").asInt(-1) : -1);
                // Filter: include frames whose module contains project root (backend)
                if (repo.getProjectRoot() == null || repo.getProjectRoot().isEmpty() || module.startsWith(repo.getProjectRoot())) {
                    stackTrace.append("    at ");
                    if (!module.isEmpty()) stackTrace.append(module).append(".");
                    stackTrace.append(function).append("(").append(filename);
                    if (lineno != -1) stackTrace.append(":").append(lineno);
                    stackTrace.append(")");
                    // Use the enhanced RepoConfig.buildFileUrl method with intelligent fallback
                    String link = repo.buildFileUrl(module, filename, lineno);
                    stackTrace.append(" [").append(link).append("]\n");
                }
            }
        }
        return stackTrace.toString();
    }

    private String buildAndroidStyleTrace(JsonNode exception, BitbucketCodeFetcher bitbucketCodeFetcher, RepoConfig repo, String commitHash) {
        String exceptionType = exception.has("type") ? exception.path("type").asText() : exception.path("name").asText("UnknownException");
        String exceptionValue = exception.has("value") ? exception.path("value").asText() : "";
        StringBuilder cleanedTrace = new StringBuilder();
        cleanedTrace.append(exceptionType);
        if (!exceptionValue.isEmpty()) cleanedTrace.append(": ").append(exceptionValue);
        cleanedTrace.append("\n");

        JsonNode frames = exception.path("stacktrace").path("frames");
        boolean foundApp = false;
        boolean skippingFramework = false;
        int skippedFramework = 0;
        if (frames.isArray()) {
            for (int i = frames.size() - 1; i >= 0; i--) {
                JsonNode frame = frames.get(i);
                String module = frame.path("module").asText("");
                String function = frame.path("function").asText("");
                String filename = frame.path("filename").asText("UnknownFile.kt");
                int lineno = frame.has("lineno") ? frame.path("lineno").asInt(-1) : (frame.has("lineNo") ? frame.path("lineNo").asInt(-1) : -1);

                boolean isFramework = isAndroidFrameworkModule(module);
                boolean isSynthetic = isSyntheticFrame(module) || isSyntheticFrame(function);
                boolean isObfuscated = isObfuscatedFrame(frame);
                boolean isApp = module != null && repo.getProjectRoot() != null && module.startsWith(repo.getProjectRoot());

                if (isApp && !isSynthetic && !isObfuscated) {
                    if (skippingFramework && skippedFramework > 0) {
                        cleanedTrace.append("    ... ").append(skippedFramework).append(" framework calls omitted ...\n");
                        skippedFramework = 0;
                    }
                    skippingFramework = false;
                    cleanedTrace.append("    at ");
                    if (!module.isEmpty()) cleanedTrace.append(module).append(".");
                    cleanedTrace.append(function).append("(").append(filename);
                    if (lineno != -1) cleanedTrace.append(":").append(lineno);
                    cleanedTrace.append(")");
                    // Use the enhanced RepoConfig.buildFileUrl method with intelligent fallback
                    String link = repo.buildFileUrl(module, filename, lineno);
                    cleanedTrace.append(" [").append(link).append("]\n");
                    foundApp = true;
                } else if (isSynthetic || isObfuscated) {
                    // drop
                } else if (!skippingFramework && foundApp && isFramework) {
                    cleanedTrace.append("    at ");
                    if (!module.isEmpty()) cleanedTrace.append(module).append(".");
                    cleanedTrace.append(function).append("(").append(filename);
                    if (lineno != -1) cleanedTrace.append(":").append(lineno);
                    cleanedTrace.append(")\n");
                    skippingFramework = true;
                } else if (skippingFramework && isFramework) {
                    skippedFramework++;
                }
            }
        }
        if (skippingFramework && skippedFramework > 0) {
            cleanedTrace.append("    ... ").append(skippedFramework).append(" framework calls omitted ...\n");
        }
        if (!foundApp) {
            cleanedTrace.append("    [Note: No application-specific code found in stack trace]\n");
        }
        return cleanedTrace.toString();
    }

    /**
     * Builds a clean, filtered stack trace string from exception node, with Bitbucket links.
     * Only shows PROJECT frames, filters out verbose Spring/Java framework frames.
     * @param exception The exception node from Sentry event data
     * @param bitbucketCodeFetcher Service for building Bitbucket links
     * @param eventData Complete Sentry event data for commit hash extraction
     * @return Stack trace string with commit-specific Bitbucket links
     */
    public String buildStackTraceStringWithBitbucketLinks(JsonNode exception, BitbucketCodeFetcher bitbucketCodeFetcher, JsonNode eventData) {
        // Extract commit hash from Sentry event data
        String commitHash = extractCommitHashFromEvent(eventData);
        System.out.println("[StackTrace] Extracted commit hash: '" + commitHash + "'");
        
        // Auto-create Sentry release if commit hash not found
        if (commitHash == null || commitHash.isEmpty() || "not-found".equals(commitHash)) {
            System.out.println("[StackTrace] No commit hash found in event, attempting to create Sentry release...");
            try {
                String newCommitHash = sentryReleaseService.createOrEnsureSentryRelease();
                if (newCommitHash != null && !newCommitHash.isEmpty()) {
                    commitHash = newCommitHash;
                    System.out.println("[StackTrace] Auto-created Sentry release with commit: " + commitHash);
                } else {
                    System.out.println("[StackTrace] WARNING: Auto-release creation failed, proceeding without commit hash");
                }
            } catch (Exception e) {
                System.err.println("[StackTrace] ERROR: Auto-release creation failed: " + e.getMessage());
            }
        }
        
        String exceptionType = exception.has("type")
                ? exception.path("type").asText()
                : exception.path("name").asText("UnknownException");
        String exceptionValue = exception.has("value")
                ? exception.path("value").asText()
                : "";
        StringBuilder stackTrace = new StringBuilder();
        stackTrace.append(exceptionType);
        if (!exceptionValue.isEmpty()) {
            stackTrace.append(": ").append(exceptionValue);
        }
        stackTrace.append("\n");
        
        // Remove projectRoot config and use detectedAppName for filtering
        JsonNode frames = exception.path("stacktrace").path("frames");
        if (frames.isArray() && frames.size() > 0) {
            boolean foundProjectFrame = false;
            // Store first project frame for potential fallback use
            String firstProjectModule = null;
            String firstProjectFunction = null;
            String firstProjectFilename = null;
            int firstProjectLineno = -1;
            System.out.println("[StackTrace] Processing " + frames.size() + " frames with detected app name: " + detectedAppName);
            for (int i = frames.size() - 1; i >= 0; i--) {
                JsonNode frame = frames.get(i);
                String module = frame.has("module") ? frame.path("module").asText("") : "";
                String function = frame.has("function") ? frame.path("function").asText("") : "";
                String filename = frame.has("filename") ? frame.path("filename").asText("") : "UnknownFile.java";
                int lineno = frame.has("lineno") ? frame.path("lineno").asInt(-1) : (frame.has("lineNo") ? frame.path("lineNo").asInt(-1) : -1);
                System.out.println("[StackTrace] Frame " + i + ": " + module + "." + function + "(" + filename + ":" + lineno + ")");
                if (firstProjectModule == null && !module.isEmpty() && (detectedAppName.isEmpty() || module.toLowerCase().contains(detectedAppName))) {
                    firstProjectModule = module;
                    firstProjectFunction = function;
                    firstProjectFilename = filename;
                    firstProjectLineno = lineno;
                    System.out.println("[StackTrace] Found first project frame: " + module + "." + function + ":" + lineno);
                }
                // FILTER: Only include frames that contain the detected app name (or include all if not detected)
                if (detectedAppName.isEmpty() || module.toLowerCase().contains(detectedAppName)) {
                    foundProjectFrame = true;
                    stackTrace.append("    at ");
                    if (!module.isEmpty()) {
                        stackTrace.append(module).append(".");
                    }
                    stackTrace.append(function).append("(").append(filename);
                    if (lineno != -1) {
                        stackTrace.append(":" ).append(lineno);
                    }
                    stackTrace.append(")");
                    
                    // Always add Bitbucket link for this frame - NEVER hardcode URLs, use commit hash
                    String bitbucketUrl = (bitbucketCodeFetcher != null)
                        ? bitbucketCodeFetcher.buildBitbucketLinkWithCommit(module, filename, lineno, commitHash)
                        : "[Bitbucket URL unavailable - service not initialized]";
                    stackTrace.append(" [").append(bitbucketUrl).append("]");
                    stackTrace.append("\n");
                    System.out.println("[StackTrace] Included frame: " + module + "." + function + ":" + lineno);
                }
            }
            
            // If no project frame found in filtered results, show error message
            if (!foundProjectFrame) {
                if (firstProjectModule != null) {
                    System.out.println("[StackTrace] ERROR: No frames passed project root filter, but found project frame: " + firstProjectModule + "." + firstProjectFunction + ":" + firstProjectLineno);
                    stackTrace.append("    [ERROR: No frames matched stacktrace.project.root filter '").append(detectedAppName).append("']\n");
                    stackTrace.append("    [DEBUG: Found project frame but excluded by filter: ").append(firstProjectModule).append(".").append(firstProjectFunction).append("(").append(firstProjectFilename).append(":").append(firstProjectLineno).append(")]\n");
                } else {
                    System.out.println("[StackTrace] ERROR: No project frames found at all in stack trace");
                    stackTrace.append("    [ERROR: No project frames found in stack trace - check if error occurred in project code]\n");
                }
            }
        } else {
            // Absolutely no frames provided by Sentry - this should be rare
            System.out.println("[StackTrace] ERROR: No frames provided by Sentry at all");
            stackTrace.append("    [ERROR: No stack trace frames available from Sentry - exception may be synthetic or malformed]\n");
        }
        return stackTrace.toString();
    }

    // Using Sentry Data from AiAnalysisService
    // Returns the most recent stack trace
    // Must trigger an error before using
    public String getMostRecentStackTrace() {
    try {
        JsonNode mostRecentEvent = getMostRecentSentryEvent();
        if (mostRecentEvent != null) {
            JsonNode exception = getExceptionNode(mostRecentEvent);
            if (exception != null) {
                return buildStackTraceString(exception, false);
            }
        }
        return "No stack trace found in the most recent Sentry event.";
    } catch (Exception e) {
        return "Error fetching or parsing Sentry data: " + e.getMessage();
    }
}

// Using Sentry Data from AiAnalysisService
// Returns the most recent stack trace
// Must trigger an error before using
// This Method maps the code to our Github Repo, for accurate lines
public String getMostRecentStackTraceWithGithubLinks() {
    try {
        JsonNode mostRecentEvent = getMostRecentSentryEvent();
        if (mostRecentEvent != null) {
            JsonNode exception = getExceptionNode(mostRecentEvent);
            if (exception != null) {
                return buildStackTraceString(exception, true);
            }
        }
        return "No stack trace found in the most recent Sentry event.";
    } catch (Exception e) {
        return "Error fetching or parsing Sentry data: " + e.getMessage();
    }
}


// HELPER METHODS//

private JsonNode getMostRecentSentryEvent() throws Exception {
    String jsonResponse = aiAnalysisService.getMostRecentSentryError();
    ObjectMapper mapper = new ObjectMapper();
    JsonNode rootNode = mapper.readTree(jsonResponse);
    if (rootNode.isArray() && rootNode.size() > 0) {
        return rootNode.get(0);
    }
    return null;
}

// Fetches the most recent Sentry Event as a JsonNode
// Return null if not found
public JsonNode getExceptionNode(JsonNode event) {
    JsonNode entries = event.path("entries");
    if (entries.isArray()) {
        for (JsonNode entry : entries) {
            if ("exception".equals(entry.path("type").asText())) {
                JsonNode values = entry.path("data").path("values");
                if (values.isArray() && values.size() > 0) {
                    return values.get(0);
                }
            }
        }
    }
    return null;
}

// Builds a readable stack trace string from exception node
// If user wants Github links, Githublinks are added.
public String buildStackTraceString(JsonNode exception, boolean withGithubLinks) {
    // Get exception type and value
    String exceptionType = exception.has("type")
            ? exception.path("type").asText()
            : exception.path("name").asText("UnknownException");
    String exceptionValue = exception.has("value")
            ? exception.path("value").asText()
            : "";
    StringBuilder stackTrace = new StringBuilder();
    stackTrace.append(exceptionType);
    if (!exceptionValue.isEmpty()) {
        stackTrace.append(": ").append(exceptionValue);
    }
    stackTrace.append("\n");
    // Iterate through stack frames (method calls that led to exception)
    // Each frame contains info (i.e fule number, filename, line number,)
    JsonNode frames = exception.path("stacktrace").path("frames");
    if (frames.isArray()) {
        for (int i = frames.size() - 1; i >= 0; i--) {
            JsonNode frame = frames.get(i);
            String module = frame.path("module").asText("");
            String function = frame.path("function").asText("");
            String filename = frame.path("filename").asText("");
            int lineno = frame.has("lineno") ? frame.path("lineno").asInt(-1) : frame.path("lineNo").asInt(-1);

            stackTrace.append("    at ");
            if (!module.isEmpty()) {
                stackTrace.append(module).append(".");
            }
            stackTrace.append(function).append("(").append(filename);
            if (lineno != -1) {
                stackTrace.append(":").append(lineno);
            }
            stackTrace.append(")");
            // Optionally add Github link for this frame
            if (withGithubLinks) {
                String githubUrl = buildGithubLink(module, filename, lineno);
                stackTrace.append(" [").append(githubUrl).append("]");
            }
            stackTrace.append("\n");
        }
    }
    return stackTrace.toString();
}

    /**
     * Extracts commit hash from Sentry event data.
     * Looks for commit hash in release field, tags, or context.
     * @param eventData Complete Sentry event JSON
     * @return Commit hash string, or null if not found
     */
    public String extractCommitHashFromEvent(JsonNode eventData) {
        if (eventData == null) return null;
        
        // First, try to get from release field (this is set by pipeline)
        JsonNode releaseNode = eventData.path("release");
        if (!releaseNode.isMissingNode() && !releaseNode.asText().isEmpty()) {
            String release = releaseNode.asText();
            // Sentry release is set as first 7 chars of commit in pipeline
            if (release.matches("^[a-f0-9]{7}$")) {
                return release;
            }
        }
        
        // Try to get from tags
        JsonNode tagsNode = eventData.path("tags");
        if (tagsNode.isArray()) {
            for (JsonNode tag : tagsNode) {
                JsonNode keyNode = tag.path("key");
                JsonNode valueNode = tag.path("value");
                if ("commit".equals(keyNode.asText()) || "commit_hash".equals(keyNode.asText())) {
                    String commitValue = valueNode.asText();
                    if (commitValue.length() >= 7) {
                        return commitValue.substring(0, 7); // Use first 7 chars
                    }
                }
            }
        }
        
        // Try to get from context
        JsonNode contextNode = eventData.path("contexts").path("app").path("build_version");
        if (!contextNode.isMissingNode()) {
            String buildVersion = contextNode.asText();
            if (buildVersion.matches("^[a-f0-9]{7,}$")) {
                return buildVersion.substring(0, 7);
            }
        }
        
        return null; // No commit hash found
    }

    // Builds Github link for a given module, filename, and line number
    // Used to map stack frames to source code repo
    private String buildGithubLink(String module, String filename, int lineno) {
        int lastDot = module.lastIndexOf('.');
        String packagePath = lastDot != -1 ? module.substring(0, lastDot).replace('.', '/') : "";
        String githubUrl = githubRepo + "/blob/" + branch + "/" + srcRoot + packagePath + "/" + filename;
        if (lineno != -1) {
            githubUrl += "#L" + lineno;
        }
        return githubUrl;
    }

}
