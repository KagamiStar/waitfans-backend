package com.waitfans.backend.component.danmu.protocol;

public final class DanmuProtocol {
    public static final int VERSION = 1;

    private DanmuProtocol() {
    }

    public static DanmuProtocolFrame frame(String type, String requestId, String errorCode, Object data) {
        return new DanmuProtocolFrame(VERSION, type, requestId, errorCode, System.currentTimeMillis(), data);
    }

    public static DanmuProtocolFrame error(String requestId, DanmuProtocolErrorCode errorCode) {
        return frame("error", requestId, errorCode.name(), null);
    }
}
