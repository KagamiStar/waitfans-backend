package com.waitfans.backend.controller;

import com.waitfans.backend.mapper.VideoMapper;
import com.waitfans.backend.pojo.Video;
import com.waitfans.backend.utils.HttpByteRange;
import com.waitfans.backend.utils.OssUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

@RestController
public class VideoMediaController {
    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    @Autowired
    private VideoMapper videoMapper;

    @Autowired
    private OssUtil ossUtil;

    @GetMapping("/media/video/{vid}")
    public ResponseEntity<StreamingResponseBody> stream(
            @PathVariable("vid") Integer vid,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader
    ) {
        Video video = videoMapper.selectById(vid);
        if (video == null || video.getStatus() == 3) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found");
        }

        OssUtil.StoredObjectMetadata metadata;
        HttpByteRange range;
        try {
            metadata = ossUtil.getObjectMetadata(video.getVideoUrl());
            range = HttpByteRange.parse(rangeHeader, metadata.getSize());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, e.getMessage(), e);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Video storage is unavailable", e);
        }

        StreamingResponseBody body = outputStream -> {
            try (InputStream inputStream = ossUtil.openObjectRange(
                    video.getVideoUrl(),
                    range.getStart(),
                    range.getLength()
            )) {
                byte[] buffer = new byte[COPY_BUFFER_SIZE];
                long remaining = range.getLength();
                while (remaining > 0) {
                    int read = inputStream.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read < 0) {
                        break;
                    }
                    outputStream.write(buffer, 0, read);
                    remaining -= read;
                }
            }
        };

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.setContentLength(range.getLength());
        headers.setContentType(mediaType(metadata.getContentType()));
        headers.setCacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic());
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "inline");
        if (range.isPartial()) {
            headers.set(
                    HttpHeaders.CONTENT_RANGE,
                    "bytes " + range.getStart() + "-" + range.getEnd() + "/" + range.getTotal()
            );
        }
        return new ResponseEntity<>(
                body,
                headers,
                range.isPartial() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK
        );
    }

    private static MediaType mediaType(String contentType) {
        try {
            return MediaType.parseMediaType(contentType);
        } catch (IllegalArgumentException ignored) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
