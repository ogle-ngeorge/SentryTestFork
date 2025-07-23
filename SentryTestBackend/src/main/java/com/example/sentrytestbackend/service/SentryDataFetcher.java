package com.example.sentrytestbackend.service;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sentry.Sentry;
import com.example.sentrytestbackend.service.SentryErrorObject;

// Methods used to Fetch Data from Sentry
// Although other classes has data fetch methods, from now on
// Newer methods and classes will refer to this for code clarity
// and organziation

@Service
public class SentryDataFetcher {

// VARIABLES //
    // Sentry API Configuration (for data fetching)
    @Value("${sentry.api.url:https://noah-3t.sentry.io}")
    private String sentryBaseUrl;
    
    @Value("${sentry.api.token:}")
    private String sentryApiToken;

    @Autowired
    private RestTemplate restTemplate;

// GETTER METHODS //
// Collection of methods to recieve Data from Sentry

// Collects errors from Sentry as a JSON Array
// Param ~ String project name to choose what erros you want from which project
    public String fetchAllSentryErrors(String projectName) {
        try{
            String url = String.format("%s/api/0/projects/noah-3t/%s/events/?full=true", sentryBaseUrl, projectName);

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + sentryApiToken);
            headers.set("Content-Type", "application/json");

            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            return response.getBody();

        } catch (Exception e){
            e.printStackTrace();
            Sentry.captureException(e);
            throw new RuntimeException("Unable to fetch data from Sentry for project:" + projectName);
        }
    }

// Fetches Project Names & IDs from each Sentry User
    public List<Map<String, String>> fetchAllSentryProjects() {
        try {
            String url = String.format("%s/api/0/projects/", sentryBaseUrl);

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + sentryApiToken);
            headers.set("Content-Type", "application/json");

            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            ObjectMapper mapper = new ObjectMapper();
            JsonNode data = mapper.readTree(response.getBody());

            List<Map<String, String>> projects = new ArrayList<>();
            for (JsonNode project : data) {
                Map<String, String> projectInfo = new HashMap<>();
                projectInfo.put("id", project.path("id").asText());
                projectInfo.put("name", project.path("name").asText());
                projects.add(projectInfo);
            }
            return projects;
        } catch (Exception e) {
            e.printStackTrace();
            Sentry.captureException(e);
            throw new RuntimeException("Unable to fetch Sentry projects");
        }
    }

// Gives String ArrayList of Project Names for Given Sentry User or Org
// Param ~ String of Project Name to get errors from
// Returns ArrayList of Project Names
    public String fetchSentryErrorNamesByProject(String projectName) {
        try {
            // Replace "noah-3t" with your actual organization slug if different
            String url = String.format("%s/api/0/projects/noah-3t/%s/events/?full=true", sentryBaseUrl, projectName);

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + sentryApiToken);
            headers.set("Content-Type", "application/json");

            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            return response.getBody();

        } catch (Exception e) {
            e.printStackTrace();
            Sentry.captureException(e);
            throw new RuntimeException("Unable to fetch data from Sentry for project:" + projectName);
        }
    }

// Fetches a specific Event error message based on project and Eventid
// Parameter ~ String organization id from sentry
// Parameter ~ String project name
// Parameter ~ String errorId
// Returns String Sentry Event by Id
    public JsonNode fetchEventsByProject(String organizationId, String projectSlug, String errorId){
        try {
            String issuesJson = curlForSentryErrorDataByProject(organizationId, projectSlug);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode errorData = mapper.readTree(issuesJson); 

            for (JsonNode event : errorData) {
                if (event.path("id").asText().equals(errorId)) {
                    return event;
                }    
            } 
            throw new RuntimeException("Event with id " + errorId + " not found in project " + projectSlug);   
        } catch (Exception e) {
            Sentry.captureException(e);
            throw new RuntimeException("Unable to fetch single error from Sentry");
        }   
    }

