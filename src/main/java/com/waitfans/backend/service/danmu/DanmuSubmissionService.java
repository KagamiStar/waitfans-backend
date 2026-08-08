package com.waitfans.backend.service.danmu;

import com.alibaba.fastjson2.JSONObject;
import com.waitfans.backend.component.danmu.protocol.DanmuProtocolErrorCode;
import com.waitfans.backend.mapper.DanmuMapper;
import com.waitfans.backend.mapper.VideoMapper;
import com.waitfans.backend.pojo.Danmu;
import com.waitfans.backend.pojo.Video;
import com.waitfans.backend.repository.PartitionedVideoStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.text.Normalizer;
import java.util.Date;
import java.util.Locale;

@Service
public class DanmuSubmissionService {
    private final DanmuMapper danmuMapper;
    private final VideoMapper videoMapper;
    private final PartitionedVideoStore partitionedVideoStore;

    @Autowired
    public DanmuSubmissionService(
            DanmuMapper danmuMapper,
            VideoMapper videoMapper,
            PartitionedVideoStore partitionedVideoStore
    ) {
        this.danmuMapper = danmuMapper;
        this.videoMapper = videoMapper;
        this.partitionedVideoStore = partitionedVideoStore;
    }

    @Transactional
    public Danmu submit(Integer uid, Integer vid, JSONObject data) {
        Video video = requirePublicVideo(vid);
        ValidatedDanmu input = validate(data, video);
        Danmu danmu = new Danmu(null, vid, uid, input.content, input.fontsize, input.mode,
                input.color, input.timePoint, 1, new Date());
        if (danmuMapper.insert(danmu) != 1) {
            throw new IllegalStateException("Danmu insert failed");
        }
        partitionedVideoStore.updateStats(vid, "danmu", true, 1);
        return danmu;
    }

    public Video requirePublicVideo(Integer vid) {
        if (vid == null || vid <= 0) throw new DanmuSubmitException(DanmuProtocolErrorCode.INVALID_VIDEO_ID);
        Video video = videoMapper.selectById(vid);
        if (video == null) throw new DanmuSubmitException(DanmuProtocolErrorCode.VIDEO_NOT_FOUND);
        if (!Integer.valueOf(1).equals(video.getStatus())) {
            throw new DanmuSubmitException(DanmuProtocolErrorCode.VIDEO_NOT_PUBLIC);
        }
        return video;
    }

    public void validateForSubmission(Integer vid, JSONObject data) {
        validate(data, requirePublicVideo(vid));
    }

    private ValidatedDanmu validate(JSONObject data, Video video) {
        if (data == null || !StringUtils.hasText(data.getString("content"))) {
            throw new DanmuSubmitException(DanmuProtocolErrorCode.CONTENT_INVALID);
        }
        String normalized = Normalizer.normalize(data.getString("content"), Normalizer.Form.NFC);
        if (normalized.codePointCount(0, normalized.length()) > 80 || hasForbiddenCodePoint(normalized)) {
            throw new DanmuSubmitException(DanmuProtocolErrorCode.CONTENT_INVALID);
        }
        String content = normalized.trim();
        if (content.isEmpty()) throw new DanmuSubmitException(DanmuProtocolErrorCode.CONTENT_INVALID);
        Integer fontsize = data.getInteger("fontsize");
        Integer mode = data.getInteger("mode");
        String color = data.getString("color");
        if ((fontsize == null || (fontsize != 18 && fontsize != 25))
                || (mode == null || mode < 1 || mode > 3)
                || color == null || !color.matches("#[0-9a-fA-F]{6}")) {
            throw new DanmuSubmitException(DanmuProtocolErrorCode.STYLE_INVALID);
        }
        Double duration = video.getDuration();
        if (duration == null || duration.isNaN() || duration.isInfinite() || duration < 0) {
            throw new DanmuSubmitException(DanmuProtocolErrorCode.VIDEO_METADATA_INVALID);
        }
        Double timePoint = data.getDouble("timePoint");
        if (timePoint == null || timePoint.isNaN() || timePoint.isInfinite() || timePoint < 0) {
            throw new DanmuSubmitException(DanmuProtocolErrorCode.TIME_POINT_INVALID);
        }
        double rounded = Math.round(timePoint * 1000D) / 1000D;
        if (rounded > duration + 1D) throw new DanmuSubmitException(DanmuProtocolErrorCode.TIME_POINT_INVALID);
        return new ValidatedDanmu(content, fontsize, mode, color.toUpperCase(Locale.ROOT), rounded);
    }

    private boolean hasForbiddenCodePoint(String value) {
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            if ((codePoint >= 0 && codePoint <= 0x1F) || (codePoint >= 0x7F && codePoint <= 0x9F)
                    || codePoint == 0x200B || codePoint == 0xFEFF || (codePoint >= 0x202A && codePoint <= 0x202E)
                    || (codePoint >= 0x2066 && codePoint <= 0x2069)) return true;
            index += Character.charCount(codePoint);
        }
        return false;
    }

    private static class ValidatedDanmu {
        private final String content;
        private final Integer fontsize;
        private final Integer mode;
        private final String color;
        private final Double timePoint;

        private ValidatedDanmu(String content, Integer fontsize, Integer mode, String color, Double timePoint) {
            this.content = content;
            this.fontsize = fontsize;
            this.mode = mode;
            this.color = color;
            this.timePoint = timePoint;
        }
    }
}
