package com.waitfans.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.waitfans.backend.pojo.UserVideo;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;

@Mapper
public interface UserVideoMapper extends BaseMapper<UserVideo> {
    @Insert("INSERT INTO user_video (uid, vid, play, love, unlove, coin, collect, play_time) " +
            "VALUES (#{uid}, #{vid}, 1, 0, 0, 0, 0, #{playedAt}) " +
            "ON DUPLICATE KEY UPDATE play = play + 1, play_time = VALUES(play_time)")
    int upsertPlay(@Param("uid") Integer uid, @Param("vid") Integer vid, @Param("playedAt") Date playedAt);
}
