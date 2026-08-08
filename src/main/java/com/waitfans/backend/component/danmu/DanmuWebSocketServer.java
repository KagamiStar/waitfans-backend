package com.waitfans.backend.component.danmu;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.waitfans.backend.component.danmu.protocol.DanmuProtocol;
import com.waitfans.backend.component.danmu.protocol.DanmuProtocolErrorCode;
import com.waitfans.backend.component.danmu.protocol.DanmuProtocolFrame;
import com.waitfans.backend.pojo.Danmu;
import com.waitfans.backend.pojo.User;
import com.waitfans.backend.service.danmu.DanmuAuthorizationService;
import com.waitfans.backend.service.danmu.DanmuRateLimiter;
import com.waitfans.backend.service.danmu.DanmuSubmissionService;
import com.waitfans.backend.service.danmu.DanmuSubmitException;
import com.waitfans.backend.utils.RedisUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * V1 uses bounded per-session send queues. Presence is local to this single JVM instance.
 * Legacy sessions keep their stable raw wire format for one release cycle.
 */
@Slf4j
@Component
@ServerEndpoint(value = "/ws/danmu/{vid}")
public class DanmuWebSocketServer {
    private static final int MAX_MESSAGE_BYTES = 4096;
    private static final long STALE_MILLIS = 60_000L;
    private static final ConcurrentMap<Session, Connection> connections = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, ConcurrentMap<Session, Connection>> rooms = new ConcurrentHashMap<>();
    private static final ThreadFactory DAEMON_THREADS = runnable -> {
        Thread thread = new Thread(runnable, "danmu-websocket");
        thread.setDaemon(true);
        return thread;
    };
    private static final ExecutorService sendExecutor = Executors.newCachedThreadPool(DAEMON_THREADS);
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(DAEMON_THREADS);

    private static RedisUtil redisUtil;
    private static DanmuSubmissionService submissionService;
    private static DanmuAuthorizationService authorizationService;
    private static DanmuRateLimiter rateLimiter;

    static {
        scheduler.scheduleAtFixedRate(DanmuWebSocketServer::removeStaleConnections, 30, 30, TimeUnit.SECONDS);
    }

    @Autowired
    public void setDependencies(RedisUtil injectedRedisUtil, DanmuSubmissionService injectedSubmissionService,
                                DanmuAuthorizationService injectedAuthorizationService,
                                DanmuRateLimiter injectedRateLimiter) {
        redisUtil = injectedRedisUtil;
        submissionService = injectedSubmissionService;
        authorizationService = injectedAuthorizationService;
        rateLimiter = injectedRateLimiter;
    }

    @OnOpen
    public void onOpen(Session session, @PathParam("vid") String vid) {
        boolean v1 = "1".equals(parameter(session, "v"));
        String negotiatedVersion = parameter(session, "v");
        if (negotiatedVersion != null && !v1) {
            directV1(session, DanmuProtocol.error(null, DanmuProtocolErrorCode.UNSUPPORTED_VERSION));
            close(session);
            return;
        }
        Integer videoId;
        try {
            videoId = parseVid(vid);
            submissionService.requirePublicVideo(videoId);
        } catch (DanmuSubmitException exception) {
            if (v1) directV1(session, DanmuProtocol.error(null, exception.getErrorCode()));
            close(session);
            return;
        } catch (RuntimeException exception) {
            if (v1) directV1(session, DanmuProtocol.error(null, DanmuProtocolErrorCode.SERVICE_UNAVAILABLE));
            close(session);
            return;
        }
        Connection connection = new Connection(session, vid, v1);
        if (connections.putIfAbsent(session, connection) != null) return;
        rooms.computeIfAbsent(vid, key -> new ConcurrentHashMap<>()).put(session, connection);
        broadcastPresence(vid);
    }

    @OnMessage
    public void onMessage(Session session, String message, @PathParam("vid") String vid) {
        Connection connection = connections.get(session);
        if (connection == null) return;
        if (message == null || message.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) {
            if (connection.v1) sendV1(connection, DanmuProtocol.error(null, DanmuProtocolErrorCode.MESSAGE_TOO_LARGE), true);
            else disconnect(connection, true);
            return;
        }
        if (connection.v1) handleV1(connection, message, vid);
        else handleLegacy(connection, message, vid);
    }

    @OnClose
    public void onClose(Session session, @PathParam("vid") String vid) {
        disconnect(connections.get(session), false);
    }

