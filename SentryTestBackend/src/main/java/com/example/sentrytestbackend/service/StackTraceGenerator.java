package com.example.sentrytestbackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class StackTraceGenerator {

    @Autowired
    private AIAnalysisService aiAnalysisService;

    // Variables to connect to github Repo
    String githubRepo = "https://github.com/ogle-ngeorge/SentryTestFork";
    String branch = "backend";
    String srcRoot = "SentryTestBackend/src/main/java/";

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
    // Get exception type and value
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
    // Iterate through stack frames (method calls that led to exception)
    // Each frame contains info (i.e fule number, filename, line number,)
    JsonNode frames = exception.path("stacktrace").path("frames");
    if (frames.isArray()) {
        for (int i = frames.size() - 1; i >= 0; i--) {
            JsonNode frame = frames.get(i);
            String module = frame.path("module").asText("");
            String function = frame.path("function").asText("");
            String filename = frame.path("filename").asText("");
            int lineno = frame.has("lineno") ? frame.path("lineno").asInt(-1) : frame.path("lineNo").asInt(-1);

            stackTrace.append("    at ");
            if (!module.isEmpty()) {
                stackTrace.append(module).append(".");
            }
            stackTrace.append(function).append("(").append(filename);
            if (lineno != -1) {
                stackTrace.append(":").append(lineno);
            }
            stackTrace.append(")");
            // Optionally add Github link for this frame
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
