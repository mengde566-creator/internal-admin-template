package com.internaladmin.module.site.model.dto;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.util.List;

/**
 * 公开主页内容（匿名读取，只暴露明确允许公开的字段，含布局与区块）。
 */
public class HomepagePublicDTO {

    /** 站点名称 */
    private String siteName;

    /** 站点简介 */
    private String introduction;

    /** 主展示图片 ID（公开文件读取接口用） */
    private Long heroFileId;

    /** 联系方式文本 */
    private String contactText;

    /** 配色编码 */
    private String colorScheme;

    /** 布局代码 */
    private String layoutCode;

    /** 区块列表（按 sortOrder 升序，可能为空） */
    private List<HomepageSectionDTO> sections;

    public String getSiteName() {
        return siteName;
    }

    public void setSiteName(String siteName) {
        this.siteName = siteName;
    }

    public String getIntroduction() {
        return introduction;
    }

    public void setIntroduction(String introduction) {
        this.introduction = introduction;
    }

    @JsonSerialize(using = ToStringSerializer.class)
    public Long getHeroFileId() {
        return heroFileId;
    }

    public void setHeroFileId(Long heroFileId) {
        this.heroFileId = heroFileId;
    }

    public String getContactText() {
        return contactText;
    }

    public void setContactText(String contactText) {
        this.contactText = contactText;
    }

    public String getColorScheme() {
        return colorScheme;
    }

    public void setColorScheme(String colorScheme) {
        this.colorScheme = colorScheme;
    }

    public String getLayoutCode() {
        return layoutCode;
    }

    public void setLayoutCode(String layoutCode) {
        this.layoutCode = layoutCode;
    }

    public List<HomepageSectionDTO> getSections() {
        return sections;
    }

    public void setSections(List<HomepageSectionDTO> sections) {
        this.sections = sections;
    }
}
