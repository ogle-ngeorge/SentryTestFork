package com.example.sentrytestbackend.service;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.PropertySource;
import java.util.Set;
import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

@Service
@PropertySource("classpath:sentrydemo.properties")
public class SentryDemoStackTraceGenerator {

    @Value("${sentrydemo.bitbucket.workspace}")
    private String bitbucketWorkspace;
    
    @Value("${sentrydemo.bitbucket.repo.name}")
    private String bitbucketRepoName;
    
    @Value("${sentrydemo.bitbucket.repo.url}")
    private String bitbucketRepoUrl;
    
    @Value("${sentrydemo.bitbucket.repo.branch}")
    private String bitbucketRepoBranch;
    
    @Value("${sentrydemo.bitbucket.repo.srcRoot}")
    private String bitbucketRepoSrcRoot;
    
    @Value("${sentrydemo.stacktrace.project.root}")
    private String projectRoot;

    // Define Android framework packages to filter out
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

    // Define patterns for synthetic/generated code
    private static final Set<String> SYNTHETIC_PATTERNS = Set.of(
        "$$ExternalSyntheticLambda",
        "$r8$lambda$",
        "$$Lambda$",
        "$$SyntheticClass",
        "D8$$SyntheticClass",
        "$$ExternalSynthetic"
    );

