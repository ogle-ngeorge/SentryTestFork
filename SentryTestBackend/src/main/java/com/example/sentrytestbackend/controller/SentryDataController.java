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

    // GET REQUEST TO GET ALL ERROR NAMES FROM PROJECT
    // Format: http://localhost:8081/api/sentry-errors?project={projectSlug}
    // http://localhost:8081/api/sentry-errors?project=android
    @GetMapping
    public ResponseEntity<List<String>> fetchAllErrorTitlesByProject(@RequestParam("project") String projectName) {
        String sentryJson = sentryDataFetcher.fetchSentryErrorNamesByProject(projectName);
        List<String> titles = sentryDataFetcher.parseErrorTitles(sentryJson);
        return ResponseEntity.ok(titles);
    }

    // GET REQUEST TO MAP GROUPED ERRROR ID TO ERROR NAME
    // Format: http://localhost:8081/api/sentry-errors/id-error-map/project/{project}
    // http://localhost:8081/api/sentry-errors/id-error-map/project/android
    @GetMapping("/id-error-map/project/{project}")
    public ResponseEntity<Map<String, String>> mapIdsWithErroName(
        @PathVariable String project){
        Map<String, String> dataMap = sentryDataFetcher.fetchMapIdWithErrorName(organizationId, project);
        return ResponseEntity.ok(dataMap);
        }
    

    // GET REQUEST TO GET ERROR MESSAGE + STACK TRACE BY PROJECT NAME & EVENT ID
    // Format: http://localhost:8081/api/sentry-errors/project/{projectSlug}/errorId/{errorId}
    // http://localhost:8081/api/sentry-errors/project/android/errorId/6753485248
    @GetMapping("/project/{project}/errorId/{errorId}")
    public ResponseEntity<Map<String, String>> fetchErrorByProjectAndId(
        @PathVariable String project, 
        @PathVariable String errorId){
        JsonNode errorData = sentryDataFetcher.fetchEventsByProject(
        organizationId, project, errorId);

    // Fetch the full event JSON (with stacktrace)
        JsonNode eventJson = sentryDataFetcher.curlForStacktraceByEventId(
            errorData.path("id").asText(), // issueId
            sentryDataFetcher.getEventIds(errorData.path("id").asText()).get(0) // first eventId
        );

    // Extract the exception node
        JsonNode exceptionNode = stackTraceGenerator.getExceptionNode(eventJson);

    // Build the stack trace string
        String stackTrace = stackTraceGenerator.buildStackTraceString(exceptionNode, true);

        Map<String, String> info = new LinkedHashMap<>();
        info.put("id", errorData.path("id").asText());
        info.put("title", errorData.path("title").asText());
        info.put("timestamp", errorData.path("lastSeen").asText());
        info.put("projectId", errorData.path("project").path("id").asText());
        info.put("stackTrace", stackTrace);

        return ResponseEntity.ok(info);
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
