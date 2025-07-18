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

// HELPER METHODS //
// Collection of methods to help accomplish tasks
// (i.e, generate random IDs, Parsing, Curling, Formatting)

// Curls to recieve Sentry Errors
// Only shows one unique error data per error name
// Requires Orgnization_id & project_slug (project name)
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

}
