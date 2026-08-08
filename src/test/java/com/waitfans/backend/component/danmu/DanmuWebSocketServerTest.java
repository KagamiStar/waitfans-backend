package com.waitfans.backend.component.danmu;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.waitfans.backend.pojo.Video;
import com.waitfans.backend.service.danmu.DanmuAuthorizationService;
import com.waitfans.backend.service.danmu.DanmuRateLimiter;
import com.waitfans.backend.service.danmu.DanmuSubmissionService;
import com.waitfans.backend.utils.RedisUtil;
import org.junit.jupiter.api.Test;

import javax.websocket.RemoteEndpoint;
import javax.websocket.Session;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DanmuWebSocketServerTest {
    @Test
    void mixedRoomUsesExactlyOneWireEncodingPerSession() throws Exception {
        DanmuWebSocketServer server = new DanmuWebSocketServer();
        configure(server);
        Session v1 = session(true, "v1");
        Session legacy = session(false, "legacy");
        RemoteEndpoint.Basic v1Remote = v1.getBasicRemote();
        RemoteEndpoint.Basic legacyRemote = legacy.getBasicRemote();

        server.onOpen(v1, "10");
        server.onOpen(legacy, "10");

        org.mockito.ArgumentCaptor<String> v1Messages = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> legacyMessages = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(v1Remote, timeout(1000).atLeast(1)).sendText(v1Messages.capture());
        verify(legacyRemote, timeout(1000).atLeast(1)).sendText(legacyMessages.capture());
        assertTrue(v1Messages.getAllValues().stream().allMatch(value -> JSON.parseObject(value).getInteger("version") == 1));
        assertTrue(legacyMessages.getAllValues().stream().allMatch(value -> value.startsWith("当前观看人数")));

        server.onClose(v1, "10");
        server.onClose(legacy, "10");
    }

    @Test
    void v1MalformedFrameGetsAProtocolErrorEnvelope() throws Exception {
        DanmuWebSocketServer server = new DanmuWebSocketServer();
        DanmuRateLimiter limiter = configure(server);
        Session session = session(true, "malformed");
        RemoteEndpoint.Basic remote = session.getBasicRemote();
        server.onOpen(session, "10");

        server.onMessage(session, "{", "10");
        server.onMessage(session, "{", "10");

        org.mockito.ArgumentCaptor<String> message = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(remote, timeout(1000).atLeast(3)).sendText(message.capture());
        JSONObject frame = JSON.parseObject(message.getAllValues().get(message.getAllValues().size() - 1));
        assertEquals(1, frame.getInteger("version"));
        assertEquals("error", frame.getString("type"));
        assertEquals("MALFORMED_FRAME", frame.getString("errorCode"));
        verify(limiter, times(2)).requireAllowed("danmu:rate:frame:malformed", 20, 10);
        server.onClose(session, "10");
    }

    @Test
    void v1RejectsInvalidVideoIdBeforeRegisteringTheSession() throws Exception {
        DanmuWebSocketServer server = new DanmuWebSocketServer();
        Session session = session(true, "invalid-video");
        RemoteEndpoint.Basic remote = session.getBasicRemote();

        server.onOpen(session, "0");

        org.mockito.ArgumentCaptor<String> message = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(remote).sendText(message.capture());
        JSONObject frame = JSON.parseObject(message.getValue());
        assertEquals("INVALID_VIDEO_ID", frame.getString("errorCode"));
        verify(session).close();
    }

    @Test
    void v1RejectsBodyVideoThatDoesNotMatchTheCanonicalRoom() throws Exception {
        DanmuWebSocketServer server = new DanmuWebSocketServer();
        configure(server);
        Session session = session(true, "body-vid");
        RemoteEndpoint.Basic remote = session.getBasicRemote();
        server.onOpen(session, "10");

        server.onMessage(session, "{\"version\":1,\"type\":\"danmu\",\"requestId\":\"x\",\"vid\":11}", "999");

        org.mockito.ArgumentCaptor<String> message = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(remote, timeout(1000).atLeast(2)).sendText(message.capture());
        JSONObject frame = JSON.parseObject(message.getAllValues().get(message.getAllValues().size() - 1));
        assertEquals("INVALID_VIDEO_ID", frame.getString("errorCode"));
        server.onClose(session, "10");
    }

    @SuppressWarnings("unchecked")
    private Session session(boolean v1, String id) throws Exception {
        Session session = mock(Session.class);
        RemoteEndpoint.Basic remote = mock(RemoteEndpoint.Basic.class);
        when(session.getId()).thenReturn(id);
        when(session.getBasicRemote()).thenReturn(remote);
        when(session.getRequestParameterMap()).thenReturn(v1
                ? Collections.singletonMap("v", Collections.singletonList("1"))
                : Collections.emptyMap());
        return session;
    }

    private DanmuRateLimiter configure(DanmuWebSocketServer server) {
        DanmuSubmissionService submissions = mock(DanmuSubmissionService.class);
        Video video = new Video();
        video.setStatus(1);
        video.setDuration(60D);
        when(submissions.requirePublicVideo(10)).thenReturn(video);
        DanmuRateLimiter limiter = mock(DanmuRateLimiter.class);
        server.setDependencies(mock(RedisUtil.class), submissions, mock(DanmuAuthorizationService.class), limiter);
        return limiter;
    }
}
