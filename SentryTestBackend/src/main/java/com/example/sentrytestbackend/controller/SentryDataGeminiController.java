// Sends Sentry & Github Data to Gemini for Review

package com.example.sentrytestbackend.controller; 
import java.util.Arrays;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.LinkedHashMap;

import com.example.sentrytestbackend.service.SentryDataFetcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.example.sentrytestbackend.service.AIAnalysisService;
import io.sentry.Sentry;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.PathVariable;
import com.example.sentrytestbackend.service.GitHubCodeFetcher;
import com.example.sentrytestbackend.service.StackTraceGenerator;
import org.springframework.beans.factory.annotation.Value;

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
    // Format: http://localhost:8081/api/gemini-suggest/project/{project}/errorId/{errorId}
    // Example: http://localhost:8081/api/gemini-suggest/project/android/errorId/6748113930
    @GetMapping("/project/{project}/errorId/{errorId}")
    public ResponseEntity<Map<String, String>> reviewErrorById(
        @PathVariable String project,
        @PathVariable String errorId) {

        JsonNode errorData = sentryDataFetcher.fetchEventsByProject(
            organizationId, project, errorId);
        String stackTrace = sentryDataFetcher.fetchStackTrace(organizationId, project, errorId);
        String githubCode = githubCodeFetcher.getGithubCode(stackTrace);

        // Use callGeminiForGithubCodeAnalysis to get suggestions
        List<String> suggestions = aiAnalysisService.callGeminiForGithubCodeAnalysis(
            stackTrace,
            errorData.toString(),
            githubCode
        );

        Map<String, String> response = new LinkedHashMap<>();
        response.put("errorId", errorId);
        response.put("suggestion", String.join("\n", suggestions));
        return ResponseEntity.ok(response);
    }
}