package com.example.sentrytestbackend.service;
import com.fasterxml.jackson.databind.JsonNode;


// Stores individual JSON errors as an object to be parsed
//
public class SentryErrorObject{
    private JsonNode fullError;
    private int count;

    // Constructor 
    public SentryErrorObject(JsonNode fullError){
        this.fullError = fullError;
        this.count = 1;
    }

    public SentryErrorObject(JsonNode fullError, int count){
        this.fullError = fullError;
        this.count = count;
    }

    // SETTER METHODS //
    public void increaseCount(){
        this.count += 1;
    }

    // GETTER METHODS //
    public JsonNode getFullError() {
        return fullError;
    }

    public int getCount() {
        return count;
    }

    // GETTER METHODS IN JSON NODE //
    public String getTitle(){
        return this.fullError.path("title").asText();
    }   
}