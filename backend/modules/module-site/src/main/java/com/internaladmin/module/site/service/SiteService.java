package com.internaladmin.module.site.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.internaladmin.module.audit.api.AuditRecordApi;
import com.internaladmin.module.file.api.FileQueryApi;
import com.internaladmin.module.file.api.FileStorageInfo;
import com.internaladmin.module.site.mapper.HomepageDraftMapper;
import com.internaladmin.module.site.mapper.HomepageDraftSectionMapper;
import com.internaladmin.module.site.mapper.HomepagePublicationMapper;
import com.internaladmin.module.site.mapper.HomepagePublicationSectionMapper;
import com.internaladmin.module.site.model.dto.HomepageDraftDTO;
import com.internaladmin.module.site.model.dto.HomepagePublicDTO;
import com.internaladmin.module.site.model.dto.HomepageSectionDTO;
import com.internaladmin.module.site.model.entity.HomepageDraftDO;
import com.internaladmin.module.site.model.entity.HomepageDraftSectionDO;
import com.internaladmin.module.site.model.entity.HomepagePublicationDO;
import com.internaladmin.module.site.model.entity.HomepagePublicationSectionDO;
import com.internaladmin.platform.kernel.error.BusinessException;
import com.internaladmin.platform.kernel.error.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 主页内容服务：草稿保存（含区块）、发布、撤回与匿名读取。
 */
@Service
public class SiteService {

    /** 主页单例 ID（表结构 CHECK(id=1) 保证最多一行） */
    private static final long HOMEPAGE_ID = 1L;

    /** 代码定义配色（REQ-V01-005） */
    private static final Set<String> ALLOWED_COLOR_SCHEMES = Set.of("GRAPHITE", "AZURE");

    /** 代码定义布局（REQ：公开主页改版） */
    private static final Set<String> ALLOWED_LAYOUT_CODES = Set.of("GRID_SPLIT", "BANNER_SPLIT");

    /** 默认布局（管理端未选布局时使用） */
    private static final String DEFAULT_LAYOUT = "GRID_SPLIT";

    /** 代码定义区块类型（REQ：第一批 4 种） */
    private static final Set<String> ALLOWED_SECTION_TYPES = Set.of("ABOUT", "SERVICE", "NEWS", "CONTACT");

    private final HomepageDraftMapper homepageDraftMapper;
    private final HomepagePublicationMapper homepagePublicationMapper;
    private final HomepageDraftSectionMapper draftSectionMapper;
    private final HomepagePublicationSectionMapper publicationSectionMapper;
    private final FileQueryApi fileQueryApi;
    private final AuditRecordApi auditRecordApi;

    public SiteService(HomepageDraftMapper homepageDraftMapper,
                       HomepagePublicationMapper homepagePublicationMapper,
                       HomepageDraftSectionMapper draftSectionMapper,
                       HomepagePublicationSectionMapper publicationSectionMapper,
                       FileQueryApi fileQueryApi,
                       AuditRecordApi auditRecordApi) {
        this.homepageDraftMapper = homepageDraftMapper;
        this.homepagePublicationMapper = homepagePublicationMapper;
        this.draftSectionMapper = draftSectionMapper;
        this.publicationSectionMapper = publicationSectionMapper;
        this.fileQueryApi = fileQueryApi;
        this.auditRecordApi = auditRecordApi;
    }

    /**
     * 获取当前草稿（含布局与区块）。
     *
     * <p>方法：{@code getDraft}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 按固定 ID 查询草稿；
     * 2. 不存在时返回 null（前端展示空表单），存在时转换草稿与区块为 DTO 返回。
     *
     * @return 草稿内容（含布局与区块）；尚无草稿时返回 null
     */
    public HomepageDraftDTO getDraft() {
        HomepageDraftDO draft = homepageDraftMapper.selectById(HOMEPAGE_ID);
        return draft == null ? null : toDraftDTO(draft);
    }

