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

@RestController
@RequestMapping("/api/sentry-errors")
public class SentryDataController {

    @Autowired
    private SentryDataFetcher sentryDataFetcher;

    // GET REQUEST TO GET ALL PROJECT NAMES
    // http://localhost:8081/api/sentry-errors/project
    @GetMapping("/project")
    public ResponseEntity<List<Map<String, String>>> fetchAllErrors() {
        List<Map<String, String>> projects = sentryDataFetcher.fetchAllSentryProjects();
        return ResponseEntity.ok(projects);
    }

    // GET REQUEST TO GET ALL ERROR NAMES FROM PROJECT
    // http://localhost:8081/api/sentry-errors?project=javascript-react
    @GetMapping
    public ResponseEntity<List<String>> fetchAllErrorTitlesByProject(@RequestParam("project") String projectName) {
        String sentryJson = sentryDataFetcher.fetchSentryErrorsByProject(projectName);
        List<String> titles = sentryDataFetcher.parseErrorTitles(sentryJson);
        return ResponseEntity.ok(titles);
    }
}
