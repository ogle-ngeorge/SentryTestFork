package com.example.sentrytestbackend.controller;

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
        return ResponseEntity.status(410).body("Deprecated: Use /api/sentry-errors/project/{project}/errorId/{errorId}");
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
        return ResponseEntity.status(410).body(Map.of("error", "Deprecated: Use /api/sentry-errors/project/{project}/errorId/{errorId}"));
    }

    /**
     * GET endpoint to test the sentry demo configuration
     * Usage: GET http://localhost:8081/api/sentry-demo/test-config
     * @return Configuration status and sample Bitbucket link
     */
    @GetMapping("/test-config")
    public ResponseEntity<Map<String, Object>> testConfig() {
        return ResponseEntity.status(410).body(Map.of("status", "Deprecated", "message", "Use /api/sentry-errors endpoints"));
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
        return ResponseEntity.status(410).body(Map.of("error", "Deprecated: Use /api/sentry-errors/project/{project}/errorId/{errorId}"));
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
        return ResponseEntity.status(410).body(Map.of(
            "error", "Deprecated: Use /api/sentry-errors/project/sentry-demo-app/errorId/" + errorId,
            "repository", "https://bitbucket.org/sentry-codespace-api/sentry-demo-app"
        ));
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