    @OnError
    public void onError(Session session, Throwable error) {
        log.warn("Danmu websocket error", error);
        disconnect(connections.get(session), true);
    }

    private static void handleV1(Connection connection, String message, String vid) {
        JSONObject frame;
        try {
            frame = JSON.parseObject(message);
        } catch (RuntimeException exception) {
            sendV1(connection, DanmuProtocol.error(null, DanmuProtocolErrorCode.MALFORMED_FRAME), false);
            return;
        }
        if (frame == null) {
            sendV1(connection, DanmuProtocol.error(null, DanmuProtocolErrorCode.MALFORMED_FRAME), false);
            return;
        }
        String requestId = frame.getString("requestId");
        if (!Integer.valueOf(DanmuProtocol.VERSION).equals(frame.getInteger("version"))) {
            sendV1(connection, DanmuProtocol.error(requestId, DanmuProtocolErrorCode.UNSUPPORTED_VERSION), false);
            return;
        }
        String type = frame.getString("type");
        if (!"danmu".equals(type) && !"heartbeat".equals(type)) {
            sendV1(connection, DanmuProtocol.error(requestId, DanmuProtocolErrorCode.UNSUPPORTED_TYPE), false);
            return;
        }
        if (!validRequestId(requestId)) {
            sendV1(connection, DanmuProtocol.error(requestId, DanmuProtocolErrorCode.MISSING_REQUEST_ID), false);
            return;
        }
        connection.lastSeen = System.currentTimeMillis();
        try {
            if ("heartbeat".equals(type)) {
                rateLimiter.requireAllowed("danmu:rate:frame:" + connection.session.getId(), 20, 10);
                rateLimiter.requireAllowed("danmu:rate:heartbeat:" + connection.session.getId(), 6, 60);
                sendV1(connection, DanmuProtocol.frame("heartbeat", requestId, null, null), false);
                return;
            }
            Integer videoId = parseVid(vid);
            User user = authorizationService.requireNormalUser(frame.getString("token"));
            submissionService.validateForSubmission(videoId, frame.getJSONObject("data"));
            applyDanmuLimits(connection, user.getUid(), videoId);
            Danmu danmu = submissionService.submit(user.getUid(), videoId, frame.getJSONObject("data"));
            refreshCaches(danmu);
            if (sendV1(connection, DanmuProtocol.frame("ack", requestId, null, danmu), false)) {
                broadcastDanmu(vid, danmu);
            }
        } catch (DanmuSubmitException exception) {
            sendV1(connection, DanmuProtocol.error(requestId, exception.getErrorCode()), false);
        } catch (RuntimeException exception) {
            log.error("Danmu submit failed for vid={}", vid, exception);
            sendV1(connection, DanmuProtocol.error(requestId, DanmuProtocolErrorCode.SEND_FAILED), false);
        }
    }

    private static void handleLegacy(Connection connection, String message, String vid) {
        try {
            JSONObject frame = JSON.parseObject(message);
            if (frame == null) return;
            connection.lastSeen = System.currentTimeMillis();
            Integer videoId = parseVid(vid);
            User user = authorizationService.requireNormalUser(frame.getString("token"));
            submissionService.validateForSubmission(videoId, frame.getJSONObject("data"));
            applyDanmuLimits(connection, user.getUid(), videoId);
            Danmu danmu = submissionService.submit(user.getUid(), videoId, frame.getJSONObject("data"));
            refreshCaches(danmu);
            broadcastDanmu(vid, danmu);
        } catch (DanmuSubmitException exception) {
            sendLegacyError(connection, exception.getErrorCode());
        } catch (RuntimeException exception) {
            log.warn("Legacy danmu submit failed for vid={}", vid, exception);
        }
    }

    private static void applyDanmuLimits(Connection connection, Integer uid, Integer vid) {
        rateLimiter.requireAllowed("danmu:rate:frame:" + connection.session.getId(), 20, 10);
        rateLimiter.requireAllowed("danmu:rate:session:" + connection.session.getId(), 1, 1);
        rateLimiter.requireAllowed("danmu:rate:user:short:" + uid, 5, 10);
        rateLimiter.requireAllowed("danmu:rate:user:long:" + uid, 30, 60);
        rateLimiter.requireAllowed("danmu:rate:video:" + vid, 300, 10);
    }

    private static void refreshCaches(Danmu danmu) {
        try {
            redisUtil.addMember("danmu_idset:" + danmu.getVid(), danmu.getId());
            redisUtil.delValue("videoStats:" + danmu.getVid());
        } catch (RuntimeException exception) {
            log.warn("Danmu cache refresh failed after commit vid={}, danmuId={}", danmu.getVid(), danmu.getId(), exception);
        }
    }

