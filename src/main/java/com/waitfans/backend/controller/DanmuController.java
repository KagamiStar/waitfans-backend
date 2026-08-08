package com.waitfans.backend.controller;

import com.waitfans.backend.pojo.CustomResponse;
import com.waitfans.backend.pojo.Danmu;
import com.waitfans.backend.service.danmu.DanmuService;
import com.waitfans.backend.service.utils.CurrentUser;
import com.waitfans.backend.utils.RedisUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
public class DanmuController {
    @Autowired
    private DanmuService danmuService;

    @Autowired
    private RedisUtil redisUtil;

    @Autowired
    private CurrentUser currentUser;

    /**
     * 获取弹幕列表
     * @param vid   视频ID
     * @return  CustomResponse对象
     */
    @GetMapping("/danmu-list/{vid}")
    public CustomResponse getDanmuList(@PathVariable("vid") String vid) {
        List<Danmu> danmuList = null;
        try {
            Set<Object> idset = redisUtil.getMembers("danmu_idset:" + vid);
            danmuList = danmuService.getDanmuListByIdset(idset);
        } catch (RuntimeException ignored) {
            // Redis is a cache. Fall back to the authoritative database below.
        }
        if (danmuList == null) {
            danmuList = danmuService.getPublishedDanmuList(Integer.parseInt(vid));
            try {
                for (Danmu danmu : danmuList) redisUtil.addMember("danmu_idset:" + vid, danmu.getId());
            } catch (RuntimeException ignored) {
                // Cache rebuild is best effort and must not hide committed danmu.
            }
        }
        CustomResponse customResponse = new CustomResponse();
        customResponse.setData(danmuList);
        return customResponse;
    }

    /**
     * 删除弹幕
     * @param id    弹幕id
     * @return  响应对象
     */
    @PostMapping("/danmu/delete")
    public CustomResponse deleteDanmu(@RequestParam("id") Integer id) {
        Integer loginUid = currentUser.getUserId();
        return danmuService.deleteDanmu(id, loginUid, currentUser.isAdmin());
    }
}
