package com.example.sentrytestbackend.config;

import io.sentry.Sentry;
import io.sentry.SentryOptions;
import io.sentry.spring.boot.jakarta.SentryProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import jakarta.annotation.PostConstruct;
import java.util.logging.Logger;

@Configuration
public class SentrySpotlightConfig {

    private static final Logger logger = Logger.getLogger(SentrySpotlightConfig.class.getName());

    @Value("${sentry.enable-spotlight:false}")
    private boolean enableSpotlight;

    @Value("${sentry.spotlight-url:http://localhost:8969}")
    private String spotlightUrl;

    @Value("${sentry.environment:development}")
    private String environment;

    @PostConstruct
    public void configureSpotlight() {
        if (enableSpotlight && "development".equals(environment)) {
            logger.info("🔍 Spotlight enabled - Connect to: " + spotlightUrl);
            
            // Configure Sentry for Spotlight integration
            // Sentry.configureScope(scope -> {
            //     scope.setTag("spotlight.enabled", "true");
            //     scope.setTag("spotlight.url", spotlightUrl);
            // });
            
            // // Log a message to verify Spotlight connection
            // Sentry.captureMessage("🔍 Spotlight connection established");
            
            System.out.println("==========================================");
            System.out.println("🔍 SPOTLIGHT INTEGRATION ENABLED");
            System.out.println("📡 Spotlight URL: " + spotlightUrl);
            System.out.println("🌍 Environment: " + environment);
            System.out.println("==========================================");
        } else {
            logger.info("Spotlight disabled or not in development environment");
        }
    }

    /**
     * Test method to send events to Spotlight
     */
    public void testSpotlightConnection() {
        if (enableSpotlight) {
            // Send test events
            Sentry.addBreadcrumb("🔍 Testing Spotlight connection");
            Sentry.captureMessage("Test message from Spring Boot to Spotlight");
            
            try {
                throw new RuntimeException("Test exception for Spotlight");
            } catch (Exception e) {
                Sentry.captureException(e);
            }
            
            logger.info("Test events sent to Spotlight");
        }
    }
} 