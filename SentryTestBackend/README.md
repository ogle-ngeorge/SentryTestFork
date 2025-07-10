# SentryTestBackend

A simple Spring Boot backend for testing Sentry integration.

## Setup

### Prerequisites
- Java 17 or higher
- Gradle
- Sentry self-hosted instance running (http://localhost:9000)

### Configuration

1. **Sentry DSN**: Update the DSN in `application.properties` if your Sentry instance is running elsewhere.

2. **Create a new project in Sentry**:
   - Go to http://localhost:9000
   - Login with: noah.george@bluefletch.com / password: "why would i type this here"
   - Create a new project for "Java Spring Boot"
   - Copy the DSN and update `application.properties`

### Running the Application

```bash
# Navigate to the backend directory
cd SentryTestBackend

# Run the application
./gradlew bootRun
```

The application will start on http://localhost:8081

## API Endpoints

### Health Check
```
GET http://localhost:8081/api/health
```
Returns the health status of the backend.

### Test Success
```
GET http://localhost:8081/api/test-success
```
Returns successful response and logs to Sentry.

### Test Error
```
GET http://localhost:8081/api/test-error
```
Intentionally throws an error that gets captured by Sentry.

### Test Data (POST)
```
POST http://localhost:8081/api/test-data
Content-Type: application/json

{
    "message": "test data",
    "trigger_error": false
}
```
Accepts POST data. Set `trigger_error: true` to test error handling.

## Testing with your Android App

You can call these endpoints from your Android app to:
1. Test successful API calls
2. Test error handling and Sentry error reporting
3. Send test data from your app to the backend

Example Android code:
```kotlin
// Test the backend health
val response = httpClient.get("http://localhost:8081/api/health")

// Test error handling
val errorResponse = httpClient.get("http://localhost:8081/api/test-error")
```

## Monitoring in Sentry

After running the endpoints, you should see:
- Successful transactions in Sentry Performance
- Error events in Sentry Issues
- Breadcrumbs and context data
- Custom tags and extra data
