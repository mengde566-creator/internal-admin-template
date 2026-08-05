package com.internaladmin.module.site.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 主页草稿区块（单例草稿的子内容，1—N）。
 * 区块随草稿整体保存与发布，发布时整体复制为发布快照区块；不建跨表外键。
 */
@TableName("site_homepage_draft_section")
public class HomepageDraftSectionDO {

    /** 应用生成的 64 位 ID（MyBatis-Plus 默认 ASSIGN_ID） */
    @TableId
    private Long id;

    /** 区块类型代码：ABOUT / SERVICE / NEWS / CONTACT */
    private String sectionType;

    /** 区块标题 */
    private String title;

    /** 区块内容（纯文本/简单多行文本） */
    private String content;

    /** 配图文件 ID（可空，只存标识，不建外键） */
    private Long heroFileId;

    /** 排序序号（0 起，由草稿保存时按数组顺序赋值） */
    private Integer sortOrder;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSectionType() {
        return sectionType;
    }

    public void setSectionType(String sectionType) {
        this.sectionType = sectionType;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Long getHeroFileId() {
        return heroFileId;
    }

    public void setHeroFileId(Long heroFileId) {
        this.heroFileId = heroFileId;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }
}
