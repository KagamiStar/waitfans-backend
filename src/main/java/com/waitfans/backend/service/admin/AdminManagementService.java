package com.waitfans.backend.service.admin;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.waitfans.backend.mapper.CategoryMapper;
import com.waitfans.backend.mapper.CommentMapper;
import com.waitfans.backend.mapper.DanmuMapper;
import com.waitfans.backend.mapper.UserMapper;
import com.waitfans.backend.mapper.VideoMapper;
import com.waitfans.backend.pojo.Category;
import com.waitfans.backend.pojo.Comment;
import com.waitfans.backend.pojo.CustomResponse;
import com.waitfans.backend.pojo.Danmu;
import com.waitfans.backend.pojo.HotSearch;
import com.waitfans.backend.pojo.User;
import com.waitfans.backend.pojo.Video;
import com.waitfans.backend.service.comment.CommentService;
import com.waitfans.backend.service.danmu.DanmuService;
import com.waitfans.backend.service.search.SearchService;
import com.waitfans.backend.service.utils.CurrentUser;
import com.waitfans.backend.utils.RedisUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AdminManagementService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final SimpleDateFormat DATE_TIME_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private VideoMapper videoMapper;

    @Autowired
    private CommentMapper commentMapper;

    @Autowired
    private DanmuMapper danmuMapper;

    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private CurrentUser currentUser;

    @Autowired
    private RedisUtil redisUtil;

    @Autowired
    private CommentService commentService;

    @Autowired
    private DanmuService danmuService;

    @Autowired
    private SearchService searchService;

    public Map<String, Object> getOverview() {
        requireAdmin();

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("users", userMapper.selectCount(new QueryWrapper<User>().ne("state", 2)));
        counts.put("admins", userMapper.selectCount(new QueryWrapper<User>().gt("role", 0).ne("state", 2)));
        counts.put("bannedUsers", userMapper.selectCount(new QueryWrapper<User>().eq("state", 1)));
        counts.put("videos", videoMapper.selectCount(new QueryWrapper<Video>()));
        counts.put("pendingVideos", videoMapper.selectCount(new QueryWrapper<Video>().eq("status", 0)));
        counts.put("comments", commentMapper.selectCount(new QueryWrapper<Comment>().eq("is_deleted", 0)));
        counts.put("danmus", danmuMapper.selectCount(new QueryWrapper<Danmu>().eq("state", 1)));

        Map<String, Object> videoStatus = new LinkedHashMap<>();
        for (int status = 0; status <= 3; status++) {
            videoStatus.put(String.valueOf(status),
                    videoMapper.selectCount(new QueryWrapper<Video>().eq("status", status)));
        }

        List<Map<String, Object>> categoryDistribution = videoMapper.selectMaps(
                new QueryWrapper<Video>()
                        .select("mc_id AS name", "COUNT(*) AS value")
                        .eq("status", 1)
                        .groupBy("mc_id")
                        .orderByDesc("value")
                        .last("LIMIT 8")
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("counts", counts);
        result.put("videoStatus", videoStatus);
        result.put("trend", buildSevenDayTrend());
        result.put("categoryDistribution", categoryDistribution);
        result.put("recentUsers", getRecentUsers());
        result.put("recentVideos", getRecentVideos());
        return result;
    }

    public Map<String, Object> getUsers(
            String keyword,
            Integer state,
            Integer role,
            Integer page,
            Integer pageSize
    ) {
        requireAdmin();
        int safePage = normalizePage(page);
        int safePageSize = normalizePageSize(pageSize);

        QueryWrapper<User> wrapper = new QueryWrapper<>();
        if (hasText(keyword)) {
            String normalized = keyword.trim();
            wrapper.and(query -> query
                    .like("username", normalized)
                    .or()
                    .like("nickname", normalized)
                    .or()
                    .eq(isInteger(normalized), "uid", normalized));
        }
        if (state != null) wrapper.eq("state", state);
        if (role != null) wrapper.eq("role", role);

        Long total = userMapper.selectCount(wrapper);
        wrapper.orderByDesc("uid").last(limitClause(safePage, safePageSize));
        List<User> users = userMapper.selectList(wrapper);

        List<Map<String, Object>> items = new ArrayList<>();
        for (User user : users) items.add(toAdminUser(user));
        return pageResult(items, total, safePage, safePageSize);
    }

    @Transactional
    public Map<String, Object> updateUserState(Integer uid, Integer state) {
        User actor = requireAdmin();
        if (state == null || (state != 0 && state != 1)) {
            throw new IllegalArgumentException("用户状态仅支持正常或封禁");
        }
        User target = requireUser(uid);
        if (target.getUid().equals(actor.getUid())) {
            throw new IllegalArgumentException("不能修改自己的账号状态");
        }
        if (target.getRole() != null && target.getRole() == 2) {
            throw new IllegalArgumentException("超级管理员账号不可被封禁");
        }
        if (target.getRole() != null && target.getRole() > 0 && actor.getRole() < 2) {
            throw new SecurityException("只有超级管理员可以管理其他管理员");
        }

        userMapper.update(null, new UpdateWrapper<User>().eq("uid", uid).set("state", state));
        invalidateUserSession(uid);
        return toAdminUser(requireUser(uid));
    }

    @Transactional
    public Map<String, Object> updateUserRole(Integer uid, Integer role) {
        User actor = requireAdmin();
        if (actor.getRole() == null || actor.getRole() != 2) {
            throw new SecurityException("只有超级管理员可以调整角色");
        }
        if (role == null || role < 0 || role > 2) {
            throw new IllegalArgumentException("角色值不合法");
        }
        User target = requireUser(uid);
        if (target.getUid().equals(actor.getUid())) {
            throw new IllegalArgumentException("不能修改自己的角色");
        }
        if (target.getRole() != null && target.getRole() == 2) {
            throw new IllegalArgumentException("不能修改其他超级管理员");
        }

        userMapper.update(null, new UpdateWrapper<User>().eq("uid", uid).set("role", role));
        invalidateUserSession(uid);
        return toAdminUser(requireUser(uid));
    }

    public Map<String, Object> getComments(
            String keyword,
            Integer vid,
            Integer deleted,
            Integer page,
            Integer pageSize
    ) {
        requireAdmin();
        int safePage = normalizePage(page);
        int safePageSize = normalizePageSize(pageSize);

        QueryWrapper<Comment> wrapper = new QueryWrapper<>();
        if (hasText(keyword)) wrapper.like("content", keyword.trim());
        if (vid != null) wrapper.eq("vid", vid);
        if (deleted != null) wrapper.eq("is_deleted", deleted);

        Long total = commentMapper.selectCount(wrapper);
        wrapper.orderByDesc("id").last(limitClause(safePage, safePageSize));
        List<Comment> comments = commentMapper.selectList(wrapper);
        Map<Integer, User> users = loadUsersFromComments(comments);
        Map<Integer, Video> videos = loadVideosFromComments(comments);

        List<Map<String, Object>> items = new ArrayList<>();
        for (Comment comment : comments) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", comment.getId());
            item.put("vid", comment.getVid());
            item.put("uid", comment.getUid());
            item.put("content", comment.getContent());
            item.put("love", comment.getLove());
            item.put("bad", comment.getBad());
            item.put("rootId", comment.getRootId());
            item.put("parentId", comment.getParentId());
            item.put("isTop", comment.getIsTop());
            item.put("isDeleted", comment.getIsDeleted());
            item.put("createTime", formatDate(comment.getCreateTime()));
            item.put("nickname", users.containsKey(comment.getUid())
                    ? users.get(comment.getUid()).getNickname() : "未知用户");
            item.put("videoTitle", videos.containsKey(comment.getVid())
                    ? videos.get(comment.getVid()).getTitle() : "视频已不存在");
            items.add(item);
        }
        return pageResult(items, total, safePage, safePageSize);
    }

    @Transactional
    public void deleteComment(Integer id) {
        User actor = requireAdmin();
        CustomResponse response = commentService.deleteComment(id, actor.getUid(), true);
        if (response.getCode() != 200) throw new IllegalArgumentException(response.getMessage());
    }

    public Map<String, Object> getDanmus(
            String keyword,
            Integer vid,
            Integer state,
            Integer page,
            Integer pageSize
    ) {
        requireAdmin();
        int safePage = normalizePage(page);
        int safePageSize = normalizePageSize(pageSize);

        QueryWrapper<Danmu> wrapper = new QueryWrapper<>();
        if (hasText(keyword)) wrapper.like("content", keyword.trim());
        if (vid != null) wrapper.eq("vid", vid);
        if (state != null) wrapper.eq("state", state);

        Long total = danmuMapper.selectCount(wrapper);
        wrapper.orderByDesc("id").last(limitClause(safePage, safePageSize));
        List<Danmu> danmus = danmuMapper.selectList(wrapper);
        Map<Integer, User> users = loadUsersFromDanmus(danmus);
        Map<Integer, Video> videos = loadVideosFromDanmus(danmus);

        List<Map<String, Object>> items = new ArrayList<>();
        for (Danmu danmu : danmus) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", danmu.getId());
            item.put("vid", danmu.getVid());
            item.put("uid", danmu.getUid());
            item.put("content", danmu.getContent());
            item.put("fontsize", danmu.getFontsize());
            item.put("mode", danmu.getMode());
            item.put("color", danmu.getColor());
            item.put("timePoint", danmu.getTimePoint());
            item.put("state", danmu.getState());
            item.put("createDate", formatDate(danmu.getCreateDate()));
            item.put("nickname", users.containsKey(danmu.getUid())
                    ? users.get(danmu.getUid()).getNickname() : "未知用户");
            item.put("videoTitle", videos.containsKey(danmu.getVid())
                    ? videos.get(danmu.getVid()).getTitle() : "视频已不存在");
            items.add(item);
        }
        return pageResult(items, total, safePage, safePageSize);
    }

    @Transactional
    public void deleteDanmu(Integer id) {
        User actor = requireAdmin();
        CustomResponse response = danmuService.deleteDanmu(id, actor.getUid(), true);
        if (response.getCode() != 200) throw new IllegalArgumentException(response.getMessage());
    }

    public List<Category> getCategories() {
        requireAdmin();
        return categoryMapper.selectList(
                new QueryWrapper<Category>().orderByAsc("mc_id").orderByAsc("sc_id")
        );
    }

    @Transactional
    public Category updateCategory(Map<String, String> values) {
        requireAdmin();
        String mcId = normalized(values.get("mcId"));
        String scId = normalized(values.get("scId"));
        String mcName = normalized(values.get("mcName"));
        String scName = normalized(values.get("scName"));
        String descr = normalized(values.get("descr"));
        String rcmTag = normalized(values.get("rcmTag"));
        if (!hasText(mcId) || !hasText(scId) || !hasText(mcName) || !hasText(scName)) {
            throw new IllegalArgumentException("分区标识和名称不能为空");
        }
        if (mcName.length() > 20 || scName.length() > 20 || descr.length() > 500) {
            throw new IllegalArgumentException("分区内容超出长度限制");
        }

        QueryWrapper<Category> identity = new QueryWrapper<Category>()
                .eq("mc_id", mcId)
                .eq("sc_id", scId);
        if (categoryMapper.selectCount(identity) == 0) {
            throw new IllegalArgumentException("分区不存在");
        }

        categoryMapper.update(null, new UpdateWrapper<Category>()
                .eq("mc_id", mcId)
                .eq("sc_id", scId)
                .set("mc_name", mcName)
                .set("sc_name", scName)
                .set("descr", descr)
                .set("rcm_tag", rcmTag));
        redisUtil.delValue("categoryList");
        redisUtil.delValue("category:" + mcId + ":" + scId);
        return categoryMapper.selectOne(identity);
    }

    public List<HotSearch> getHotSearch() {
        requireAdmin();
        List<HotSearch> items = searchService.getHotSearch();
        return items == null ? Collections.emptyList() : items;
    }

    public List<HotSearch> upsertHotSearch(String keyword, Double score) {
        requireAdmin();
        String normalized = normalized(keyword);
        if (!hasText(normalized) || normalized.length() > 30) {
            throw new IllegalArgumentException("热搜词长度应为 1 到 30 个字符");
        }
        if (score == null || score < 0) throw new IllegalArgumentException("热度不能小于 0");
        redisUtil.zsetWithScore("search_word", normalized, score);
        return getHotSearch();
    }

    public List<HotSearch> removeHotSearch(String keyword) {
        requireAdmin();
        String normalized = normalized(keyword);
        if (!hasText(normalized)) throw new IllegalArgumentException("热搜词不能为空");
        redisUtil.zsetDelMember("search_word", normalized);
        return getHotSearch();
    }

    private List<Map<String, Object>> buildSevenDayTrend() {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate firstDay = LocalDate.now(zone).minusDays(6);
        Date startDate = Date.from(firstDay.atStartOfDay(zone).toInstant());
        DateTimeFormatter keyFormatter = DateTimeFormatter.ISO_LOCAL_DATE;
        DateTimeFormatter labelFormatter = DateTimeFormatter.ofPattern("MM/dd");

        Map<String, Map<String, Object>> days = new LinkedHashMap<>();
        for (int i = 0; i < 7; i++) {
            LocalDate day = firstDay.plusDays(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", day.format(keyFormatter));
            item.put("label", day.format(labelFormatter));
            item.put("users", 0);
            item.put("videos", 0);
            days.put(day.format(keyFormatter), item);
        }

        List<User> users = userMapper.selectList(
                new QueryWrapper<User>().select("create_date").ge("create_date", startDate)
        );
        for (User user : users) incrementTrend(days, user.getCreateDate(), "users", zone);

        List<Video> videos = videoMapper.selectList(
                new QueryWrapper<Video>().select("upload_date").ge("upload_date", startDate)
        );
        for (Video video : videos) incrementTrend(days, video.getUploadDate(), "videos", zone);
        return new ArrayList<>(days.values());
    }

    private void incrementTrend(
            Map<String, Map<String, Object>> days,
            Date date,
            String field,
            ZoneId zone
    ) {
        if (date == null) return;
        String key = date.toInstant().atZone(zone).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
        Map<String, Object> item = days.get(key);
        if (item != null) item.put(field, ((Integer) item.get(field)) + 1);
    }

    private List<Map<String, Object>> getRecentUsers() {
        List<User> users = userMapper.selectList(
                new QueryWrapper<User>().orderByDesc("uid").last("LIMIT 5")
        );
        List<Map<String, Object>> items = new ArrayList<>();
        for (User user : users) items.add(toAdminUser(user));
        return items;
    }

    private List<Map<String, Object>> getRecentVideos() {
        List<Video> videos = videoMapper.selectList(
                new QueryWrapper<Video>().orderByDesc("vid").last("LIMIT 5")
        );
        Set<Integer> userIds = new LinkedHashSet<>();
        for (Video video : videos) userIds.add(video.getUid());
        Map<Integer, User> users = loadUsers(userIds);

        List<Map<String, Object>> items = new ArrayList<>();
        for (Video video : videos) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("vid", video.getVid());
            item.put("title", video.getTitle());
            item.put("status", video.getStatus());
            item.put("uploadDate", formatDate(video.getUploadDate()));
            item.put("nickname", users.containsKey(video.getUid())
                    ? users.get(video.getUid()).getNickname() : "未知用户");
            items.add(item);
        }
        return items;
    }

    private Map<Integer, User> loadUsersFromComments(List<Comment> comments) {
        Set<Integer> ids = new LinkedHashSet<>();
        for (Comment comment : comments) if (comment.getUid() != null) ids.add(comment.getUid());
        return loadUsers(ids);
    }

    private Map<Integer, Video> loadVideosFromComments(List<Comment> comments) {
        Set<Integer> ids = new LinkedHashSet<>();
        for (Comment comment : comments) if (comment.getVid() != null) ids.add(comment.getVid());
        return loadVideos(ids);
    }

    private Map<Integer, User> loadUsersFromDanmus(List<Danmu> danmus) {
        Set<Integer> ids = new LinkedHashSet<>();
        for (Danmu danmu : danmus) if (danmu.getUid() != null) ids.add(danmu.getUid());
        return loadUsers(ids);
    }

    private Map<Integer, Video> loadVideosFromDanmus(List<Danmu> danmus) {
        Set<Integer> ids = new LinkedHashSet<>();
        for (Danmu danmu : danmus) if (danmu.getVid() != null) ids.add(danmu.getVid());
        return loadVideos(ids);
    }

    private Map<Integer, User> loadUsers(Set<Integer> ids) {
        Map<Integer, User> result = new HashMap<>();
        if (ids.isEmpty()) return result;
        for (User user : userMapper.selectBatchIds(ids)) result.put(user.getUid(), user);
        return result;
    }

    private Map<Integer, Video> loadVideos(Set<Integer> ids) {
        Map<Integer, Video> result = new HashMap<>();
        if (ids.isEmpty()) return result;
        for (Video video : videoMapper.selectBatchIds(ids)) result.put(video.getVid(), video);
        return result;
    }

    private Map<String, Object> toAdminUser(User user) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("uid", user.getUid());
        item.put("username", user.getUsername());
        item.put("nickname", user.getNickname());
        item.put("avatar", user.getAvatar());
        item.put("state", user.getState());
        item.put("role", user.getRole());
        item.put("auth", user.getAuth());
        item.put("authMsg", user.getAuthMsg());
        item.put("vip", user.getVip());
        item.put("exp", user.getExp());
        item.put("createDate", formatDate(user.getCreateDate()));
        return item;
    }

    private Map<String, Object> pageResult(
            List<Map<String, Object>> items,
            Long total,
            int page,
            int pageSize
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", total == null ? 0 : total);
        result.put("page", page);
        result.put("pageSize", pageSize);
        return result;
    }

    private User requireAdmin() {
        Integer uid = currentUser.getUserId();
        User actor = userMapper.selectById(uid);
        if (actor == null || actor.getRole() == null || actor.getRole() == 0) {
            throw new SecurityException("您不是管理员，无权访问");
        }
        if (actor.getState() != null && actor.getState() != 0) {
            throw new SecurityException("管理员账号状态异常");
        }
        return actor;
    }

    private User requireUser(Integer uid) {
        if (uid == null) throw new IllegalArgumentException("用户 ID 不能为空");
        User user = userMapper.selectById(uid);
        if (user == null) throw new IllegalArgumentException("用户不存在");
        return user;
    }

    private void invalidateUserSession(Integer uid) {
        redisUtil.delValue("user:" + uid);
        redisUtil.delValue("security:user:" + uid);
        redisUtil.delValue("security:admin:" + uid);
        redisUtil.delValue("token:user:" + uid);
        redisUtil.delValue("token:admin:" + uid);
    }

    private int normalizePage(Integer page) {
        return page == null || page < 1 ? 1 : page;
    }

    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) return 10;
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private String limitClause(int page, int pageSize) {
        return "LIMIT " + ((page - 1) * pageSize) + ", " + pageSize;
    }

    private String formatDate(Date date) {
        if (date == null) return null;
        synchronized (DATE_TIME_FORMAT) {
            return DATE_TIME_FORMAT.format(date);
        }
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private boolean isInteger(String value) {
        if (!hasText(value)) return false;
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) return false;
        }
        return true;
    }
}
