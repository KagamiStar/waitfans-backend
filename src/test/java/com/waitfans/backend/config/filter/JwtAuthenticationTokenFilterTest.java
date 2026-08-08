package com.waitfans.backend.config.filter;

import com.waitfans.backend.utils.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import javax.servlet.FilterChain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtAuthenticationTokenFilterTest {
    @Test
    void invalidBearerTokenIsRejectedEvenForPublicPlayRoute() throws Exception {
        JwtAuthenticationTokenFilter filter = new JwtAuthenticationTokenFilter();
        JwtUtil jwtUtil = mock(JwtUtil.class);
        ReflectionTestUtils.setField(filter, "jwtUtil", jwtUtil);
        when(jwtUtil.verifyToken("invalid")).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/video/play");
        request.addHeader("Authorization", "Bearer invalid");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        verify(chain, never()).doFilter(request, response);
    }
}
