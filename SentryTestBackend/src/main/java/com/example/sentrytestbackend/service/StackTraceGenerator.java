
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
    String githubRepo = "https://github.com/DoubtfulCoder/SentryTest";
    String branch = "backend";
    String srcRoot = "SentryTestBackend/src/main/java/";

    // Using Sentry Data from AiAnalysisService
    // Returns the most recent stack trace
    // Must trigger an error before using
    public String getMostRecentStackTrace() {
    try {
        String jsonResponse = aiAnalysisService.getRawSentryErrorData();
        ObjectMapper mapper = new ObjectMapper();
        JsonNode rootNode = mapper.readTree(jsonResponse);
        if (rootNode.isArray() && rootNode.size() > 0) {
            JsonNode mostRecentEvent = rootNode.get(0);
            JsonNode entries = mostRecentEvent.path("entries");
            if (entries.isArray()) {
                for (JsonNode entry : entries) {
                    if ("exception".equals(entry.path("type").asText())) {
                        JsonNode values = entry.path("data").path("values");
                        if (values.isArray() && values.size() > 0) {
                            JsonNode exception = values.get(0);
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
                            JsonNode frames = exception.path("stacktrace").path("frames");
                            if (frames.isArray()) {
                                // Sentry frames are in reverse order, so print from last to first
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
                                    stackTrace.append(")\n");
                                }
                            }
                            return stackTrace.toString();
                        }
                    }
                }
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
        String jsonResponse = aiAnalysisService.getRawSentryErrorData();
        ObjectMapper mapper = new ObjectMapper();
        JsonNode rootNode = mapper.readTree(jsonResponse);
        if (rootNode.isArray() && rootNode.size() > 0) {
            JsonNode mostRecentEvent = rootNode.get(0);
            JsonNode entries = mostRecentEvent.path("entries");
            if (entries.isArray()) {
                for (JsonNode entry : entries) {
                    if ("exception".equals(entry.path("type").asText())) {
                        JsonNode values = entry.path("data").path("values");
                        if (values.isArray() && values.size() > 0) {
                            JsonNode exception = values.get(0);
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
                            JsonNode frames = exception.path("stacktrace").path("frames");
                            if (frames.isArray()) {
                                for (int i = frames.size() - 1; i >= 0; i--) {
                                    JsonNode frame = frames.get(i);
                                    String module = frame.path("module").asText("");
                                    String function = frame.path("function").asText("");
                                    String filename = frame.path("filename").asText("");
                                    int lineno = frame.has("lineno") ? frame.path("lineno").asInt(-1) : frame.path("lineNo").asInt(-1);

                                    // Build package path from module (replace . with /)
                                    int lastDot = module.lastIndexOf('.');
                                    String packagePath = lastDot != -1 ? module.substring(0, lastDot).replace('.', '/') : "";
                                    String githubUrl = githubRepo + "/blob/" + branch + "/" + srcRoot + packagePath + "/" + filename;
                                    if (lineno != -1) {
                                        githubUrl += "#L" + lineno;
}

                                    stackTrace.append("    at ");
                                    if (!module.isEmpty()) {
                                        stackTrace.append(module).append(".");
                                    }
                                    stackTrace.append(function).append("(").append(filename);
                                    if (lineno != -1) {
                                        stackTrace.append(":").append(lineno);
                                    }
                                    stackTrace.append(") [").append(githubUrl).append("]\n");
                                }
                            }
                            return stackTrace.toString();
                        }
                    }
                }
            }
        }
        return "No stack trace found in the most recent Sentry event.";
    } catch (Exception e) {
        return "Error fetching or parsing Sentry data: " + e.getMessage();
    }
}
}
