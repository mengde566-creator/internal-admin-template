package com.internaladmin.module.iam.model.dto;

import com.internaladmin.platform.kernel.error.BusinessException;
import com.internaladmin.platform.kernel.error.ErrorCode;

/**
 * 用户分页查询条件。
 */
public class UserQueryDTO {

    /** 页码，从 1 开始 */
    private long page = 1;

    /** 每页条数，默认 10，最大 100 */
    private long size = 10;

    /** 关键字：账号或显示名称模糊匹配（可选） */
    private String keyword;

    public long getPage() {
        return page;
    }

    public void setPage(long page) {
        this.page = page;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        if (size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "每页条数需在 1-100 之间");
        }
        this.size = size;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }
}
