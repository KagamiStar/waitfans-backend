package com.waitfans.backend.service.media;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaPreviewTokenServiceTest {
    @Test
    void tokenExpiresAndCannotBeReusedForAnotherVideo() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-08T00:00:00Z"));
        MediaPreviewTokenService service = new MediaPreviewTokenService(
                clock,
                Duration.ofSeconds(10),
                new SecureRandom()
        );
        String token = service.issue(5);

        assertTrue(service.matches(5, token));
        assertFalse(service.matches(6, token));

        clock.instant = clock.instant.plusSeconds(10);
        assertFalse(service.matches(5, token));
    }

    private static class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
