package com.waitfans.backend.service.video;

import com.waitfans.backend.mapper.VideoMapper;
import com.waitfans.backend.pojo.UserVideo;
import com.waitfans.backend.pojo.Video;
import com.waitfans.backend.pojo.dto.VideoPlayOutcome;
import com.waitfans.backend.pojo.dto.VideoPlayResult;
import com.waitfans.backend.utils.RedisUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

@Service
@Slf4j
public class VideoPlayTrackingService {
    public static final String VISITOR_COOKIE_NAME = "wf_visitor";
    public static final long DEDUP_SECONDS = 30 * 60;
    public static final long VISITOR_SESSION_SECONDS = 180L * 24 * 60 * 60;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final VideoMapper videoMapper;
    private final RedisUtil redisUtil;
    private final VideoPlayPersistenceService persistenceService;

    @Autowired
    public VideoPlayTrackingService(
            VideoMapper videoMapper,
            RedisUtil redisUtil,
            VideoPlayPersistenceService persistenceService
    ) {
        this.videoMapper = videoMapper;
        this.redisUtil = redisUtil;
        this.persistenceService = persistenceService;
    }

    public VideoPlayOutcome record(Integer vid, Integer uid, String existingVisitorToken) {
        if (vid == null || vid <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid video id");
        }
        Video video = videoMapper.selectById(vid);
        if (video == null || !Integer.valueOf(1).equals(video.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found");
        }

        String cookieToSet = null;
        String dedupKey;
        if (uid != null) {
            dedupKey = userDedupKey(uid, vid);
        } else {
            VisitorSession visitorSession = resolveVisitorSession(existingVisitorToken);
            if (visitorSession == null) {
                log.error("Video play visitor session unavailable for vid={}", vid);
                return unavailable(null, vid, null);
            }
            cookieToSet = visitorSession.cookieToSet;
            dedupKey = visitorDedupKey(visitorSession.digest, vid);
        }

        final boolean claimed;
        try {
            claimed = redisUtil.setIfAbsent(dedupKey, "1", DEDUP_SECONDS);
        } catch (RuntimeException exception) {
            log.error("Video play tracking unavailable for vid={}", vid, exception);
            return unavailable(uid, vid, cookieToSet);
        }

        if (!claimed) {
            UserVideo interaction = interactionAndRefreshHistory(uid, vid);
            try {
                Long ttl = redisUtil.getExpire(dedupKey);
                return new VideoPlayOutcome(new VideoPlayResult(false, VideoPlayResult.Reason.DUPLICATE,
                        ttl == null || ttl < 0 ? 0 : ttl, interaction), cookieToSet);
            } catch (RuntimeException exception) {
                log.error("Video play tracking TTL unavailable for vid={}", vid, exception);
                return unavailable(uid, vid, cookieToSet);
            }
        }

        try {
            UserVideo interaction = persistenceService.count(uid, vid);
            refreshHistory(uid, vid);
            return new VideoPlayOutcome(new VideoPlayResult(true, VideoPlayResult.Reason.COUNTED, 0, interaction), cookieToSet);
        } catch (RuntimeException exception) {
            try {
                redisUtil.delValue(dedupKey);
            } catch (RuntimeException cleanupException) {
                log.error("Could not release video play dedup key for vid={}", vid, cleanupException);
            }
            log.error("Video play tracking persistence failed for vid={}", vid, exception);
            return unavailable(uid, vid, cookieToSet);
        }
    }

    private VideoPlayOutcome unavailable(Integer uid, Integer vid, String cookieToSet) {
        return new VideoPlayOutcome(new VideoPlayResult(false, VideoPlayResult.Reason.TRACKING_UNAVAILABLE,
                0, interaction(uid, vid)), cookieToSet);
    }

    private UserVideo interactionAndRefreshHistory(Integer uid, Integer vid) {
        UserVideo interaction = interaction(uid, vid);
        refreshHistory(uid, vid);
        return interaction;
    }

    private UserVideo interaction(Integer uid, Integer vid) {
        try {
            return persistenceService.interaction(uid, vid);
        } catch (RuntimeException exception) {
            log.error("Could not load video interaction for uid={}, vid={}", uid, vid, exception);
            return null;
        }
    }

    private void refreshHistory(Integer uid, Integer vid) {
        if (uid == null) return;
        try {
            redisUtil.zset("user_video_history:" + uid, vid);
        } catch (RuntimeException exception) {
            log.error("Could not update video history for uid={}, vid={}", uid, vid, exception);
        }
    }

    static String userDedupKey(Integer uid, Integer vid) {
        return "play:dedup:user:" + uid + ":video:" + vid;
    }

    static String visitorDedupKey(String digest, Integer vid) {
        return "play:dedup:visitor:" + digest + ":video:" + vid;
    }

    static String visitorSessionKey(String digest) {
        return "play:visitor:session:" + digest;
    }

    static String visitorDigest(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static String newVisitorToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String normalizeVisitorToken(String token) {
        return token != null && token.matches("[A-Za-z0-9_-]{43}") ? token : null;
    }

    private VisitorSession resolveVisitorSession(String existingVisitorToken) {
        String token = normalizeVisitorToken(existingVisitorToken);
        try {
            if (token != null) {
                String digest = visitorDigest(token);
                if (redisUtil.isExist(visitorSessionKey(digest))) {
                    return new VisitorSession(digest, null);
                }
            }
            String newToken = newVisitorToken();
            String digest = visitorDigest(newToken);
            if (!redisUtil.setIfAbsent(visitorSessionKey(digest), "1", VISITOR_SESSION_SECONDS)) {
                return null;
            }
            return new VisitorSession(digest, newToken);
        } catch (RuntimeException exception) {
            log.error("Could not create visitor session", exception);
            return null;
        }
    }

    private static class VisitorSession {
        private final String digest;
        private final String cookieToSet;

        private VisitorSession(String digest, String cookieToSet) {
            this.digest = digest;
            this.cookieToSet = cookieToSet;
        }
    }
}
