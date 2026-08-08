package com.waitfans.backend.component.danmu.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class DanmuProtocolTest {
    @Test
    void v1FramesAlwaysContainFixedFields() {
        DanmuProtocolFrame frame = DanmuProtocol.frame("heartbeat", "request_1", null, null);

        assertEquals(1, frame.getVersion().intValue());
        assertEquals("heartbeat", frame.getType());
        assertEquals("request_1", frame.getRequestId());
        assertNull(frame.getErrorCode());
        assertNotNull(frame.getServerTime());
        assertNull(frame.getData());
    }

    @Test
    void errorsUseStableWireCodes() {
        DanmuProtocolFrame frame = DanmuProtocol.error("request_1", DanmuProtocolErrorCode.AUTH_EXPIRED);

        assertEquals("error", frame.getType());
        assertEquals("AUTH_EXPIRED", frame.getErrorCode());
        assertEquals("request_1", frame.getRequestId());
    }
}
