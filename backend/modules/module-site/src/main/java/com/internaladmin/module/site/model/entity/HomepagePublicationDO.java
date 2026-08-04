package com.internaladmin.module.site.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 主页当前公开快照（单例，主键固定为 1），与草稿隔离。
 */
@TableName("site_homepage_publication")
public class HomepagePublicationDO {

    /** 固定为 1（单例） */
    @TableId
    private Long id;

    /** 已发布站点名称快照 */
    private String siteName;

    /** 已发布简介快照 */
    private String introduction;

    /** 已发布主图 ID 快照 */
    private Long heroFileId;

    /** 已发布联系方式快照 */
    private String contactText;

    /** 已发布配色编码快照 */
    private String colorScheme;

    /** 匿名访问是否可见（撤回时置 false） */
    private Boolean visible;

    /** 最近发布者用户 ID */
    private Long publishedBy;

    /** 最近成功发布时间 */
    private LocalDateTime publishedAt;

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

    public Boolean getVisible() {
        return visible;
    }

    public void setVisible(Boolean visible) {
        this.visible = visible;
    }

    public Long getPublishedBy() {
        return publishedBy;
    }

    public void setPublishedBy(Long publishedBy) {
        this.publishedBy = publishedBy;
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(LocalDateTime publishedAt) {
        this.publishedAt = publishedAt;
    }
}
