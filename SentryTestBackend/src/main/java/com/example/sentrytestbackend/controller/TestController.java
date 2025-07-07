package com.example.sentrytestbackend.controller;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.sentry.Sentry;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*") // Allow all origins for testing
public class TestController {

    private final Random random = new Random();

    // GET REQUEST TO CHECK IF BACKEND RUNNING ~ Returns UP with Timestamp.
    // Makes sure Server works before running other tests
    @GetMapping("/health")
    // ResponseEntity sets up JSON responses for the viewer to see after HTTP Request.
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "UP");
        response.put("message", "Backend is running");
        response.put("timestamp", String.valueOf(System.currentTimeMillis()));

        // Send custom event to Sentry
        Sentry.addBreadcrumb("Backend Started and Running");

        return ResponseEntity.ok(response);
    }

    // GET REQUEST TO TEST SUCCESSFUL API CALLS ~ Make sure Sentry tracks API calls correctly.
    @GetMapping("/test-success")
    public ResponseEntity<Map<String, Object>> testSuccess() {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "This is a successful API call");
        response.put("data", Map.of(
            "id", random.nextInt(1000),
            "value", "test-data-" + random.nextInt(100)
        ));
        
        // Send a custom event to Sentry
        Sentry.addBreadcrumb("Successful API call to /test-success");
        
        return ResponseEntity.ok(response);
    }

    // GET REQUEST TO SEND ERROR MESSAGE ~ Check if handling errors correctly.
    @GetMapping("/test-error")
    public ResponseEntity<Map<String, String>> testError() {
        try {
            // Intentionally cause an error for testing
            throw new RuntimeException("This is a test error for Sentry!");
        } catch (Exception e) {
            // Capture the exception in Sentry
            Sentry.captureException(e);
            
            Map<String, String> response = new HashMap<>();
            response.put("error", "An error occurred");
            response.put("message", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }
    
    // GET REQUEST TO SEND DIVIDE BY ZERO ERROR (Isn't working ATM)
    @GetMapping("divide-by-zero")
    public ResponseEntity<Map<String, String>> testDivideByZero(){
        try {
            int x = 1;
            x = x / 0;
        } catch (Exception e) {
            Sentry.captureException(e);
            Map<String, String> response = new HashMap<>();
            response.put("error", "Tried to divide by zero!");
            response.put("message", e.getMessage());
            return ResponseEntity.status(500).body(response);

        }
    }
}
