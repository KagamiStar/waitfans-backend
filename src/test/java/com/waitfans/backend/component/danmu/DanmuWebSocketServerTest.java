package com.waitfans.backend.component.danmu;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import javax.websocket.RemoteEndpoint;
import javax.websocket.Session;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DanmuWebSocketServerTest {
    @Test
    void mixedRoomUsesExactlyOneWireEncodingPerSession() throws Exception {
        DanmuWebSocketServer server = new DanmuWebSocketServer();
        Session v1 = session(true, "v1");
        Session legacy = session(false, "legacy");
        RemoteEndpoint.Basic v1Remote = v1.getBasicRemote();
        RemoteEndpoint.Basic legacyRemote = legacy.getBasicRemote();

        server.onOpen(v1, "10");
        server.onOpen(legacy, "10");

        org.mockito.ArgumentCaptor<String> v1Messages = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> legacyMessages = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(v1Remote, atLeast(1)).sendText(v1Messages.capture());
        verify(legacyRemote, atLeast(1)).sendText(legacyMessages.capture());
        assertTrue(v1Messages.getAllValues().stream().allMatch(value -> JSON.parseObject(value).getInteger("version") == 1));
        assertTrue(legacyMessages.getAllValues().stream().allMatch(value -> value.startsWith("当前观看人数")));

        server.onClose(v1, "10");
        server.onClose(legacy, "10");
    }

    @Test
    void v1MalformedFrameGetsAProtocolErrorEnvelope() throws Exception {
        DanmuWebSocketServer server = new DanmuWebSocketServer();
        Session session = session(true, "malformed");
        RemoteEndpoint.Basic remote = session.getBasicRemote();
        server.onOpen(session, "10");
        clearInvocations(remote);

        server.onMessage(session, "{", "10");

        org.mockito.ArgumentCaptor<String> message = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(remote).sendText(message.capture());
        JSONObject frame = JSON.parseObject(message.getValue());
        assertEquals(1, frame.getInteger("version"));
        assertEquals("error", frame.getString("type"));
        assertEquals("MALFORMED_FRAME", frame.getString("errorCode"));
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
}
