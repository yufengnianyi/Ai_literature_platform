package com.example.demo_01.aop;

import com.example.demo_01.annotation.AuthCheck;
import com.example.demo_01.exception.BusinessException;
import com.example.demo_01.user.UserService;
import com.example.demo_01.user.model.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthInterceptorTest {

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void shouldRejectWhenUserIsNotAdmin() throws Throwable {
        AuthInterceptor interceptor = new AuthInterceptor();
        UserService userService = mock(UserService.class);
        injectUserService(interceptor, userService);

        User loginUser = new User();
        loginUser.setUserId("u-1");
        loginUser.setUserRole("user");
        when(userService.getLoginUser(Mockito.any(HttpServletRequest.class))).thenReturn(loginUser);

        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        AuthCheck authCheck = getAuthCheck();

        assertThrows(BusinessException.class, () -> interceptor.doInterceptor(joinPoint, authCheck));
    }

    private void injectUserService(AuthInterceptor interceptor, UserService userService) {
        try {
            var field = AuthInterceptor.class.getDeclaredField("userService");
            field.setAccessible(true);
            field.set(interceptor, userService);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private AuthCheck getAuthCheck() {
        try {
            Method method = SecuredMethods.class.getDeclaredMethod("adminOnly");
            return method.getAnnotation(AuthCheck.class);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    private static final class SecuredMethods {
        @AuthCheck(mustRole = "admin")
        private void adminOnly() {
        }
    }
}
