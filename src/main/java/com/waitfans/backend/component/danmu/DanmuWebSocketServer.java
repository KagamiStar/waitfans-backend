package com.waitfans.backend.component.danmu;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.waitfans.backend.component.danmu.protocol.DanmuProtocol;
import com.waitfans.backend.component.danmu.protocol.DanmuProtocolErrorCode;
import com.waitfans.backend.component.danmu.protocol.DanmuProtocolFrame;
import com.waitfans.backend.pojo.Danmu;
import com.waitfans.backend.pojo.User;
import com.waitfans.backend.service.danmu.DanmuSubmissionService;
import com.waitfans.backend.service.danmu.DanmuSubmitException;
import com.waitfans.backend.utils.JwtUtil;
import com.waitfans.backend.utils.RedisUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Legacy sessions keep the pre-V1 raw Danmu/text wire format for one stable release cycle.
 */
@Slf4j
@Component
@ServerEndpoint(value = "/ws/danmu/{vid}")
public class DanmuWebSocketServer {
    private static JwtUtil jwtUtil;
    private static RedisUtil redisUtil;
    private static DanmuSubmissionService submissionService;

    private static final Map<String, Set<Session>> videoConnectionMap = new ConcurrentHashMap<>();
    private static final Map<Session, Boolean> v1Sessions = new ConcurrentHashMap<>();

    @Autowired
    public void setDependencies(JwtUtil injectedJwtUtil, RedisUtil injectedRedisUtil,
                                DanmuSubmissionService injectedSubmissionService) {
        jwtUtil = injectedJwtUtil;
        redisUtil = injectedRedisUtil;
        submissionService = injectedSubmissionService;
    }

    @OnOpen
    public void onOpen(Session session, @PathParam("vid") String vid) {
        String negotiatedVersion = parameter(session, "v");
        if (negotiatedVersion != null && !"1".equals(negotiatedVersion)) {
            sendV1(session, DanmuProtocol.error(null, DanmuProtocolErrorCode.UNSUPPORTED_VERSION));
            close(session);
            return;
        }
        v1Sessions.put(session, "1".equals(negotiatedVersion));
        videoConnectionMap.computeIfAbsent(vid, key -> Collections.newSetFromMap(new ConcurrentHashMap<Session, Boolean>()))
                .add(session);
        broadcastPresence(vid);
    }

    @OnMessage
    public void onMessage(Session session, String message, @PathParam("vid") String vid) {
        if (Boolean.TRUE.equals(v1Sessions.get(session))) {
            handleV1(session, message, vid);
        } else {
            handleLegacy(session, message, vid);
        }
    }

    @OnClose
    public void onClose(Session session, @PathParam("vid") String vid) {
        v1Sessions.remove(session);
        Set<Session> sessions = videoConnectionMap.get(vid);
        if (sessions == null) return;
        sessions.remove(session);
        if (sessions.isEmpty()) {
            videoConnectionMap.remove(vid, sessions);
        } else {
            broadcastPresence(vid);
        }
    }

    @OnError
    public void onError(Throwable error) {
        log.warn("Danmu websocket error", error);
    }

    private void handleV1(Session session, String message, String vid) {
        JSONObject frame;
        try {
            frame = JSON.parseObject(message);
        } catch (RuntimeException exception) {
            sendV1(session, DanmuProtocol.error(null, DanmuProtocolErrorCode.MALFORMED_FRAME));
            return;
        }
        if (frame == null) {
            sendV1(session, DanmuProtocol.error(null, DanmuProtocolErrorCode.MALFORMED_FRAME));
            return;
        }
        String requestId = frame.getString("requestId");
        if (!Integer.valueOf(DanmuProtocol.VERSION).equals(frame.getInteger("version"))) {
            sendV1(session, DanmuProtocol.error(requestId, DanmuProtocolErrorCode.UNSUPPORTED_VERSION));
            return;
        }
        String type = frame.getString("type");
        if (!"danmu".equals(type) && !"heartbeat".equals(type)) {
            sendV1(session, DanmuProtocol.error(requestId, DanmuProtocolErrorCode.UNSUPPORTED_TYPE));
            return;
        }
        if (!validRequestId(requestId)) {
            sendV1(session, DanmuProtocol.error(requestId, DanmuProtocolErrorCode.MISSING_REQUEST_ID));
            return;
        }
        if ("heartbeat".equals(type)) {
            sendV1(session, DanmuProtocol.frame("heartbeat", requestId, null, null));
            return;
        }
        User user = authenticate(frame.getString("token"), session, requestId, true);
        if (user == null) return;
        try {
            Danmu danmu = submissionService.submit(user.getUid(), parseVid(vid), frame.getJSONObject("data"));
            refreshCaches(danmu);
            sendV1(session, DanmuProtocol.frame("ack", requestId, null, danmu));
            broadcastDanmu(vid, danmu);
        } catch (DanmuSubmitException exception) {
            sendV1(session, DanmuProtocol.error(requestId, exception.getErrorCode()));
        } catch (RuntimeException exception) {
            log.error("Danmu submit failed for vid={}", vid, exception);
            sendV1(session, DanmuProtocol.error(requestId, DanmuProtocolErrorCode.SEND_FAILED));
        }
    }

