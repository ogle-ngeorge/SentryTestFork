package com.example.sentrytestbackend.controller;

import com.example.sentrytestbackend.service.SentryDataFetcher;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.beans.factory.annotation.Value;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import com.example.sentrytestbackend.service.StackTraceGenerator;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.ArrayList;
import com.fasterxml.jackson.databind.ObjectMapper;


@RestController
@RequestMapping("/api/sentry-errors")
public class SentryDataController {
    
    // ...existing code...
    @Autowired
    private com.example.sentrytestbackend.service.BitbucketCodeFetcher bitbucketCodeFetcher;

    @Autowired
    private SentryDataFetcher sentryDataFetcher;

    @Autowired
    private StackTraceGenerator stackTraceGenerator;

    @Value("${sentry.organization.id}")
    private String organizationId;

    // GET REQUEST TO GET ALL PROJECT NAMES
    // http://localhost:8081/api/sentry-errors/projects
    @GetMapping("/projects")
    public ResponseEntity<List<Map<String, String>>> fetchAllErrorsNames() {
        List<Map<String, String>> projects = sentryDataFetcher.fetchAllSentryProjects();
        return ResponseEntity.ok(projects);
    }

    // GET REQUEST TO GET ALL ERROR NAMES FROM PROJECT WITH OCCURRENCE COUNTS
    // Format: http://localhost:8081/api/sentry-errors?project={projectSlug}
    // http://localhost:8081/api/sentry-errors?project=android
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> fetchAllErrorTitlesByProject(@RequestParam("project") String projectName) {
        try {
            String issuesJson = sentryDataFetcher.curlForSentryErrorDataByProject(organizationId, projectName);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode issues = mapper.readTree(issuesJson);
            
            List<Map<String, Object>> errorList = new ArrayList<>();
            for (JsonNode issue : issues) {
                Map<String, Object> errorInfo = new HashMap<>();
                errorInfo.put("id", issue.path("id").asText());
                errorInfo.put("title", issue.path("title").asText());
                errorInfo.put("count", issue.path("count").asInt());
                errorInfo.put("lastSeen", issue.path("lastSeen").asText());
                errorList.add(errorInfo);
            }
            return ResponseEntity.ok(errorList);
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }

    // GET REQUEST TO MAP GROUPED ERRROR ID TO ERROR NAME
    // Format: http://localhost:8081/api/sentry-errors/id-error-map/project/{project}
    // http://localhost:8081/api/sentry-errors/id-error-map/project/android
    @GetMapping("/id-error-map/project/{project}")
    public ResponseEntity<Map<String, String>> mapIdsWithErrorName(
        @PathVariable String project) {
        // Fetch all issues for the project
        String issuesJson = sentryDataFetcher.curlForSentryErrorDataByProject(organizationId, project);
        Map<String, String> dataMap = new HashMap<>();
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode issues = mapper.readTree(issuesJson);
            for (JsonNode issue : issues) {
                String issueId = issue.path("id").asText();
                // Get the most recent eventId for this issue
                List<String> eventIds = sentryDataFetcher.getEventIds(issueId);
                String title = issue.path("title").asText();
                if (!eventIds.isEmpty()) {
                    // Fetch the most recent event JSON and use its title if available
                    JsonNode eventJson = sentryDataFetcher.curlForStacktraceByEventId(issueId, eventIds.get(0));
                    if (eventJson.has("title")) {
                        title = eventJson.path("title").asText();
                    }
                }
                dataMap.put(issueId, title);
            }
            return ResponseEntity.ok(dataMap);
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }
    

    // GET REQUEST TO GET ERROR MESSAGE + STACK TRACE BY PROJECT NAME & EVENT ID WITH OCCURRENCE COUNT
    // Format: http://localhost:8081/api/sentry-errors/project/{projectSlug}/errorId/{errorId}
    // http://localhost:8081/api/sentry-errors/project/android/errorId/6748278147
    @GetMapping("/project/{project}/errorId/{errorId}")
    public ResponseEntity<Map<String, Object>> fetchErrorByProjectAndId(
        @PathVariable String project,
        @PathVariable String errorId) {
        try {
            JsonNode errorData = sentryDataFetcher.fetchEventsByProject(
                organizationId, project, errorId);

            // Fetch the full event JSON (with stacktrace)
            JsonNode eventJson = sentryDataFetcher.curlForStacktraceByEventId(
                errorData.path("id").asText(), // issueId
                sentryDataFetcher.getEventIds(errorData.path("id").asText()).get(0) // first eventId
            );

            // Extract the exception node
            JsonNode exceptionNode = stackTraceGenerator.getExceptionNode(eventJson);

            // Build the stack trace string (with Bitbucket links)
            String stackTrace = stackTraceGenerator.buildStackTraceStringWithBitbucketLinks(exceptionNode, bitbucketCodeFetcher);
            System.out.println("[DEBUG] Stack trace with Bitbucket links:\n" + stackTrace);

            // Fetch Bitbucket code snippet(s) from stack trace, handle 404 gracefully
            String bitbucketCodeOnly;
            try {
                String raw = bitbucketCodeFetcher.getBitbucketCodeFromStackTrace(stackTrace, 3, errorData.path("lastSeen").asText());
                // Split by snippet separator and filter for project files only
                StringBuilder filtered = new StringBuilder();
                String[] snippets = raw.split("\\n\\nSnippet for:");
                for (String snippet : snippets) {
                    // Ensure the separator is restored for all but the first
                    String fullSnippet = snippet;
                    if (!snippet.startsWith("Snippet for:")) {
                        fullSnippet = "Snippet for:" + snippet;
                    }
                    // Check if this snippet is for a project file
                    if (fullSnippet.contains("com/example/sentrytestbackend")) {
                        filtered.append(fullSnippet.trim()).append("\n\n");
                    }
                }
                bitbucketCodeOnly = filtered.length() > 0 ? filtered.toString().trim() : "No project code snippets found.";
            } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
                bitbucketCodeOnly = "Bitbucket file not found for one or more frames.";
            } catch (Exception e) {
                bitbucketCodeOnly = "Error fetching Bitbucket code: " + e.getMessage();
            }

            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", errorData.path("id").asText());
            info.put("title", errorData.path("title").asText());
            info.put("timestamp", errorData.path("lastSeen").asText());
            info.put("projectId", errorData.path("project").path("id").asText());
            info.put("count", errorData.path("count").asInt());
            info.put("stackTrace", stackTrace); // Section 1: stacktrace with Bitbucket links
            info.put("bitbucketCode", bitbucketCodeOnly); // Section 2: only the code snippet from Bitbucket

            return ResponseEntity.ok(info);
        } catch (Exception e) {
            Map<String, Object> errorInfo = new LinkedHashMap<>();
            errorInfo.put("error", "Failed to fetch error details: " + e.getMessage());
            return ResponseEntity.status(500).body(errorInfo);
        }
    }

