// Redirect Links for Sentry
// Sends out Messages to the Self Hosted Sentry

package com.example.sentrytestbackend.controller; 
import java.util.Arrays;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.sentrytestbackend.service.AIAnalysisService;
import io.sentry.Sentry;

@RestController
@RequestMapping("/api") // Base Annotation for base URL paths (EX ~ )
public class TestController {

    private final Random random = new Random();

    @Autowired
    private AIAnalysisService aiAnalysisService;
    
    // GET REQUEST TO CHECK IF BACKEND RUNNING ~ Returns UP with Timestamp.
    // Makes sure Server works before running other tests
    // http://localhost:8081/api/health
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
    // http://localhost:8081/api/test-success
    @GetMapping("/test-success")
    public ResponseEntity<Map<String, Object>> testSuccess() {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "This is a successful API call");
        response.put("data", Map.of(
            "id", random.nextInt(1000), // Generates random Integer ID values
            "value", "test-data-" + random.nextInt(100)
        ));
        
        // Send a custom event to Sentry
        Sentry.addBreadcrumb("Successful API call to /test-success");
        
        return ResponseEntity.status(200).body(response);
    }

    // GET REQUEST TO SEND ERROR MESSAGE ~ Check if handling errors correctly.
    // http://localhost:8081/api/test-error
    @GetMapping("/test-error")
    public ResponseEntity<Map<String, String>> testError() {
        // Intentionally cause an error for testing
        throw new RuntimeException("This is a test error for Sentry!");
    }
    
    // GET REQUEST TO SEND DIVIDE BY ZERO ERROR
    // http://localhost:8081/api/divide-by-zero
    @GetMapping("/divide-by-zero")
    public ResponseEntity<?> testDivideByZero() {
        try {
            int x = 0;
            int result = 1 / x;
            return ResponseEntity.ok(result);
        } catch (ArithmeticException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Divide by zero error!");
            error.put("message", e.getMessage());
            Sentry.captureException(e); // Optional: send to Sentry
            return ResponseEntity.status(500).body(error);
        }
    }

    // GET REQUEST TO GET AI TO VIEW ERROR DATA
    // http://localhost:8081/api/gemini-data
    @GetMapping("/gemini-data")
    public ResponseEntity<String> getGeminiData() {
        String data = aiAnalysisService.readSentryErrorData();
        return ResponseEntity.ok(data);
    }

    // GET REQUEST TO GET GEMINI AI ANALYSIS
    // http://localhost:8081/api/gemini-analysis
    @GetMapping("/gemini-analysis")
    public ResponseEntity<String> getGeminiAnalysis() {
        String analysis = aiAnalysisService.generateAnalysis();
        return ResponseEntity.ok(analysis);
    }

    // GET REQUEST TO GET AI SUGGESTIONS
    // http://localhost:8081/api/gemini-suggestions
    @GetMapping("/gemini-suggestions")
    public ResponseEntity<List<String>> getGeminiSuggestions() {
        List<String> suggestions = aiAnalysisService.generateSuggestion();
        return ResponseEntity.ok(suggestions);
    }
}
