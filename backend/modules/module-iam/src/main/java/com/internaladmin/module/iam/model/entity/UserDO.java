package com.internaladmin.module.iam.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 用户数据对象。内部人员统一账号；密码只保存哈希；支持软删除（deleted）。
 */
@TableName("iam_user")
public class UserDO {

    /** 用户 ID（应用生成） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属部门 ID */
    private Long departmentId;

    /** 登录账号，唯一 */
    private String username;

    /** 页面展示名称 */
    private String displayName;

    /** 密码哈希，禁止保存明文 */
    private String passwordHash;

    /** 是否已修改初始密码（首次登录强制改密依据） */
    private Boolean passwordChanged;

    /** 软删除标志（0 未删 / 1 已删）。显式初始化 0：SQLite ALTER 不写默认值且 @TableLogic 不保证 insert 填充 */
    @TableLogic
    private Integer deleted = 0;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDepartmentId() {
        return departmentId;
    }

    public void setDepartmentId(Long departmentId) {
        this.departmentId = departmentId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Boolean getPasswordChanged() {
        return passwordChanged;
    }

    public void setPasswordChanged(Boolean passwordChanged) {
        this.passwordChanged = passwordChanged;
    }

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }
}