    /**
     * 保存草稿（内容与配色、布局、区块一起保存，不影响已发布快照）。
     *
     * <p>方法：{@code saveDraft}</p>
     *
     * <p>执行链路（共 7 步）：</p>
     * 1. 调用 {@link #validateDraft(HomepageDraftDTO)} 校验配色、布局与每个区块（类型/配图存在）；
     * 2. 按固定 ID 查询草稿，不存在则新建（固定 ID=1）；
     * 3. 复制基础字段、配色与布局到草稿；
     * 4. 整体保存区块：调用 {@link #upsertSections(List)} 按传入数组顺序新增/更新/删除区块；
     * 5. 持久化草稿；
     * 6. 重新读取草稿与区块，组装最新 DTO；
     * 7. 返回保存后的草稿 DTO（含后端生成的区块 ID 与 sortOrder）。
     *
     * @param dto 草稿内容（含布局与区块）
     * @return 保存后的草稿内容
     * @throws BusinessException 配色未注册、布局不支持、区块类型非法或配图不存在时抛出
     */
    @Transactional
    public HomepageDraftDTO saveDraft(HomepageDraftDTO dto) {
        validateDraft(dto);
        HomepageDraftDO draft = homepageDraftMapper.selectById(HOMEPAGE_ID);
        if (draft == null) {
            draft = new HomepageDraftDO();
            draft.setId(HOMEPAGE_ID);
        }
        applyDraftFields(draft, dto);
        homepageDraftMapper.insertOrUpdate(draft);
        upsertSections(dto.getSections());
        return toDraftDTO(homepageDraftMapper.selectById(HOMEPAGE_ID));
    }

    /**
     * 发布草稿：复制为公开快照并记录审计（含布局与区块整体复制）。
     *
     * <p>方法：{@code publish}</p>
     *
     * <p>执行链路（共 7 步）：</p>
     * 1. 查询草稿，不存在时抛出业务异常；
     * 2. 从安全上下文解析当前用户 ID；
     * 3. 复制草稿基础字段、配色与布局到发布快照（visible=true、publishedBy、publishedAt）；
     * 4. 调用 {@link #copySectionsToPublication()} 整体复制草稿区块为发布快照区块（先清后写，顺序一致）；
     * 5. 持久化快照（同一事务）；
     * 6. 调用 {@link AuditRecordApi#record(Long, String, Long, String)} 记录 SITE_PUBLISH 成功（随主事务提交）；
     * 7. 任一步失败时由 Controller 在事务回滚后记录 FAILURE 审计（无半发布状态）。
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
            copySectionsToPublication();
            auditRecordApi.record(operatorId, "SITE_PUBLISH", HOMEPAGE_ID, "SUCCESS");
        } catch (RuntimeException e) {
            throw e;
        }
    }

    /**
     * 撤回发布：停止公开访问并记录审计（草稿与区块保留）。
     *
     * <p>方法：{@code withdraw}</p>
     *
     * <p>执行链路（共 4 步）：</p>
     * 1. 查询发布快照，不存在时抛出业务异常；
     * 2. 将 visible 置为 false；
     * 3. 持久化并调用 {@link AuditRecordApi#record(Long, String, Long, String)} 记录 SITE_WITHDRAW 成功；
     * 4. 失败时由外层记录 FAILURE 并重新抛出。
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
     * 匿名读取公开主页（含布局与区块，仅可见快照）。
     *
     * <p>方法：{@code getPublic}</p>
     *
     * <p>执行链路（共 3 步）：</p>
     * 1. 按固定 ID 查询发布快照；
     * 2. 快照不存在或不可见时返回 null（不泄露草稿）；
     * 3. 转换快照基础字段、配色、布局与区块为公开 DTO 返回。
     *
     * @return 公开主页内容（含布局与区块）；未发布或已撤回时返回 null
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
        dto.setLayoutCode(publication.getLayoutCode() == null ? DEFAULT_LAYOUT : publication.getLayoutCode());
        dto.setSections(loadPublicationSections());
        return dto;
    }

    /**
     * 公开文件读取校验：仅当文件被当前可见快照或其区块引用时返回存储信息。
     *
     * <p>方法：{@code getPublicFile}</p>
     *
     * <p>执行链路（共 5 步）：</p>
     * 1. 查询发布快照，不可见时返回 null；
     * 2. 比较快照 hero_file_id 与请求文件 ID，一致则通过；
     * 3. 不一致时遍历发布快照区块的 hero_file_id，命中任一即通过；
     * 4. 均不匹配时返回 null（未发布内容不泄露）；
     * 5. 调用 {@link FileQueryApi#findById(Long)} 获取存储信息并返回。
     *
     * @param fileId 请求的文件 ID
     * @return 文件存储信息；不可公开读取时返回 null
     */
    public FileStorageInfo getPublicFile(Long fileId) {
        HomepagePublicationDO publication = homepagePublicationMapper.selectById(HOMEPAGE_ID);
        if (publication == null || !Boolean.TRUE.equals(publication.getVisible())) {
            return null;
        }
        if (fileId.equals(publication.getHeroFileId())) {
            return fileQueryApi.findById(fileId);
        }
        boolean referencedBySection = loadPublicationSections().stream()
                .anyMatch(section -> fileId.equals(section.getHeroFileId()));
        if (referencedBySection) {
            return fileQueryApi.findById(fileId);
        }
        return null;
    }

