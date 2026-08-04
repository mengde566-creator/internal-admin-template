package com.internaladmin.module.site.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 主页草稿（单例，主键固定为 1）。修改草稿不影响已发布快照。
 */
@TableName("site_homepage_draft")
public class HomepageDraftDO {

    /** 固定为 1（单例） */
    @TableId
    private Long id;

    /** 站点名称 */
    private String siteName;

    /** 站点简介 */
    private String introduction;

    /** 主展示图片 ID */
    private Long heroFileId;

    /** 联系方式文本 */
    private String contactText;

    /** 配色编码：GRAPHITE 或 AZURE */
    private String colorScheme;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

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
