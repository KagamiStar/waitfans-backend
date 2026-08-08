package com.waitfans.backend.service.danmu;

import com.waitfans.backend.component.danmu.protocol.DanmuProtocolErrorCode;
import com.waitfans.backend.mapper.UserMapper;
import com.waitfans.backend.pojo.User;
import com.waitfans.backend.utils.JwtUtil;
import com.waitfans.backend.utils.RedisUtil;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DanmuAuthorizationServiceTest {
    @Test
    void distinguishesNormalAdminAndRedisFailure() {
        RedisUtil redis = mock(RedisUtil.class);
        UserMapper users = mock(UserMapper.class);
        DanmuAuthorizationService service = new DanmuAuthorizationService(redis, users);
        JwtUtil tokens = new JwtUtil();
        ReflectionTestUtils.setField(tokens, "redisUtil", redis);
        String userToken = tokens.createToken("7", "user");
        User user = new User();
        user.setUid(7);
        user.setState(0);
        user.setRole(0);
        when(redis.getValue("token:user:7")).thenReturn(userToken);
        when(users.selectById(7)).thenReturn(user);

        assertEquals(7, service.requireNormalUser("Bearer " + userToken).getUid().intValue());

        String adminToken = tokens.createToken("7", "admin");
        DanmuSubmitException forbidden = assertThrows(DanmuSubmitException.class,
                () -> service.requireNormalUser("Bearer " + adminToken));
        assertEquals(DanmuProtocolErrorCode.AUTH_FORBIDDEN, forbidden.getErrorCode());

        when(redis.getValue("token:user:7")).thenThrow(new IllegalStateException("redis down"));
        DanmuSubmitException unavailable = assertThrows(DanmuSubmitException.class,
                () -> service.requireNormalUser("Bearer " + userToken));
        assertEquals(DanmuProtocolErrorCode.SERVICE_UNAVAILABLE, unavailable.getErrorCode());
    }
}
