package com.internaladmin.app;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.internaladmin.module.audit.mapper.AuditOperationMapper;
import com.internaladmin.module.audit.model.entity.AuditOperationDO;
import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.site.mapper.HomepagePublicationSectionMapper;
import com.internaladmin.module.site.model.entity.HomepagePublicationSectionDO;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
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
import static org.hamcrest.Matchers.hasItem;

/**
 * module-site 集成测试：上传/草稿/发布/公开读取/撤回/草稿与发布隔离。
 *
 * <p>测试库 data/test-site.db 与测试上传目录独立于开发环境；站点名唯一避免互相干扰；
 * 发布类测试结束前撤回，保证"未发布时 404"场景不受影响。</p>
 */
@SpringBootTest(classes = Application.class, properties = {
        "spring.datasource.url=jdbc:sqlite:./data/test-site.db?foreign_keys=on",
        "app.admin-initial-password=TestPass123",
        "app.storage-root=./data/test-site-uploads"
})
@AutoConfigureMockMvc
@Import(SiteFlowTest.MapperTestConfiguration.class)
class SiteFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuditOperationMapper auditOperationMapper;

    @Autowired
    private PublicationSectionMapperControl publicationSectionMapperControl;

    /**
     * 通过 JDK 动态代理包装真实 MyBatis Mapper，仅在测试需要时控制 insert 失败并记录调用。
     * 代理不替换 Mapper 实现，事务、数据库写入和其余方法仍由真实 Mapper 执行。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class MapperTestConfiguration {

        @Bean
        PublicationSectionMapperControl publicationSectionMapperControl() {
            return new PublicationSectionMapperControl();
        }

        @Bean
        BeanPostProcessor publicationSectionMapperWrapper(PublicationSectionMapperControl control) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!"homepagePublicationSectionMapper".equals(beanName)
                            || !(bean instanceof HomepagePublicationSectionMapper)) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(
                            HomepagePublicationSectionMapper.class.getClassLoader(),
                            new Class<?>[]{HomepagePublicationSectionMapper.class},
                            (proxy, method, args) -> {
                                control.beforeInvoke(method, args);
                                try {
                                    return method.invoke(bean, args);
                                } catch (InvocationTargetException exception) {
                                    throw exception.getCause();
                                }
                            });
                }
            };
        }
    }

    static final class PublicationSectionMapperControl {

        private final List<MapperInvocation> invocations = new ArrayList<>();
        private boolean failInserts;

        void clear() {
            invocations.clear();
            failInserts = false;
        }

        void failInserts() {
            failInserts = true;
        }

        void beforeInvoke(Method method, Object[] args) {
            invocations.add(new MapperInvocation(method.getName(), args == null ? new Object[0] : args.clone()));
            if (failInserts && "insert".equals(method.getName())) {
                failInserts = false;
                throw new IllegalStateException("受控的发布区块写入失败");
            }
        }

        List<MapperInvocation> invocations() {
            return List.copyOf(invocations);
        }
    }

    record MapperInvocation(String methodName, Object[] arguments) {
    }

    private String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }

    private MockHttpSession loginAsAdmin() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(csrf())
                        .content("{\"username\":\"admin\",\"password\":\"TestPass123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissions", hasItem(PermissionCodes.FILE_MANAGE)))
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

    private String draft(String siteName, String introduction, String heroFileId, String colorScheme,
                         String layoutCode, String sectionType, String sectionTitle, String sectionContent) {
        return ("{\"siteName\":\"%s\",\"introduction\":\"%s\",\"heroFileId\":\"%s\","
                + "\"contactText\":\"contact@test.com\",\"colorScheme\":\"%s\",\"layoutCode\":\"%s\","
                + "\"sections\":[{\"sectionType\":\"%s\",\"title\":\"%s\",\"content\":\"%s\","
                + "\"heroFileId\":null,\"sortOrder\":0}]}"
        )
                .formatted(siteName, introduction, heroFileId, colorScheme, layoutCode,
                        sectionType, sectionTitle, sectionContent);
    }

    private void saveDraft(MockHttpSession session, String draft) throws Exception {
        mockMvc.perform(put("/api/site/draft")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(csrf())
                        .content(draft))
                .andExpect(status().isOk());
    }

    private long auditCount(String action, String result) {
        return auditOperationMapper.selectCount(new LambdaQueryWrapper<AuditOperationDO>()
                .eq(AuditOperationDO::getAction, action)
                .eq(AuditOperationDO::getTargetId, 1L)
                .eq(AuditOperationDO::getResult, result));
    }

    private void assertPublicSnapshot(String siteName, String introduction, String colorScheme,
                                      String layoutCode, String sectionType, String sectionTitle,
                                      String sectionContent) throws Exception {
        mockMvc.perform(get("/api/public/site"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.siteName").value(siteName))
                .andExpect(jsonPath("$.data.introduction").value(introduction))
                .andExpect(jsonPath("$.data.colorScheme").value(colorScheme))
                .andExpect(jsonPath("$.data.layoutCode").value(layoutCode))
                .andExpect(jsonPath("$.data.sections[0].sectionType").value(sectionType))
                .andExpect(jsonPath("$.data.sections[0].title").value(sectionTitle))
                .andExpect(jsonPath("$.data.sections[0].content").value(sectionContent));
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
    @DisplayName("完整 A/B 草稿隔离、发布区块失败回滚、公开文件边界、撤回与审计")
    void publicationRollbackKeepsCompleteAAndAuditsEveryOutcome() throws Exception {
        MockHttpSession session = loginAsAdmin();
        String publishedFileId = uploadPng(session);
        String unreferencedFileId = uploadPng(session);
        String siteNameA = unique("站点A");
        String introductionA = "A 的完整简介";
        String sectionTitleA = "A 的关于区块";
        String sectionContentA = "A 的完整公开区块内容";
        String draftA = draft(siteNameA, introductionA, publishedFileId, "GRAPHITE", "GRID_SPLIT",
                "ABOUT", sectionTitleA, sectionContentA);

        long publishSuccessBefore = auditCount("SITE_PUBLISH", "SUCCESS");
        long publishFailureBefore = auditCount("SITE_PUBLISH", "FAILURE");
        long withdrawSuccessBefore = auditCount("SITE_WITHDRAW", "SUCCESS");

        saveDraft(session, draftA);
        mockMvc.perform(post("/api/site/publish").session(session).with(csrf())).andExpect(status().isOk());
        assertThat(auditCount("SITE_PUBLISH", "SUCCESS")).isEqualTo(publishSuccessBefore + 1);
        assertPublicSnapshot(siteNameA, introductionA, "GRAPHITE", "GRID_SPLIT",
                "ABOUT", sectionTitleA, sectionContentA);

        mockMvc.perform(get("/api/public/files/" + publishedFileId)).andExpect(status().isOk());
        mockMvc.perform(get("/api/public/files/" + unreferencedFileId)).andExpect(status().isNotFound());

        String siteNameB = unique("站点B");
        String introductionB = "B 的不同简介";
        String sectionTitleB = "B 的服务区块";
        String sectionContentB = "B 的不同草稿区块内容";
        String draftB = draft(siteNameB, introductionB, publishedFileId, "AZURE", "BANNER_SPLIT",
                "SERVICE", sectionTitleB, sectionContentB);
        saveDraft(session, draftB);

        publicationSectionMapperControl.clear();
        publicationSectionMapperControl.failInserts();
        mockMvc.perform(post("/api/site/publish").session(session).with(csrf()))
                .andExpect(status().isInternalServerError());
        List<MapperInvocation> publishSectionWrites = publicationSectionMapperControl.invocations();
        assertThat(publishSectionWrites).extracting(MapperInvocation::methodName)
                .containsExactly("delete", "insert");
        assertThat(publishSectionWrites.get(1).arguments()[0])
                .isInstanceOf(HomepagePublicationSectionDO.class);
        assertThat(((HomepagePublicationSectionDO) publishSectionWrites.get(1).arguments()[0]).getContent())
                .isEqualTo(sectionContentB);
        assertThat(auditCount("SITE_PUBLISH", "FAILURE")).isEqualTo(publishFailureBefore + 1);

        // 发布主表更新和发布区块清空都在同一事务中；受控区块写入失败后公开快照必须完整保留 A。
        assertPublicSnapshot(siteNameA, introductionA, "GRAPHITE", "GRID_SPLIT",
                "ABOUT", sectionTitleA, sectionContentA);

        mockMvc.perform(post("/api/site/withdraw").session(session).with(csrf())).andExpect(status().isOk());
        assertThat(auditCount("SITE_WITHDRAW", "SUCCESS")).isEqualTo(withdrawSuccessBefore + 1);
        mockMvc.perform(get("/api/public/site")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/public/files/" + publishedFileId)).andExpect(status().isNotFound());

        // 撤回只隐藏发布快照，管理草稿及 B 区块仍必须保留。
        mockMvc.perform(get("/api/site/draft").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.siteName").value(siteNameB))
                .andExpect(jsonPath("$.data.introduction").value(introductionB))
                .andExpect(jsonPath("$.data.colorScheme").value("AZURE"))
                .andExpect(jsonPath("$.data.layoutCode").value("BANNER_SPLIT"))
                .andExpect(jsonPath("$.data.sections[0].sectionType").value("SERVICE"))
                .andExpect(jsonPath("$.data.sections[0].title").value(sectionTitleB))
                .andExpect(jsonPath("$.data.sections[0].content").value(sectionContentB));
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
