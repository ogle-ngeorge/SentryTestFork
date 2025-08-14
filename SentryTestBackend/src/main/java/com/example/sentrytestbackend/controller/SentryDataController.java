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
import com.example.sentrytestbackend.service.SentryReleaseService;
import com.example.sentrytestbackend.service.RepoResolver;
import com.example.sentrytestbackend.service.RepoConfig;


@RestController
@RequestMapping("/api/sentry-errors")
public class SentryDataController {
    @Value("${code.host}")
    private String codeHost;
    
    // ...existing code...
    @Autowired
    private com.example.sentrytestbackend.service.BitbucketCodeFetcher bitbucketCodeFetcher;

    @Autowired
    private com.example.sentrytestbackend.service.GitHubCodeFetcher githubCodeFetcher;

    @Autowired
    private SentryDataFetcher sentryDataFetcher;

    @Autowired
    private StackTraceGenerator stackTraceGenerator;

    @Autowired
    private SentryReleaseService sentryReleaseService;

    @Autowired
    private RepoResolver repoResolver;

    @Value("${stacktrace.project.root}")
    private String stacktraceProjectRoot;
    @Value("${sentry.organization.id}")
    private String organizationId;

    // GET REQUEST TO GET ALL PROJECT NAMES
    // http://localhost:8081/api/sentry-errors/projects
    @GetMapping("/projects")
    public ResponseEntity<List<Map<String, String>>> fetchAllErrorsNames() {
        List<Map<String, String>> projects = sentryDataFetcher.fetchAllSentryProjects();
        return ResponseEntity.ok(projects);
    }

