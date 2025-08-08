package com.example.sentrytestbackend.controller;

import com.example.sentrytestbackend.service.SentryDemoStackTraceGenerator;
import com.example.sentrytestbackend.service.SentryDataFetcher;
import com.example.sentrytestbackend.service.StackTraceGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

@RestController
@RequestMapping("/api/sentry-demo")
public class SentryDemoController {

    @Autowired
    private SentryDemoStackTraceGenerator sentryDemoStackTraceGenerator;

    @Autowired
    private SentryDataFetcher sentryDataFetcher;

    @Autowired
    private StackTraceGenerator stackTraceGenerator;

    @Value("${sentry.organization.id}")
    private String organizationId;

    /**
     * POST endpoint to parse and clean Android stack traces for the sentry demo project
     * Usage: POST http://localhost:8081/api/sentry-demo/parse-android-stacktrace
     * Body: Raw Android stack trace as text
     * @param stackTrace Raw Android stack trace string
     * @return Cleaned stack trace with Bitbucket links and framework lines pruned
     */
    @PostMapping("/parse-android-stacktrace")
    public ResponseEntity<String> parseAndroidStackTrace(@RequestBody String stackTrace) {
        try {
            String cleanedStackTrace = sentryDemoStackTraceGenerator.parseAndPruneAndroidStackTrace(stackTrace);
            return ResponseEntity.ok(cleanedStackTrace);
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Error parsing stack trace: " + e.getMessage());
        }
    }

    /**
     * POST endpoint to analyze Android stack traces with detailed statistics
     * Usage: POST http://localhost:8081/api/sentry-demo/analyze-android-stacktrace
     * Body: Raw Android stack trace as text
     * @param stackTrace Raw Android stack trace string
     * @return JSON response with cleaned trace and frame statistics
     */
    @PostMapping("/analyze-android-stacktrace")
    public ResponseEntity<Map<String, Object>> analyzeAndroidStackTrace(@RequestBody String stackTrace) {
        try {
            SentryDemoStackTraceGenerator.StackTraceAnalysis analysis = 
                sentryDemoStackTraceGenerator.analyzeAndroidStackTrace(stackTrace);
            
            Map<String, Object> response = new HashMap<>();
            response.put("cleanedTrace", analysis.getCleanedTrace());
            response.put("statistics", Map.of(
                "totalFrames", analysis.getTotalFrames(),
                "appFrames", analysis.getAppFrames(),
                "frameworkFrames", analysis.getFrameworkFrames(),
                "reductionPercentage", analysis.getTotalFrames() > 0 ? 
                    Math.round((1.0 - (double) analysis.getAppFrames() / analysis.getTotalFrames()) * 100) : 0
            ));
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error analyzing stack trace: " + e.getMessage());
            return ResponseEntity.status(500).body(errorResponse);
        }
    }

    /**
     * GET endpoint to test the sentry demo configuration
     * Usage: GET http://localhost:8081/api/sentry-demo/test-config
     * @return Configuration status and sample Bitbucket link
     */
    @GetMapping("/test-config")
    public ResponseEntity<Map<String, Object>> testConfig() {
        try {
            // Test with a sample Android stack trace
            String sampleStackTrace = "java.lang.RuntimeException: Test Sentry error button pressed\n" +
                "    at com.example.demologinapp.MainActivityKt.LoginContent$lambda$25$lambda$24$lambda$23$lambda$22(MainActivity.kt:154)\n" +
                "    at com.example.demologinapp.MainActivityKt.$r8$lambda$pj0FrVxNOZR0DWYWmrwo1K5_ldU\n" +
                "    at androidx.compose.foundation.ClickableNode$clickPointerInput$3.invoke-k-4lQ0M(Clickable.kt:639)";
            
            String result = sentryDemoStackTraceGenerator.parseAndPruneAndroidStackTrace(sampleStackTrace);
            
            Map<String, Object> response = new HashMap<>();
            response.put("status", "Configuration loaded successfully");
            response.put("sampleResult", result);
            response.put("message", "Sentry Demo stack trace parser is ready to use");
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("status", "Configuration error");
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.status(500).body(errorResponse);
        }
    }

