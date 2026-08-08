package com.waitfans.backend.service.video;

import com.waitfans.backend.mapper.VideoMapper;
import com.waitfans.backend.pojo.UserVideo;
import com.waitfans.backend.pojo.Video;
import com.waitfans.backend.pojo.dto.VideoPlayOutcome;
import com.waitfans.backend.pojo.dto.VideoPlayResult;
import com.waitfans.backend.utils.RedisUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoPlayTrackingServiceTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 2, 3})
    void rejectsEveryNonPublishedStatus(int status) {
        Fixture fixture = fixture();
        when(fixture.videoMapper.selectById(10)).thenReturn(video(10, status));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> fixture.service.record(10, null, null));

        assertEquals(404, error.getStatus().value());
        verify(fixture.redisUtil, never()).setIfAbsent(anyString(), any(), anyLong());
    }

    @Test
    void rejectsUnknownAndInvalidVideosBeforeCookieOrDedup() {
        Fixture fixture = fixture();
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> fixture.service.record(0, null, null)).getStatus().value());

        ResponseStatusException missing = assertThrows(ResponseStatusException.class,
                () -> fixture.service.record(10, null, null));
        assertEquals(404, missing.getStatus().value());
        verify(fixture.redisUtil, never()).setIfAbsent(anyString(), any(), anyLong());
    }

    @Test
    void countsPublishedLoggedInPlayWithAtomicUserKeyAndInteraction() {
        Fixture fixture = publishedFixture();
        UserVideo interaction = interaction(7, 10);
        when(fixture.redisUtil.setIfAbsent("play:dedup:user:7:video:10", "1", 1800)).thenReturn(true);
        when(fixture.persistence.count(7, 10)).thenReturn(interaction);

        VideoPlayOutcome outcome = fixture.service.record(10, 7, null);

        assertTrue(outcome.getResult().isCounted());
        assertEquals(VideoPlayResult.Reason.COUNTED, outcome.getResult().getReason());
        assertEquals(interaction, outcome.getResult().getInteraction());
        assertNull(outcome.getVisitorCookieValue());
        verify(fixture.redisUtil).zset("user_video_history:7", 10);
    }

    @Test
    void guestIssuesStrongCookieAndNeverUsesRawTokenInKey() {
        Fixture fixture = publishedFixture();
        when(fixture.redisUtil.setIfAbsent(anyString(), any(), anyLong())).thenReturn(true);

        VideoPlayOutcome first = fixture.service.record(10, null, null);

        String token = first.getVisitorCookieValue();
        assertNotNull(token);
        assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
        assertNotEquals(token, VideoPlayTrackingService.visitorDigest(token));
        verify(fixture.redisUtil).setIfAbsent(
                "play:dedup:visitor:" + VideoPlayTrackingService.visitorDigest(token) + ":video:10",
                "1", 1800
        );
        assertEquals(64, VideoPlayTrackingService.visitorDigest(token).length());
    }

    @Test
    void guestReusesValidCookieWithoutIssuingAnother() {
        Fixture fixture = publishedFixture();
        String existing = VideoPlayTrackingService.newVisitorToken();
        when(fixture.redisUtil.setIfAbsent(anyString(), any(), anyLong())).thenReturn(true);

        VideoPlayOutcome outcome = fixture.service.record(10, null, existing);

        assertNull(outcome.getVisitorCookieValue());
        verify(fixture.redisUtil).setIfAbsent(
                VideoPlayTrackingService.visitorDedupKey(VideoPlayTrackingService.visitorDigest(existing), 10),
                "1", 1800
        );
    }

    @Test
    void duplicateDoesNotCountButRefreshesLoggedInHistoryAndReturnsInteraction() {
        Fixture fixture = publishedFixture();
        UserVideo interaction = interaction(7, 10);
        when(fixture.redisUtil.setIfAbsent(anyString(), any(), anyLong())).thenReturn(false);
        when(fixture.redisUtil.getExpire(anyString())).thenReturn(913L);
        when(fixture.persistence.interaction(7, 10)).thenReturn(interaction);

        VideoPlayOutcome outcome = fixture.service.record(10, 7, null);

        assertFalse(outcome.getResult().isCounted());
        assertEquals(VideoPlayResult.Reason.DUPLICATE, outcome.getResult().getReason());
        assertEquals(913, outcome.getResult().getNextEligibleInSeconds());
        assertEquals(interaction, outcome.getResult().getInteraction());
        verify(fixture.persistence, never()).count(anyInt(), anyInt());
        verify(fixture.redisUtil).zset("user_video_history:7", 10);
    }

    @Test
    void persistenceFailureReleasesClaimAndDoesNotReportCounted() {
        Fixture fixture = publishedFixture();
        when(fixture.redisUtil.setIfAbsent(anyString(), any(), anyLong())).thenReturn(true);
        when(fixture.persistence.count(7, 10)).thenThrow(new IllegalStateException("database down"));
        when(fixture.persistence.interaction(7, 10)).thenReturn(interaction(7, 10));

        VideoPlayOutcome outcome = fixture.service.record(10, 7, null);

        assertEquals(VideoPlayResult.Reason.TRACKING_UNAVAILABLE, outcome.getResult().getReason());
        verify(fixture.redisUtil).delValue("play:dedup:user:7:video:10");
    }

    @Test
    void redisFailureFailsClosedWithoutDatabaseCount() {
        Fixture fixture = publishedFixture();
        when(fixture.redisUtil.setIfAbsent(anyString(), any(), anyLong())).thenThrow(new IllegalStateException("redis down"));
        when(fixture.persistence.interaction(7, 10)).thenReturn(interaction(7, 10));

        VideoPlayOutcome outcome = fixture.service.record(10, 7, null);

        assertEquals(VideoPlayResult.Reason.TRACKING_UNAVAILABLE, outcome.getResult().getReason());
        verify(fixture.persistence, never()).count(anyInt(), anyInt());
    }

    @Test
    void atomicClaimContractCountsAtMostOnceForConcurrentRequests() throws Exception {
        Fixture fixture = publishedFixture();
        AtomicBoolean claimed = new AtomicBoolean();
        when(fixture.redisUtil.setIfAbsent(anyString(), any(), anyLong()))
                .thenAnswer(invocation -> claimed.compareAndSet(false, true));
        when(fixture.redisUtil.getExpire(anyString())).thenReturn(1799L);
        when(fixture.persistence.count(7, 10)).thenReturn(interaction(7, 10));
        when(fixture.persistence.interaction(7, 10)).thenReturn(interaction(7, 10));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    fixture.service.record(10, 7, null);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertTrue(ready.await(1, TimeUnit.SECONDS));
        start.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));

        verify(fixture.persistence, times(1)).count(7, 10);
    }

    private Fixture publishedFixture() {
        Fixture fixture = fixture();
        when(fixture.videoMapper.selectById(10)).thenReturn(video(10, 1));
        return fixture;
    }

    private Fixture fixture() {
        VideoMapper videoMapper = mock(VideoMapper.class);
        RedisUtil redisUtil = mock(RedisUtil.class);
        VideoPlayPersistenceService persistence = mock(VideoPlayPersistenceService.class);
        return new Fixture(videoMapper, redisUtil, persistence,
                new VideoPlayTrackingService(videoMapper, redisUtil, persistence));
    }

    private Video video(int vid, int status) {
        Video video = new Video();
        video.setVid(vid);
        video.setStatus(status);
        return video;
    }

    private UserVideo interaction(int uid, int vid) {
        UserVideo interaction = new UserVideo();
        interaction.setUid(uid);
        interaction.setVid(vid);
        interaction.setPlay(1);
        return interaction;
    }

    private static class Fixture {
        private final VideoMapper videoMapper;
        private final RedisUtil redisUtil;
        private final VideoPlayPersistenceService persistence;
        private final VideoPlayTrackingService service;

        private Fixture(VideoMapper videoMapper, RedisUtil redisUtil,
                        VideoPlayPersistenceService persistence, VideoPlayTrackingService service) {
            this.videoMapper = videoMapper;
            this.redisUtil = redisUtil;
            this.persistence = persistence;
            this.service = service;
        }
    }
}
