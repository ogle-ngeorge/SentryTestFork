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
    public String fetchAllSentryErrors() {
        try{
            String url = String.format("%s/api/0/projects/noah-3t/android/events/?full=true", sentryBaseUrl);

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + sentryApiToken);
            headers.set("Content-Type", "application/json");

            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            return response.getBody();

        } catch (Exception e){
            e.printStackTrace();
            Sentry.captureException(e);
            throw new RuntimeException("Unable to fetch data from Sentry");
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
// Returns ArrayList of Project Names
public String fetchSentryErrorsByProject(String projectName) {
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
// Collects x amount of errors from Sentry as a Json Array
// Collects recent errors
// Parameter ~ Int value to determine how many errors to fetch
// Returns JsonArray of a collection of errors.
    // public String fetchSomeSentryErrors(int amount){
    //     Object mapper = new ObjectMapper();
    //     JsonNode data = mapper.read(fetchAllSentryErrors());
    //     Map<<<Integer> <String, SentryErrorObject>>> errorCounts = new HashMap<>();
        
    //     for (int i = 0; i <= amount; i++){
    //         String title = data.get(i).path("title").asText();
    //         if (errorCounts.contains(title)) {
                
    //         }
    //     }
        
    // }
    

// STORAGE METHODS //
// Collection of methods to store data from Sentry to send to Firebase

// Parses JSON data for main error type. Stores JsonNode objects.
// No parameters ~ Does call fetchSentryErrors for errors
// String == Error Type (i.e RuntimeException)
// Integer == Number of Occurances (Used to save space to not store the same error as new ones)
// Returns dictionary of titles and the full error.
    public Map<String, SentryErrorObject> storeSentryErrorsByTitle(){
        try{
            int count = 0;
            ObjectMapper mapper = new ObjectMapper();
            JsonNode data = mapper.readTree(fetchAllSentryErrors());
            Map<String, SentryErrorObject> errorCounts = new HashMap<>();

            for (JsonNode event : data) {
                String title = event.path("title").asText();
                errorCounts.put(title, new SentryErrorObject(event));
            }
            return errorCounts;
        } catch (Exception e){
            Sentry.captureException(e);
            throw new RuntimeException("Unable to parse data from Sentry");
        }
    }

// HELPER METHODS //
// Collection of methods to help accomplish tasks
// (i.e, generate random IDs, Parsing)

// Parses Errors and Returns the titles
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
// Will be used to 
public String randomUUID(){
    return UUID.randomUUID().toString();
}

}
