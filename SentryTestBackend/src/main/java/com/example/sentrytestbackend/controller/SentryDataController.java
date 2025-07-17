package com.example.sentrytestbackend.controller;

import com.example.sentrytestbackend.service.SentryDataFetcher;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sentry-errors")
public class SentryDataController {

    @Autowired
    private SentryDataFetcher sentryDataFetcher;

    // GET REQUEST TO SEE STACK TRACE FROM MOST RECENT ERROR
    // http://localhost:8081/api/sentry-errors/project
    @GetMapping("/project")
    public ResponseEntity<List<Map<String, String>>> seeAllErrors() {
        List<Map<String, String>> errors = sentryDataFetcher.fetchAllSentryProjects();
        return ResponseEntity.ok(errors);
    }
}
