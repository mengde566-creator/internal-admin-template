package com.internaladmin.module.site.model.dto;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 主页区块传输对象（草稿与发布快照共用）。
 *
 * <p>id 为空表示新增区块；不为空表示更新已有区块。sortOrder 由后端按数组顺序赋值，
 * 前端无需传递；响应中原样返回以便前端保持一致顺序。</p>
 */
public class HomepageSectionDTO {

    /** 区块 ID（64 位字符串；新增时为空） */
    private Long id;

    /** 区块类型代码：ABOUT / SERVICE / NEWS / CONTACT */
    @NotBlank(message = "区块类型不能为空")
    private String sectionType;

    /** 区块标题 */
    @NotBlank(message = "区块标题不能为空")
    private String title;

    /** 区块内容 */
    @NotBlank(message = "区块内容不能为空")
    private String content;

    /** 配图文件 ID（64 位字符串，可空） */
    private Long heroFileId;

    /** 排序序号（后端赋值，响应中返回） */
    private Integer sortOrder;

    @JsonSerialize(using = ToStringSerializer.class)
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

    @JsonSerialize(using = ToStringSerializer.class)
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
