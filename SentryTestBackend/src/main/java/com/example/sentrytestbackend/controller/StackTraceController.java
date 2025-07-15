package com.example.sentrytestbackend.controller;

import org.springframework.http.ResponseEntity;
import com.example.sentrytestbackend.service.GitHubCodeFetcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import com.example.sentrytestbackend.service.AIAnalysisService;
import java.util.List;
import com.example.sentrytestbackend.service.StackTraceGenerator;

@RestController
@RequestMapping("/api/stacktrace")
public class StackTraceController {
    
    @Autowired
    private StackTraceGenerator stackTraceGenerator;

    @Autowired
    private AIAnalysisService aiAnalysisService;

    // GET REQUEST TO SEE STACK TRACE FROM MOST RECENT ERROR
    // http://localhost:8081/api/stacktrace/see-stack-trace
    // Currently in classic Java format
    @GetMapping("/see-stack-trace")
    public ResponseEntity<String> seeStackTrace() {
        String stackTrace = stackTraceGenerator.getMostRecentStackTrace();
        return ResponseEntity.ok(stackTrace);
    }

    // GET REQUEST TO SEE STACK TRACE FROM MOST RECENt ERROR WITH GITHUB CODE CONENCTIONS
    // http://localhost:8081/api/stacktrace/see-stack-trace-github
    // Currently in classic Java format
    @GetMapping("/see-stack-trace-github")
    public ResponseEntity<String> seeStackTraceGithub(){
        String stackTrace = stackTraceGenerator.getMostRecentStackTraceWithGithubLinks();
        return ResponseEntity.ok(stackTrace);
    }

    
    // GET REQUEST TO GET GEMINI RESPONSE ON STACKTRACE ERRORS
    // http://localhost:8081/api/stacktrace/gemini-stacktrace
    @GetMapping("/gemini-stacktrace")
    public ResponseEntity<List<String>> getGeminiStackTrace(){
        List<String> interpretation = aiAnalysisService.generateTraceInterpretation(stackTraceGenerator);
        return ResponseEntity.ok(interpretation);
    }
}
