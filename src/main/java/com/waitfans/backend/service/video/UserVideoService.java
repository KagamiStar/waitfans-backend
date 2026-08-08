package com.waitfans.backend.service.video;

import com.waitfans.backend.pojo.UserVideo;

public interface UserVideoService {
    /**
     * 点赞或点踩，返回更新后的信息
     * @param uid   用户ID
     * @param vid   视频ID
     * @param isLove    赞还是踩 true赞 false踩
     * @param isSet     设置还是取消  true设置 false取消
     * @return  更新后的信息
     */
    UserVideo setLoveOrUnlove(Integer uid, Integer vid, boolean isLove, boolean isSet);

    /**
     * 收藏或取消收藏
     * @param uid   用户ID
     * @param vid   视频ID
     * @param isCollect 是否收藏 true收藏 false取消
     */
    void collectOrCancel(Integer uid, Integer vid, boolean isCollect);
}
