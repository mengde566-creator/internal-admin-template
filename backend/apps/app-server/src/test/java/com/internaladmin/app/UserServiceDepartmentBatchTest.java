package com.internaladmin.app;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import com.internaladmin.module.audit.api.AuditRecordApi;
import com.internaladmin.module.iam.api.DepartmentQueryApi;
import com.internaladmin.module.iam.mapper.DepartmentMapper;
import com.internaladmin.module.iam.mapper.RoleMapper;
import com.internaladmin.module.iam.mapper.UserMapper;
import com.internaladmin.module.iam.mapper.UserRoleMapper;
import com.internaladmin.module.iam.model.entity.DepartmentDO;
import com.internaladmin.module.iam.model.entity.UserDO;
import com.internaladmin.module.iam.model.entity.UserRoleDO;
import com.internaladmin.module.iam.model.dto.UserQueryDTO;
import com.internaladmin.module.iam.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserServiceDepartmentBatchTest {

    @Test
    void pageLoadsDepartmentsOnceForManyUsers() {
        initLambdaMetadata(UserDO.class);
        initLambdaMetadata(UserRoleDO.class);
        UserDO first = user(1L, 10L);
        UserDO second = user(2L, 10L);
        UserDO third = user(3L, 11L);
        Page<UserDO> page = new Page<>(1, 10, 3);
        page.setRecords(List.of(first, second, third));
        AtomicInteger departmentQueries = new AtomicInteger();

        DepartmentDO sales = department(10L, "销售");
        DepartmentDO support = department(11L, "支持");
        UserService service = new UserService(
                proxy(UserMapper.class, (method, args) -> method.getName().equals("selectPage") ? page : null),
                proxy(UserRoleMapper.class, (method, args) -> method.getName().equals("selectList") ? List.of() : null),
                proxy(RoleMapper.class, (method, args) -> method.getName().equals("selectBatchIds") ? List.of() : null),
                proxy(DepartmentMapper.class, (method, args) -> {
                    if (method.getName().equals("selectBatchIds")) {
                        departmentQueries.incrementAndGet();
                        return List.of(sales, support);
                    }
                    return null;
                }),
                (DepartmentQueryApi) id -> null,
                (PasswordEncoder) new PasswordEncoder() {
                    @Override public String encode(CharSequence rawPassword) { return rawPassword.toString(); }
                    @Override public boolean matches(CharSequence rawPassword, String encodedPassword) { return true; }
                },
                (AuditRecordApi) (operatorId, action, targetId, result) -> { });

        UserQueryDTO query = new UserQueryDTO();
        query.setPage(1);
        query.setSize(10);
        var result = service.page(query);

        assertEquals(1, departmentQueries.get());
        assertEquals(List.of("销售", "销售", "支持"), result.getRecords().stream()
                .map(item -> item.getDepartmentName()).toList());
    }

    private void initLambdaMetadata(Class<?> entityType) {
        Configuration configuration = new Configuration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "test");
        assistant.setCurrentNamespace(entityType.getName());
        TableInfoHelper.initTableInfo(assistant, entityType);
    }

    private UserDO user(Long id, Long departmentId) {
        UserDO user = new UserDO();
        user.setId(id);
        user.setDepartmentId(departmentId);
        user.setUsername("u" + id);
        user.setDisplayName("用户" + id);
        return user;
    }

    private DepartmentDO department(Long id, String name) {
        DepartmentDO department = new DepartmentDO();
        department.setId(id);
        department.setCode("D" + id);
        department.setName(name);
        department.setEnabled(1);
        return department;
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> invocation.call(method, args));
    }

    @FunctionalInterface
    private interface Invocation {
        Object call(java.lang.reflect.Method method, Object[] args);
    }
}
