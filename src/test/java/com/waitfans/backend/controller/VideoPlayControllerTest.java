package com.waitfans.backend.controller;

import com.waitfans.backend.pojo.CustomResponse;
import com.waitfans.backend.pojo.dto.VideoPlayOutcome;
import com.waitfans.backend.pojo.dto.VideoPlayRequest;
import com.waitfans.backend.pojo.dto.VideoPlayResult;
import com.waitfans.backend.service.utils.CurrentUser;
import com.waitfans.backend.service.video.VideoPlayTrackingService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.servlet.http.Cookie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoPlayControllerTest {
    @Test
    void usesAuthenticatedUidAndIssuesHttpOnlyVisitorCookieOnlyWhenNeeded() {
        CurrentUser currentUser = mock(CurrentUser.class);
        VideoPlayTrackingService tracking = mock(VideoPlayTrackingService.class);
        when(currentUser.getUserIdOrNull()).thenReturn(null);
        when(tracking.record(eq(10), eq(null), eq(null))).thenReturn(new VideoPlayOutcome(
                new VideoPlayResult(true, VideoPlayResult.Reason.COUNTED, 0, null), "new-token"
        ));
        VideoPlayController controller = new VideoPlayController(currentUser, tracking);
        VideoPlayRequest request = new VideoPlayRequest();
        request.setVid(10);
        MockHttpServletResponse response = new MockHttpServletResponse();

        CustomResponse result = controller.record(request, new MockHttpServletRequest(), response);

        assertEquals(200, result.getCode());
        String cookie = response.getHeader("Set-Cookie");
        assertTrue(cookie.contains("wf_visitor=new-token"));
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("SameSite=Lax"));
        assertTrue(cookie.contains("Path=/"));
        assertTrue(cookie.contains("Max-Age=15552000"));
    }

    @Test
    void forwardsExistingCookieAndSecurityContextUserInsteadOfRequestIdentity() {
        CurrentUser currentUser = mock(CurrentUser.class);
        VideoPlayTrackingService tracking = mock(VideoPlayTrackingService.class);
        when(currentUser.getUserIdOrNull()).thenReturn(7);
        when(tracking.record(10, 7, "existing-token")).thenReturn(new VideoPlayOutcome(
                new VideoPlayResult(false, VideoPlayResult.Reason.DUPLICATE, 1, null), null
        ));
        VideoPlayController controller = new VideoPlayController(currentUser, tracking);
        VideoPlayRequest body = new VideoPlayRequest();
        body.setVid(10);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("wf_visitor", "existing-token"));

        controller.record(body, request, new MockHttpServletResponse());

        verify(tracking).record(10, 7, "existing-token");
    }
}
