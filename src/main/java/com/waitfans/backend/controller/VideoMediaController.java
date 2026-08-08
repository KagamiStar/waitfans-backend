package com.waitfans.backend.controller;

import com.waitfans.backend.mapper.VideoMapper;
import com.waitfans.backend.pojo.Video;
import com.waitfans.backend.pojo.CustomResponse;
import com.waitfans.backend.pojo.dto.MediaPreviewTicket;
import com.waitfans.backend.service.media.MediaPreviewTokenService;
import com.waitfans.backend.service.utils.CurrentUser;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

@RestController
public class VideoMediaController {
    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    private VideoMapper videoMapper;

    private OssUtil ossUtil;

    private CurrentUser currentUser;

    private MediaPreviewTokenService previewTokenService;

    @Autowired
    public VideoMediaController(
            VideoMapper videoMapper,
            OssUtil ossUtil,
            CurrentUser currentUser,
            MediaPreviewTokenService previewTokenService
    ) {
        this.videoMapper = videoMapper;
        this.ossUtil = ossUtil;
        this.currentUser = currentUser;
        this.previewTokenService = previewTokenService;
    }

    @GetMapping("/media/video/{vid}")
    public ResponseEntity<StreamingResponseBody> stream(
            @PathVariable("vid") Integer vid,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader
    ) {
        Video video = videoMapper.selectById(vid);
        if (!isPublic(video)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found");
        }
        return stream(video, rangeHeader, CacheControl.noStore());
    }

    @PostMapping("/media/video/{vid}/preview-token")
    public CustomResponse createPreviewTicket(@PathVariable("vid") Integer vid) {
        Video video = videoMapper.selectById(vid);
        if (!isPreviewTicketAvailable(video)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found");
        }
        CustomResponse response = new CustomResponse();
        if (isPublic(video)) {
            response.setData(new MediaPreviewTicket("/media/video/" + vid, 0));
            return response;
        }
        Integer userId = currentUser.getUserId();
        if (!Objects.equals(userId, video.getUid()) && !Boolean.TRUE.equals(currentUser.isAdmin())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found");
        }
        String token = previewTokenService.issue(vid);
        response.setData(new MediaPreviewTicket(
                "/media/preview/" + vid + "?token=" + token,
                MediaPreviewTokenService.TTL_SECONDS
        ));
        return response;
    }

    @GetMapping("/media/preview/{vid}")
    public ResponseEntity<StreamingResponseBody> preview(
            @PathVariable("vid") Integer vid,
            @RequestParam("token") String token,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader
    ) {
        if (!previewTokenService.matches(vid, token)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found");
        }
        Video video = videoMapper.selectById(vid);
        if (!isPreviewable(video)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found");
        }
        return stream(video, rangeHeader, CacheControl.noStore());
    }

    private ResponseEntity<StreamingResponseBody> stream(
            Video video,
            String rangeHeader,
            CacheControl cacheControl
    ) {
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
        headers.setCacheControl(cacheControl);
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

    private static boolean isPublic(Video video) {
        return video != null && Integer.valueOf(1).equals(video.getStatus());
    }

    private static boolean isPreviewable(Video video) {
        return video != null && (Integer.valueOf(0).equals(video.getStatus()) || Integer.valueOf(2).equals(video.getStatus()));
    }

    private static boolean isPreviewTicketAvailable(Video video) {
        return isPublic(video) || isPreviewable(video);
    }

    private static MediaType mediaType(String contentType) {
        try {
            return MediaType.parseMediaType(contentType);
        } catch (IllegalArgumentException ignored) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
