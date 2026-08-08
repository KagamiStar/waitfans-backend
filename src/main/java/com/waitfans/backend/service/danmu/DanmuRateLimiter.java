package com.waitfans.backend.service.danmu;

import com.waitfans.backend.component.danmu.protocol.DanmuProtocolErrorCode;
import com.waitfans.backend.utils.RedisUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DanmuRateLimiter {
    private final RedisUtil redisUtil;

    @Autowired
    public DanmuRateLimiter(RedisUtil redisUtil) {
        this.redisUtil = redisUtil;
    }

    public void requireAllowed(String key, long limit, long seconds) {
        try {
            if (redisUtil.incrementAndExpire(key, seconds) > limit) {
                throw new DanmuSubmitException(DanmuProtocolErrorCode.RATE_LIMITED);
            }
        } catch (DanmuSubmitException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new DanmuSubmitException(DanmuProtocolErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}
