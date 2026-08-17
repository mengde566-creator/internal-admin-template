package com.internaladmin.app;

import com.internaladmin.module.iam.mapper.DepartmentMapper;
import com.internaladmin.module.iam.mapper.RoleMapper;
import com.internaladmin.module.iam.mapper.RolePermissionMapper;
import com.internaladmin.module.iam.mapper.SystemConfigMapper;
import com.internaladmin.module.iam.mapper.UserMapper;
import com.internaladmin.module.iam.mapper.UserRoleMapper;
import com.internaladmin.module.iam.model.dto.LoginDTO;
import com.internaladmin.module.iam.model.entity.DepartmentDO;
import com.internaladmin.module.iam.model.entity.UserDO;
import com.internaladmin.module.iam.service.AuthService;
import com.internaladmin.module.iam.service.IamActorService;
import com.internaladmin.module.iam.service.SystemConfigService;
import com.internaladmin.platform.kernel.error.BusinessException;
import com.internaladmin.platform.kernel.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.context.HttpRequestResponseHolder;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IamDepartmentGuardTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void loginRejectsMissingDepartmentBeforeCreatingSession() {
        RecordingSecurityRepository repository = new RecordingSecurityRepository();
        RecordingSessionStrategy sessionStrategy = new RecordingSessionStrategy();
        AuthService service = authService(null, repository, sessionStrategy);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.login(loginRequest(), new MockHttpServletRequest(), new MockHttpServletResponse()));

        assertEquals(ErrorCode.UNAUTHORIZED, error.getErrorCode());
        assertEquals("当前用户所属部门不存在", error.getMessage());
        assertEquals(0, repository.saved.get());
        assertEquals(0, sessionStrategy.calls.get());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void loginRejectsDisabledDepartmentBeforeCreatingSession() {
        DepartmentDO department = department(1L, 0);
        RecordingSecurityRepository repository = new RecordingSecurityRepository();
        RecordingSessionStrategy sessionStrategy = new RecordingSessionStrategy();
        AuthService service = authService(department, repository, sessionStrategy);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.login(loginRequest(), new MockHttpServletRequest(), new MockHttpServletResponse()));

        assertEquals(ErrorCode.BUSINESS_REJECTED, error.getErrorCode());
        assertEquals("当前用户所属部门已停用", error.getMessage());
        assertEquals(0, repository.saved.get());
        assertEquals(0, sessionStrategy.calls.get());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void actorRejectsDisabledDepartment() {
        UserDO user = user();
        IamActorService service = new IamActorService(
                mapper(UserMapper.class, user, null),
                mapper(DepartmentMapper.class, null, department(1L, 0)),
                mapper(UserRoleMapper.class, null, null),
                mapper(RoleMapper.class, null, null),
                mapper(RolePermissionMapper.class, null, null));

        BusinessException error = assertThrows(BusinessException.class, () -> service.resolve(user.getId()));

        assertEquals(ErrorCode.BUSINESS_REJECTED, error.getErrorCode());
        assertEquals("当前用户所属部门已停用", error.getMessage());
    }

    @Test
    void currentUserRejectsDisabledDepartment() {
        AuthService service = authService(department(1L, 0),
                new RecordingSecurityRepository(), new RecordingSessionStrategy());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(9L, null, List.of()));
        SecurityContextHolder.setContext(context);

        BusinessException error = assertThrows(BusinessException.class, service::currentUser);

        assertEquals(ErrorCode.BUSINESS_REJECTED, error.getErrorCode());
        assertEquals("当前用户所属部门已停用", error.getMessage());
    }

    private AuthService authService(DepartmentDO department,
                                    SecurityContextRepository repository,
                                    SessionAuthenticationStrategy sessionStrategy) {
        UserDO user = user();
        return new AuthService(
                mapper(UserMapper.class, user, null),
                mapper(DepartmentMapper.class, null, department),
                mapper(UserRoleMapper.class, null, null),
                mapper(RolePermissionMapper.class, null, null),
                new BCryptPasswordEncoder(),
                new SystemConfigService(mapper(SystemConfigMapper.class, null, null)),
                sessionStrategy,
                repository,
                (LogoutHandler) (request, response, authentication) -> { });
    }

    private LoginDTO loginRequest() {
        LoginDTO dto = new LoginDTO();
        dto.setUsername("guard-user");
        dto.setPassword("CorrectPass123");
        return dto;
    }

    private UserDO user() {
        UserDO user = new UserDO();
        user.setId(9L);
        user.setUsername("guard-user");
        user.setPasswordHash(new BCryptPasswordEncoder().encode("CorrectPass123"));
        user.setDepartmentId(1L);
        return user;
    }

    private DepartmentDO department(Long id, int enabled) {
        DepartmentDO department = new DepartmentDO();
        department.setId(id);
        department.setCode("DPT");
        department.setName("部门");
        department.setEnabled(enabled);
        return department;
    }

    @SuppressWarnings("unchecked")
    private <T> T mapper(Class<T> type, UserDO user, DepartmentDO department) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getName().equals("selectOne") && type == UserMapper.class) {
                return user;
            }
            if (method.getName().equals("selectById") && type == UserMapper.class) {
                return user;
            }
            if (method.getName().equals("selectById") && type == DepartmentMapper.class) {
                return department;
            }
            if (method.getName().equals("selectList") || method.getName().equals("selectBatchIds")) {
                return List.of();
            }
            if (method.getReturnType() == int.class || method.getReturnType() == long.class) {
                return 0;
            }
            if (method.getReturnType() == boolean.class) {
                return false;
            }
            if (method.getName().equals("toString")) {
                return type.getSimpleName() + "Stub";
            }
            return null;
        });
    }

    private static final class RecordingSecurityRepository implements SecurityContextRepository {
        private final AtomicInteger saved = new AtomicInteger();

        @Override
        public SecurityContext loadContext(HttpRequestResponseHolder requestResponseHolder) {
            return SecurityContextHolder.createEmptyContext();
        }

        @Override
        public void saveContext(SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
            saved.incrementAndGet();
        }

        @Override
        public boolean containsContext(HttpServletRequest request) {
            return false;
        }
    }

    private static final class RecordingSessionStrategy implements SessionAuthenticationStrategy {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public void onAuthentication(Authentication authentication,
                                      HttpServletRequest request,
                                      HttpServletResponse response) {
            calls.incrementAndGet();
        }
    }
}