    /**
     * Parses an Android stack trace string and prunes unnecessary framework lines.
     * Focuses on keeping application-specific code and removing framework noise.
     * @param stackTraceString Raw Android stack trace as a string
     * @return Cleaned stack trace with only relevant lines and Bitbucket links
     */
    public String parseAndPruneAndroidStackTrace(String stackTraceString) {
        if (stackTraceString == null || stackTraceString.isEmpty()) {
            return "Empty stack trace provided";
        }
        
        StringBuilder cleanedTrace = new StringBuilder();
        String[] lines = stackTraceString.split("\n");
        
        // Extract and add the exception line (first line)
        if (lines.length > 0) {
            cleanedTrace.append(lines[0]).append("\n");
        }
        
        boolean foundAppCode = false;
        boolean skippingFramework = false;
        int frameworkLinesSkipped = 0;
        
        // Process each stack frame line
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("at ")) {
                // Parse the stack frame components
                String frame = line.substring(3); // Remove "at " prefix
                
                // Check if this is app-specific code or framework code
                boolean isFrameworkCode = isFrameworkCode(frame);
                boolean isSyntheticCode = isSyntheticCode(frame);
                boolean isAppCode = isAppCode(frame);
                boolean isObfuscatedCode = isObfuscatedCode(frame);
                
                // Keep app-specific code that's not synthetic or obfuscated
                if (isAppCode && !isSyntheticCode && !isObfuscatedCode) {
                    if (skippingFramework && frameworkLinesSkipped > 0) {
                        cleanedTrace.append("    ... ").append(frameworkLinesSkipped).append(" framework calls omitted ...\n");
                        frameworkLinesSkipped = 0;
                    }
                    skippingFramework = false;
                    
                    // Add the stack frame with Bitbucket link
                    cleanedTrace.append("    at ").append(frame);
                    
                    // Add Bitbucket link for app code
                    if (isAppCode) {
                        String bitbucketLink = buildBitbucketLink(frame);
                        if (bitbucketLink != null) {
                            cleanedTrace.append(" [").append(bitbucketLink).append("]");
                        }
                    }
                    
                    cleanedTrace.append("\n");
                    foundAppCode = true;
                } else if (isSyntheticCode || isObfuscatedCode) {
                    // Skip synthetic/generated/obfuscated code completely
                    continue;
                } else if (!skippingFramework && foundAppCode) {
                    // Include first framework line after app code for context
                    cleanedTrace.append("    at ").append(frame).append("\n");
                    skippingFramework = true;
                } else if (skippingFramework) {
                    // Count skipped framework lines
                    frameworkLinesSkipped++;
                }
            } else if (!line.isEmpty()) {
                // Include any non-stack-frame lines (like "Caused by:")
                cleanedTrace.append(line).append("\n");
                skippingFramework = false;
            }
        }
        
        // Add final count of skipped lines if needed
        if (skippingFramework && frameworkLinesSkipped > 0) {
            cleanedTrace.append("    ... ").append(frameworkLinesSkipped).append(" framework calls omitted ...\n");
        }
        
        // Add a note if no app code was found
        if (!foundAppCode) {
            cleanedTrace.append("    [Note: No application-specific code found in stack trace]\n");
        }
        
        return cleanedTrace.toString();
    }

    /**
     * Checks if a stack frame belongs to Android framework code
     */
    private boolean isFrameworkCode(String frame) {
        for (String prefix : ANDROID_FRAMEWORK_PREFIXES) {
            if (frame.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a stack frame is synthetic/generated code
     */
    private boolean isSyntheticCode(String frame) {
        for (String pattern : SYNTHETIC_PATTERNS) {
            if (frame.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a stack frame is obfuscated code (R8/ProGuard generated)
     */
    private boolean isObfuscatedCode(String frame) {
        // R8/ProGuard obfuscated methods often have patterns like:
        // - $r8$lambda$ (already covered in synthetic)
        // - Single character method names with weird suffixes
        // - Methods with no line numbers (:0)
        // - Methods that end with random character sequences
        
        return frame.contains(":0)") || // No line number available
               frame.matches(".*\\$[a-zA-Z0-9_]{10,}.*") || // Long random suffixes after $
               frame.matches(".*\\.[a-zA-Z]\\(.*:0\\)"); // Single character method names with no line number
    }

    /**
     * Checks if a stack frame belongs to the application code
     */
    private boolean isAppCode(String frame) {
        return frame.startsWith(projectRoot);
    }

    /**
     * Builds a Bitbucket link for a given stack frame
     * @param frame The stack frame string (e.g., "com.example.demologinapp.MainActivityKt.method(MainActivity.kt:154)")
     * @return Bitbucket URL or null if cannot be parsed
     */
    private String buildBitbucketLink(String frame) {
        try {
            // Parse the frame to extract components
            // Pattern: package.Class.method(File.extension:lineNumber)
            Pattern pattern = Pattern.compile("^([a-zA-Z0-9_.]+)\\.([a-zA-Z0-9_$]+)\\(([^:]+):(\\d+)\\)");
            Matcher matcher = pattern.matcher(frame);
            
            if (matcher.find()) {
                String fullPackageAndClass = matcher.group(1);
                String methodName = matcher.group(2);
                String filename = matcher.group(3);
                int lineNumber = Integer.parseInt(matcher.group(4));
                
                // Extract just the package path (without the class name)
                // The class name is usually the last part before the method
                int lastDot = fullPackageAndClass.lastIndexOf('.');
                String packagePath = "";
                if (lastDot != -1) {
                    packagePath = fullPackageAndClass.substring(0, lastDot).replace('.', '/');
                }
                
                // Build the Bitbucket URL - use only package path + filename (no class name)
                String srcRoot = bitbucketRepoSrcRoot;
                if (!srcRoot.endsWith("/")) srcRoot += "/";
                
                String fullPath;
                if (!packagePath.isEmpty()) {
                    fullPath = srcRoot + packagePath + "/" + filename;
                } else {
                    fullPath = srcRoot + filename;
                }
                
                // Remove any duplicate path segments
                fullPath = cleanPath(fullPath);
                
                String bitbucketUrl = bitbucketRepoUrl + "/src/" + bitbucketRepoBranch + "/" + fullPath + "#lines-" + lineNumber;
                
                return bitbucketUrl;
            }
        } catch (Exception e) {
            System.err.println("[SentryDemo] Error building Bitbucket link for frame: " + frame + " - " + e.getMessage());
        }
        
        return null;
    }

    /**
     * Cleans up the path by removing duplicate segments
     */
    private String cleanPath(String path) {
        String[] parts = path.split("/");
        StringBuilder cleanedPath = new StringBuilder();
        String prev = null;
        
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (!part.equals(prev)) {
                if (cleanedPath.length() > 0) cleanedPath.append("/");
                cleanedPath.append(part);
            }
            prev = part;
        }
        
        return cleanedPath.toString();
    }

    /**
     * Parses a raw Android stack trace and returns a structured analysis
     * @param stackTraceString Raw Android stack trace
     * @return Analysis including cleaned trace, app frames count, and framework frames count
     */
    public StackTraceAnalysis analyzeAndroidStackTrace(String stackTraceString) {
        if (stackTraceString == null || stackTraceString.isEmpty()) {
            return new StackTraceAnalysis("Empty stack trace provided", 0, 0, 0);
        }
        
        String[] lines = stackTraceString.split("\n");
        int totalFrames = 0;
        int appFrames = 0;
        int frameworkFrames = 0;
        
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("at ")) {
                totalFrames++;
                String frame = line.substring(3);
                
                if (isAppCode(frame)) {
                    appFrames++;
                } else if (isFrameworkCode(frame)) {
                    frameworkFrames++;
                }
            }
        }
        
        String cleanedTrace = parseAndPruneAndroidStackTrace(stackTraceString);
        return new StackTraceAnalysis(cleanedTrace, totalFrames, appFrames, frameworkFrames);
    }

    /**
     * Data class to hold stack trace analysis results
     */
    public static class StackTraceAnalysis {
        private final String cleanedTrace;
        private final int totalFrames;
        private final int appFrames;
        private final int frameworkFrames;

        public StackTraceAnalysis(String cleanedTrace, int totalFrames, int appFrames, int frameworkFrames) {
            this.cleanedTrace = cleanedTrace;
            this.totalFrames = totalFrames;
            this.appFrames = appFrames;
            this.frameworkFrames = frameworkFrames;
        }

        public String getCleanedTrace() { return cleanedTrace; }
        public int getTotalFrames() { return totalFrames; }
        public int getAppFrames() { return appFrames; }
        public int getFrameworkFrames() { return frameworkFrames; }
    }
}
