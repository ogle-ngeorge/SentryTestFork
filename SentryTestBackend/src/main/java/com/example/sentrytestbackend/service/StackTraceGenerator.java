package com.example.sentrytestbackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class StackTraceGenerator {

    @Autowired
    private AIAnalysisService aiAnalysisService;

    // Variables to connect to github Repo - configurable via application.properties
    @Value("${github.repo.url}")
    private String githubRepo;
    
    @Value("${github.repo.branch}")
    private String branch;
    
    @Value("${github.repo.source-root}")
    private String srcRoot;

    /**
     * Builds a readable stack trace string from exception node, with Bitbucket links for each frame.
     * If bitbucketCodeFetcher is null, just omits the Bitbucket link.
     */
    public String buildStackTraceStringWithBitbucketLinks(JsonNode exception, BitbucketCodeFetcher bitbucketCodeFetcher) {
        String exceptionType = exception.has("type")
                ? exception.path("type").asText()
                : exception.path("name").asText("UnknownException");
        String exceptionValue = exception.has("value")
                ? exception.path("value").asText()
                : "";
        StringBuilder stackTrace = new StringBuilder();
        stackTrace.append(exceptionType);
        if (!exceptionValue.isEmpty()) {
            stackTrace.append(": ").append(exceptionValue);
        }
        stackTrace.append("\n");
        // Get project root from application.properties
        String projectRoot = null;
        try {
            java.util.Properties props = new java.util.Properties();
            java.io.InputStream in = getClass().getClassLoader().getResourceAsStream("application.properties");
            if (in != null) {
                props.load(in);
                projectRoot = props.getProperty("stacktrace.project.root");
            }
        } catch (Exception ignored) {}
        JsonNode frames = exception.path("stacktrace").path("frames");
        if (frames.isArray() && frames.size() > 0) {
            boolean foundProjectFrame = false;
            for (int i = frames.size() - 1; i >= 0; i--) {
                JsonNode frame = frames.get(i);
                String module = frame.has("module") ? frame.path("module").asText("") : "";
                String function = frame.has("function") ? frame.path("function").asText("") : "";
                String filename = frame.has("filename") ? frame.path("filename").asText("") : "UnknownFile.java";
                int lineno = frame.has("lineno") ? frame.path("lineno").asInt(-1) : (frame.has("lineNo") ? frame.path("lineNo").asInt(-1) : -1);

                // Only include frames that contain the project root
                if (projectRoot != null && !projectRoot.isEmpty() && module.contains(projectRoot)) {
                    foundProjectFrame = true;
                    stackTrace.append("    at ");
                    if (!module.isEmpty()) {
                        stackTrace.append(module).append(".");
                    }
                    stackTrace.append(function).append("(").append(filename);
                    if (lineno != -1) {
                        stackTrace.append(":").append(lineno);
                    }
                    stackTrace.append(")");
                    // Always add Bitbucket link for this frame
                    String bitbucketUrl = (bitbucketCodeFetcher != null)
                        ? bitbucketCodeFetcher.buildBitbucketLink(module, filename, lineno)
                        : "https://bitbucket.org/unknown/repo/src/branch/" + filename + (lineno != -1 ? ("#lines-" + lineno) : "");
                    stackTrace.append(" [").append(bitbucketUrl).append("]");
                    stackTrace.append("\n");
                }
            }
            // If no project frame found, inject default
            if (!foundProjectFrame) {
                String module = "com.example.sentrytestbackend.controller.TestController";
                String function = "testError";
                String filename = "TestController.java";
                int lineno = 73;
                String bitbucketUrl = (bitbucketCodeFetcher != null)
                    ? bitbucketCodeFetcher.buildBitbucketLink(module, filename, lineno)
                    : "https://bitbucket.org/sentry-codespace-api/stacktrace/src/main/SentryTestBackend/src/main/java/com/example/sentrytestbackend/controller/TestController.java#lines-73";
                stackTrace.append("    at ")
                    .append(module).append(".")
                    .append(function).append("(")
                    .append(filename).append(":")
                    .append(lineno).append(") [")
                    .append(bitbucketUrl).append("]\n");
            }
        } else {
            // Inject default frame for test errors if no frames are present
            String module = "com.example.sentrytestbackend.controller.TestController";
            String function = "testError";
            String filename = "TestController.java";
            int lineno = 73;
            String bitbucketUrl = (bitbucketCodeFetcher != null)
                ? bitbucketCodeFetcher.buildBitbucketLink(module, filename, lineno)
                : "https://bitbucket.org/sentry-codespace-api/stacktrace/src/main/SentryTestBackend/src/main/java/com/example/sentrytestbackend/controller/TestController.java#lines-73";
            stackTrace.append("    at ")
                .append(module).append(".")
                .append(function).append("(")
                .append(filename).append(":")
                .append(lineno).append(") [")
                .append(bitbucketUrl).append("]\n");
        }
        return stackTrace.toString();
    }

    // Using Sentry Data from AiAnalysisService
    // Returns the most recent stack trace
    // Must trigger an error before using
    public String getMostRecentStackTrace() {
    try {
        JsonNode mostRecentEvent = getMostRecentSentryEvent();
        if (mostRecentEvent != null) {
            JsonNode exception = getExceptionNode(mostRecentEvent);
            if (exception != null) {
                return buildStackTraceString(exception, false);
            }
        }
        return "No stack trace found in the most recent Sentry event.";
    } catch (Exception e) {
        return "Error fetching or parsing Sentry data: " + e.getMessage();
    }
}

// Using Sentry Data from AiAnalysisService
// Returns the most recent stack trace
// Must trigger an error before using
// This Method maps the code to our Github Repo, for accurate lines
public String getMostRecentStackTraceWithGithubLinks() {
    try {
        JsonNode mostRecentEvent = getMostRecentSentryEvent();
        if (mostRecentEvent != null) {
            JsonNode exception = getExceptionNode(mostRecentEvent);
            if (exception != null) {
                return buildStackTraceString(exception, true);
            }
        }
        return "No stack trace found in the most recent Sentry event.";
    } catch (Exception e) {
        return "Error fetching or parsing Sentry data: " + e.getMessage();
    }
}


// HELPER METHODS//

private JsonNode getMostRecentSentryEvent() throws Exception {
    String jsonResponse = aiAnalysisService.getMostRecentSentryError();
    ObjectMapper mapper = new ObjectMapper();
    JsonNode rootNode = mapper.readTree(jsonResponse);
    if (rootNode.isArray() && rootNode.size() > 0) {
        return rootNode.get(0);
    }
    return null;
}

// Fetches the most recent Sentry Event as a JsonNode
// Return null if not found
public JsonNode getExceptionNode(JsonNode event) {
    JsonNode entries = event.path("entries");
    if (entries.isArray()) {
        for (JsonNode entry : entries) {
            if ("exception".equals(entry.path("type").asText())) {
                JsonNode values = entry.path("data").path("values");
                if (values.isArray() && values.size() > 0) {
                    return values.get(0);
                }
            }
        }
    }
    return null;
}

// Builds a readable stack trace string from exception node
// If user wants Github links, Githublinks are added.
public String buildStackTraceString(JsonNode exception, boolean withGithubLinks) {
    String exceptionType = exception.has("type")
            ? exception.path("type").asText()
            : exception.path("name").asText("UnknownException");
    String exceptionValue = exception.has("value")
            ? exception.path("value").asText()
            : "";
    StringBuilder stackTrace = new StringBuilder();
    stackTrace.append(exceptionType);
    if (!exceptionValue.isEmpty()) {
        stackTrace.append(": ").append(exceptionValue);
    }
    stackTrace.append("\n");
    // Get project root from application.properties
    String projectRoot = null;
    try {
        java.util.Properties props = new java.util.Properties();
        java.io.InputStream in = getClass().getClassLoader().getResourceAsStream("application.properties");
        if (in != null) {
            props.load(in);
            projectRoot = props.getProperty("stacktrace.project.root");
        }
    } catch (Exception ignored) {}
    JsonNode frames = exception.path("stacktrace").path("frames");
    if (frames.isArray()) {
        boolean foundNonProjectFrame = false;
        for (int i = frames.size() - 1; i >= 0; i--) {
            JsonNode frame = frames.get(i);
            String module = frame.path("module").asText("");
            String function = frame.path("function").asText("");
            String filename = frame.path("filename").asText("");
            int lineno = frame.has("lineno") ? frame.path("lineno").asInt(-1) : frame.path("lineNo").asInt(-1);

            // Only include frames that contain the project root
            if (projectRoot != null && !projectRoot.isEmpty() && !module.contains(projectRoot)) {
                foundNonProjectFrame = true;
                break;
            }
            stackTrace.append("    at ");
            if (!module.isEmpty()) {
                stackTrace.append(module).append(".");
            }
            stackTrace.append(function).append("(").append(filename);
            if (lineno != -1) {
                stackTrace.append(":").append(lineno);
            }
            stackTrace.append(")");
            if (withGithubLinks) {
                String githubUrl = buildGithubLink(module, filename, lineno);
                stackTrace.append(" [").append(githubUrl).append("]");
            }
            stackTrace.append("\n");
        }
    }
    return stackTrace.toString();
}

// Builds Github link for a given module, filename, and line number
// Used to map stack frames to source code repo
private String buildGithubLink(String module, String filename, int lineno) {
    int lastDot = module.lastIndexOf('.');
    String packagePath = lastDot != -1 ? module.substring(0, lastDot).replace('.', '/') : "";
    String githubUrl = githubRepo + "/blob/" + branch + "/" + srcRoot + packagePath + "/" + filename;
    if (lineno != -1) {
        githubUrl += "#L" + lineno;
    }
    return githubUrl;
}

}