    /**
     * 校验草稿内容（配色注册、布局支持、主图存在、每个区块类型与配图）。
     *
     * @param dto 草稿内容
     * @throws BusinessException 校验不通过时抛出
     */
    private void validateDraft(HomepageDraftDTO dto) {
        if (!ALLOWED_COLOR_SCHEMES.contains(dto.getColorScheme())) {
            throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "不支持的配色编码");
        }
        String layoutCode = dto.getLayoutCode() == null ? DEFAULT_LAYOUT : dto.getLayoutCode();
        if (!ALLOWED_LAYOUT_CODES.contains(layoutCode)) {
            throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "不支持的布局编码");
        }
        if (!fileQueryApi.exists(dto.getHeroFileId())) {
            throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "主展示图片不存在");
        }
        if (dto.getSections() != null) {
            for (HomepageSectionDTO section : dto.getSections()) {
                if (!ALLOWED_SECTION_TYPES.contains(section.getSectionType())) {
                    throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "不支持的区块类型：" + section.getSectionType());
                }
                if (section.getHeroFileId() != null && !fileQueryApi.exists(section.getHeroFileId())) {
                    throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "区块配图不存在");
                }
            }
        }
    }

    /**
     * 整体保存草稿区块：新增无 ID 区块、更新有 ID 区块、删除列表外的旧区块，并按数组顺序赋 sortOrder。
     *
     * <p>方法：{@code upsertSections}</p>
     *
     * <p>执行链路（共 4 步）：</p>
     * 1. 读取现有全部草稿区块，按 ID 建立索引；
     * 2. 收集传入区块中已有 ID，删除不在列表中的旧区块；
     * 3. 按数组顺序遍历：无 ID 新建、有 ID 更新，逐字段赋值并设置 sortOrder=索引；
     * 4. 完成持久化。
     *
     * @param sections 传入的区块列表（顺序即展示顺序）；为 null 时清空全部区块
     */
    private void upsertSections(List<HomepageSectionDTO> sections) {
        List<HomepageDraftSectionDO> existing = draftSectionMapper.selectList(
                new LambdaQueryWrapper<HomepageDraftSectionDO>().orderByAsc(HomepageDraftSectionDO::getSortOrder));
        Map<Long, HomepageDraftSectionDO> existingById = existing.stream()
                .collect(Collectors.toMap(HomepageDraftSectionDO::getId, Function.identity()));
        List<HomepageSectionDTO> incoming = sections == null ? List.of() : sections;
        Set<Long> incomingIds = incoming.stream()
                .map(HomepageSectionDTO::getId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        for (HomepageDraftSectionDO old : existing) {
            if (!incomingIds.contains(old.getId())) {
                draftSectionMapper.deleteById(old.getId());
            }
        }
        for (int i = 0; i < incoming.size(); i++) {
            HomepageSectionDTO source = incoming.get(i);
            HomepageDraftSectionDO target = source.getId() == null
                    ? new HomepageDraftSectionDO()
                    : existingById.getOrDefault(source.getId(), new HomepageDraftSectionDO());
            target.setSectionType(source.getSectionType());
            target.setTitle(source.getTitle());
            target.setContent(source.getContent());
            target.setHeroFileId(source.getHeroFileId());
            target.setSortOrder(i);
            if (source.getId() == null) {
                draftSectionMapper.insert(target);
            } else {
                draftSectionMapper.updateById(target);
            }
        }
    }

    /**
     * 发布时整体复制草稿区块为发布快照区块（先清后写，顺序与草稿一致）。
     *
     * <p>方法：{@code copySectionsToPublication}</p>
     *
     * <p>执行链路（共 3 步）：</p>
     * 1. 删除现有全部发布快照区块；
     * 2. 按 sortOrder 升序读取草稿区块；
     * 3. 逐条复制字段并插入新发布快照区块（新 ID，保持顺序）。
     */
    private void copySectionsToPublication() {
        publicationSectionMapper.delete(new LambdaQueryWrapper<>());
        List<HomepageDraftSectionDO> draftSections = draftSectionMapper.selectList(
                new LambdaQueryWrapper<HomepageDraftSectionDO>().orderByAsc(HomepageDraftSectionDO::getSortOrder));
        for (HomepageDraftSectionDO source : draftSections) {
            HomepagePublicationSectionDO target = new HomepagePublicationSectionDO();
            target.setSectionType(source.getSectionType());
            target.setTitle(source.getTitle());
            target.setContent(source.getContent());
            target.setHeroFileId(source.getHeroFileId());
            target.setSortOrder(source.getSortOrder());
            publicationSectionMapper.insert(target);
        }
    }

    /**
     * 读取当前草稿区块（按 sortOrder 升序）并转为 DTO。
     *
     * @return 区块 DTO 列表（可能为空）
     */
    private List<HomepageSectionDTO> loadDraftSections() {
        return draftSectionMapper.selectList(
                new LambdaQueryWrapper<HomepageDraftSectionDO>().orderByAsc(HomepageDraftSectionDO::getSortOrder))
                .stream()
                .map(this::toSectionDTO)
                .toList();
    }

    /**
     * 读取当前发布快照区块（按 sortOrder 升序）并转为 DTO。
     *
     * @return 区块 DTO 列表（可能为空）
     */
    private List<HomepageSectionDTO> loadPublicationSections() {
        return publicationSectionMapper.selectList(
                new LambdaQueryWrapper<HomepagePublicationSectionDO>().orderByAsc(HomepagePublicationSectionDO::getSortOrder))
                .stream()
                .map(this::toSectionDTO)
                .toList();
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
        draft.setLayoutCode(dto.getLayoutCode() == null ? DEFAULT_LAYOUT : dto.getLayoutCode());
    }

    private void applyPublicationFields(HomepagePublicationDO publication, HomepageDraftDO draft) {
        publication.setSiteName(draft.getSiteName());
        publication.setIntroduction(draft.getIntroduction());
        publication.setHeroFileId(draft.getHeroFileId());
        publication.setContactText(draft.getContactText());
        publication.setColorScheme(draft.getColorScheme());
        publication.setLayoutCode(draft.getLayoutCode() == null ? DEFAULT_LAYOUT : draft.getLayoutCode());
    }

    private HomepageDraftDTO toDraftDTO(HomepageDraftDO draft) {
        HomepageDraftDTO dto = new HomepageDraftDTO();
        dto.setSiteName(draft.getSiteName());
        dto.setIntroduction(draft.getIntroduction());
        dto.setHeroFileId(draft.getHeroFileId());
        dto.setContactText(draft.getContactText());
        dto.setColorScheme(draft.getColorScheme());
        dto.setLayoutCode(draft.getLayoutCode() == null ? DEFAULT_LAYOUT : draft.getLayoutCode());
        dto.setSections(loadDraftSections());
        return dto;
    }

    private HomepageSectionDTO toSectionDTO(HomepageDraftSectionDO section) {
        return toSectionDTO(section.getId(), section.getSectionType(), section.getTitle(),
                section.getContent(), section.getHeroFileId(), section.getSortOrder());
    }

    private HomepageSectionDTO toSectionDTO(HomepagePublicationSectionDO section) {
        return toSectionDTO(section.getId(), section.getSectionType(), section.getTitle(),
                section.getContent(), section.getHeroFileId(), section.getSortOrder());
    }

    private HomepageSectionDTO toSectionDTO(Long id, String sectionType, String title,
                                            String content, Long heroFileId, Integer sortOrder) {
        HomepageSectionDTO dto = new HomepageSectionDTO();
        dto.setId(id);
        dto.setSectionType(sectionType);
        dto.setTitle(title);
        dto.setContent(content);
        dto.setHeroFileId(heroFileId);
        dto.setSortOrder(sortOrder);
        return dto;
    }
}
