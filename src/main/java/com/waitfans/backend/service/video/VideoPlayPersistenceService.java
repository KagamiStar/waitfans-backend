package com.waitfans.backend.service.video;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.waitfans.backend.mapper.UserVideoMapper;
import com.waitfans.backend.pojo.UserVideo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

@Service
public class VideoPlayPersistenceService {
    private final UserVideoMapper userVideoMapper;
    private final VideoStatsService videoStatsService;

    @Autowired
    public VideoPlayPersistenceService(UserVideoMapper userVideoMapper, VideoStatsService videoStatsService) {
        this.userVideoMapper = userVideoMapper;
        this.videoStatsService = videoStatsService;
    }

    @Transactional
    public UserVideo count(Integer uid, Integer vid) {
        if (uid != null) {
            userVideoMapper.upsertPlay(uid, vid, new Date());
        }
        videoStatsService.updateStats(vid, "play", true, 1);
        return uid == null ? null : interaction(uid, vid);
    }

    public UserVideo interaction(Integer uid, Integer vid) {
        if (uid == null) return null;
        QueryWrapper<UserVideo> query = new QueryWrapper<>();
        query.eq("uid", uid).eq("vid", vid);
        return userVideoMapper.selectOne(query);
    }
}
