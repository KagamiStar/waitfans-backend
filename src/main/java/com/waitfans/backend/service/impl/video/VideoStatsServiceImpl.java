package com.waitfans.backend.service.impl.video;

import com.waitfans.backend.mapper.VideoStatsMapper;
import com.waitfans.backend.pojo.VideoStats;
import com.waitfans.backend.repository.PartitionedVideoStore;
import com.waitfans.backend.service.video.VideoStatsService;
import com.waitfans.backend.utils.RedisUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.*;

@Service
public class VideoStatsServiceImpl implements VideoStatsService {
    @Autowired
    private VideoStatsMapper videoStatsMapper;

    @Autowired
    private PartitionedVideoStore partitionedVideoStore;

    @Autowired
    private RedisUtil redisUtil;

    @Autowired
    @Qualifier("taskExecutor")
    private Executor taskExecutor;

    /**
     * 根据视频ID查询视频常变数据
     * @param vid 视频ID
     * @return 视频数据统计
     */
    @Override
    public VideoStats getVideoStatsById(Integer vid) {
        VideoStats videoStats = redisUtil.getObject("videoStats:" + vid, VideoStats.class);
        if (videoStats == null) {
            videoStats = videoStatsMapper.selectById(vid);
            if (videoStats != null) {
                VideoStats finalVideoStats = videoStats;
                CompletableFuture.runAsync(() -> {
                    redisUtil.setExObjectValue("videoStats:" + vid, finalVideoStats);    // 异步更新到redis
                }, taskExecutor);
            } else {
                return null;
            }
        }
        // 多线程查redis反而更慢了，所以干脆直接查数据库
        return videoStats;
    }

    /**
     * 更新指定字段
     * @param vid   视频ID
     * @param column    对应数据库的列名
     * @param increase  是否增加，true则增加 false则减少
     * @param count 增减数量 一般是1，只有投币可以加2
     */
    @Override
    public void updateStats(Integer vid, String column, boolean increase, Integer count) {
        partitionedVideoStore.updateStats(vid, column, increase, count);
        redisUtil.delValue("videoStats:" + vid);
    }

    /**
     * 同时更新点赞和点踩
     * @param vid   视频ID
     * @param addGood   是否点赞，true则good+1&bad-1，false则good-1&bad+1
     */
    @Override
    public void updateGoodAndBad(Integer vid, boolean addGood) {
        partitionedVideoStore.updateGoodAndBad(vid, addGood);
        redisUtil.delValue("videoStats:" + vid);
    }
}
