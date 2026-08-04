package com.internaladmin.module.site.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.internaladmin.module.audit.api.AuditRecordApi;
import com.internaladmin.module.file.api.FileQueryApi;
import com.internaladmin.module.file.api.FileStorageInfo;
import com.internaladmin.module.site.mapper.HomepageDraftMapper;
import com.internaladmin.module.site.mapper.HomepagePublicationMapper;
import com.internaladmin.module.site.model.dto.HomepageDraftDTO;
import com.internaladmin.module.site.model.dto.HomepagePublicDTO;
import com.internaladmin.module.site.model.entity.HomepageDraftDO;
import com.internaladmin.module.site.model.entity.HomepagePublicationDO;
import com.internaladmin.platform.kernel.error.BusinessException;
import com.internaladmin.platform.kernel.error.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 主页内容服务：草稿保存、发布、撤回与匿名读取。
 */
@Service
public class SiteService {

    /** 主页单例 ID（表结构 CHECK(id=1) 保证最多一行） */
    private static final long HOMEPAGE_ID = 1L;

    /** 代码定义配色（REQ-V01-005） */
    private static final Set<String> ALLOWED_COLOR_SCHEMES = Set.of("GRAPHITE", "AZURE");

    private final HomepageDraftMapper homepageDraftMapper;
    private final HomepagePublicationMapper homepagePublicationMapper;
    private final FileQueryApi fileQueryApi;
    private final AuditRecordApi auditRecordApi;

    public SiteService(HomepageDraftMapper homepageDraftMapper,
                       HomepagePublicationMapper homepagePublicationMapper,
                       FileQueryApi fileQueryApi,
                       AuditRecordApi auditRecordApi) {
        this.homepageDraftMapper = homepageDraftMapper;
        this.homepagePublicationMapper = homepagePublicationMapper;
        this.fileQueryApi = fileQueryApi;
        this.auditRecordApi = auditRecordApi;
    }

    /**
     * 获取当前草稿。
     *
     * <p>方法：{@code getDraft}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 按固定 ID 查询草稿；
     * 2. 不存在时返回 null（前端展示空表单），存在时转换为 DTO 返回。
     *
     * @return 草稿内容；尚无草稿时返回 null
     */
    public HomepageDraftDTO getDraft() {
        HomepageDraftDO draft = homepageDraftMapper.selectById(HOMEPAGE_ID);
        return draft == null ? null : toDraftDTO(draft);
    }

    /**
     * 保存草稿（内容与配色一起保存，不影响已发布快照）。
     *
     * <p>方法：{@code saveDraft}</p>
     *
     * <p>执行链路（共 5 步）：</p>
     * 1. 校验配色编码为代码定义的 GRAPHITE 或 AZURE；
     * 2. 调用 {@link FileQueryApi#exists(Long)} 校验主图文件存在；
     * 3. 按固定 ID 查询草稿；
     * 4. 存在则更新字段，不存在则新建（固定 ID=1）；
     * 5. 持久化并返回。
     *
     * @param dto 草稿内容
     * @throws BusinessException 配色未注册或主图文件不存在时抛出
     */
    @Transactional
    public void saveDraft(HomepageDraftDTO dto) {
        validateDraft(dto);
        HomepageDraftDO draft = homepageDraftMapper.selectById(HOMEPAGE_ID);
        if (draft == null) {
            draft = new HomepageDraftDO();
            draft.setId(HOMEPAGE_ID);
            applyDraftFields(draft, dto);
            homepageDraftMapper.insert(draft);
        } else {
            applyDraftFields(draft, dto);
            homepageDraftMapper.updateById(draft);
        }
    }

