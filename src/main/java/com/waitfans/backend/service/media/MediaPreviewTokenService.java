package com.waitfans.backend.service.media;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class MediaPreviewTokenService {
    public static final long TTL_SECONDS = 300;

    private final ConcurrentMap<String, PreviewToken> tokens = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom;
    private final Clock clock;
    private final Duration ttl;

    public MediaPreviewTokenService() {
        this(Clock.systemUTC(), Duration.ofSeconds(TTL_SECONDS), new SecureRandom());
    }

    MediaPreviewTokenService(Clock clock, Duration ttl, SecureRandom secureRandom) {
        this.clock = clock;
        this.ttl = ttl;
        this.secureRandom = secureRandom;
    }

    public String issue(Integer vid) {
        removeExpired();
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.put(token, new PreviewToken(vid, clock.instant().plus(ttl)));
        return token;
    }

    public boolean matches(Integer vid, String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        PreviewToken previewToken = tokens.get(token);
        if (previewToken == null) {
            return false;
        }
        if (!clock.instant().isBefore(previewToken.expiresAt)) {
            tokens.remove(token, previewToken);
            return false;
        }
        return vid.equals(previewToken.vid);
    }

    private void removeExpired() {
        Instant now = clock.instant();
        tokens.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt));
    }

    private static class PreviewToken {
        private final Integer vid;
        private final Instant expiresAt;

        private PreviewToken(Integer vid, Instant expiresAt) {
            this.vid = vid;
            this.expiresAt = expiresAt;
        }
    }
}
