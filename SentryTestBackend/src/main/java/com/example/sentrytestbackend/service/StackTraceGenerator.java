
package com.example.sentrytestbackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class StackTraceGenerator {

    @Autowired
    private AIAnalysisService aiAnalysisService;

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
                                // Try to get type and value, fallback to name
                                String exceptionType = exception.has("type")
                                        ? exception.path("type").asText()
                                        : exception.path("name").asText("UnknownException");
                                StringBuilder stackTrace = new StringBuilder();
                                stackTrace.append(exceptionType).append("\n");
                                JsonNode frames = exception.path("stacktrace").path("frames");
                                if (frames.isArray()) {
                                    for (JsonNode frame : frames) {
                                        stackTrace.append(frame.toString()).append("\n");
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