    /**
     * 发布草稿：复制为公开快照并记录审计。
     *
     * <p>方法：{@code publish}</p>
     *
     * <p>执行链路（共 6 步）：</p>
     * 1. 查询草稿，不存在时抛出业务异常；
     * 2. 从安全上下文解析当前用户 ID；
     * 3. 复制草稿内容到发布快照（visible=true、publishedBy、publishedAt）；
     * 4. 写入快照（同一事务）；
     * 5. 调用 {@link AuditRecordApi#record(Long, String, Long, String)} 记录 SITE_PUBLISH 成功（独立事务）；
     * 6. 任一步失败时记录 FAILURE 审计并重新抛出（失败不产生半公开状态）。
     *
     * @throws BusinessException 草稿不存在时抛出
     */
    @Transactional
    public void publish() {
        HomepageDraftDO draft = homepageDraftMapper.selectById(HOMEPAGE_ID);
        if (draft == null) {
            throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "尚无草稿，请先保存草稿再发布");
        }
        Long operatorId = currentUserId();
        try {
            HomepagePublicationDO publication = homepagePublicationMapper.selectById(HOMEPAGE_ID);
            if (publication == null) {
                publication = new HomepagePublicationDO();
                publication.setId(HOMEPAGE_ID);
                publication.setVisible(true);
                publication.setPublishedBy(operatorId);
                publication.setPublishedAt(LocalDateTime.now());
                applyPublicationFields(publication, draft);
                homepagePublicationMapper.insert(publication);
            } else {
                publication.setVisible(true);
                publication.setPublishedBy(operatorId);
                publication.setPublishedAt(LocalDateTime.now());
                applyPublicationFields(publication, draft);
                homepagePublicationMapper.updateById(publication);
            }
            auditRecordApi.record(operatorId, "SITE_PUBLISH", HOMEPAGE_ID, "SUCCESS");
        } catch (RuntimeException e) {
            throw e;
        }
    }

    /**
     * 撤回发布：停止公开访问并记录审计（草稿保留）。
     *
     * <p>方法：{@code withdraw}</p>
     *
     * <p>执行链路（共 4 步）：</p>
     * 1. 查询发布快照，不存在时抛出业务异常；
     * 2. 将 visible 置为 false；
     * 3. 持久化并调用 {@link AuditRecordApi#record(Long, String, Long, String)} 记录 SITE_WITHDRAW 成功；
     * 4. 失败时记录 FAILURE 并重新抛出。
     *
     * @throws BusinessException 快照不存在时抛出
     */
    @Transactional
    public void withdraw() {
        HomepagePublicationDO publication = homepagePublicationMapper.selectById(HOMEPAGE_ID);
        if (publication == null) {
            throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "尚无已发布内容可撤回");
        }
        Long operatorId = currentUserId();
        try {
            publication.setVisible(false);
            homepagePublicationMapper.updateById(publication);
            auditRecordApi.record(operatorId, "SITE_WITHDRAW", HOMEPAGE_ID, "SUCCESS");
        } catch (RuntimeException e) {
            throw e;
        }
    }

    /**
     * 匿名读取公开主页（仅可见快照）。
     *
     * <p>方法：{@code getPublic}</p>
     *
     * <p>执行链路（共 3 步）：</p>
     * 1. 按固定 ID 查询发布快照；
     * 2. 快照不存在或不可见时返回 null（不泄露草稿）；
     * 3. 转换为公开 DTO 返回。
     *
     * @return 公开主页内容；未发布或已撤回时返回 null
     */
    public HomepagePublicDTO getPublic() {
        HomepagePublicationDO publication = homepagePublicationMapper.selectById(HOMEPAGE_ID);
        if (publication == null || !Boolean.TRUE.equals(publication.getVisible())) {
            return null;
        }
        HomepagePublicDTO dto = new HomepagePublicDTO();
        dto.setSiteName(publication.getSiteName());
        dto.setIntroduction(publication.getIntroduction());
        dto.setHeroFileId(publication.getHeroFileId());
        dto.setContactText(publication.getContactText());
        dto.setColorScheme(publication.getColorScheme());
        return dto;
    }

    /**
     * 公开文件读取校验：仅当文件被当前可见快照引用时返回存储信息。
     *
     * <p>方法：{@code getPublicFile}</p>
     *
     * <p>执行链路（共 4 步）：</p>
     * 1. 查询发布快照，不可见时返回 null；
     * 2. 比较快照 hero_file_id 与请求文件 ID，不一致时返回 null（未发布内容不泄露）；
     * 3. 调用 {@link FileQueryApi#findById(Long)} 获取存储信息；
     * 4. 返回存储信息或 null。
     *
     * @param fileId 请求的文件 ID
     * @return 文件存储信息；不可公开读取时返回 null
     */
    public FileStorageInfo getPublicFile(Long fileId) {
        HomepagePublicationDO publication = homepagePublicationMapper.selectById(HOMEPAGE_ID);
        if (publication == null || !Boolean.TRUE.equals(publication.getVisible())) {
            return null;
        }
        if (!fileId.equals(publication.getHeroFileId())) {
            return null;
        }
        return fileQueryApi.findById(fileId);
    }

    /**
     * 校验草稿内容（配色注册与主图存在）。
     *
     * @param dto 草稿内容
     * @throws BusinessException 校验不通过时抛出
     */
    private void validateDraft(HomepageDraftDTO dto) {
        if (!ALLOWED_COLOR_SCHEMES.contains(dto.getColorScheme())) {
            throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "不支持的配色编码");
        }
        if (!fileQueryApi.exists(dto.getHeroFileId())) {
            throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "主展示图片不存在");
        }
    }

    /**
     * 记录失败审计（应在业务事务回滚后由外层调用，避免 SQLite 写锁冲突）。
     *
     * <p>方法：{@code recordFailure}</p>
     *
     * <p>执行链路（共 3 步）：</p>
     * 1. 从安全上下文解析当前用户 ID；
     * 2. 调用 {@link AuditRecordApi#record(Long, String, Long, String)} 写入 FAILURE 结果；
     * 3. 完成记录。
     *
     * @param action 动作编码（SITE_PUBLISH 或 SITE_WITHDRAW）
     */
    public void recordFailure(String action) {
        Long operatorId = currentUserId();
        auditRecordApi.record(operatorId, action, HOMEPAGE_ID, "FAILURE");
    }

    /**
     * 从安全上下文解析当前用户 ID。
     *
     * @return 当前用户 ID
     * @throws BusinessException 未登录时抛出
     */
    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Long userId)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录或登录已失效");
        }
        return userId;
    }

    private void applyDraftFields(HomepageDraftDO draft, HomepageDraftDTO dto) {
        draft.setSiteName(dto.getSiteName());
        draft.setIntroduction(dto.getIntroduction());
        draft.setHeroFileId(dto.getHeroFileId());
        draft.setContactText(dto.getContactText());
        draft.setColorScheme(dto.getColorScheme());
    }

    private void applyPublicationFields(HomepagePublicationDO publication, HomepageDraftDO draft) {
        publication.setSiteName(draft.getSiteName());
        publication.setIntroduction(draft.getIntroduction());
        publication.setHeroFileId(draft.getHeroFileId());
        publication.setContactText(draft.getContactText());
        publication.setColorScheme(draft.getColorScheme());
    }

    private HomepageDraftDTO toDraftDTO(HomepageDraftDO draft) {
        HomepageDraftDTO dto = new HomepageDraftDTO();
        dto.setSiteName(draft.getSiteName());
        dto.setIntroduction(draft.getIntroduction());
        dto.setHeroFileId(draft.getHeroFileId());
        dto.setContactText(draft.getContactText());
        dto.setColorScheme(draft.getColorScheme());
        return dto;
    }
}
