package com.waitfans.backend.service.danmu;

import com.waitfans.backend.component.danmu.protocol.DanmuProtocolErrorCode;
import com.waitfans.backend.mapper.UserMapper;
import com.waitfans.backend.pojo.User;
import com.waitfans.backend.utils.JwtUtil;
import com.waitfans.backend.utils.RedisUtil;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class DanmuAuthorizationService {
    private final RedisUtil redisUtil;
    private final UserMapper userMapper;

    @Autowired
    public DanmuAuthorizationService(RedisUtil redisUtil, UserMapper userMapper) {
        this.redisUtil = redisUtil;
        this.userMapper = userMapper;
    }

    public User requireNormalUser(String authorization) {
        if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
            throw new DanmuSubmitException(DanmuProtocolErrorCode.AUTH_REQUIRED);
        }
        String token = authorization.substring(7);
        Claims claims = JwtUtil.getAllClaimsFromToken(token);
        if (claims == null) throw new DanmuSubmitException(DanmuProtocolErrorCode.AUTH_EXPIRED);
        String role = String.valueOf(claims.get("role"));
        String subject = claims.getSubject();
        if (!"user".equals(role)) throw new DanmuSubmitException(DanmuProtocolErrorCode.AUTH_FORBIDDEN);
        Integer uid;
        try {
            uid = Integer.valueOf(subject);
        } catch (RuntimeException exception) {
            throw new DanmuSubmitException(DanmuProtocolErrorCode.AUTH_EXPIRED);
        }
        try {
            Object cachedToken = redisUtil.getValue("token:user:" + uid);
            if (!token.equals(cachedToken)) throw new DanmuSubmitException(DanmuProtocolErrorCode.AUTH_EXPIRED);
        } catch (DanmuSubmitException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new DanmuSubmitException(DanmuProtocolErrorCode.SERVICE_UNAVAILABLE);
        }
        try {
            User user = userMapper.selectById(uid);
            if (user == null || !Integer.valueOf(0).equals(user.getState()) || !Integer.valueOf(0).equals(user.getRole())) {
                throw new DanmuSubmitException(DanmuProtocolErrorCode.AUTH_FORBIDDEN);
            }
            return user;
        } catch (DanmuSubmitException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new DanmuSubmitException(DanmuProtocolErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}
