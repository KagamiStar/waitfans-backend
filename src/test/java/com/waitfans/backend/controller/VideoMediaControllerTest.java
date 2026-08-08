package com.waitfans.backend.controller;

import com.waitfans.backend.mapper.VideoMapper;
import com.waitfans.backend.pojo.CustomResponse;
import com.waitfans.backend.pojo.Video;
import com.waitfans.backend.pojo.dto.MediaPreviewTicket;
import com.waitfans.backend.service.media.MediaPreviewTokenService;
import com.waitfans.backend.service.utils.CurrentUser;
import com.waitfans.backend.utils.OssUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VideoMediaControllerTest {
    private VideoMapper videoMapper;
    private OssUtil ossUtil;
    private CurrentUser currentUser;
    private MediaPreviewTokenService previewTokens;
    private VideoMediaController controller;

    @BeforeEach
    void setUp() throws Exception {
        videoMapper = mock(VideoMapper.class);
        ossUtil = mock(OssUtil.class);
        currentUser = mock(CurrentUser.class);
        previewTokens = new MediaPreviewTokenService();
        controller = new VideoMediaController(videoMapper, ossUtil, currentUser, previewTokens);
        when(ossUtil.getObjectMetadata(anyString()))
                .thenReturn(new OssUtil.StoredObjectMetadata(100, "video/mp4"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, 3})
    void publicMediaRejectsEveryNonPublishedStatus(int status) {
        when(videoMapper.selectById(10)).thenReturn(video(10, 7, status));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> controller.stream(10, null)
        );

        assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
    }

    @Test
    void publicMediaStreamsOnlyPublishedVideoWithoutReusableCacheHeaders() {
        when(videoMapper.selectById(10)).thenReturn(video(10, 7, 1));

        ResponseEntity<?> response = controller.stream(10, "bytes=0-9");

        assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        assertFalse(response.getHeaders().getCacheControl().contains("public"));
    }

    @Test
    void authorCanCreatePreviewTicketForPendingVideo() {
        when(videoMapper.selectById(10)).thenReturn(video(10, 7, 0));
        when(currentUser.getUserId()).thenReturn(7);

        CustomResponse response = controller.createPreviewTicket(10);
        MediaPreviewTicket ticket = (MediaPreviewTicket) response.getData();

        assertTrue(ticket.getUrl().startsWith("/media/preview/10?token="));
        assertEquals(MediaPreviewTokenService.TTL_SECONDS, ticket.getExpiresInSeconds());
    }

    @Test
    void otherUserCannotCreatePreviewTicketForNonPublicVideo() {
        when(videoMapper.selectById(10)).thenReturn(video(10, 7, 2));
        when(currentUser.getUserId()).thenReturn(8);
        when(currentUser.isAdmin()).thenReturn(false);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> controller.createPreviewTicket(10)
        );

        assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
    }

    @Test
    void administratorCanCreatePreviewTicketForReturnedVideo() {
        when(videoMapper.selectById(10)).thenReturn(video(10, 7, 2));
        when(currentUser.getUserId()).thenReturn(8);
        when(currentUser.isAdmin()).thenReturn(true);

        CustomResponse response = controller.createPreviewTicket(10);

        assertNotNull(response.getData());
    }

    @Test
    void deletedVideoCannotCreatePreviewTicket() {
        when(videoMapper.selectById(10)).thenReturn(video(10, 7, 3));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> controller.createPreviewTicket(10)
        );

        assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
    }

    @Test
    void previewRejectsWrongAndVidMismatchedTokens() {
        String validForDifferentVid = previewTokens.issue(11);

        assertNotFound(() -> controller.preview(10, "wrong", null));
        assertNotFound(() -> controller.preview(10, validForDifferentVid, null));
    }

    @Test
    void previewUsesNoStoreCacheHeaders() {
        when(videoMapper.selectById(10)).thenReturn(video(10, 7, 0));
        String token = previewTokens.issue(10);

        ResponseEntity<?> response = controller.preview(10, token, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        assertFalse(response.getHeaders().getCacheControl().contains("public"));
    }

    private void assertNotFound(ThrowingRunnable action) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, action::run);
        assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
    }

    private Video video(int vid, int uid, int status) {
        Video video = new Video();
        video.setVid(vid);
        video.setUid(uid);
        video.setStatus(status);
        video.setVideoUrl("video/" + vid + ".mp4");
        return video;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }
}
