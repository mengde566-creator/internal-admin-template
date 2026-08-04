package com.internaladmin.module.site.model.dto;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * 公开主页内容（匿名读取，只暴露明确允许公开的字段）。
 */
public class HomepagePublicDTO {

    /** 站点名称 */
    private String siteName;

    /** 站点简介 */
    private String introduction;

    /** 主展示图片 ID（公开文件读取接口用） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long heroFileId;

    /** 联系方式文本 */
    private String contactText;

    /** 配色编码 */
    private String colorScheme;

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
}
