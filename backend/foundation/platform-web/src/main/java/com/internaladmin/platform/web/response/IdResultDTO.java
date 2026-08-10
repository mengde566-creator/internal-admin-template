package com.internaladmin.platform.web.response;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * 通用 ID 结果（创建类接口返回）。
 *
 * <p>64 位应用生成 ID 一律按字符串传输（防前端 JS 精度丢失），本 DTO 保证 ID 序列化为字符串。</p>
 */
public class IdResultDTO {

    /** ID（64 位整数按字符串传输） */
    private final Long id;

    /**
     * 构造 ID 结果。
     *
     * @param id 新记录 ID
     */
    public IdResultDTO(Long id) {
        this.id = id;
    }

    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() {
        return id;
    }
}
