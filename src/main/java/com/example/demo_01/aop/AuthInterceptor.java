package com.example.demo_01.aop;

import com.example.demo_01.annotation.AuthCheck;
import com.example.demo_01.user.UserService;
import com.example.demo_01.user.constant.UserConstant;
import com.example.demo_01.user.model.entity.User;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

@Aspect
@Component
public class AuthInterceptor {

    @Resource
    private UserService userService;

    @Around("@annotation(authCheck)")
    public Object doInterceptor(ProceedingJoinPoint joinPoint, AuthCheck authCheck) throws Throwable {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "login required");
        }

        HttpServletRequest request = attributes.getRequest();
        User loginUser = userService.getLoginUser(request);
        String mustRole = authCheck.mustRole();
        if (!mustRole.isBlank()
                && UserConstant.ADMIN_ROLE.equals(mustRole)
                && !userService.isAdmin(loginUser)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "no auth");
        }
        return joinPoint.proceed();
    }
}
