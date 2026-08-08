package com.waitfans.backend.service.danmu;

import com.waitfans.backend.component.danmu.protocol.DanmuProtocolErrorCode;
import com.waitfans.backend.utils.RedisUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DanmuRateLimiterTest {
    @Test
    void mapsAtomicCounterLimitsAndRedisFailures() {
        RedisUtil redis = mock(RedisUtil.class);
        DanmuRateLimiter limiter = new DanmuRateLimiter(redis);
        when(redis.incrementAndExpire("key", 10)).thenReturn(6L);

        DanmuSubmitException limited = assertThrows(DanmuSubmitException.class,
                () -> limiter.requireAllowed("key", 5, 10));
        assertEquals(DanmuProtocolErrorCode.RATE_LIMITED, limited.getErrorCode());

        when(redis.incrementAndExpire("down", 10)).thenThrow(new IllegalStateException("redis down"));
        DanmuSubmitException unavailable = assertThrows(DanmuSubmitException.class,
                () -> limiter.requireAllowed("down", 5, 10));
        assertEquals(DanmuProtocolErrorCode.SERVICE_UNAVAILABLE, unavailable.getErrorCode());
    }
}