    // GET REQUEST TO FETCH BITBUCKET CODE SNIPPET
    // Format: http://localhost:8081/api/sentry-errors/bitbucket-code?url={bitbucketUrl}&context={context}&timestamp={errorTimestamp}
    @GetMapping("/bitbucket-code")
    public ResponseEntity<Map<String, Object>> fetchBitbucketCode(
            @RequestParam("url") String bitbucketUrl,
            @RequestParam(value = "context", defaultValue = "10") int context,
            @RequestParam("timestamp") String errorTimestamp) {
        try {
            String codeSnippet = bitbucketCodeFetcher.mapToBitbucketCode(bitbucketUrl, context, errorTimestamp);
            return ResponseEntity.ok(Map.of("code", codeSnippet));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                "error", "Failed to fetch Bitbucket code: " + e.getMessage()
            ));
        }
    }

    // GET REQUEST TO TEST PROJECT CONFIGURATIONS
    // Format: http://localhost:8081/api/sentry-errors/config-test?project={projectName}
    @GetMapping("/config-test")
    public ResponseEntity<Map<String, Object>> testProjectConfiguration(
            @RequestParam(value = "project", required = false) String project) {
        try {
            Map<String, Object> response = new HashMap<>();
            
            if (project != null && !project.trim().isEmpty()) {
                // Test specific project configuration
                RepoConfig config = repoResolver.resolve(project);
                response.put("project", project);
                response.put("config", Map.of(
                    "projectName", config.getProjectName(),
                    "projectRoot", config.getProjectRoot(),
                    "repoUrl", config.getRepoUrl(),
                    "branch", config.getBranch(),
                    "srcRoot", config.getSrcRoot(),
                    "workspace", config.getWorkspace(),
                    "repositoryName", config.getRepositoryName(),
                    "hasParsedBitbucketUrl", config.hasParsedBitbucketUrl()
                ));
                
                // Test URL building with project's actual package structure (dynamic)
                String projectRoot = config.getProjectRoot();
                String testModule = projectRoot + ".TestClass";  // Use project's actual root package
                String testFilename = "TestClass.java";  // Generic test file
                int testLineNumber = 1;  // Generic line number
                String testUrl = config.buildFileUrl(testModule, testFilename, testLineNumber);
                response.put("testFileUrl", testUrl);
                
                // Add URL validation test
                response.put("urlValidation", Map.of(
                    "staticBuildUrl", testUrl,
                    "message", "This URL is built using configured source.path. If invalid, search API will be used as fallback."
                ));
                
            } else {
                // Show all cached configurations
                Map<String, RepoConfig> allConfigs = repoResolver.getCachedConfigs();
                Map<String, Object> configSummary = new HashMap<>();
                
                for (Map.Entry<String, RepoConfig> entry : allConfigs.entrySet()) {
                    RepoConfig config = entry.getValue();
                    configSummary.put(entry.getKey(), Map.of(
                        "projectRoot", config.getProjectRoot(),
                        "workspace", config.getWorkspace() != null ? config.getWorkspace() : "N/A",
                        "repositoryName", config.getRepositoryName() != null ? config.getRepositoryName() : "N/A",
                        "branch", config.getBranch(),
                        "hasParsedUrl", config.hasParsedBitbucketUrl()
                    ));
                }
                
                response.put("allCachedConfigurations", configSummary);
                response.put("totalConfigs", allConfigs.size());
            }
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                "error", "Failed to test configuration: " + e.getMessage()
            ));
        }
    }

    // GET REQUEST TO GET ALL ERROR NAMES FROM PROJECT WITH OCCURRENCE COUNTS
    // Format: http://localhost:8081/api/sentry-errors?project={projectSlug}
    // http://localhost:8081/api/sentry-errors?project=sentry-demo-app
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
                errorInfo.put("userCount", issue.path("userCount").asInt()); // Add userCount
                errorList.add(errorInfo);
            }
            return ResponseEntity.ok(errorList);
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }

    // GET REQUEST TO MAP GROUPED ERRROR ID TO ERROR NAME
    // Format: http://localhost:8081/api/sentry-errors/id-error-map/project/{project}
    // http://localhost:8081/api/sentry-errors/id-error-map/project/sentry-demo-app
    @GetMapping("/id-error-map/project/{project}")
    public ResponseEntity<Map<String, String>> mapIdsWithErrorName(
        @PathVariable String project) {
        // Fetch all issues for the project
        String issuesJson = sentryDataFetcher.curlForSentryErrorDataByProject(organizationId, project);
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode issues = mapper.readTree(issuesJson);
            // Map to keep only the most recent issue for each title
            Map<String, IssueWithDate> mostRecentByTitle = new HashMap<>();

                    for (JsonNode issue : issues) {
            String issueId = issue.path("id").asText();
            String title = issue.path("title").asText();
            String lastSeen = issue.path("lastSeen").asText("");
            
            // The issues endpoint already provides all the data we need!
            // No need for additional API calls to get event data
            
            IssueWithDate prev = mostRecentByTitle.get(title);
            if (prev == null || lastSeen.compareTo(prev.lastSeen) > 0) {
                mostRecentByTitle.put(title, new IssueWithDate(issueId, lastSeen));
            }
        }

            Map<String, String> dataMap = new HashMap<>();
            for (Map.Entry<String, IssueWithDate> entry : mostRecentByTitle.entrySet()) {
                dataMap.put(entry.getValue().issueId, entry.getKey());
            }
            return ResponseEntity.ok(dataMap);
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }

    // GET REQUEST TO GET ERROR MESSAGE + STACK TRACE BY PROJECT NAME & EVENT ID WITH OCCURRENCE COUNT
    // Format: http://localhost:8081/api/sentry-errors/project/{projectSlug}/errorId/{errorId}
    // http://localhost:8081/api/sentry-errors/project/sentry-demo-app/errorId/6810289382
    @GetMapping("/project/{project}/errorId/{errorId}")
    public ResponseEntity<Map<String, Object>> fetchErrorByProjectAndId(
        @PathVariable String project,
        @PathVariable String errorId) {
        // (Global release is now set at startup, no need to set per-request)
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

            String stackTrace;
            String codeSnippet = "";
            if ("bitbucket".equalsIgnoreCase(codeHost)) {
                try {
                    stackTrace = stackTraceGenerator.buildStackTraceStringAuto(exceptionNode, bitbucketCodeFetcher, eventJson, project);
                    System.out.println("[DEBUG] Stack trace with Bitbucket links:\n" + stackTrace);
                } catch (Exception e) {
                    System.err.println("[ERROR] Failed to build stack trace for project: " + project + " - " + e.getMessage());
                    stackTrace = "Error building stack trace: " + e.getMessage();
                }
                
                try {
                    // Safely resolve repository configuration
                    RepoConfig repoConfig = repoResolver.resolve(project);
                    String srcRootFilter = repoConfig != null ? repoConfig.getSrcRoot() : "";
                    
                    String raw = bitbucketCodeFetcher.getBitbucketCodeFromStackTrace(stackTrace, 3, errorData.path("lastSeen").asText(), srcRootFilter);
                    StringBuilder filtered = new StringBuilder();
                    String[] snippets = raw.split("\\n\\nSnippet for:");
                    for (String snippet : snippets) {
                        String fullSnippet = snippet;
                        if (!snippet.startsWith("Snippet for:")) {
                            fullSnippet = "Snippet for:" + snippet;
                        }
                        if (srcRootFilter == null || srcRootFilter.isEmpty() || fullSnippet.contains(srcRootFilter.replace("/", "/"))) {
                            filtered.append(fullSnippet.trim()).append("\n\n");
                        }
                    }
                    codeSnippet = filtered.length() > 0 ? filtered.toString().trim() : "No project code snippets found.";
                } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
                    codeSnippet = "Bitbucket file not found for one or more frames.";
                } catch (Exception e) {
                    System.err.println("[ERROR] Error fetching Bitbucket code for project: " + project + " - " + e.getMessage());
                    codeSnippet = "Error fetching Bitbucket code: " + e.getMessage();
                }
            } else {
                stackTrace = stackTraceGenerator.buildStackTraceString(exceptionNode, true); // true = with GitHub links
                System.out.println("[DEBUG] Stack trace with GitHub links:\n" + stackTrace);
                try {
                    String raw = githubCodeFetcher.getGithubCode(stackTrace);
                    StringBuilder filtered = new StringBuilder();
                    String[] snippets = raw.split("\\n\\nSnippet for:");
                    for (String snippet : snippets) {
                        String fullSnippet = snippet;
                        if (!snippet.startsWith("Snippet for:")) {
                            fullSnippet = "Snippet for:" + snippet;
                        }
                        if (fullSnippet.contains(stacktraceProjectRoot)) {
                            filtered.append(fullSnippet.trim()).append("\n\n");
                        }
                    }
                    codeSnippet = filtered.length() > 0 ? filtered.toString().trim() : "No project code snippets found.";
                } catch (Exception e) {
                    codeSnippet = "Error fetching GitHub code: " + e.getMessage();
                }
            }

            // Extract commit hash from event data
            String commitHash = stackTraceGenerator.extractCommitHashFromEvent(eventJson);

            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", errorData.path("id").asText());
            info.put("title", errorData.path("title").asText());
            info.put("timestamp", errorData.path("lastSeen").asText());
            info.put("projectId", errorData.path("project").path("id").asText());
            info.put("count", errorData.path("count").asInt());
            info.put("userCount", errorData.path("userCount").asInt()); // Add userCount
            info.put("commitHash", commitHash != null ? commitHash : "not-found");
            info.put("stackTrace", stackTrace);
            info.put("codeSnippet", codeSnippet);
            System.out.println("[DEBUG] Returning error info: " + info);
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
                    String commitHash = null;
                    if (!eventIds.isEmpty()) {
                        JsonNode eventJson = sentryDataFetcher.curlForStacktraceByEventId(issueId, eventIds.get(0));
                        JsonNode exceptionNode = stackTraceGenerator.getExceptionNode(eventJson);
                        
                        // Extract commit hash from event data
                        commitHash = stackTraceGenerator.extractCommitHashFromEvent(eventJson);
                        
                        stackTrace = stackTraceGenerator.buildStackTraceStringAuto(exceptionNode, bitbucketCodeFetcher, eventJson, project);
                        try {
                            String raw = bitbucketCodeFetcher.getBitbucketCodeFromStackTrace(stackTrace, 3, issue.path("lastSeen").asText(), repoResolver.resolve(project).getSrcRoot());
                            StringBuilder filtered = new StringBuilder();
                            String[] snippets = raw.split("\\n\\nSnippet for:");
                            for (String snippet : snippets) {
                                String fullSnippet = snippet;
                                if (!snippet.startsWith("Snippet for:")) {
                                    fullSnippet = "Snippet for:" + snippet;
                                }
                                String srcRootFilter = repoResolver.resolve(project).getSrcRoot();
                                if (srcRootFilter == null || srcRootFilter.isEmpty() || fullSnippet.contains(srcRootFilter.replace("/", "/"))) {
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
                    info.put("userCount", issue.path("userCount").asInt()); // Add userCount
                    info.put("commitHash", commitHash != null ? commitHash : "not-found");
                    info.put("stackTrace", stackTrace);
                    info.put("bitbucketCode", bitbucketCodeOnly);
                    System.out.println("[DEBUG] Returning batch error info: " + info);
                    result.add(info);
                }
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }

    // GET REQUEST TO GET ERROR DATA WITH ACTIVITY IN SPECIFIED TIME PERIOD
    // Shows errors that either first occurred OR reoccurred within the time window
    // Format: http://localhost:8081/api/sentry-errors/recent?project={projectSlug}&hours={hours}
    // http://localhost:8081/api/sentry-errors/recent?project=codemap-testing (defaults to 24 hours)
    // http://localhost:8081/api/sentry-errors/recent?project=sentry-demo-app&hours=168 (1 week)
    @GetMapping("/recent")
    public ResponseEntity<List<Map<String, Object>>> fetchRecentErrorsByProject(
            @RequestParam("project") String projectName,
            @RequestParam(value = "hours", defaultValue = "24") int hours) {
        try {
            String issuesJson = sentryDataFetcher.curlForSentryErrorDataByProject(organizationId, projectName);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode issues = mapper.readTree(issuesJson);
            
            // Calculate cutoff timestamp based on specified hours
            long millisecondsAgo = System.currentTimeMillis() - (hours * 60L * 60L * 1000L);
            java.time.Instant cutoffTime = java.time.Instant.ofEpochMilli(millisecondsAgo);
            
            List<Map<String, Object>> recentErrorList = new ArrayList<>();
            for (JsonNode issue : issues) {
                String firstSeenStr = issue.path("firstSeen").asText();
                String lastSeenStr = issue.path("lastSeen").asText();
                
                boolean includeError = false;
                
                // Check firstSeen (for new errors)
                if (!firstSeenStr.isEmpty() && !firstSeenStr.equals("null")) {
                    try {
                        java.time.Instant firstSeenTime = java.time.Instant.parse(firstSeenStr);
                        if (firstSeenTime.isAfter(cutoffTime)) {
                            includeError = true;
                        }
                    } catch (java.time.format.DateTimeParseException e) {
                        // Continue to check lastSeen
                    }
                }
                
                // Check lastSeen (for recurring errors) if not already included
                if (!includeError && !lastSeenStr.isEmpty()) {
                    try {
                        java.time.Instant lastSeenTime = java.time.Instant.parse(lastSeenStr);
                        if (lastSeenTime.isAfter(cutoffTime)) {
                            includeError = true;
                        }
                    } catch (java.time.format.DateTimeParseException e) {
                        System.err.println("Failed to parse timestamps for issue: " + issue.path("id").asText());
                        continue;
                    }
                }
                
                // Include error if it has activity within the time period
                if (includeError) {
                    Map<String, Object> errorInfo = new HashMap<>();
                    errorInfo.put("id", issue.path("id").asText());
                    errorInfo.put("title", issue.path("title").asText());
                    errorInfo.put("count", issue.path("count").asInt());
                    errorInfo.put("userCount", issue.path("userCount").asInt());
                    errorInfo.put("lastSeen", lastSeenStr);
                    errorInfo.put("firstSeen", firstSeenStr);
                    errorInfo.put("level", issue.path("level").asText());
                    errorInfo.put("status", issue.path("status").asText());
                    errorInfo.put("hoursSpecified", hours); // For debugging/confirmation
                    recentErrorList.add(errorInfo);
                }
            }
            
            return ResponseEntity.ok(recentErrorList);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).build();
        }
    }
    
    // GET REQUEST TO TEST DYNAMIC URL BUILDING
    // Format: http://localhost:8081/api/sentry-errors/test-dynamic-url?project={project}&module={module}&filename={filename}&line={line}
    @GetMapping("/test-dynamic-url")
    public ResponseEntity<Map<String, Object>> testDynamicUrlBuilding(
            @RequestParam("project") String project,
            @RequestParam("module") String module,
            @RequestParam("filename") String filename,
            @RequestParam(value = "line", defaultValue = "1") int lineNumber) {
        try {
            
            System.out.println("[TestEndpoint] Testing dynamic URL building:");
            System.out.println("[TestEndpoint]   Project: " + project);
            System.out.println("[TestEndpoint]   Module: " + module);
            System.out.println("[TestEndpoint]   Filename: " + filename);
            System.out.println("[TestEndpoint]   Line: " + lineNumber);
            
            // Test both static and dynamic URL building
            RepoConfig config = repoResolver.resolve(project);
            if (config == null) {
                return ResponseEntity.status(404).body(Map.of(
                    "error", "No configuration found for project: " + project
                ));
            }
            
            // Static URL building
            String staticUrl = config.buildFileUrl(module, filename, lineNumber);
            
            // Dynamic URL building with fallback
            String dynamicUrl = repoResolver.buildFileUrlWithFallback(project, module, filename, lineNumber);
            
            Map<String, Object> response = new HashMap<>();
            response.put("project", project);
            response.put("module", module);
            response.put("filename", filename);
            response.put("lineNumber", lineNumber);
            response.put("staticUrl", staticUrl);
            response.put("dynamicUrl", dynamicUrl);
            response.put("urlsMatch", staticUrl != null && staticUrl.equals(dynamicUrl));
            response.put("projectConfig", Map.of(
                "projectRoot", config.getProjectRoot(),
                "srcRoot", config.getSrcRoot(),
                "workspace", config.getWorkspace() != null ? config.getWorkspace() : "N/A",
                "repository", config.getRepositoryName() != null ? config.getRepositoryName() : "N/A",
                "hasParsedUrl", config.hasParsedBitbucketUrl()
            ));
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                "error", "Dynamic URL test failed: " + e.getMessage()
            ));
        }
    }
    
    // GET REQUEST TO TEST DYNAMIC FILE DISCOVERY
    // Format: http://localhost:8081/api/sentry-errors/discover-file?workspace={workspace}&repo={repo}&branch={branch}&filename={filename}&package={package}
    @GetMapping("/discover-file")
    public ResponseEntity<Map<String, Object>> discoverFileLocation(
            @RequestParam("workspace") String workspace,
            @RequestParam("repo") String repository,
            @RequestParam("branch") String branch,
            @RequestParam("filename") String filename,
            @RequestParam(value = "package", required = false) String packageName) {
        try {
            
            System.out.println("[DiscoverEndpoint] Testing dynamic file discovery:");
            System.out.println("[DiscoverEndpoint]   Workspace: " + workspace);
            System.out.println("[DiscoverEndpoint]   Repository: " + repository);
            System.out.println("[DiscoverEndpoint]   Branch: " + branch);
            System.out.println("[DiscoverEndpoint]   Filename: " + filename);
            System.out.println("[DiscoverEndpoint]   Package: " + packageName);
            
            // Use enhanced file discovery
            String discoveredPath = bitbucketCodeFetcher.discoverFileLocation(workspace, repository, branch, filename, packageName);
            
            Map<String, Object> response = new HashMap<>();
            response.put("workspace", workspace);
            response.put("repository", repository);
            response.put("branch", branch);
            response.put("filename", filename);
            response.put("packageName", packageName);
            response.put("discoveredPath", discoveredPath);
            response.put("success", discoveredPath != null);
            
            if (discoveredPath != null) {
                // Build the complete Bitbucket URL
                String completeUrl = String.format("https://bitbucket.org/%s/%s/src/%s/%s", 
                    workspace, repository, branch, discoveredPath);
                response.put("bitbucketUrl", completeUrl);
                response.put("message", "File discovered successfully via dynamic search");
            } else {
                response.put("message", "File not found using any discovery strategy");
            }
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                "error", "File discovery failed: " + e.getMessage()
            ));
        }
    }
    
    // GET REQUEST TO TEST SIMPLIFIED DYNAMIC CONFIGURATION
    // Format: http://localhost:8081/api/sentry-errors/test-simplified-config?project={project}&module={module}&filename={filename}&line={line}
    @GetMapping("/test-simplified-config")
    public ResponseEntity<Map<String, Object>> testSimplifiedConfiguration(
            @RequestParam("project") String project,
            @RequestParam("module") String module,
            @RequestParam("filename") String filename,
            @RequestParam(value = "line", defaultValue = "1") int lineNumber) {
        try {
            
            System.out.println("[SimplifiedConfigTest] Testing simplified configuration approach:");
            System.out.println("[SimplifiedConfigTest]   Project: " + project);
            System.out.println("[SimplifiedConfigTest]   Module: " + module);
            System.out.println("[SimplifiedConfigTest]   Filename: " + filename);
            System.out.println("[SimplifiedConfigTest]   Line: " + lineNumber);
            
            RepoConfig config = repoResolver.resolve(project);
            if (config == null) {
                return ResponseEntity.status(404).body(Map.of(
                    "error", "No configuration found for project: " + project
                ));
            }
            
            // Inject BitbucketCodeFetcher for dynamic discovery
            config.setBitbucketCodeFetcher(bitbucketCodeFetcher);
            
            // Test both approaches
            String currentApproach = config.buildFileUrl(module, filename, lineNumber);
            String simplifiedApproach = config.buildFileUrlWithDynamicDiscovery(module, filename, lineNumber);
            
            Map<String, Object> response = new HashMap<>();
            response.put("project", project);
            response.put("module", module);
            response.put("filename", filename);
            response.put("lineNumber", lineNumber);
            
            // Current approach results
            response.put("currentApproach", Map.of(
                "method", "Static configuration with source.path",
                "url", currentApproach != null ? currentApproach : "FAILED",
                "success", currentApproach != null
            ));
            
            // Simplified approach results  
            response.put("simplifiedApproach", Map.of(
                "method", "Dynamic discovery ending at /src/",
                "url", simplifiedApproach != null ? simplifiedApproach : "FAILED",
                "success", simplifiedApproach != null
            ));
            
            // Comparison
            response.put("comparison", Map.of(
                "urlsMatch", currentApproach != null && currentApproach.equals(simplifiedApproach),
                "recommendation", simplifiedApproach != null ? 
                    "Simplified approach works - you can remove source.path configuration" :
                    "Simplified approach failed - keep current configuration for now"
            ));
            
            // Show what simplified config would look like
            if (config.hasParsedBitbucketUrl()) {
                response.put("simplifiedConfigExample", Map.of(
                    "current", String.format("project.%s.source.path=%s", project, config.getSrcRoot()),
                    "simplified", "# No source.path needed - ends at /src/ and uses dynamic discovery",
                    "bitbucketUrl", String.format("project.%s.bitbucket.url=https://bitbucket.org/%s/%s/src/%s/",
                        project, config.getWorkspace(), config.getRepositoryName(), config.getBranch())
                ));
            }
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                "error", "Simplified configuration test failed: " + e.getMessage()
            ));
        }
    }

    // Add this helper class at the end of SentryDataController
    private static class IssueWithDate {
        public final String issueId;
        public final String lastSeen;
        public IssueWithDate(String issueId, String lastSeen) {
        this.issueId = issueId;
            this.lastSeen = lastSeen;
            }
        }

    //
    // https://sentry.io/api/0/projects/noah-3t/android/issues/

}