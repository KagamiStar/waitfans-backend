package com.waitfans.backend.pojo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class MediaPreviewTicket {
    private String url;
    private long expiresInSeconds;
}
