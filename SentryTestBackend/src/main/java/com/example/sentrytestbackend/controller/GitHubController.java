package com.example.sentrytestbackend.controller;

import org.springframework.http.ResponseEntity;
import com.example.sentrytestbackend.service.GitHubCodeFetcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import com.example.sentrytestbackend.service.AIAnalysisService;
import java.util.List;
import com.example.sentrytestbackend.service.StackTraceGenerator;


@RestController
@RequestMapping("/api/github")
public class GitHubController {

    @Autowired
   private GitHubCodeFetcher gitHubCodeFetcher;
   
   @Autowired 
   private StackTraceGenerator stackTraceGenerator;

   @Autowired
   private AIAnalysisService aiAnalysisService;
 
   // GET REQUEST TO GITHUB API TO TEST LINK TO GET CODE SNIPPET
   // http://localhost:8081/api/github/test
   @GetMapping("/test")
   public String testGitHubFetcher() {
       String stackTrace = "[https://github.com/DoubtfulCoder/SentryTest/blob/backend/SentryTestBackend/src/main/java/com/example/sentrytestbackend/controller/TestController.java#L100] at jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:104)";
       try {
           String result = gitHubCodeFetcher.getGithubCode(stackTrace);
           return "<html><body><h2>GitHubCodeFetcher Test Result</h2><pre>" + result + "</pre></body></html>";
       } catch (Exception e) {
           return "<html><body><h2>Error</h2><pre>" + e.getMessage() + "</pre></body></html>";
       }
   }

    // GET REQUEST TO GEMINI API TO REVIEW CODE SNIPPET W/ STACK TRACE AND ERROR
    // http://localhost:8081/api/github/gemini-review
   @GetMapping("/gemini-review")
   public ResponseEntity<String> geminiCodeReview(){
    List<String> gemini_response = aiAnalysisService.generateGithubCodeAnalysis(stackTraceGenerator);
    return ResponseEntity.ok(String.join("\n", gemini_response));
   }

    // GET REQUEST TO GEMINI API TO REVIEW MULTIPLE CODE SNIPPETS W/ STACK TRACES AND ERROR
    // http://localhost:8081/api/github/gemini-review-multiple
   @GetMapping("/gemini-review-multiple")
   public ResponseEntity<List<List<String>>> geminiCodeReviewAll(@RequestParam(defaultValue = "4") int maxErrors) {
    List<List<String>> gemini_response = aiAnalysisService.generateGithubCodeAnalysisForAll(stackTraceGenerator, maxErrors); 
    return ResponseEntity.ok(gemini_response);
   }

   @PostMapping("/fetch-code")
   public String fetchCode(@RequestBody String stackTrace) {
       try {
           return gitHubCodeFetcher.getGithubCode(stackTrace);
       } catch (Exception e) {
           return "Error: " + e.getMessage();
       }
   }
}
