package com.waitfans.backend.component.danmu.protocol;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DanmuProtocolFrame {
    private Integer version;
    private String type;
    private String requestId;
    private String errorCode;
    private Long serverTime;
    private Object data;
}
