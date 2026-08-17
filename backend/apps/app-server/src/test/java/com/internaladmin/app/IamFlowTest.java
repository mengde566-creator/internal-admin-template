package com.internaladmin.app;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.internaladmin.module.audit.mapper.AuditOperationMapper;
import com.internaladmin.module.audit.model.entity.AuditOperationDO;
import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.iam.api.IamActorApi;
import com.internaladmin.module.iam.api.ScopeMode;
import com.internaladmin.module.iam.mapper.DepartmentMapper;
import com.internaladmin.module.iam.mapper.RoleMapper;
import com.internaladmin.module.iam.mapper.RolePermissionMapper;
import com.internaladmin.module.iam.model.entity.RolePermissionDO;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * IAM 集成测试：登录/强制改密/权限/软删除/角色引用/系统参数。
 *
 * <p>测试库 data/test-iam.db 独立于开发库；测试数据使用唯一用户名避免互相干扰；
 * 修改全局状态（admin 密码、系统参数）的测试执行后自行恢复。</p>
 */
@SpringBootTest(classes = Application.class, properties = {
        "spring.datasource.url=jdbc:sqlite:./data/test-iam.db?foreign_keys=on",
        "app.admin-initial-password=TestPass123"
})
@AutoConfigureMockMvc
class IamFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuditOperationMapper auditOperationMapper;

    @Autowired
    private RoleMapper roleMapper;

    @Autowired
    private RolePermissionMapper rolePermissionMapper;

    @Autowired
    private DepartmentMapper departmentMapper;

    @Autowired
    private IamActorApi iamActorApi;

    private String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }

    private MockHttpSession loginAsAdmin() throws Exception {
        return login("admin", "TestPass123");
    }

    private MockHttpSession login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(csrf())
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private int departmentTreeVersion(MockHttpSession session) throws Exception {
        MvcResult tree = mockMvc.perform(get("/api/departments/tree").session(session))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(tree.getResponse().getContentAsString())
                .path("data").path("version").asInt();
    }

    private String createDepartment(MockHttpSession session, String code, String name,
                                    String parentId, int version) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/departments")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(csrf())
                        .content("{\"code\":\"" + code + "\",\"name\":\"" + name
                                + "\",\"parentId\":\"" + parentId + "\",\"sortOrder\":0,\"version\":"
                                + version + "}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asText();
    }

    @Test
    @DisplayName("管理员初始化后登录成功，返回权限数组")
    void loginSucceeds() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(csrf())
                        .content("{\"username\":\"admin\",\"password\":\"TestPass123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("admin"))
                .andExpect(jsonPath("$.data.departmentId").value("1"))
                .andExpect(jsonPath("$.data.departmentCode").value("ROOT"))
                .andExpect(jsonPath("$.data.permissions").isArray());
    }

    @Test
    @DisplayName("真实登录后 Session 保持，可访问受保护接口")
    void realLoginSessionWorks() throws Exception {
        MockHttpSession session = loginAsAdmin();
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("admin"));
    }

    @Test
    @DisplayName("错误密码登录返回 401 统一提示")
    void loginFailsWithWrongPassword() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(csrf())
                        .content("{\"username\":\"admin\",\"password\":\"wrong-pass\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("未登录访问受保护接口返回 401")
    void unauthenticatedGets401() throws Exception {
        mockMvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("真实持久化无权限账号访问 IAM、发布和上传接口均返回 403")
    void persistedUserWithoutPermissionsGets403FromProtectedApis() throws Exception {
        MockHttpSession adminSession = loginAsAdmin();
        String roleCode = unique("NOPERM");
        MvcResult role = mockMvc.perform(post("/api/roles")
                        .session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(csrf())
                        .content("{\"code\":\"" + roleCode + "\",\"name\":\"无权限角色\",\"permissionCodes\":[]}"))
                .andExpect(status().isOk())
                .andReturn();
        String roleId = objectMapper.readTree(role.getResponse().getContentAsString())
                .path("data").path("id").asText();
        String username = unique("noaccess");
        mockMvc.perform(post("/api/users")
                        .session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(csrf())
                        .content("{\"username\":\"" + username + "\",\"displayName\":\"无权限用户\",\"departmentId\":\"1\",\"password\":\"NoAccessPass123\",\"roleIds\":[\"" + roleId + "\"]}"))
                .andExpect(status().isOk());
        MockHttpSession unprivilegedSession = login(username, "NoAccessPass123");

        mockMvc.perform(get("/api/users").session(unprivilegedSession))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/site/publish").session(unprivilegedSession).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(multipart("/api/files")
                        .file(new MockMultipartFile("file", "blocked.png", MediaType.IMAGE_PNG_VALUE,
                                new byte[]{1, 2, 3}))
                        .session(unprivilegedSession)
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("文件权限独立于主页编辑权限")
    void filePermissionIsIndependentFromSiteEdit() throws Exception {
        MockHttpSession adminSession = loginAsAdmin();

        String siteRoleCode = unique("SITEONLY");
        MvcResult siteRole = mockMvc.perform(post("/api/roles")
                        .session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(csrf())
                        .content("{\"code\":\"" + siteRoleCode + "\",\"name\":\"仅主页编辑\",\"permissionCodes\":[\""
                                + PermissionCodes.SITE_HOMEPAGE_EDIT + "\"]}"))
                .andExpect(status().isOk())
                .andReturn();
        String siteRoleId = objectMapper.readTree(siteRole.getResponse().getContentAsString())
                .path("data").path("id").asText();
        String siteOnlyUsername = unique("siteonly");
        mockMvc.perform(post("/api/users")
                        .session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(csrf())
                        .content("{\"username\":\"" + siteOnlyUsername + "\",\"displayName\":\"仅主页编辑用户\","
                                + "\"departmentId\":\"1\",\"password\":\"SiteOnlyPass123\",\"roleIds\":[\"" + siteRoleId + "\"]}"))
                .andExpect(status().isOk());
        MockHttpSession siteOnlySession = login(siteOnlyUsername, "SiteOnlyPass123");
        mockMvc.perform(multipart("/api/files")
                        .file(new MockMultipartFile("file", "blocked.png", MediaType.IMAGE_PNG_VALUE, tinyPng()))
                        .session(siteOnlySession)
                        .with(csrf()))
                .andExpect(status().isForbidden());

        String fileRoleCode = unique("FILEONLY");
        MvcResult fileRole = mockMvc.perform(post("/api/roles")
                        .session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(csrf())
                        .content("{\"code\":\"" + fileRoleCode + "\",\"name\":\"文件管理\",\"permissionCodes\":[\""
                                + PermissionCodes.FILE_MANAGE + "\"]}"))
                .andExpect(status().isOk())
                .andReturn();
        String fileRoleId = objectMapper.readTree(fileRole.getResponse().getContentAsString())
                .path("data").path("id").asText();
        String fileOnlyUsername = unique("fileonly");
        mockMvc.perform(post("/api/users")
                        .session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(csrf())
                        .content("{\"username\":\"" + fileOnlyUsername + "\",\"displayName\":\"文件管理用户\","
                                + "\"departmentId\":\"1\",\"password\":\"FileOnlyPass123\",\"roleIds\":[\"" + fileRoleId + "\"]}"))
                .andExpect(status().isOk());
        MockHttpSession fileOnlySession = login(fileOnlyUsername, "FileOnlyPass123");
        MvcResult upload = mockMvc.perform(multipart("/api/files")
                        .file(new MockMultipartFile("file", "allowed.png", MediaType.IMAGE_PNG_VALUE, tinyPng()))
                        .session(fileOnlySession)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        String fileId = objectMapper.readTree(upload.getResponse().getContentAsString())
                .path("data").path("fileId").asText();
        mockMvc.perform(get("/api/files/" + fileId).session(fileOnlySession))
                .andExpect(status().isOk());
    }

    private byte[] tinyPng() throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    @Test
    @DisplayName("新用户首次登录 mustChangePassword=true，改密后变 false")
    void changePasswordClearsFlag() throws Exception {
        MockHttpSession session = loginAsAdmin();
        String username = unique("newuser");
        // 创建新用户（初始 password_changed=0）
        mockMvc.perform(post("/api/users")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(csrf())
                        .content("{\"username\":\"" + username + "\",\"displayName\":\"新用户\",\"departmentId\":\"1\",\"password\":\"NewUserPass123\",\"roleIds\":[]}"))
                .andExpect(status().isOk());
        // 首次登录：mustChangePassword=true
        MvcResult firstLogin = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(csrf())
                        .content("{\"username\":\"" + username + "\",\"password\":\"NewUserPass123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mustChangePassword").value(true))
                .andReturn();
        MockHttpSession userSession = (MockHttpSession) firstLogin.getRequest().getSession(false);
        // 改密
        mockMvc.perform(post("/api/auth/change-password")
                        .session(userSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(csrf())
                        .content("{\"oldPassword\":\"NewUserPass123\",\"newPassword\":\"ChangedPass456\"}"))
                .andExpect(status().isOk());
        // 重新登录：mustChangePassword=false
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(csrf())
                        .content("{\"username\":\"" + username + "\",\"password\":\"ChangedPass456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mustChangePassword").value(false));
    }

    @Test
    @DisplayName("系统参数关闭强制改密后，新用户登录不强制（并恢复参数）")
    void systemConfigDisablesForcePasswordChange() throws Exception {
        MockHttpSession session = loginAsAdmin();
        String username = unique("newbie");
        mockMvc.perform(put("/api/system/configs/force_password_change")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(csrf())
                        .content("{\"value\":\"false\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/users")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(csrf())
                        .content("{\"username\":\"" + username + "\",\"displayName\":\"新用户\",\"departmentId\":\"1\",\"password\":\"NewbiePass123\",\"roleIds\":[]}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(csrf())
                        .content("{\"username\":\"" + username + "\",\"password\":\"NewbiePass123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mustChangePassword").value(false));
        // 恢复参数
        mockMvc.perform(put("/api/system/configs/force_password_change")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(csrf())
                        .content("{\"value\":\"true\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("初始化管理员与当前登录账号均不可删除，并返回精确业务原因")
    void protectedUsersCannotBeDeleted() throws Exception {
        MockHttpSession adminSession = loginAsAdmin();
        MvcResult currentAdmin = mockMvc.perform(get("/api/auth/me").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("admin"))
                .andReturn();
        String initialAdminId = objectMapper.readTree(currentAdmin.getResponse().getContentAsString())
                .path("data").path("userId").asText();
        mockMvc.perform(delete("/api/users/" + initialAdminId).session(adminSession).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("不能删除初始化管理员"));

        String roleCode = unique("SELFDELETE");
        MvcResult role = mockMvc.perform(post("/api/roles")
                        .session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(csrf())
                        .content("{\"code\":\"" + roleCode + "\",\"name\":\"用户管理员\",\"permissionCodes\":[\"iam:user:manage\"]}"))
                .andExpect(status().isOk())
                .andReturn();
        String roleId = objectMapper.readTree(role.getResponse().getContentAsString())
                .path("data").path("id").asText();
        String username = unique("selfdelete");
        MvcResult user = mockMvc.perform(post("/api/users")
                        .session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(csrf())
                        .content("{\"username\":\"" + username + "\",\"displayName\":\"当前账号\",\"departmentId\":\"1\",\"password\":\"SelfDeletePass123\",\"roleIds\":[\"" + roleId + "\"]}"))
                .andExpect(status().isOk())
                .andReturn();
        String userId = objectMapper.readTree(user.getResponse().getContentAsString())
                .path("data").path("id").asText();
        MockHttpSession currentUserSession = login(username, "SelfDeletePass123");

        mockMvc.perform(delete("/api/users/" + userId).session(currentUserSession).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("不能删除当前登录账号"));
    }

    @Test
    @DisplayName("软删除用户后查询和登录不可见，并记录成功审计")
    void softDeleteUser() throws Exception {
        MockHttpSession session = loginAsAdmin();
        String username = unique("todelete");
        MvcResult create = mockMvc.perform(post("/api/users")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(csrf())
                        .content("{\"username\":\"" + username + "\",\"displayName\":\"待删\",\"departmentId\":\"1\",\"password\":\"DeletePass123\",\"roleIds\":[]}"))
                .andExpect(status().isOk())
                .andReturn();
        String userId = objectMapper.readTree(create.getResponse().getContentAsString())
                .path("data").path("id").asText();
        mockMvc.perform(delete("/api/users/" + userId).session(session).with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/users").param("keyword", username).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records").isEmpty());
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(csrf())
                        .content("{\"username\":\"" + username + "\",\"password\":\"DeletePass123\"}"))
                .andExpect(status().isUnauthorized());
        assertNotNull(auditOperationMapper.selectOne(new LambdaQueryWrapper<AuditOperationDO>()
                .eq(AuditOperationDO::getAction, "USER_DELETE")
                .eq(AuditOperationDO::getTargetId, Long.valueOf(userId))
                .eq(AuditOperationDO::getResult, "SUCCESS")));
    }

    @Test
    @DisplayName("被用户引用的角色删除被拒绝（400 提示）")
    void roleDeleteRejectedWhenReferenced() throws Exception {
        MockHttpSession session = loginAsAdmin();
        String code = "REF" + UUID.randomUUID().toString().substring(0, 6);
        MvcResult role = mockMvc.perform(post("/api/roles")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(csrf())
                        .content("{\"code\":\"" + code + "\",\"name\":\"被引用\",\"permissionCodes\":[]}"))
                .andExpect(status().isOk())
                .andReturn();
        String roleId = objectMapper.readTree(role.getResponse().getContentAsString())
                .path("data").path("id").asText();
        String username = unique("holder");
        mockMvc.perform(post("/api/users")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(csrf())
                        .content("{\"username\":\"" + username + "\",\"displayName\":\"持有者\",\"departmentId\":\"1\",\"password\":\"HolderPass123\",\"roleIds\":[\"" + roleId + "\"]}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/roles/" + roleId).session(session).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("正被用户")));
    }

    @Test
    @DisplayName("回归：被软删除用户的角色引用不阻塞删除（引用校验过滤 deleted）")
    void roleDeleteIgnoresSoftDeletedUserReferences() throws Exception {
        MockHttpSession session = loginAsAdmin();
        String code = "SOFTREF" + UUID.randomUUID().toString().substring(0, 4);
        MvcResult role = mockMvc.perform(post("/api/roles")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(csrf())
                        .content("{\"code\":\"" + code + "\",\"name\":\"软删引用\",\"permissionCodes\":[]}"))
                .andExpect(status().isOk())
                .andReturn();
        String roleId = objectMapper.readTree(role.getResponse().getContentAsString())
                .path("data").path("id").asText();
        String username = unique("softrefuser");
        // 用户绑定该角色
        MvcResult user = mockMvc.perform(post("/api/users")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(csrf())
                        .content("{\"username\":\"" + username + "\",\"displayName\":\"软删用户\",\"departmentId\":\"1\",\"password\":\"SoftRefPass123\",\"roleIds\":[\"" + roleId + "\"]}"))
                .andExpect(status().isOk())
                .andReturn();
        String userId = objectMapper.readTree(user.getResponse().getContentAsString())
                .path("data").path("id").asText();
        // 软删除用户（关联记录保留在 iam_user_role）
        mockMvc.perform(delete("/api/users/" + userId).session(session).with(csrf()))
                .andExpect(status().isOk());
        // 角色应可删除（有效用户引用为空）
        mockMvc.perform(delete("/api/roles/" + roleId).session(session).with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("无引用角色删除后角色、权限关联均清理，并记录成功审计")
    void roleDeleteSucceedsWhenUnreferenced() throws Exception {
        MockHttpSession session = loginAsAdmin();
        String code = "FREE" + UUID.randomUUID().toString().substring(0, 6);
        MvcResult role = mockMvc.perform(post("/api/roles")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(csrf())
                        .content("{\"code\":\"" + code + "\",\"name\":\"无引用\",\"permissionCodes\":[\""
                                + PermissionCodes.USER_MANAGE + "\"]}"))
                .andExpect(status().isOk())
                .andReturn();
        String roleId = objectMapper.readTree(role.getResponse().getContentAsString())
                .path("data").path("id").asText();
        mockMvc.perform(delete("/api/roles/" + roleId).session(session).with(csrf()))
                .andExpect(status().isOk());
        Long deletedRoleId = Long.valueOf(roleId);
        assertNull(roleMapper.selectById(deletedRoleId));
        assertEquals(0, rolePermissionMapper.selectCount(new LambdaQueryWrapper<RolePermissionDO>()
                .eq(RolePermissionDO::getRoleId, deletedRoleId)));
        assertNotNull(auditOperationMapper.selectOne(new LambdaQueryWrapper<AuditOperationDO>()
                .eq(AuditOperationDO::getAction, "ROLE_DELETE")
                .eq(AuditOperationDO::getTargetId, deletedRoleId)
                .eq(AuditOperationDO::getResult, "SUCCESS")));
    }

    @Test
    @DisplayName("部门树支持创建移动并保护 ROOT、循环和编码复用")
    void departmentTreeProtectsRootCycleAndCodeReuse() throws Exception {
        MockHttpSession session = loginAsAdmin();
        String rootId = objectMapper.readTree(mockMvc.perform(get("/api/departments/tree").session(session))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("data").path("nodes").get(0).path("id").asText();
        String code = unique("DPT");
        String parentCode = unique("PARENT");
        String parentId = createDepartment(session, parentCode, "父部门", rootId,
                departmentTreeVersion(session));
        String childId = createDepartment(session, code, "子部门", parentId,
                departmentTreeVersion(session));

        mockMvc.perform(put("/api/departments/" + parentId)
                        .session(session).contentType(MediaType.APPLICATION_JSON).with(csrf())
                        .content("{\"name\":\"父部门\",\"parentId\":\"" + childId
                                + "\",\"sortOrder\":0,\"version\":" + departmentTreeVersion(session) + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("不能将部门移动到自己或后代节点下"));

        mockMvc.perform(put("/api/departments/" + rootId)
                        .session(session).contentType(MediaType.APPLICATION_JSON).with(csrf())
                        .content("{\"name\":\"根部门\",\"parentId\":\"" + rootId
                                + "\",\"sortOrder\":0,\"version\":" + departmentTreeVersion(session) + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("ROOT 部门不可移动、停用或删除"));

        mockMvc.perform(post("/api/departments")
                        .session(session).contentType(MediaType.APPLICATION_JSON).with(csrf())
                        .content("{\"code\":\"" + code + "\",\"name\":\"重复编码\",\"parentId\":\""
                                + rootId + "\",\"sortOrder\":0,\"version\":" + departmentTreeVersion(session) + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("部门编码已存在且不可复用"));

        mockMvc.perform(delete("/api/departments/" + rootId).session(session).with(csrf())
                        .param("version", String.valueOf(departmentTreeVersion(session))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("ROOT 部门不可移动、停用或删除"));
        assertNotNull(auditOperationMapper.selectOne(new LambdaQueryWrapper<AuditOperationDO>()
                .eq(AuditOperationDO::getAction, "DEPARTMENT_CREATE")
                .eq(AuditOperationDO::getTargetId, Long.valueOf(childId))
                .eq(AuditOperationDO::getResult, "SUCCESS")));
    }

    @Test
    @DisplayName("部门启停和软删除拒绝有效子部门/用户，并要求祖先启用")
    void departmentEnableDisableAndDeleteRules() throws Exception {
        MockHttpSession session = loginAsAdmin();
        String rootId = "1";
        String parentId = createDepartment(session, unique("RULEP"), "规则父部门", rootId,
                departmentTreeVersion(session));
        String childId = createDepartment(session, unique("RULEC"), "规则子部门", parentId,
                departmentTreeVersion(session));

        mockMvc.perform(put("/api/departments/" + parentId + "/enabled")
                        .session(session).contentType(MediaType.APPLICATION_JSON).with(csrf())
                        .content("{\"enabled\":false,\"version\":" + departmentTreeVersion(session) + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("部门存在启用子部门，不能停用"));
        mockMvc.perform(put("/api/departments/" + childId + "/enabled")
                        .session(session).contentType(MediaType.APPLICATION_JSON).with(csrf())
                        .content("{\"enabled\":false,\"version\":" + departmentTreeVersion(session) + "}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/departments/" + parentId + "/enabled")
                        .session(session).contentType(MediaType.APPLICATION_JSON).with(csrf())
                        .content("{\"enabled\":false,\"version\":" + departmentTreeVersion(session) + "}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/departments/" + childId + "/enabled")
                        .session(session).contentType(MediaType.APPLICATION_JSON).with(csrf())
                        .content("{\"enabled\":true,\"version\":" + departmentTreeVersion(session) + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("停用祖先部门时不能启用当前部门"));

        mockMvc.perform(put("/api/departments/" + parentId + "/enabled")
                        .session(session).contentType(MediaType.APPLICATION_JSON).with(csrf())
                        .content("{\"enabled\":true,\"version\":" + departmentTreeVersion(session) + "}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/departments/" + childId + "/enabled")
                        .session(session).contentType(MediaType.APPLICATION_JSON).with(csrf())
                        .content("{\"enabled\":true,\"version\":" + departmentTreeVersion(session) + "}"))
                .andExpect(status().isOk());

        String username = unique("deptuser");
        mockMvc.perform(post("/api/users").session(session).contentType(MediaType.APPLICATION_JSON).with(csrf())
                        .content("{\"username\":\"" + username + "\",\"displayName\":\"部门用户\",\"departmentId\":\""
                                + parentId + "\",\"password\":\"DeptUserPass123\",\"roleIds\":[]}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/departments/" + parentId + "/enabled")
                        .session(session).contentType(MediaType.APPLICATION_JSON).with(csrf())
                        .content("{\"enabled\":false,\"version\":" + departmentTreeVersion(session) + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("部门存在有效用户，不能停用"));
        mockMvc.perform(delete("/api/departments/" + parentId).session(session).with(csrf())
                        .param("version", String.valueOf(departmentTreeVersion(session))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("部门存在未删除子部门，不能删除"));

        mockMvc.perform(delete("/api/departments/" + childId).session(session).with(csrf())
                        .param("version", String.valueOf(departmentTreeVersion(session))))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/departments/" + parentId).session(session).with(csrf())
                        .param("version", String.valueOf(departmentTreeVersion(session))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("部门存在有效用户，不能删除"));
    }

    @Test
    @DisplayName("部门树 ROOT 版本 CAS 对相同修订只允许一次写入")
    void departmentRootVersionCasRejectsStaleRevision() throws Exception {
        MockHttpSession session = loginAsAdmin();
        int version = departmentTreeVersion(session);
        assertEquals(1, departmentMapper.compareAndIncrementRootVersion(version));
        assertEquals(0, departmentMapper.compareAndIncrementRootVersion(version));
    }

    @Test
    @DisplayName("部门写入 HTTP 契约要求版本，并拒绝重复旧修订")
    void departmentHttpRequiresVersionAndRejectsStaleRevision() throws Exception {
        MockHttpSession session = loginAsAdmin();
        String rootId = "1";
        String code = unique("HTTPVER");
        mockMvc.perform(post("/api/departments").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"缺版本\",\"parentId\":\"1\",\"sortOrder\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("version 部门树版本不能为空"));

        int version = departmentTreeVersion(session);
        mockMvc.perform(post("/api/departments").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"首次写入\",\"parentId\":\"1\",\"sortOrder\":0,\"version\":" + version + "}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/departments").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + unique("STALE") + "\",\"name\":\"旧修订\",\"parentId\":\"" + rootId + "\",\"sortOrder\":0,\"version\":" + version + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("部门树已被其他管理员修改，请刷新后重试"));

        String departmentId = createDepartment(session, unique("HTTPUP"), "待更新", rootId,
                departmentTreeVersion(session));
        mockMvc.perform(put("/api/departments/" + departmentId).session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"缺版本更新\",\"parentId\":\"1\",\"sortOrder\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("version 部门树版本不能为空"));
        mockMvc.perform(put("/api/departments/" + departmentId + "/enabled").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("version 部门树版本不能为空"));
        mockMvc.perform(delete("/api/departments/" + departmentId).session(session).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("version 参数不能为空"));
    }

    @Test
    @DisplayName("可信部门范围来自服务端用户身份，伪造 departmentId 不改变 /me")
    void trustedDepartmentScopeIgnoresClientDepartment() throws Exception {
        MockHttpSession adminSession = loginAsAdmin();
        String departmentId = createDepartment(adminSession, unique("SCOPE"), "范围部门", "1",
                departmentTreeVersion(adminSession));
        String username = unique("scopeuser");
        MvcResult created = mockMvc.perform(post("/api/users").session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).with(csrf())
                        .content("{\"username\":\"" + username + "\",\"displayName\":\"范围用户\",\"departmentId\":\""
                                + departmentId + "\",\"password\":\"ScopeUserPass123\",\"roleIds\":[]}"))
                .andExpect(status().isOk()).andReturn();
        Long userId = Long.valueOf(objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asText());
        assertEquals(ScopeMode.CURRENT_DEPARTMENT, iamActorApi.resolve(userId).getScopeMode());
        assertEquals(Long.valueOf(departmentId), iamActorApi.resolve(userId).getDepartmentId());
        MockHttpSession userSession = login(username, "ScopeUserPass123");
        mockMvc.perform(get("/api/auth/me").param("departmentId", "1").session(userSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.departmentId").value(departmentId));
        Long adminId = Long.valueOf(objectMapper.readTree(mockMvc.perform(get("/api/auth/me").session(adminSession))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("data").path("userId").asText());
        assertEquals(ScopeMode.ALL_DEPARTMENTS, iamActorApi.resolve(adminId).getScopeMode());
    }
}
