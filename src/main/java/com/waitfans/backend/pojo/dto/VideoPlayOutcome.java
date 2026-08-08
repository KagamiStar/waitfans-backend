package com.waitfans.backend.pojo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class VideoPlayOutcome {
    private VideoPlayResult result;
    private String visitorCookieValue;
}
