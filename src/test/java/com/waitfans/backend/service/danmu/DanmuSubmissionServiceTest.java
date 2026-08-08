package com.waitfans.backend.service.danmu;

import com.alibaba.fastjson2.JSONObject;
import com.waitfans.backend.component.danmu.protocol.DanmuProtocolErrorCode;
import com.waitfans.backend.mapper.DanmuMapper;
import com.waitfans.backend.mapper.VideoMapper;
import com.waitfans.backend.pojo.Danmu;
import com.waitfans.backend.pojo.Video;
import com.waitfans.backend.repository.PartitionedVideoStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DanmuSubmissionServiceTest {
    @Test
    void commitsDanmuAndPartitionStatsBeforeAcknowledgementCanBeSent() {
        VideoMapper videos = mock(VideoMapper.class);
        DanmuMapper danmus = mock(DanmuMapper.class);
        PartitionedVideoStore store = mock(PartitionedVideoStore.class);
        Video video = new Video();
        video.setStatus(1);
        video.setDuration(30D);
        when(videos.selectById(10)).thenReturn(video);
        when(danmus.insert(any(Danmu.class))).thenAnswer(call -> {
            call.getArgument(0, Danmu.class).setId(88);
            return 1;
        });
        DanmuSubmissionService service = new DanmuSubmissionService(danmus, videos, store);

        Danmu result = service.submit(7, 10, payload());

        assertEquals(88, result.getId().intValue());
        assertEquals(7, result.getUid().intValue());
        verify(store).updateStats(10, "danmu", true, 1);
    }

    @Test
    void nonPublicVideoNeverWritesDanmu() {
        VideoMapper videos = mock(VideoMapper.class);
        Video video = new Video();
        video.setStatus(0);
        when(videos.selectById(10)).thenReturn(video);
        DanmuSubmissionService service = new DanmuSubmissionService(mock(DanmuMapper.class), videos,
                mock(PartitionedVideoStore.class));

        DanmuSubmitException error = assertThrows(DanmuSubmitException.class,
                () -> service.submit(7, 10, payload()));

        assertEquals(DanmuProtocolErrorCode.VIDEO_NOT_PUBLIC, error.getErrorCode());
    }

    @Test
    void normalizesValidPayloadAndRejectsForbiddenUnicode() {
        VideoMapper videos = mock(VideoMapper.class);
        DanmuMapper danmus = mock(DanmuMapper.class);
        PartitionedVideoStore store = mock(PartitionedVideoStore.class);
        Video video = new Video();
        video.setStatus(1);
        video.setDuration(5D);
        when(videos.selectById(10)).thenReturn(video);
        when(danmus.insert(any(Danmu.class))).thenAnswer(call -> {
            call.getArgument(0, Danmu.class).setId(89);
            return 1;
        });
        DanmuSubmissionService service = new DanmuSubmissionService(danmus, videos, store);
        JSONObject valid = payload();
        valid.put("content", " e\u0301 ");
        valid.put("color", "#aBc123");
        valid.put("timePoint", 1.23456D);

        Danmu normalized = service.submit(7, 10, valid);

        assertEquals("é", normalized.getContent());
        assertEquals("#ABC123", normalized.getColor());
        assertEquals(1.235D, normalized.getTimePoint());
        valid.put("content", "\u00A0\u3000👩‍💻\u3000\u00A0");
        assertEquals("👩‍💻", service.submit(7, 10, valid).getContent());
        valid.put("content", codePoints(80));
        service.submit(7, 10, valid);
        valid.put("content", codePoints(81));
        DanmuSubmitException tooLong = assertThrows(DanmuSubmitException.class, () -> service.submit(7, 10, valid));
        assertEquals(DanmuProtocolErrorCode.CONTENT_INVALID, tooLong.getErrorCode());
        valid.put("content", "bad\u200Btext");
        DanmuSubmitException error = assertThrows(DanmuSubmitException.class, () -> service.submit(7, 10, valid));
        assertEquals(DanmuProtocolErrorCode.CONTENT_INVALID, error.getErrorCode());
    }

    @Test
    void requiresUsableDurationMetadata() {
        VideoMapper videos = mock(VideoMapper.class);
        Video video = new Video();
        video.setStatus(1);
        when(videos.selectById(10)).thenReturn(video);
        DanmuSubmissionService service = new DanmuSubmissionService(mock(DanmuMapper.class), videos,
                mock(PartitionedVideoStore.class));

        DanmuSubmitException error = assertThrows(DanmuSubmitException.class, () -> service.submit(7, 10, payload()));

        assertEquals(DanmuProtocolErrorCode.VIDEO_METADATA_INVALID, error.getErrorCode());
    }

    @Test
    void rejectsZeroDurationAndPreRoundTimeOverflow() {
        VideoMapper videos = mock(VideoMapper.class);
        Video video = new Video();
        video.setStatus(1);
        video.setDuration(0D);
        when(videos.selectById(10)).thenReturn(video);
        DanmuSubmissionService service = new DanmuSubmissionService(mock(DanmuMapper.class), videos,
                mock(PartitionedVideoStore.class));
        assertEquals(DanmuProtocolErrorCode.VIDEO_METADATA_INVALID,
                assertThrows(DanmuSubmitException.class, () -> service.submit(7, 10, payload())).getErrorCode());
        video.setDuration(5D);
        JSONObject overflow = payload();
        overflow.put("timePoint", 6.0004D);
        assertEquals(DanmuProtocolErrorCode.TIME_POINT_INVALID,
                assertThrows(DanmuSubmitException.class, () -> service.submit(7, 10, overflow)).getErrorCode());
    }

    private String codePoints(int count) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < count; index++) builder.append("😀");
        return builder.toString();
    }

    private JSONObject payload() {
        JSONObject data = new JSONObject();
        data.put("content", "hello");
        data.put("fontsize", 25);
        data.put("mode", 1);
        data.put("color", "#FFFFFF");
        data.put("timePoint", 2.5);
        return data;
    }
}
