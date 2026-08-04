package com.internaladmin.module.iam.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 系统参数。全局可配置项，登录等行为读取参数决定。
 */
@TableName("system_config")
public class SystemConfigDO {

    /** 参数 ID（应用生成） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 参数名称（展示用） */
    private String name;

    /** 参数键，唯一 */
    private String paramKey;

    /** 参数值 */
    private String paramValue;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getParamKey() {
        return paramKey;
    }

    public void setParamKey(String paramKey) {
        this.paramKey = paramKey;
    }

    public String getParamValue() {
        return paramValue;
    }

    public void setParamValue(String paramValue) {
        this.paramValue = paramValue;
    }
}