    // GET REQUEST TO GET ERROR MESSAGE + STACK TRACE BY PROJECT NAME & EVENT ID WITH OCCURRENCE COUNT
    // Format: http://localhost:8081/api/sentry-errors/project/{project}/errors?ids={id1},{id2},{id3}
    // http://localhost:8081/api/sentry-errors/project/android/errors?ids=6748881802,6744676878
    @GetMapping("/project/{project}/errors")
    public ResponseEntity<List<Map<String, Object>>> fetchErrorsByIds(
        @PathVariable String project,
        @RequestParam("ids") String idsCsv) {

        // --- Old implementation (GitHub style, just id, title, stackTrace) ---
        // Set<String> ids = Arrays.stream(idsCsv.split(","))
        //     .map(String::trim)
        //     .collect(Collectors.toSet());
        //
        // String issuesJson = sentryDataFetcher.curlForSentryErrorDataByProject(organizationId, project);
        // List<Map<String, Object>> result = new ArrayList<>();
        //
        // try {
        //     ObjectMapper mapper = new ObjectMapper();
        //     JsonNode issues = mapper.readTree(issuesJson);
        //
        //     for (JsonNode issue : issues) {
        //         String issueId = issue.path("id").asText();
        //         if (ids.contains(issueId)) {
        //             String title = issue.path("title").asText();
        //             String timestamp = issue.path("lastSeen").asText();
        //             String projectId = issue.path("project").path("id").asText();
        //
        //             // Get first eventId for this issue
        //             List<String> eventIds = sentryDataFetcher.getEventIds(issueId);
        //             String stackTrace = "";
        //             if (!eventIds.isEmpty()) {
        //                 JsonNode eventJson = sentryDataFetcher.curlForStacktraceByEventId(issueId, eventIds.get(0));
        //                 JsonNode exceptionNode = stackTraceGenerator.getExceptionNode(eventJson);
        //                 stackTrace = stackTraceGenerator.buildStackTraceString(exceptionNode, true);
        //             }
        //
        //             Map<String, Object> info = new LinkedHashMap<>();
        //             info.put("id", issueId);
        //             info.put("title", title);
        //             info.put("timestamp", timestamp);
        //             info.put("projectId", projectId);
        //             info.put("count", issue.path("count").asInt());
        //             info.put("stackTrace", stackTrace);
        //
        //             result.add(info);
        //         }
        //     }
        //     return ResponseEntity.ok(result);
        // } catch (Exception e) {
        //     return ResponseEntity.status(500).build();
        // }

        // --- New implementation: id, title, timestamp, projectId, count, stackTrace, bitbucketCode ---
        Set<String> ids = Arrays.stream(idsCsv.split(","))
            .map(String::trim)
            .collect(Collectors.toSet());

        String issuesJson = sentryDataFetcher.curlForSentryErrorDataByProject(organizationId, project);
        List<Map<String, Object>> result = new ArrayList<>();

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode issues = mapper.readTree(issuesJson);

            for (JsonNode issue : issues) {
                String issueId = issue.path("id").asText();
                if (ids.contains(issueId)) {
                    String title = issue.path("title").asText();
                    String timestamp = issue.path("lastSeen").asText();
                    String projectId = issue.path("project").path("id").asText();

                    // Get first eventId for this issue
                    List<String> eventIds = sentryDataFetcher.getEventIds(issueId);
                    String stackTrace = "";
                    String bitbucketCodeOnly = "";
                    if (!eventIds.isEmpty()) {
                        JsonNode eventJson = sentryDataFetcher.curlForStacktraceByEventId(issueId, eventIds.get(0));
                        JsonNode exceptionNode = stackTraceGenerator.getExceptionNode(eventJson);
                        stackTrace = stackTraceGenerator.buildStackTraceStringWithBitbucketLinks(exceptionNode, bitbucketCodeFetcher);
                        try {
                            String raw = bitbucketCodeFetcher.getBitbucketCodeFromStackTrace(stackTrace, 3, issue.path("lastSeen").asText());
                            StringBuilder filtered = new StringBuilder();
                            String[] snippets = raw.split("\\n\\nSnippet for:");
                            for (String snippet : snippets) {
                                String fullSnippet = snippet;
                                if (!snippet.startsWith("Snippet for:")) {
                                    fullSnippet = "Snippet for:" + snippet;
                                }
                                if (fullSnippet.contains("com/example/sentrytestbackend")) {
                                    filtered.append(fullSnippet.trim()).append("\n\n");
                                }
                            }
                            bitbucketCodeOnly = filtered.length() > 0 ? filtered.toString().trim() : "No project code snippets found.";
                        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
                            bitbucketCodeOnly = "Bitbucket file not found for one or more frames.";
                        } catch (Exception e) {
                            bitbucketCodeOnly = "Error fetching Bitbucket code: " + e.getMessage();
                        }
                    }

                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("id", issueId);
                    info.put("title", title);
                    info.put("timestamp", timestamp);
                    info.put("projectId", projectId);
                    info.put("count", issue.path("count").asInt());
                    info.put("stackTrace", stackTrace);
                    info.put("bitbucketCode", bitbucketCodeOnly);

                    result.add(info);
                }
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }








    // GET REQUEST TO GET GROUPED ERROR MESSAGES FOR ALL ERRORS IN PROJECT
    // Format: http://localhost:8081/api/sentry-errors/project/{project}/errors
    // http://localhost:8081/api/sentry-errors/project/android/errors
    // @GetMapping("/project/{project}/errors")
    // public ResponseEntity<List<Map<String, String>>> fetchErrorsByTitles(
    //     @PathVariable String project,
    //     @RequestParam("ids") String idsCsv) {

    // }

    //
    // https://sentry.io/api/0/projects/noah-3t/android/issues/

}