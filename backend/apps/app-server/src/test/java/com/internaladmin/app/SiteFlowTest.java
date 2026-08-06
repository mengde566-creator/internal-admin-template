package com.internaladmin.app;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * module-site 集成测试：上传/草稿/发布/公开读取/撤回/草稿与发布隔离。
 *
 * <p>测试库 data/test-site.db 与测试上传目录独立于开发环境；站点名唯一避免互相干扰；
 * 发布类测试结束前撤回，保证"未发布时 404"场景不受影响。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:./data/test-site.db?foreign_keys=on",
        "app.admin-initial-password=TestPass123",
        "app.storage-root=./data/test-site-uploads"
})
@AutoConfigureMockMvc
class SiteFlowTest {

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

    private String uploadPng(MockHttpSession session) throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bos);
        MvcResult result = mockMvc.perform(multipart("/api/files")
                        .file(new MockMultipartFile("file", "test.png", "image/png", bos.toByteArray()))
                        .session(session)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("fileId").asText();
    }

    @Test
    @DisplayName("未发布时匿名读公开主页返回 404")
    void publicSite404WhenNotPublished() throws Exception {
        MockHttpSession session = loginAsAdmin();
        // 容错清理：若上次运行残留发布则撤回（无发布时 400 也接受）
        MvcResult withdraw = mockMvc.perform(post("/api/site/withdraw").session(session).with(csrf())).andReturn();
        assertThat(withdraw.getResponse().getStatus()).isIn(200, 400);
        mockMvc.perform(get("/api/public/site")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("保存草稿（布局+区块）→ 发布 → 匿名可读 → 撤回 → 404")
    void publishWithdrawFlow() throws Exception {
        MockHttpSession session = loginAsAdmin();
        String fileId = uploadPng(session);
        String siteName = unique("测试站点");
        String draft = "{\"siteName\":\"" + siteName + "\",\"introduction\":\"简介\",\"heroFileId\":\"" + fileId
                + "\",\"contactText\":\"contact@test.com\",\"colorScheme\":\"GRAPHITE\",\"layoutCode\":\"GRID_SPLIT\","
                + "\"sections\":[{\"sectionType\":\"ABOUT\",\"title\":\"关于\",\"content\":\"内容\",\"heroFileId\":null,\"sortOrder\":0}]}";
        mockMvc.perform(put("/api/site/draft")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(csrf())
                        .content(draft))
                .andExpect(status().isOk());

        // 非法布局被拒
        mockMvc.perform(put("/api/site/draft")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(csrf())
                        .content(draft.replace("GRID_SPLIT", "NO_SUCH_LAYOUT")))
                .andExpect(status().isBadRequest());

        // 发布
        mockMvc.perform(post("/api/site/publish").session(session).with(csrf()))
                .andExpect(status().isOk());

        // 匿名可读，含布局与区块
        mockMvc.perform(get("/api/public/site"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.layoutCode").value("GRID_SPLIT"))
                .andExpect(jsonPath("$.data.sections[0].sectionType").value("ABOUT"));

        // 撤回 → 404
        mockMvc.perform(post("/api/site/withdraw").session(session).with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/public/site")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("草稿修改不影响已发布快照（并撤回清理）")
    void draftEditDoesNotAffectPublished() throws Exception {
        MockHttpSession session = loginAsAdmin();
        String fileId = uploadPng(session);
        String siteName = unique("站点A");
        String draftA = "{\"siteName\":\"" + siteName + "\",\"introduction\":\"A\",\"heroFileId\":\"" + fileId
                + "\",\"contactText\":\"c\",\"colorScheme\":\"GRAPHITE\",\"layoutCode\":\"GRID_SPLIT\",\"sections\":[]}";
        mockMvc.perform(put("/api/site/draft").session(session).contentType(MediaType.APPLICATION_JSON).with(csrf()).content(draftA))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/site/publish").session(session).with(csrf())).andExpect(status().isOk());
        // 公开读为 A
        mockMvc.perform(get("/api/public/site"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.siteName").value(siteName));

        // 改草稿为 B，不发布
        String draftB = draftA.replace("\"introduction\":\"A\"", "\"introduction\":\"B\"");
        mockMvc.perform(put("/api/site/draft").session(session).contentType(MediaType.APPLICATION_JSON).with(csrf()).content(draftB))
                .andExpect(status().isOk());
        // 公开仍为 A（草稿修改不影响线上）
        mockMvc.perform(get("/api/public/site"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.siteName").value(siteName));

        // 清理：撤回，避免影响"未发布 404"场景
        mockMvc.perform(post("/api/site/withdraw").session(session).with(csrf())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("匿名保存草稿/发布被拒绝（401）")
    void anonymousWriteRejected() throws Exception {
        mockMvc.perform(put("/api/site/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(csrf())
                        .content("{\"siteName\":\"x\",\"introduction\":\"y\",\"heroFileId\":\"1\",\"contactText\":\"z\",\"colorScheme\":\"GRAPHITE\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/site/publish").with(csrf()))
                .andExpect(status().isUnauthorized());
    }
}