// Fetches stack trace for specific issues
    public String fetchStackTrace(String organizationId, String projectSlug, String issueId){
        List<String> eventId = getEventIds(issueId);
        if (eventId.isEmpty()) {
            throw new RuntimeException("No event IDs found for issue " + issueId);
        }
        JsonNode stackTraceJson = curlForStacktraceByEventId(issueId, eventId.get(0));
        return stackTraceJson.toString();
    }

    public Map<String, String> fetchMapIdWithErrorName(String organizationId, String projectSlug){
        try {
            String issuesJson = curlForSentryErrorDataByProject(organizationId, projectSlug);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode data = mapper.readTree(issuesJson);

            Map<String, String> result = new HashMap<>();
            for (JsonNode event : data) {
                Map<String, String> map = new HashMap<>();
                String id = "event_id: " + event.path("id").asText();
                String name = "error_name: " + event.path("title").asText();
                result.put(id, name);
            }
            return result;
        } catch (Exception e){
            Sentry.captureException(e);
            throw new RuntimeException("Unable to fetch error id-name map from Sentry");
        }
    }

    public Set<String> fetchErrorIdList(String organizationId, String projectSlug){
        try{
            String issuesJson = curlForSentryErrorDataByProject(organizationId, projectSlug);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode data = mapper.readTree(issuesJson);

            Set<String> result = new HashSet<>();
            for (JsonNode event : data){
                String eventId = event.path("id").asText();
                result.add(eventId);
            }   
            return result;
        } catch (Exception e){
            Sentry.captureException(e);
            throw new RuntimeException("Unable to fetch list of error ids from Sentry");
        }
    }
// HELPER METHODS //
// Collection of methods to help accomplish tasks
// (i.e, generate random IDs, Parsing, Curling, Formatting)


// Curls to recieve Sentry Errors
// Only shows one unique error data per error name
// Requires Orgnization_id & project_slug (project name)
// Same as this endpoint: https://sentry.io/api/0/projects/noah-3t/android/issues/
    public String curlForSentryErrorDataByProject(String organizationId, String projectSlug){
        String url = String.format("%s/api/0/projects/%s/%s/issues/", sentryBaseUrl, organizationId, projectSlug);
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + sentryApiToken);
        headers.set("Content-Type", "application/json");
        HttpEntity<String> entity = new HttpEntity<>(headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
        String issuesJson = response.getBody();
        return issuesJson; // Returns JSON string
    }

// Curls to recieve Event ID to get stacktrace
// Requires Event issue ID and eventId (from getEventIds)
    public JsonNode curlForStacktraceByEventId(String issueId, String eventId) {
        try {
            String url = String.format("%s/api/0/issues/%s/events/%s/", sentryBaseUrl, issueId, eventId);
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + sentryApiToken);
            headers.set("Content-Type", "application/json");
            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            ObjectMapper mapper = new ObjectMapper();
            return mapper.readTree(response.getBody()); // Returns the full event JSON (including stacktrace)
        } catch (Exception e) {
            Sentry.captureException(e);
            throw new RuntimeException("Unable to fetch stacktrace from Sentry");
        }
    }

// Returns eventId to get stacktrace
// Unique id for individual errors
// Requires issueId
    public List<String> getEventIds(String issueId){
        try {
            String url = String.format("%s/api/0/issues/%s/events/", sentryBaseUrl, issueId);
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + sentryApiToken);
            headers.set("Content-Type", "application/json");
            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            ObjectMapper mapper = new ObjectMapper();
            JsonNode events = mapper.readTree(response.getBody());
            List<String> eventIds = new ArrayList<>();
            for (JsonNode event : events) {
                String eventId = event.path("eventID").asText();
                if (!eventId.isEmpty()) {
                    eventIds.add(eventId);
                }
            }
            return eventIds;
        } catch (Exception e) {
            Sentry.captureException(e);
            throw new RuntimeException("Unable to fetch event IDs from Sentry");
        }
    }

// Parses Sentry errors and Returns the titles
// Params ~ JSON Erros from Sentry
// Returns List<String> ArrayList of All Error Titles
    public List<String> parseErrorTitles(String sentryErrors){
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode data = mapper.readTree(sentryErrors);

            Set<String> uniqueTitles = new HashSet<>();
            for (JsonNode event : data) {
                String title = event.path("title").asText();
                if (!title.isEmpty()) {
                    uniqueTitles.add(title);
                }
            }
            return new ArrayList<>(uniqueTitles);
        } catch (Exception e) {
            Sentry.captureException(e);
            throw new RuntimeException("Unable to parse error titles from Sentry JSON");
        }
    }

// Generate random ID for grouped errors
public String randomUUID(){
    return UUID.randomUUID().toString();
}

