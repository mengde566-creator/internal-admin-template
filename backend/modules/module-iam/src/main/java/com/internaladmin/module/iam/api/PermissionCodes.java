package com.internaladmin.module.iam.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 0.1 权限编码注册表。
 *
 * <p>权限点由代码注册（不建权限表），数据库只保存角色与权限编码的关联。
 * 本类同时作为跨模块权限契约：业务模块的接口按这些编码做方法级权限校验。</p>
 */
public final class PermissionCodes {

    private PermissionCodes() {
    }

    /** 用户管理：创建和管理用户。 */
    public static final String USER_MANAGE = "iam:user:manage";

    /** 角色管理：创建和管理角色。 */
    public static final String ROLE_MANAGE = "iam:role:manage";

    /** 主页内容维护：编辑、保存草稿、预览。 */
    public static final String SITE_HOMEPAGE_EDIT = "site:homepage:edit";

    /** 主页发布：发布与撤回。 */
    public static final String SITE_HOMEPAGE_PUBLISH = "site:homepage:publish";

    /** 系统设置：查看与修改系统参数。 */
    public static final String SYSTEM_CONFIG_MANAGE = "system:config:manage";

    /** 系统管理员角色初始拥有的全部权限编码。 */
    public static final String[] SYSTEM_ADMIN_PERMISSIONS = {
            USER_MANAGE,
            ROLE_MANAGE,
            SITE_HOMEPAGE_EDIT,
            SITE_HOMEPAGE_PUBLISH,
            SYSTEM_CONFIG_MANAGE
    };

    /** 全部已注册权限项（编码 → 说明），供前端权限选择器使用；顺序即展示顺序。 */
    public static final Map<String, String> REGISTERED_PERMISSIONS = new LinkedHashMap<>();

    static {
        REGISTERED_PERMISSIONS.put(USER_MANAGE, "用户管理");
        REGISTERED_PERMISSIONS.put(ROLE_MANAGE, "角色管理");
        REGISTERED_PERMISSIONS.put(SITE_HOMEPAGE_EDIT, "主页内容编辑");
        REGISTERED_PERMISSIONS.put(SITE_HOMEPAGE_PUBLISH, "主页发布");
        REGISTERED_PERMISSIONS.put(SYSTEM_CONFIG_MANAGE, "系统设置");
    }
}
