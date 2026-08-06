package com.internaladmin.app;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:./data/test-iam.db?foreign_keys=on",
        "app.admin-initial-password=TestPass123"
})
@AutoConfigureMockMvc
class IamFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }

    private MockHttpSession loginAsAdmin() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(csrf())
                        .content("{\"username\":\"admin\",\"password\":\"TestPass123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
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
    @DisplayName("已登录但无用户管理权限访问 /api/users 返回 403")
    void forbiddenWithoutPermission() throws Exception {
        mockMvc.perform(get("/api/users")
                        .with(user("editor1").authorities(
                                new org.springframework.security.core.authority.SimpleGrantedAuthority("site:homepage:edit"))))
                .andExpect(status().isForbidden());
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
                        .content("{\"username\":\"" + username + "\",\"displayName\":\"新用户\",\"password\":\"NewUserPass123\",\"roleIds\":[]}"))
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
                        .content("{\"username\":\"" + username + "\",\"displayName\":\"新用户\",\"password\":\"NewbiePass123\",\"roleIds\":[]}"))
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
    @DisplayName("软删除用户后不可登录")
    void softDeleteUser() throws Exception {
        MockHttpSession session = loginAsAdmin();
        String username = unique("todelete");
        MvcResult create = mockMvc.perform(post("/api/users")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(csrf())
                        .content("{\"username\":\"" + username + "\",\"displayName\":\"待删\",\"password\":\"DeletePass123\",\"roleIds\":[]}"))
                .andExpect(status().isOk())
                .andReturn();
        String userId = objectMapper.readTree(create.getResponse().getContentAsString())
                .path("data").path("id").asText();
        mockMvc.perform(delete("/api/users/" + userId).session(session).with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(csrf())
                        .content("{\"username\":\"" + username + "\",\"password\":\"DeletePass123\"}"))
                .andExpect(status().isUnauthorized());
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
                        .content("{\"username\":\"" + username + "\",\"displayName\":\"持有者\",\"password\":\"HolderPass123\",\"roleIds\":[\"" + roleId + "\"]}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/roles/" + roleId).session(session).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("已被用户使用")));
    }

    @Test
    @DisplayName("无引用角色可删除")
    void roleDeleteSucceedsWhenUnreferenced() throws Exception {
        MockHttpSession session = loginAsAdmin();
        String code = "FREE" + UUID.randomUUID().toString().substring(0, 6);
        MvcResult role = mockMvc.perform(post("/api/roles")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(csrf())
                        .content("{\"code\":\"" + code + "\",\"name\":\"无引用\",\"permissionCodes\":[]}"))
                .andExpect(status().isOk())
                .andReturn();
        String roleId = objectMapper.readTree(role.getResponse().getContentAsString())
                .path("data").path("id").asText();
        mockMvc.perform(delete("/api/roles/" + roleId).session(session).with(csrf()))
                .andExpect(status().isOk());
    }
}
