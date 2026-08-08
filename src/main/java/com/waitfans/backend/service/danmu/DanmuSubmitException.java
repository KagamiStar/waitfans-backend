package com.waitfans.backend.service.danmu;

import com.waitfans.backend.component.danmu.protocol.DanmuProtocolErrorCode;

public class DanmuSubmitException extends RuntimeException {
    private final DanmuProtocolErrorCode errorCode;

    public DanmuSubmitException(DanmuProtocolErrorCode errorCode) {
        this.errorCode = errorCode;
    }

    public DanmuProtocolErrorCode getErrorCode() {
        return errorCode;
    }
}