    private static void broadcastDanmu(String vid, Danmu danmu) {
        broadcast(vid, "danmu", danmu, JSON.toJSONString(danmu));
    }

    private static void broadcastPresence(String vid) {
        ConcurrentMap<Session, Connection> room = rooms.get(vid);
        int viewerCount = room == null ? 0 : room.size();
        Map<String, Integer> data = Collections.singletonMap("viewerCount", viewerCount);
        broadcast(vid, "presence", data, "当前观看人数" + viewerCount);
    }

    private static void broadcast(String vid, String type, Object data, String legacyText) {
        ConcurrentMap<Session, Connection> room = rooms.get(vid);
        if (room == null) return;
        DanmuProtocolFrame frame = DanmuProtocol.frame(type, null, null, data);
        for (Connection connection : room.values()) {
            if (connection.v1) sendV1(connection, frame, false);
            else enqueue(connection, legacyText, false);
        }
    }

    private static boolean sendV1(Connection connection, DanmuProtocolFrame frame, boolean closeAfter) {
        return enqueue(connection, JSON.toJSONString(frame), closeAfter);
    }

    private static boolean enqueue(Connection connection, String message, boolean closeAfter) {
        if (connection == null || !connection.sender.enqueue(message, closeAfter)) {
            disconnect(connection, true);
            return false;
        }
        return true;
    }

    private static void sendLegacyError(Connection connection, DanmuProtocolErrorCode errorCode) {
        if (errorCode == DanmuProtocolErrorCode.AUTH_EXPIRED || errorCode == DanmuProtocolErrorCode.AUTH_REQUIRED) {
            enqueue(connection, "登录已过期", false);
        } else if (errorCode == DanmuProtocolErrorCode.SERVICE_UNAVAILABLE) {
            enqueue(connection, "弹幕服务暂不可用", false);
        } else {
            enqueue(connection, "弹幕发送失败", false);
        }
    }

    private static void removeStaleConnections() {
        long now = System.currentTimeMillis();
        for (Connection connection : connections.values()) {
            if (connection.v1 && now - connection.lastSeen > STALE_MILLIS) disconnect(connection, true);
        }
    }

    private static void disconnect(Connection connection, boolean closeSocket) {
        if (connection == null || !connection.removed.compareAndSet(false, true)) return;
        connections.remove(connection.session, connection);
        connection.sender.stop();
        ConcurrentMap<Session, Connection> room = rooms.get(connection.vid);
        if (room != null && room.remove(connection.session, connection)) {
            if (room.isEmpty()) rooms.remove(connection.vid, room);
            else broadcastPresence(connection.vid);
        }
        if (closeSocket) close(connection.session);
    }

    private static void directV1(Session session, DanmuProtocolFrame frame) {
        try {
            session.getBasicRemote().sendText(JSON.toJSONString(frame));
        } catch (IOException | RuntimeException ignored) {
            // The session is closed immediately after an onOpen rejection.
        }
    }

    private static Integer parseVid(String vid) {
        try {
            Integer value = Integer.valueOf(vid);
            if (value <= 0) throw new NumberFormatException();
            return value;
        } catch (RuntimeException exception) {
            throw new DanmuSubmitException(DanmuProtocolErrorCode.INVALID_VIDEO_ID);
        }
    }

    private static boolean validRequestId(String requestId) {
        return requestId != null && requestId.length() <= 64 && requestId.matches("[A-Za-z0-9_-]+");
    }

    private static String parameter(Session session, String name) {
        Map<String, java.util.List<String>> parameters = session.getRequestParameterMap();
        if (parameters == null) return null;
        java.util.List<String> values = parameters.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static void close(Session session) {
        try {
            session.close();
        } catch (IOException ignored) {
            // Nothing useful can be sent after a closed websocket.
        }
    }

    private static final class Connection {
        private final Session session;
        private final String vid;
        private final boolean v1;
        private final AtomicBoolean removed = new AtomicBoolean(false);
        private final DanmuSessionSender sender;
        private volatile long lastSeen = System.currentTimeMillis();

        private Connection(Session session, String vid, boolean v1) {
            this.session = session;
            this.vid = vid;
            this.v1 = v1;
            this.sender = new DanmuSessionSender(session, sendExecutor, scheduler, () -> disconnect(this, true));
        }
    }
}