// Extract enhanced context from Sentry event data for better Gemini analysis
public Map<String, Object> extractEnhancedContext(JsonNode eventData) {
    Map<String, Object> enhancedContext = new HashMap<>();
    
    try {
        // Extract breadcrumbs
        List<Map<String, Object>> breadcrumbs = extractBreadcrumbs(eventData);
        if (!breadcrumbs.isEmpty()) {
            enhancedContext.put("breadcrumbs", breadcrumbs);
        }
        
        // Extract HTTP request details
        Map<String, Object> requestDetails = extractRequestDetails(eventData);
        if (!requestDetails.isEmpty()) {
            enhancedContext.put("request", requestDetails);
        }
        
        // Extract error metadata
        Map<String, Object> errorMetadata = extractErrorMetadata(eventData);
        if (!errorMetadata.isEmpty()) {
            enhancedContext.put("error", errorMetadata);
        }
        
        // Extract basic context info
        if (eventData.has("user") && !eventData.path("user").isEmpty()) {
            JsonNode user = eventData.path("user");
            Map<String, Object> userInfo = new HashMap<>();
            if (user.has("ip_address") && !user.path("ip_address").isNull()) {
                userInfo.put("ip_address", user.path("ip_address").asText());
            }
            if (user.has("geo") && !user.path("geo").isEmpty()) {
                JsonNode geo = user.path("geo");
                Map<String, String> geoInfo = new HashMap<>();
                if (geo.has("country_code")) geoInfo.put("country", geo.path("country_code").asText());
                if (geo.has("city")) geoInfo.put("city", geo.path("city").asText());
                if (!geoInfo.isEmpty()) userInfo.put("geo", geoInfo);
            }
            if (!userInfo.isEmpty()) {
                enhancedContext.put("user", userInfo);
            }
        }
        
        // Extract environment info
        if (eventData.has("tags") && eventData.path("tags").isArray()) {
            Map<String, String> environmentInfo = new HashMap<>();
            for (JsonNode tag : eventData.path("tags")) {
                String key = tag.path("key").asText();
                String value = tag.path("value").asText();
                if ("environment".equals(key) || "release".equals(key) || 
                    "transaction".equals(key) || "url".equals(key)) {
                    environmentInfo.put(key, value);
                }
            }
            if (!environmentInfo.isEmpty()) {
                enhancedContext.put("environment", environmentInfo);
            }
        }
        
    } catch (Exception e) {
        Sentry.captureException(e);
        // Return what we have so far, don't fail completely
    }
    
    return enhancedContext;
}

// Extract breadcrumbs from Sentry event data
private List<Map<String, Object>> extractBreadcrumbs(JsonNode eventData) {
    List<Map<String, Object>> breadcrumbs = new ArrayList<>();
    
    try {
        if (eventData.has("entries") && eventData.path("entries").isArray()) {
            for (JsonNode entry : eventData.path("entries")) {
                if ("breadcrumbs".equals(entry.path("type").asText()) && 
                    entry.has("data") && entry.path("data").has("values") &&
                    entry.path("data").path("values").isArray()) {
                    
                    for (JsonNode crumb : entry.path("data").path("values")) {
                        Map<String, Object> breadcrumb = new HashMap<>();
                        
                        if (crumb.has("timestamp") && !crumb.path("timestamp").isNull()) {
                            breadcrumb.put("timestamp", crumb.path("timestamp").asText());
                        }
                        if (crumb.has("level") && !crumb.path("level").isNull()) {
                            breadcrumb.put("level", crumb.path("level").asText());
                        }
                        if (crumb.has("message") && !crumb.path("message").isNull()) {
                            breadcrumb.put("message", crumb.path("message").asText());
                        }
                        if (crumb.has("category") && !crumb.path("category").isNull()) {
                            breadcrumb.put("category", crumb.path("category").asText());
                        }
                        if (crumb.has("type") && !crumb.path("type").isNull()) {
                            breadcrumb.put("type", crumb.path("type").asText());
                        }
                        
                        // Extract HTTP request data from breadcrumbs
                        if (crumb.has("data") && !crumb.path("data").isEmpty()) {
                            JsonNode data = crumb.path("data");
                            Map<String, Object> crumbData = new HashMap<>();
                            if (data.has("method")) crumbData.put("method", data.path("method").asText());
                            if (data.has("url")) crumbData.put("url", data.path("url").asText());
                            if (!crumbData.isEmpty()) {
                                breadcrumb.put("data", crumbData);
                            }
                        }
                        
                        if (!breadcrumb.isEmpty()) {
                            breadcrumbs.add(breadcrumb);
                        }
                    }
                    break; // Found breadcrumbs entry, no need to continue
                }
            }
        }
    } catch (Exception e) {
        Sentry.captureException(e);
    }
    
    return breadcrumbs;
}

