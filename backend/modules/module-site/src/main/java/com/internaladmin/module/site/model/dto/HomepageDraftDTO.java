package com.internaladmin.module.site.model.dto;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 主页草稿内容（获取与保存共用）。
 */
public class HomepageDraftDTO {

    /** 站点名称 */
    @NotBlank(message = "站点名称不能为空")
    @Size(max = 120, message = "站点名称长度不能超过 120")
    private String siteName;

    /** 站点简介 */
    @NotBlank(message = "站点简介不能为空")
    private String introduction;

    /** 主展示图片 ID（64 位整数按字符串传输） */
    @NotNull(message = "主展示图片不能为空")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long heroFileId;

    /** 联系方式文本 */
    @NotBlank(message = "联系方式不能为空")
    private String contactText;

    /** 配色编码：GRAPHITE 或 AZURE */
    @NotBlank(message = "配色不能为空")
    @Size(max = 32, message = "配色编码长度不合法")
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
