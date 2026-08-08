package com.waitfans.backend.controller;

import com.waitfans.backend.pojo.CustomResponse;
import com.waitfans.backend.pojo.dto.VideoPlayOutcome;
import com.waitfans.backend.pojo.dto.VideoPlayRequest;
import com.waitfans.backend.service.utils.CurrentUser;
import com.waitfans.backend.service.video.VideoPlayTrackingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.time.Duration;

@RestController
public class VideoPlayController {
    private static final Duration VISITOR_COOKIE_MAX_AGE = Duration.ofDays(180);

    private final CurrentUser currentUser;
    private final VideoPlayTrackingService playTrackingService;

    @Autowired
    public VideoPlayController(CurrentUser currentUser, VideoPlayTrackingService playTrackingService) {
        this.currentUser = currentUser;
        this.playTrackingService = playTrackingService;
    }

    @PostMapping("/video/play")
    public CustomResponse record(
            @RequestBody VideoPlayRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        if (request == null || request.getVid() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid video id");
        }
        VideoPlayOutcome outcome = playTrackingService.record(
                request.getVid(), currentUser.getUserIdOrNull(), visitorCookie(servletRequest)
        );
        if (outcome.getVisitorCookieValue() != null) {
            ResponseCookie cookie = ResponseCookie.from(VideoPlayTrackingService.VISITOR_COOKIE_NAME,
                            outcome.getVisitorCookieValue())
                    .httpOnly(true)
                    .sameSite("Lax")
                    .path("/")
                    .maxAge(VISITOR_COOKIE_MAX_AGE)
                    .secure(servletRequest.isSecure())
                    .build();
            servletResponse.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        }
        CustomResponse response = new CustomResponse();
        response.setData(outcome.getResult());
        return response;
    }

    private String visitorCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (VideoPlayTrackingService.VISITOR_COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
