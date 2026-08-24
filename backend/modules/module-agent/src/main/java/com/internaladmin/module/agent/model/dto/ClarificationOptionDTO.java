package com.internaladmin.module.agent.model.dto;

/** 用户可见的业务候选；optionToken 仅作为服务端校验凭据返回。 */
public record ClarificationOptionDTO(String code, String name, String baseUnit, String optionToken) {
}