// Extract HTTP request details from Sentry event data
private Map<String, Object> extractRequestDetails(JsonNode eventData) {
    Map<String, Object> requestDetails = new HashMap<>();
    
    try {
        if (eventData.has("entries") && eventData.path("entries").isArray()) {
            for (JsonNode entry : eventData.path("entries")) {
                if ("request".equals(entry.path("type").asText()) && 
                    entry.has("data") && !entry.path("data").isEmpty()) {
                    
                    JsonNode data = entry.path("data");
                    
                    if (data.has("method") && !data.path("method").isNull()) {
                        requestDetails.put("method", data.path("method").asText());
                    }
                    if (data.has("url") && !data.path("url").isNull()) {
                        requestDetails.put("url", data.path("url").asText());
                    }
                    if (data.has("query") && data.path("query").isArray() && data.path("query").size() > 0) {
                        requestDetails.put("query_params", data.path("query").toString());
                    }
                    if (data.has("headers") && data.path("headers").isArray()) {
                        // Extract key headers
                        Map<String, String> importantHeaders = new HashMap<>();
                        for (JsonNode header : data.path("headers")) {
                            if (header.isArray() && header.size() >= 2) {
                                String headerName = header.get(0).asText().toLowerCase();
                                String headerValue = header.get(1).asText();
                                
                                // Only include important headers
                                if (headerName.equals("user-agent") || headerName.equals("content-type") ||
                                    headerName.equals("accept") || headerName.equals("referer")) {
                                    importantHeaders.put(headerName, headerValue);
                                }
                            }
                        }
                        if (!importantHeaders.isEmpty()) {
                            requestDetails.put("headers", importantHeaders);
                        }
                    }
                    break; // Found request entry, no need to continue
                }
            }
        }
    } catch (Exception e) {
        Sentry.captureException(e);
    }
    
    return requestDetails;
}

// Extract error metadata from Sentry event data
private Map<String, Object> extractErrorMetadata(JsonNode eventData) {
    Map<String, Object> errorMetadata = new HashMap<>();
    
    try {
        // Extract from title and message
        if (eventData.has("title") && !eventData.path("title").isNull()) {
            errorMetadata.put("title", eventData.path("title").asText());
        }
        if (eventData.has("message") && !eventData.path("message").isNull() && 
            !eventData.path("message").asText().isEmpty()) {
            errorMetadata.put("message", eventData.path("message").asText());
        }
        if (eventData.has("culprit") && !eventData.path("culprit").isNull()) {
            errorMetadata.put("culprit", eventData.path("culprit").asText());
        }
        
        // Extract from exception entries
        if (eventData.has("entries") && eventData.path("entries").isArray()) {
            for (JsonNode entry : eventData.path("entries")) {
                if ("exception".equals(entry.path("type").asText()) && 
                    entry.has("data") && entry.path("data").has("values") &&
                    entry.path("data").path("values").isArray() && 
                    entry.path("data").path("values").size() > 0) {
                    
                    JsonNode exception = entry.path("data").path("values").get(0);
                    
                    if (exception.has("type") && !exception.path("type").isNull()) {
                        errorMetadata.put("exception_type", exception.path("type").asText());
                    }
                    if (exception.has("value") && !exception.path("value").isNull()) {
                        errorMetadata.put("exception_value", exception.path("value").asText());
                    }
                    if (exception.has("module") && !exception.path("module").isNull()) {
                        errorMetadata.put("exception_module", exception.path("module").asText());
                    }
                    break; // Found exception entry, no need to continue
                }
            }
        }
        
        // Extract metadata info
        if (eventData.has("metadata") && !eventData.path("metadata").isEmpty()) {
            JsonNode metadata = eventData.path("metadata");
            if (metadata.has("filename") && !metadata.path("filename").isNull()) {
                errorMetadata.put("filename", metadata.path("filename").asText());
            }
            if (metadata.has("function") && !metadata.path("function").isNull()) {
                errorMetadata.put("function", metadata.path("function").asText());
            }
        }
        
    } catch (Exception e) {
        Sentry.captureException(e);
    }
    
    return errorMetadata;
}

}
