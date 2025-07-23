// Sends Sentry & Github Data to Gemini for Review

package com.example.sentrytestbackend.controller;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.ArrayList;
import java.util.Arrays;

import com.example.sentrytestbackend.service.SentryDataFetcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.example.sentrytestbackend.service.AIAnalysisService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.PathVariable;
import com.example.sentrytestbackend.service.GitHubCodeFetcher;
import com.example.sentrytestbackend.service.StackTraceGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/gemini-suggest") // Base Annotation for base URL paths (EX ~ )
public class SentryDataGeminiController {

    @Autowired
    private AIAnalysisService aiAnalysisService;

    @Autowired
    private GitHubCodeFetcher githubCodeFetcher;

    @Autowired
    private StackTraceGenerator stackTraceController;

    @Autowired
    private SentryDataFetcher sentryDataFetcher;

    @Value("${sentry.organization.id}")
    private String organizationId;

    // GET REQUEST TO GEMINI & SENTRY TO GET SUGGESTION FOR 1 ERROR BASED ON ID
    // Format: http://localhost:8081/api/gemini-suggest/project/{project}/errorId/{errorId}?useBitbucket={true FOR BITBUCKET false FOR GITHUB}
    // Example: http://localhost:8081/api/gemini-suggest/project/android/errorId/6744676878?useBitbucket=true
    @GetMapping("/project/{project}/errorId/{errorId}")
    public ResponseEntity<Map<String, String>> reviewErrorById(
        @PathVariable String project,
        @PathVariable String errorId,
        @RequestParam(value = "useBitbucket", defaultValue = "false") boolean useBitbucket) {

        JsonNode errorData = sentryDataFetcher.fetchEventsByProject(
            organizationId, project, errorId);
        
        // Get properly formatted stack trace with GitHub links
        List<String> eventIds = sentryDataFetcher.getEventIds(errorId);
        if (eventIds.isEmpty()) {
            throw new RuntimeException("No event IDs found for issue " + errorId);
        }
        JsonNode stackTraceJson = sentryDataFetcher.curlForStacktraceByEventId(errorId, eventIds.get(0));
        JsonNode exceptionNode = stackTraceController.getExceptionNode(stackTraceJson);
        String stackTrace = stackTraceController.buildStackTraceString(exceptionNode, true); // true = with GitHub links
        
        // Extract enhanced context (breadcrumbs, request details, error metadata)
        Map<String, Object> enhancedContext = sentryDataFetcher.extractEnhancedContext(errorData);

        List<String> suggestions;
        if (useBitbucket) {
            // Fetch code from Bitbucket and use the same analysis method
            String bitbucketCode = githubCodeFetcher.getBitbucketCode(stackTrace);
            suggestions = aiAnalysisService.callGeminiForGithubCodeAnalysisWithContext(
                stackTrace,
                errorData.toString(),
                bitbucketCode,
                enhancedContext
            );
        } else {
            // Default: use GitHub
            String githubCode = githubCodeFetcher.getGithubCode(stackTrace);
            suggestions = aiAnalysisService.callGeminiForGithubCodeAnalysisWithContext(
                stackTrace,
                errorData.toString(),
                githubCode,
                enhancedContext
            );
        }

        Map<String, String> response = new LinkedHashMap<>();
        response.put("errorId", errorId);
        // Format the suggestion for neatness
        StringBuilder formatted = new StringBuilder();
        formatted.append("\uD83E\uDD16 **Gemini AI Suggestions**\n\n");
        for (String suggestion : suggestions) {
            // Add spacing and markdown code block if not present
            if (!suggestion.trim().isEmpty()) {
                formatted.append(suggestion.trim()).append("\n\n");
            }
        }
        response.put("suggestion", formatted.toString().trim());
        return ResponseEntity.ok(response);
    }

    // GET REQUEST TO GEMINI & SENTRY TO GET SUGGESTION MULTIPLE ERRORS BASED ON ID
    // Format: http://localhost:8081/api/gemini-suggest/batch/project/{project}/errors?ids={id1},{id2},{id3}
    // http://localhost:8081/api/gemini-suggest/batch/project/android/errors?ids=6744676878,6745069181
    @GetMapping("/batch/project/{project}/errors")
    public ResponseEntity<List<Map<String, Object>>> reviewMultipleErrorsByID(
        @PathVariable String project,
        @RequestParam("ids") String idsCsv) {

        Set<String> ids = Arrays.stream(idsCsv.split(","))
            .map(String::trim)
            .collect(Collectors.toSet());

        List<Map<String, Object>> batchResults = new ArrayList<>();

        for (String errorId : ids) {
            JsonNode errorData = sentryDataFetcher.fetchEventsByProject(organizationId, project, errorId);
            
            // Get properly formatted stack trace with GitHub links for each error
            List<String> eventIds = sentryDataFetcher.getEventIds(errorId);
            if (eventIds.isEmpty()) {
                // Skip this error if no event IDs found
                continue;
            }
            JsonNode stackTraceJson = sentryDataFetcher.curlForStacktraceByEventId(errorId, eventIds.get(0));
            JsonNode exceptionNode = stackTraceController.getExceptionNode(stackTraceJson);
            String stackTrace = stackTraceController.buildStackTraceString(exceptionNode, true); // true = with GitHub links
            
            // Now fetch GitHub code using the formatted stack trace with links
            String githubCode = githubCodeFetcher.getGithubCode(stackTrace);
            
            // Extract enhanced context for each error
            Map<String, Object> enhancedContext = sentryDataFetcher.extractEnhancedContext(errorData);

            List<String> suggestions = aiAnalysisService.callGeminiForGithubCodeAnalysisWithContext(
                stackTrace,
                errorData.toString(),
                githubCode,
                enhancedContext
            );

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("errorId", errorId);
            result.put("suggestions", suggestions);
            batchResults.add(result);

            try {
                Thread.sleep(500); // 500ms delay between Gemini requests
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        return ResponseEntity.ok(batchResults);
    }
}