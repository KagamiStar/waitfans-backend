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

import java.util.Date;

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
        Video video = videoMapper.selectById(vid);
        if (video == null) throw new DanmuSubmitException(DanmuProtocolErrorCode.VIDEO_NOT_FOUND);
        if (!Integer.valueOf(1).equals(video.getStatus())) {
            throw new DanmuSubmitException(DanmuProtocolErrorCode.VIDEO_NOT_PUBLIC);
        }
        if (data == null || !StringUtils.hasText(data.getString("content"))) {
            throw new DanmuSubmitException(DanmuProtocolErrorCode.CONTENT_INVALID);
        }
        Integer fontsize = data.getInteger("fontsize");
        Integer mode = data.getInteger("mode");
        String color = data.getString("color");
        if (fontsize == null || mode == null || !StringUtils.hasText(color)) {
            throw new DanmuSubmitException(DanmuProtocolErrorCode.STYLE_INVALID);
        }
        Double timePoint = data.getDouble("timePoint");
        if (timePoint == null || timePoint < 0 || timePoint.isInfinite() || timePoint.isNaN()) {
            throw new DanmuSubmitException(DanmuProtocolErrorCode.TIME_POINT_INVALID);
        }
        Danmu danmu = new Danmu(null, vid, uid, data.getString("content").trim(), fontsize, mode,
                color, timePoint, 1, new Date());
        if (danmuMapper.insert(danmu) != 1) {
            throw new IllegalStateException("Danmu insert failed");
        }
        partitionedVideoStore.updateStats(vid, "danmu", true, 1);
        return danmu;
    }
}
