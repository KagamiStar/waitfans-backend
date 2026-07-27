package com.waitfans.backend.service.impl.user;

import com.waitfans.backend.pojo.CustomResponse;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserAccountServiceImplTest {

    @Test
    void adminLoginReturnsBusinessErrorWhenAuthenticationFails() {
        AuthenticationProvider authenticationProvider = mock(AuthenticationProvider.class);
        when(authenticationProvider.authenticate(any()))
                .thenThrow(new BadCredentialsException("invalid credentials"));

        UserAccountServiceImpl service = new UserAccountServiceImpl();
        ReflectionTestUtils.setField(service, "authenticationProvider", authenticationProvider);

        CustomResponse response = service.adminLogin("missing-admin", "wrong-password");

        assertEquals(403, response.getCode());
        assertEquals("账号或密码不正确", response.getMessage());
    }
}
