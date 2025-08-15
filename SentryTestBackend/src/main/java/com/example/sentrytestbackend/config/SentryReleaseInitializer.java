package com.example.sentrytestbackend.config;

import com.example.sentrytestbackend.service.SentryReleaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import io.sentry.Sentry;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

// --- SentryReleaseUpdater for scheduled polling ---
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Initializes Sentry release on application startup
 * Creates release with current Bitbucket commit hash for error tracking
 */
@EnableScheduling
@Component
public class SentryReleaseInitializer {

    @Autowired
    private SentryReleaseService sentryReleaseService;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        System.out.println("[Startup] Initializing Sentry release with Bitbucket commit...");
        try {
            sentryReleaseService.initializeReleaseOnStartup();
            String release = sentryReleaseService.getCurrentReleaseForErrors();
            if (release != null) {
                Sentry.configureScope(scope -> {
                    scope.setTag("release", release);
                    scope.setTag("commit_hash", release);
                });
                System.out.println("[Sentry] Global release set to: " + release);
            } else {
                System.err.println("[Sentry] WARNING: No release found to set globally.");
            }
            System.out.println("[Startup] Sentry release initialization completed.");
        } catch (Exception e) {
            System.err.println("[Startup] ERROR: Failed to initialize Sentry release: " + e.getMessage());
            System.err.println("[Startup] Application will continue without Sentry release tracking.");
            // Don't re-throw - allow application to start even if Sentry setup fails
        }
    }
}

@Component
class SentryReleaseUpdater {
    @Autowired
    private SentryReleaseService sentryReleaseService;
    private String lastRelease = null;

    @Scheduled(fixedDelay = 60000) // every 60 seconds
    public void updateSentryRelease() {
        String currentRelease = sentryReleaseService.getCurrentReleaseForErrors();
        System.out.println("[Sentry] Polled current release: " + currentRelease);
        if (currentRelease != null && !currentRelease.equals(lastRelease)) {
            // Ensure Sentry release exists for the new commit
            String ensuredRelease = sentryReleaseService.createOrEnsureSentryRelease();
            if (ensuredRelease != null && ensuredRelease.equals(currentRelease)) {
                io.sentry.Sentry.configureScope(scope -> {
                    scope.setTag("release", currentRelease);
                    scope.setTag("commit_hash", currentRelease);
                });
                lastRelease = currentRelease;
                System.out.println("[Sentry] Global release updated to: " + currentRelease);
            } else {
                System.err.println("[Sentry] ERROR: Failed to create/verify Sentry release for commit: " + currentRelease);
            }
        }
    }
}