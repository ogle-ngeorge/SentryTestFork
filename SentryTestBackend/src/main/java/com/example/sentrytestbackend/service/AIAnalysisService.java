// Connects with Sentry's PostgreSQL Database to Recieve Error Data


package com.example.sentrytestbackend.service;

import org.springframework.stereotype.Service;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;
import io.sentry.Sentry;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.io.FileWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class AIAnalysisService {
    
    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private GitHubCodeFetcher githubCodeFetcher;

    // Gemini API Configuration - now configurable via application.properties
    @Value("${gemini.api.key}")
    private String geminiApiKey;
    
    @Value("${gemini.api.model}")
    private String geminiApiModel;
    
    @Value("${gemini.api.base-url}")
    private String geminiBaseUrl;
    
    // Sentry API Configuration (for data fetching)
    @Value("${sentry.api.url}")
    private String sentryBaseUrl;
    
    @Value("${sentry.organization.id}")
    private String sentryOrgId;
    
    @Value("${sentry.default.project}")
    private String sentryDefaultProject;
    
    @Value("${sentry.api.token:}")
    private String sentryApiToken;

    // DATA RETRIEVAL METHODS // 
    // Receive Data using Sentry API
    public String readSentryErrorData() {
        try {
            return fetchErrorsFromSentryAPI();
        } catch (Exception e) {
            Sentry.captureException(e);
            return "Error Data unavailable - Sentry API connection failed: " + e.getMessage();
        }
    }
    
    // GEMINI DATA CALL GENERATE METHODS //

    // Create a code analysis using github code, recent sentry error, and stacktrace from recent sentry error
    public List<String> generateGithubCodeAnalysis(StackTraceGenerator stackTraceGenerator){
        try {
            String stackTrace = stackTraceGenerator.getMostRecentStackTraceWithGithubLinks();
            String sentryError = getMostRecentSentryError();
            String githubCode = githubCodeFetcher.getGithubCode(stackTrace);
            return callGeminiForGithubCodeAnalysis(stackTrace, sentryError, githubCode);

        } catch (Exception e) {
            Sentry.captureException(e);
            return Arrays.asList("AI Analysis unavailable");
        }  
    }

    // Using Data from readSentryErrorData, create an analysis using patterns
    public String generateAnalysis(){
        try {
            String errorData = readSentryErrorData();
            return callGeminiForAnalysis(errorData);
        } catch (Exception e) {
            Sentry.captureException(e);
            return "AI Analysis unavailable";
        }
    }

    //Interpretation of Stacktrace
    public List<String> generateTraceInterpretation(StackTraceGenerator stackTraceGenerator){
        try{
            String stackTrace = stackTraceGenerator.getMostRecentStackTraceWithGithubLinks();
            return callGeminiforStackTraceAnalysis(stackTrace);
        } catch (Exception e){
            Sentry.captureException(e);
            return Arrays.asList("Gemini AI Stack Trace Analysis Fail");
        }
    }
    // Suggestions on how to fix error
    public List<String> generateSuggestion(){
        try{
            String errorData = readSentryErrorData();
            return callGeminiForSuggestions(errorData);
        } catch (Exception e) {
            Sentry.captureException(e);
            return Arrays.asList("Unable to generate Suggestions");
        }
    }

    // GEMINI METHODS BELOW //

    // Call Gemini to Recieve x num of error and stack traces, and code snippets for review from those errors
    public List<List<String>> generateGithubCodeAnalysisForAll(StackTraceGenerator stackTraceGenerator, int maxErrors) {
        List<List<String>> allAnalyses = new ArrayList<>();
        try {
            String allErrorsJson = getRawSentryErrorData();
            ObjectMapper mapper = new ObjectMapper();
            JsonNode rootNode = mapper.readTree(allErrorsJson);
            if (rootNode.isArray()) {
                int errorCount = 0;
                for (JsonNode event : rootNode) {
                    if (errorCount > maxErrors){
                        break;
                    }
                    String singleEventArray = "[" + event.toString() + "]";
                    String stackTrace = stackTraceGenerator.getMostRecentStackTraceWithGithubLinks();
                    String sentryError = singleEventArray;
                    String githubCode = githubCodeFetcher.getGithubCode(stackTrace);
                    List<String> analysis = callGeminiForGithubCodeAnalysis(stackTrace, sentryError, githubCode);
                    allAnalyses.add(analysis);
                    errorCount++;
                }
            }
        } catch (Exception e) {
            Sentry.captureException(e);
            allAnalyses.add(Arrays.asList("AI Analysis unavailable: " + e.getMessage()));
        }
        return allAnalyses;
    }

    // Call Gemini API for code analysis
    public List<String> callGeminiForGithubCodeAnalysis(String stackTraceData, String sentryError, String githubCode){
        try{
            String prompt = createCodeAnalysisPrompt(stackTraceData, sentryError, githubCode);
            String geminiResponse = callGeminiAPI(prompt);
            return parseSuggestionsResponse(geminiResponse);
        } catch (Exception e){
            Sentry.captureException(e);
            return Arrays.asList("Gemini AI Code Review failed: " + e.getMessage());
        }
    }

    // Call Gemini API for code analysis with enhanced context (breadcrumbs, request details, error metadata)
    public List<String> callGeminiForGithubCodeAnalysisWithContext(String stackTraceData, String sentryError, String githubCode, Map<String, Object> enhancedContext){
        try{
            String prompt = createEnhancedCodeAnalysisPrompt(stackTraceData, sentryError, githubCode, enhancedContext);
            
            // Log the full prompt to JSON file for debugging
            logPromptToFile(prompt, stackTraceData, sentryError, githubCode, enhancedContext);
            
            String geminiResponse = callGeminiAPI(prompt);
            return parseSuggestionsResponse(geminiResponse);
        } catch (Exception e){
            Sentry.captureException(e);
            return Arrays.asList("Gemini AI Code Review failed: " + e.getMessage());
        }
    }

    // Call Gemini API for suggestions
    private List<String> callGeminiForSuggestions(String errorData) {
        try {
            String prompt = createSuggestionsPrompt(errorData);
            String geminiResponse = callGeminiAPI(prompt);
            return parseSuggestionsResponse(geminiResponse);
            
        } catch (Exception e) {
            Sentry.captureException(e);
            return Arrays.asList("Gemini AI Suggestions failed: " + e.getMessage());
        }
    }

    // Call Gemini API for stack trace analysis
    private List<String> callGeminiforStackTraceAnalysis(String stackTraceWithGithubLinks){
        try{
            String prompt = createStackAnalysisPrompt(stackTraceWithGithubLinks);
            String geminiResponse = callGeminiAPI(prompt);
            return parseGeminiInterpretation(geminiResponse);
        } catch (Exception e) {
            Sentry.captureException(e);
            return Arrays.asList("Gemini AI Stack Trace Analysis Fail");
        }
    }

    // Call Gemini API for error analysis
    private String callGeminiForAnalysis(String errorData) {
        try {
            String prompt = createAnalysisPrompt(errorData);
            String geminiResponse = callGeminiAPI(prompt);
            return parseGeminiResponse(geminiResponse);
            
        } catch (Exception e) {
            Sentry.captureException(e);
            return "Gemini AI Analysis failed: " + e.getMessage();
        }
    }

    // GEMINI PROMPT GENERATION METHODS //

    // Using the StackTrace, Error Message, and code from github source code
    // Create a diagnosis and analysis as to what causes the problem using Gemini
    private String createCodeAnalysisPrompt(String stackTraceData, String sentryError, String githubCode){
        return "You are an expert softare engineer analyzing stack trace data and error data from sentry. Please analyze the following lines " +
        " and try to figure out where the error is coming from and what line." +
        " Refer to the GitHub links in the stack trace for the exact code location." +
        " Refer to the Stack Trace, Errors, and Code from Github for context. " +
        " Please post the code snippet. \n\n" +
        " Stack Trace:\n" + stackTraceData + "\n\n" +
        " Sentry Error: \n" + sentryError + "\n\n" +
        " Code from github: \n" + githubCode + "\n\n";
    }

    // Create enhanced code analysis prompt with context (breadcrumbs, request details, error metadata)
    private String createEnhancedCodeAnalysisPrompt(String stackTraceData, String sentryError, String githubCode, Map<String, Object> enhancedContext) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are an expert software engineer analyzing stack trace data and error data from Sentry. Please analyze the following " +
        "and try to figure out where the error is coming from and what line. " +
        "Refer to the GitHub links in the stack trace for the exact code location. " +
        "Use the execution context (breadcrumbs, request details, error metadata) to understand the full picture. " +
        "Please be specific and actionable in your analysis.\n\n");
        
        prompt.append("Stack Trace:\n").append(stackTraceData).append("\n\n");
        prompt.append("Sentry Error Data:\n").append(sentryError).append("\n\n");
        prompt.append("GitHub Code:\n").append(githubCode).append("\n\n");

        // Add enhanced context if available
        if (enhancedContext != null && !enhancedContext.isEmpty()) {
            prompt.append("=== EXECUTION CONTEXT ===\n\n");
            
            // Add breadcrumbs
            if (enhancedContext.containsKey("breadcrumbs")) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> breadcrumbs = (List<Map<String, Object>>) enhancedContext.get("breadcrumbs");
                prompt.append("Breadcrumbs (execution trail leading to error):\n");
                for (Map<String, Object> breadcrumb : breadcrumbs) {
                    prompt.append("- ");
                    if (breadcrumb.containsKey("timestamp")) prompt.append(breadcrumb.get("timestamp")).append(" ");
                    if (breadcrumb.containsKey("level")) prompt.append("[").append(breadcrumb.get("level")).append("] ");
                    if (breadcrumb.containsKey("category")) prompt.append("(").append(breadcrumb.get("category")).append(") ");
                    if (breadcrumb.containsKey("message")) prompt.append(breadcrumb.get("message"));
                    if (breadcrumb.containsKey("data")) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> data = (Map<String, Object>) breadcrumb.get("data");
                        if (data.containsKey("method") && data.containsKey("url")) {
                            prompt.append(" - ").append(data.get("method")).append(" ").append(data.get("url"));
                        }
                    }
                    prompt.append("\n");
                }
                prompt.append("\n");
            }
            
            // Add request details
            if (enhancedContext.containsKey("request")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> request = (Map<String, Object>) enhancedContext.get("request");
                prompt.append("HTTP Request Details:\n");
                for (Map.Entry<String, Object> entry : request.entrySet()) {
                    prompt.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
                prompt.append("\n");
            }
            
            // Add error metadata
            if (enhancedContext.containsKey("error")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> error = (Map<String, Object>) enhancedContext.get("error");
                prompt.append("Error Metadata:\n");
                for (Map.Entry<String, Object> entry : error.entrySet()) {
                    prompt.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
                prompt.append("\n");
            }
            
            // Add user context
            if (enhancedContext.containsKey("user")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> user = (Map<String, Object>) enhancedContext.get("user");
                prompt.append("User Context:\n");
                for (Map.Entry<String, Object> entry : user.entrySet()) {
                    prompt.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
                prompt.append("\n");
            }
            
            // Add environment context
            if (enhancedContext.containsKey("environment")) {
                @SuppressWarnings("unchecked")
                Map<String, String> environment = (Map<String, String>) enhancedContext.get("environment");
                prompt.append("Environment:\n");
                for (Map.Entry<String, String> entry : environment.entrySet()) {
                    prompt.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
                prompt.append("\n");
            }
        }

        prompt.append("Please provide:\n" +
                     "1. Root cause analysis based on the execution flow\n" +
                     "2. Specific code location and line causing the issue\n" +
                     "3. Immediate fix recommendations\n" +
                     "4. Prevention strategies for similar errors");

        return prompt.toString();
    }


    // Create analysis prompt to interpret stack trace data for Gemini
    private String createStackAnalysisPrompt(String stackTraceData) {
        return "You are an expert softare engineer analyzing stack trace data from sentry. Please analyze the following lines " +
        " and try to figure out where the error is coming from and what line. Be sepcific and actionable in your analysis." +
        " Refer to the GitHub links in the stack trace for the exact code location. \n\n" +
        " Stack Trace:\n" + stackTraceData + "\n\n";
    }

    // Create analysis prompt for Gemini
    private String createAnalysisPrompt(String errorData) {
        return "You are an expert software engineer analyzing error data from a production system. " +
               "Please analyze the following error data and provide insights on patterns, root causes, " +
               "and severity assessment. Be specific and actionable in your analysis.\n\n" +
               "Error Data:\n" + errorData + "\n\n" +
               "Please provide:\n" +
               "1. Pattern Recognition\n" +
               "2. Root Cause Analysis\n" +
               "3. Severity Assessment\n" +
               "4. Business Impact\n" +
               "5. Recommended Actions";
    }

    // Create suggestions prompt for Gemini
    private String createSuggestionsPrompt(String errorData) {
        return "You are a senior software architect providing technical recommendations. " +
               "Based on the following error data, provide specific, actionable suggestions " +
               "for fixing and preventing these errors. Focus on practical solutions.\n\n" +
               "Error Data:\n" + errorData + "\n\n" +
               "Please provide suggestions in these categories:\n" +
               "1. Immediate fixes\n" +
               "2. Code improvements\n" +
               "3. Testing strategies\n" +
               "4. Monitoring enhancements\n" +
               "5. Long-term architecture improvements";
    }

    // Call Gemini API
    private String callGeminiAPI(String prompt) {
        try {
            // Construct Gemini API URL dynamically
            String geminiApiUrl = String.format("%s/%s:generateContent?key=%s", 
                geminiBaseUrl, geminiApiModel, geminiApiKey);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            // Create request body for Gemini API
            Map<String, Object> requestBody = new HashMap<>();
            Map<String, Object> contents = new HashMap<>();
            Map<String, Object> parts = new HashMap<>();
            parts.put("text", prompt);
            contents.put("parts", Arrays.asList(parts));
            requestBody.put("contents", Arrays.asList(contents));
            
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            
            ResponseEntity<String> response = restTemplate.exchange(
                geminiApiUrl, HttpMethod.POST, entity, String.class);
            
            return response.getBody();
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to call Gemini API", e);
        }
    }

    // PARSING GEMINI RESPONSE METHODS //

    // Parse Gemini Interpretations to make it readable
    private List<String> parseGeminiInterpretation(String geminiResponse) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode rootNode = mapper.readTree(geminiResponse);
            JsonNode candidatesNode = rootNode.path("candidates");
            if (candidatesNode.isArray() && candidatesNode.size() > 0) {
                JsonNode contentNode = candidatesNode.get(0).path("content");
                JsonNode partsNode = contentNode.path("parts");
                if (partsNode.isArray() && partsNode.size() > 0) {
                    String interpretationText = partsNode.get(0).path("text").asText();
                    String[] lines = interpretationText.split("\n");
                    List<String> result = new ArrayList<>();
                    result.add("🤖 GEMINI AI INTERPRETATION:");
                    // Add the first non-empty line as the summary
                    for (String line : lines) {
                        if (!line.trim().isEmpty()) {
                            result.add(line.trim());
                            break;
                        }
                    }
                    // Try to find a GitHub link in the response and add it
                    for (String line : lines) {
                        if (line.contains("github.com")) {
                            result.add("GitHub Link: " + line.trim());
                            break;
                        }
                    }
                    return result;
                }
            }
            return Arrays.asList("🤖 GEMINI AI INTERPRETATION:", "Unable to parse Gemini response");
        } catch (Exception e) {
            return Arrays.asList("🤖 GEMINI AI INTERPRETATION:", "Error parsing response: " + e.getMessage());
        }
    }

    // Parse Gemini Suggestions to make it readable
    private List<String> parseSuggestionsResponse(String geminiResponse) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode rootNode = mapper.readTree(geminiResponse);
            
            JsonNode candidatesNode = rootNode.path("candidates");
            if (candidatesNode.isArray() && candidatesNode.size() > 0) {
                JsonNode contentNode = candidatesNode.get(0).path("content");
                JsonNode partsNode = contentNode.path("parts");
                if (partsNode.isArray() && partsNode.size() > 0) {
                    String suggestionsText = partsNode.get(0).path("text").asText();
                    
                    // Convert text to list format
                    List<String> suggestions = new ArrayList<>();
                    suggestions.add("🤖 GEMINI AI SUGGESTIONS:");
                    suggestions.add("");
                    
                    String[] lines = suggestionsText.split("\n");
                    for (String line : lines) {
                        if (!line.trim().isEmpty()) {
                            suggestions.add(line.trim());
                        }
                    }
                    
                    return suggestions;
                }
            }
            
            return Arrays.asList("🤖 GEMINI AI SUGGESTIONS:", "Unable to parse Gemini response");
            
        } catch (Exception e) {
            return Arrays.asList("🤖 GEMINI AI SUGGESTIONS:", "Error parsing response: " + e.getMessage());
        }
    }

    // Parse Gemini response for analysis
    private String parseGeminiResponse(String geminiResponse) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode rootNode = mapper.readTree(geminiResponse);
            
            JsonNode candidatesNode = rootNode.path("candidates");
            if (candidatesNode.isArray() && candidatesNode.size() > 0) {
                JsonNode contentNode = candidatesNode.get(0).path("content");
                JsonNode partsNode = contentNode.path("parts");
                if (partsNode.isArray() && partsNode.size() > 0) {
                    String analysisText = partsNode.get(0).path("text").asText();
                    return "🤖 GEMINI AI ANALYSIS:\n\n" + analysisText;
                }
            }
            
            return "🤖 GEMINI AI ANALYSIS:\n\nUnable to parse Gemini response";
            
        } catch (Exception e) {
            return "🤖 GEMINI AI ANALYSIS:\n\nError parsing response: " + e.getMessage();
        }
    }

    // DATA RETRIEVE METHODS //    

    // Fetches raw JSON data from Sentry's API
    // Returns it as a readable summary of all error data
    private String fetchErrorsFromSentryAPI() {
        try {
            // Construct Sentry API URL for events
            String url = String.format("%s/api/0/projects/%s/%s/events/?full=true", sentryBaseUrl, sentryOrgId, sentryDefaultProject);
            
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + sentryApiToken);
            headers.set("Content-Type", "application/json");
            
            HttpEntity<String> entity = new HttpEntity<>(headers);
            
            ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.GET, entity, String.class);
            
            return processSentryAPIResponse(response.getBody());
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch errors from Sentry API", e);
        }
    }

    // Returns raw JSON string from Sentry's API
    public String getRawSentryErrorData() {
        try {
            // Construct Sentry API URL for events
            String url = String.format("%s/api/0/projects/%s/%s/events/?full=true", sentryBaseUrl, sentryOrgId, sentryDefaultProject);

            // Performs GET request on Sentry's API to recieve data
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + sentryApiToken);
            headers.set("Content-Type", "application/json");

            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.GET, entity, String.class);

            // Return the raw JSON string from Sentry
            return response.getBody();

        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch errors from Sentry API", e);
        }
    }

    // Fetches most recent error 
    // USED FOR STACK TRACES
    public String getMostRecentSentryError() {
        try {
            String allErrorsJson = getRawSentryErrorData(); // gets raw JSON array from Sentry
            ObjectMapper mapper = new ObjectMapper();
            JsonNode rootNode = mapper.readTree(allErrorsJson);
            if (rootNode.isArray() && rootNode.size() > 0) {
                JsonNode mostRecentEvent = rootNode.get(0);
                String singleEventArray = "[" + mostRecentEvent.toString() + "]";
                return singleEventArray;
            }
            return "No errors found.";
        } catch (Exception e) {
            Sentry.captureException(e);
            return "Failed to fetch most recent error: " + e.getMessage();
        }
    }
    
    // Process Sentry API response into readable format
    private String processSentryAPIResponse(String jsonResponse) {
        StringBuilder errorData = new StringBuilder();
        errorData.append("=== REAL SENTRY ERROR DATA ===\n\n");
        
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode rootNode = mapper.readTree(jsonResponse);
            
            Map<String, Integer> errorTypeCounts = new HashMap<>();
            List<String> errorMessages = new ArrayList<>();
            final int[] totalErrorsArray = {0}; // Use array to make it effectively final
            
            if (rootNode.isArray()) {
                for (JsonNode event : rootNode) {
                    totalErrorsArray[0]++;
                    
                    // Extract error type
                    String errorType = extractErrorType(event);
                    errorTypeCounts.merge(errorType, 1, Integer::sum);
                    
                    // Extract error message
                    String message = event.path("message").asText();
                    if (!message.isEmpty()) {
                        errorMessages.add(message);
                    }
                }
            }
            
            int totalErrors = totalErrorsArray[0];
            errorData.append(String.format("Total errors: %d\n\n", totalErrors));
            
            // Error type breakdown
            errorData.append("ERROR TYPE BREAKDOWN:\n");
            errorTypeCounts.forEach((type, count) -> {
                double percentage = totalErrors > 0 ? (double) count / totalErrors * 100 : 0;
                errorData.append(String.format("- %s: %d occurrences (%.1f%%)\n", 
                    type, count, percentage));
            });
            
            // Sample error messages
            errorData.append("\nSAMPLE ERROR MESSAGES:\n");
            errorMessages.stream().limit(5).forEach(msg -> 
                errorData.append(String.format("- %s\n", msg)));
            
        } catch (Exception e) {
            errorData.append("Error parsing Sentry API response: ").append(e.getMessage());
        }
        
        return errorData.toString();
    }
    
    // Parses a singular Sentry error and determines the type.
    private String extractErrorType(JsonNode event) {
        try {
            // Check exception entries
            // Looks for errors like "NullPointerException" or "ArithmeticExceptions"
            JsonNode entries = event.path("entries");
            if (entries.isArray()) {
                for (JsonNode entry : entries) {
                    if ("exception".equals(entry.path("type").asText())) {
                        JsonNode values = entry.path("data").path("values");
                        if (values.isArray() && values.size() > 0) {
                            return values.get(0).path("type").asText();
                        }
                    }
                }
            }
            // Check for message tyoe
            String message = event.path("message").asText();
            if (!message.isEmpty()){
                return "Message: " + message;
            }
            // Check for level
            String level = event.path("level").asText();
            if (!level.isEmpty()){
                return "Level" + level;
            }
            // Check platform (i.e if no exception found return "AndroidError")
            String platform = event.path("platform").asText();
            if ("android".equals(platform)) {
                return "AndroidError";
            }
        } catch (Exception e) {
            // Fallback as it returns UnknownError if it can't categorize error.
        }
        
        return "UnknownError";
    }

    // Helper method to log the full prompt and context to a JSON file
    private void logPromptToFile(String prompt, String stackTraceData, String sentryError, String githubCode, Map<String, Object> enhancedContext) {
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            String fileName = "gemini_prompt_log_" + timestamp + ".json";
            File file = new File(fileName);
            FileWriter writer = new FileWriter(file);

            Map<String, Object> logData = new HashMap<>();
            logData.put("timestamp", timestamp);
            logData.put("prompt", prompt);
            logData.put("stackTraceData", stackTraceData);
            logData.put("sentryError", sentryError);
            logData.put("githubCode", githubCode);
            logData.put("enhancedContext", enhancedContext);

            writer.write(new ObjectMapper().writeValueAsString(logData));
            writer.close();
            System.out.println("Prompt and context logged to " + fileName);
        } catch (Exception e) {
            Sentry.captureException(e);
            System.err.println("Failed to log prompt to file: " + e.getMessage());
        }
    }
}
