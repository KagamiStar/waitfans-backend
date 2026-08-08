package com.waitfans.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.util.AntPathMatcher;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaSecurityRouteTest {
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Test
    void publicMediaPatternCannotMatchPreviewTicketEndpoint() {
        assertTrue(pathMatcher.match("/media/video/*", "/media/video/10"));
        assertFalse(pathMatcher.match("/media/video/*", "/media/video/10/preview-token"));
        assertTrue(pathMatcher.match("/media/preview/**", "/media/preview/10"));
    }

    @Test
    void unifiedPlayRouteDoesNotReopenLegacyVisitorEndpoint() {
        assertTrue(pathMatcher.match("/video/play", "/video/play"));
        assertFalse(pathMatcher.match("/video/play", "/video/play/visitor"));
        assertFalse(pathMatcher.match("/video/play", "/video/play/user"));
    }
}
