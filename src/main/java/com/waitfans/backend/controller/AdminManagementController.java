package com.waitfans.backend.controller;

import com.waitfans.backend.pojo.CustomResponse;
import com.waitfans.backend.service.admin.AdminManagementService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.function.Supplier;

@Slf4j
@RestController
@RequestMapping("/admin/management")
public class AdminManagementController {

    @Autowired
    private AdminManagementService adminManagementService;

    @GetMapping("/overview")
    public CustomResponse overview() {
        return execute(() -> adminManagementService.getOverview(), "概览加载成功");
    }

    @GetMapping("/users")
    public CustomResponse users(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "state", required = false) Integer state,
            @RequestParam(value = "role", required = false) Integer role,
            @RequestParam(value = "page", defaultValue = "1") Integer page,
            @RequestParam(value = "pageSize", defaultValue = "10") Integer pageSize
    ) {
        return execute(
                () -> adminManagementService.getUsers(keyword, state, role, page, pageSize),
                "用户列表加载成功"
        );
    }

    @PostMapping("/users/{uid}/state")
    public CustomResponse updateUserState(
            @PathVariable("uid") Integer uid,
            @RequestBody Map<String, Integer> body
    ) {
        return execute(
                () -> adminManagementService.updateUserState(uid, body.get("state")),
                "用户状态已更新"
        );
    }

    @PostMapping("/users/{uid}/role")
    public CustomResponse updateUserRole(
            @PathVariable("uid") Integer uid,
            @RequestBody Map<String, Integer> body
    ) {
        return execute(
                () -> adminManagementService.updateUserRole(uid, body.get("role")),
                "用户角色已更新"
        );
    }

    @GetMapping("/comments")
    public CustomResponse comments(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "vid", required = false) Integer vid,
            @RequestParam(value = "deleted", required = false) Integer deleted,
            @RequestParam(value = "page", defaultValue = "1") Integer page,
            @RequestParam(value = "pageSize", defaultValue = "10") Integer pageSize
    ) {
        return execute(
                () -> adminManagementService.getComments(keyword, vid, deleted, page, pageSize),
                "评论列表加载成功"
        );
    }

    @PostMapping("/comments/{id}/delete")
    public CustomResponse deleteComment(@PathVariable("id") Integer id) {
        return execute(() -> {
            adminManagementService.deleteComment(id);
            return null;
        }, "评论已删除");
    }

    @GetMapping("/danmus")
    public CustomResponse danmus(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "vid", required = false) Integer vid,
            @RequestParam(value = "state", required = false) Integer state,
            @RequestParam(value = "page", defaultValue = "1") Integer page,
            @RequestParam(value = "pageSize", defaultValue = "10") Integer pageSize
    ) {
        return execute(
                () -> adminManagementService.getDanmus(keyword, vid, state, page, pageSize),
                "弹幕列表加载成功"
        );
    }

    @PostMapping("/danmus/{id}/delete")
    public CustomResponse deleteDanmu(@PathVariable("id") Integer id) {
        return execute(() -> {
            adminManagementService.deleteDanmu(id);
            return null;
        }, "弹幕已删除");
    }

    @GetMapping("/categories")
    public CustomResponse categories() {
        return execute(() -> adminManagementService.getCategories(), "分区列表加载成功");
    }

    @PostMapping("/categories/update")
    public CustomResponse updateCategory(@RequestBody Map<String, String> body) {
        return execute(() -> adminManagementService.updateCategory(body), "分区信息已更新");
    }

    @GetMapping("/hot-search")
    public CustomResponse hotSearch() {
        return execute(() -> adminManagementService.getHotSearch(), "热搜列表加载成功");
    }

    @PostMapping("/hot-search/update")
    public CustomResponse updateHotSearch(@RequestBody Map<String, Object> body) {
        return execute(() -> adminManagementService.upsertHotSearch(
                String.valueOf(body.getOrDefault("keyword", "")),
                toDouble(body.get("score"))
        ), "热搜词已保存");
    }

    @PostMapping("/hot-search/remove")
    public CustomResponse removeHotSearch(@RequestBody Map<String, String> body) {
        return execute(
                () -> adminManagementService.removeHotSearch(body.get("keyword")),
                "热搜词已移除"
        );
    }

    private Double toDouble(Object value) {
        if (value == null) return null;
        try {
            return Double.valueOf(String.valueOf(value));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("热度必须是数字");
        }
    }

    private CustomResponse execute(Supplier<Object> action, String successMessage) {
        CustomResponse response = new CustomResponse();
        try {
            response.setData(action.get());
            response.setMessage(successMessage);
        } catch (SecurityException exception) {
            response.setCode(403);
            response.setMessage(exception.getMessage());
        } catch (IllegalArgumentException exception) {
            response.setCode(400);
            response.setMessage(exception.getMessage());
        } catch (Exception exception) {
            log.error("管理员操作执行失败", exception);
            response.setCode(500);
            response.setMessage("操作失败，请稍后重试");
        }
        return response;
    }
}
