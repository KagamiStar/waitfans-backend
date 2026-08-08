package com.waitfans.backend.controller;

import com.waitfans.backend.pojo.CustomResponse;
import com.waitfans.backend.service.utils.CurrentUser;
import com.waitfans.backend.service.video.UserVideoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserVideoController {
    @Autowired
    private UserVideoService userVideoService;

    @Autowired
    private CurrentUser currentUser;

    /**
     * 点赞或点踩
     * @param vid   视频ID
     * @param isLove    赞还是踩 true赞 false踩
     * @param isSet     点还是取消 true点 false取消
     * @return 返回用户与该视频更新后的交互数据
     */
    @PostMapping("/video/love-or-not")
    public CustomResponse loveOrNot(@RequestParam("vid") Integer vid,
                                    @RequestParam("isLove") boolean isLove,
                                    @RequestParam("isSet") boolean isSet) {
        Integer uid = currentUser.getUserId();
        CustomResponse customResponse = new CustomResponse();
        customResponse.setData(userVideoService.setLoveOrUnlove(uid, vid, isLove, isSet));
        return customResponse;
    }

}