    private void handleLegacy(Session session, String message, String vid) {
        try {
            JSONObject frame = JSON.parseObject(message);
            User user = authenticate(frame.getString("token"), session, null, false);
            if (user == null) return;
            Danmu danmu = submissionService.submit(user.getUid(), parseVid(vid), frame.getJSONObject("data"));
            refreshCaches(danmu);
            broadcastDanmu(vid, danmu);
        } catch (RuntimeException exception) {
            log.warn("Legacy danmu submit failed for vid={}", vid);
        }
    }

    private User authenticate(String authorization, Session session, String requestId, boolean v1) {
        if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
            sendAuthError(session, requestId, v1, DanmuProtocolErrorCode.AUTH_REQUIRED);
            return null;
        }
        String token = authorization.substring(7);
        boolean validToken;
        try {
            validToken = jwtUtil.verifyToken(token);
        } catch (RuntimeException exception) {
            sendAuthError(session, requestId, v1, DanmuProtocolErrorCode.SERVICE_UNAVAILABLE);
            return null;
        }
        if (!validToken) {
            sendAuthError(session, requestId, v1, DanmuProtocolErrorCode.AUTH_EXPIRED);
            return null;
        }
        try {
            String userId = JwtUtil.getSubjectFromToken(token);
            String role = JwtUtil.getClaimFromToken(token, "role");
            User user = redisUtil.getObject("security:" + role + ":" + userId, User.class);
            if (user == null) {
                sendAuthError(session, requestId, v1, DanmuProtocolErrorCode.AUTH_EXPIRED);
            }
            return user;
        } catch (RuntimeException exception) {
            if (v1) sendV1(session, DanmuProtocol.error(requestId, DanmuProtocolErrorCode.SERVICE_UNAVAILABLE));
            return null;
        }
    }

    private void sendAuthError(Session session, String requestId, boolean v1, DanmuProtocolErrorCode code) {
        if (v1) {
            sendV1(session, DanmuProtocol.error(requestId, code));
        } else {
            sendRaw(session, code == DanmuProtocolErrorCode.SERVICE_UNAVAILABLE ? "弹幕服务暂不可用" : "登录已过期");
        }
    }

    private void refreshCaches(Danmu danmu) {
        try {
            redisUtil.addMember("danmu_idset:" + danmu.getVid(), danmu.getId());
            redisUtil.delValue("videoStats:" + danmu.getVid());
        } catch (RuntimeException exception) {
            log.warn("Danmu cache refresh failed after commit vid={}, danmuId={}", danmu.getVid(), danmu.getId(), exception);
        }
    }

    private void broadcastDanmu(String vid, Danmu danmu) {
        broadcast(vid, "danmu", danmu, JSON.toJSONString(danmu));
    }

    private void broadcastPresence(String vid) {
        Set<Session> sessions = videoConnectionMap.get(vid);
        int viewerCount = sessions == null ? 0 : sessions.size();
        Map<String, Integer> data = Collections.singletonMap("viewerCount", viewerCount);
        broadcast(vid, "presence", data, "当前观看人数" + viewerCount);
    }

    private void broadcast(String vid, String type, Object data, String legacyText) {
        Set<Session> sessions = videoConnectionMap.get(vid);
        if (sessions == null) return;
        DanmuProtocolFrame frame = DanmuProtocol.frame(type, null, null, data);
        for (Session session : sessions) {
            if (Boolean.TRUE.equals(v1Sessions.get(session))) sendV1(session, frame);
            else sendRaw(session, legacyText);
        }
    }

    private void sendV1(Session session, DanmuProtocolFrame frame) {
        sendRaw(session, JSON.toJSONString(frame));
    }

    private void sendRaw(Session session, String text) {
        try {
            session.getBasicRemote().sendText(text);
        } catch (IOException | RuntimeException exception) {
            log.warn("Danmu websocket send failed", exception);
        }
    }

    private static boolean validRequestId(String requestId) {
        return requestId != null && requestId.length() <= 64 && requestId.matches("[A-Za-z0-9_-]+");
    }

    private static Integer parseVid(String vid) {
        try {
            return Integer.valueOf(vid);
        } catch (NumberFormatException exception) {
            throw new DanmuSubmitException(DanmuProtocolErrorCode.VIDEO_NOT_FOUND);
        }
    }

    private static String parameter(Session session, String name) {
        java.util.List<String> values = session.getRequestParameterMap().get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static void close(Session session) {
        try {
            session.close();
        } catch (IOException ignored) {
            // Nothing useful can be sent after an unsupported protocol version.
        }
    }
}