    /**
     * POST endpoint specifically for the demo login app stack traces
     * Usage: POST http://localhost:8081/api/sentry-demo/parse-login-app-trace
     * Body: Raw Android stack trace from the demo login app
     * @param stackTrace Raw Android stack trace string
     * @return Cleaned stack trace optimized for the demo login app
     */
    @PostMapping("/parse-login-app-trace")
    public ResponseEntity<Map<String, Object>> parseLoginAppTrace(@RequestBody String stackTrace) {
        try {
            SentryDemoStackTraceGenerator.StackTraceAnalysis analysis = 
                sentryDemoStackTraceGenerator.analyzeAndroidStackTrace(stackTrace);
            
            Map<String, Object> response = new HashMap<>();
            response.put("original", stackTrace);
            response.put("cleaned", analysis.getCleanedTrace());
            response.put("summary", Map.of(
                "totalFrames", analysis.getTotalFrames(),
                "appFrames", analysis.getAppFrames(),
                "frameworkFrames", analysis.getFrameworkFrames(),
                "linesRemoved", analysis.getFrameworkFrames()
            ));
            response.put("project", "Demo Login App");
            response.put("repository", "https://bitbucket.org/bluefletch/sentry-demo-app");
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error parsing login app stack trace: " + e.getMessage());
            errorResponse.put("project", "Demo Login App");
            return ResponseEntity.status(500).body(errorResponse);
        }
    }

    /**
     * GET endpoint to fetch error details specifically for sentry-demo-app project
     * Usage: GET http://localhost:8081/api/sentry-demo/project/sentry-demo-app/errorId/{errorId}
     * This endpoint uses the Android stack trace parser for proper formatting
     * @param errorId The Sentry error ID
     * @return Error details with cleaned Android stack trace and Bitbucket links
     */
    @GetMapping("/project/sentry-demo-app/errorId/{errorId}")
    public ResponseEntity<Map<String, Object>> fetchDemoAppError(@PathVariable String errorId) {
        try {
            // Fetch error data from Sentry
            JsonNode errorData = sentryDataFetcher.fetchEventsByProject(organizationId, "sentry-demo-app", errorId);

            // Get event IDs and fetch the stack trace
            List<String> eventIds = sentryDataFetcher.getEventIds(errorId);
            if (eventIds.isEmpty()) {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("error", "No event IDs found for error " + errorId);
                return ResponseEntity.status(404).body(errorResponse);
            }

            // Fetch the full event JSON with stack trace
            JsonNode eventJson = sentryDataFetcher.curlForStacktraceByEventId(errorId, eventIds.get(0));
            
            // Extract the exception node
            JsonNode exceptionNode = stackTraceGenerator.getExceptionNode(eventJson);

            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", errorData.path("id").asText());
            info.put("title", errorData.path("title").asText());
            info.put("timestamp", errorData.path("lastSeen").asText());
            info.put("projectId", errorData.path("project").path("id").asText());
            info.put("count", errorData.path("count").asInt());
            info.put("userCount", errorData.path("userCount").asInt());

            String stackTrace = "";
            String commitHash = "not-found";

            if (exceptionNode != null) {
                // Extract commit hash
                commitHash = stackTraceGenerator.extractCommitHashFromEvent(eventJson);
                if (commitHash == null || commitHash.isEmpty()) {
                    commitHash = "not-found";
                }

                // Build the standard stack trace first
                String rawStackTrace = stackTraceGenerator.buildStackTraceString(exceptionNode, false);
                
                // If this looks like an Android stack trace, use our demo parser
                if (isAndroidStackTrace(rawStackTrace)) {
                    stackTrace = sentryDemoStackTraceGenerator.parseAndPruneAndroidStackTrace(rawStackTrace);
                } else {
                    // Fall back to standard parsing
                    stackTrace = rawStackTrace;
                }
            } else {
                stackTrace = "No stack trace available for this error.";
            }

            info.put("commitHash", commitHash);
            info.put("stackTrace", stackTrace);
            info.put("codeSnippet", "Android app - code snippets not yet implemented for demo project");
            info.put("project", "sentry-demo-app");
            info.put("repository", "https://bitbucket.org/bluefletch/sentry-demo-app");

            return ResponseEntity.ok(info);
        } catch (Exception e) {
            Map<String, Object> errorInfo = new LinkedHashMap<>();
            errorInfo.put("error", "Failed to fetch demo app error details: " + e.getMessage());
            errorInfo.put("errorId", errorId);
            return ResponseEntity.status(500).body(errorInfo);
        }
    }

    /**
     * Helper method to detect if a stack trace is from an Android app
     */
    private boolean isAndroidStackTrace(String stackTrace) {
        return stackTrace.contains("androidx.") || 
               stackTrace.contains("android.") || 
               stackTrace.contains("kotlin.") ||
               stackTrace.contains("com.example.demologinapp");
    }
}
