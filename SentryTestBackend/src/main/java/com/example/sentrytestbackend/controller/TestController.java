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
import com.example.sentrytestbackend.service.StackTraceGenerator;
import com.example.sentrytestbackend.config.SentrySpotlightConfig;
import io.sentry.Sentry;

@RestController
@RequestMapping("/api") // Base Annotation for base URL paths (EX ~ )
@CrossOrigin(origins = "*") // Allow all origins for testing
public class TestController {

    private final Random random = new Random();

    @Autowired
    private AIAnalysisService aiAnalysisService;
    
    @Autowired
    private SentrySpotlightConfig sentrySpotlightConfig;

    @Autowired
    private StackTraceGenerator stackTraceGenerator;

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
        try {
            // Intentionally cause an error for testing
            throw new RuntimeException("This is a test error for Sentry!");
        } catch (Exception e) {
            // Capture the exception in Sentry
            Sentry.captureException(e);
            Sentry.captureMessage("This is a test message");
            
            Map<String, String> response = new HashMap<>();
            response.put("error", "A test error occurred!");
            response.put("message", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }
    
    // GET REQUEST TO SEND DIVIDE BY ZERO ERROR (Isn't working ATM)
    // http://localhost:8081/api/divide-by-zero
    @GetMapping("/divide-by-zero")
    public ResponseEntity<Map<String, String>> testDivideByZero(){
        try {
            int x = 1;
            x = x / 0;
        } catch (Exception e) {
            Sentry.captureException(e);
            Sentry.captureMessage("Divide by Zero Error!");

            Map<String, String> response = new HashMap<>();
            response.put("error", "Tried to divide by zero!");
            response.put("message", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
        Map<String, String> response = new HashMap<>();
        response.put("result", "No error occurred");
        return ResponseEntity.ok(response);
    }

    // GET REQUEST TO VIEW ERROR DATA
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

    // GET REQUEST TO GET GEMINI RESPONSE ON STACKTRACE ERRORS
    // http://localhost:8081/api/gemini-stacktrace
    @GetMapping("/gemini-stacktrace")
    public ResponseEntity<List<String>> getGeminiStackTrace(){
        List<String> interpretation = aiAnalysisService.generateTraceInterpretation(stackTraceGenerator);
        return ResponseEntity.ok(interpretation);
    }

    // GET REQUEST TO GET AI SUGGESTIONS
    // http://localhost:8081/api/gemini-suggestions
    @GetMapping("/gemini-suggestions")
    public ResponseEntity<List<String>> getGeminiSuggestions() {
        List<String> suggestions = aiAnalysisService.generateSuggestion();
        return ResponseEntity.ok(suggestions);
    }

    // // GET REQUEST TO TEST SPOTLIGHT CONNECTION
    // // http://localhost:8081/api/test-spotlight
    // @GetMapping("/test-spotlight")
    // public ResponseEntity<Map<String, Object>> testSpotlight() {
    //     Map<String, Object> response = new HashMap<>();
        
    //     try {
    //         // Test Spotlight connection
    //         sentrySpotlightConfig.testSpotlightConnection();
            
    //         // Send various test events
    //         Sentry.addBreadcrumb("🔍 Spotlight test initiated");
    //         Sentry.captureMessage("Test message from /test-spotlight endpoint");
            
    //         // Create a test exception
    //         try {
    //             throw new RuntimeException("Test exception for Spotlight integration");
    //         } catch (Exception e) {
    //             Sentry.captureException(e);
    //         }
            
    //         response.put("success", true);
    //         response.put("message", "Spotlight test events sent successfully");
    //         response.put("spotlight_url", "http://localhost:8969");
    //         response.put("instructions", Arrays.asList(
    //             "1. Make sure Spotlight is running on localhost:8969",
    //             "2. Check your terminal for Spotlight connection messages",
    //             "3. View events in the Spotlight interface"
    //         ));
            
    //         return ResponseEntity.ok(response);
            
    //     } catch (Exception e) {
    //         Sentry.captureException(e);
    //         response.put("success", false);
    //         response.put("error", "Failed to test Spotlight connection");
    //         response.put("message", e.getMessage());
    //         return ResponseEntity.status(500).body(response);
    //     }
    // }

    // GET REQUEST TO SEE STACK TRACE FROM MOST RECENT ERROR
    // http://localhost:8081/api/see-stack-trace
    // Currently in classic Java format
    @GetMapping("/see-stack-trace")
    public ResponseEntity<String> seeStackTrace() {
        String stackTrace = stackTraceGenerator.getMostRecentStackTrace();
        return ResponseEntity.ok(stackTrace);
    }

    // GET REQUEST TO SEE STACK TRACE FROM MOST RECENt ERROR WITH GITHUB CODE CONENCTIONS
    // http://localhost:8081/api/see-stack-trace-github
    // Currently in classic Java format
    @GetMapping("/see-stack-trace-github")
    public ResponseEntity<String> seeStackTraceGithub(){
        String stackTrace = stackTraceGenerator.getMostRecentStackTraceWithGithubLinks();
        return ResponseEntity.ok(stackTrace);
    }


}
