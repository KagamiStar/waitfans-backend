package com.waitfans.backend.pojo.dto;

import com.waitfans.backend.pojo.UserVideo;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class VideoPlayResult {
    private boolean counted;
    private Reason reason;
    private long nextEligibleInSeconds;
    private UserVideo interaction;

    public enum Reason {
        COUNTED,
        DUPLICATE,
        TRACKING_UNAVAILABLE
    }
}
