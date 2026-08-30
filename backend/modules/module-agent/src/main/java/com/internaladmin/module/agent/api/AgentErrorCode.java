package com.internaladmin.module.agent.api;

import com.internaladmin.platform.kernel.error.ErrorCodeContract;

/** Agent-owned error vocabulary; it is intentionally not part of platform ErrorCode. */
public enum AgentErrorCode implements ErrorCodeContract {
    PARAMETER_INVALID("AI_PARAMETER_INVALID", "助手请求参数不合法"),
    BUSINESS_REJECTED("AI_BUSINESS_REJECTED", "助手无法完成该业务操作"),
    CANDIDATE_INVALID("AI_CANDIDATE_INVALID", "候选已失效，请重新查询"),
    TOOL_FORBIDDEN("AI_TOOL_FORBIDDEN", "当前用户无权查看该数据"),
    TOOL_TIMEOUT("AI_TOOL_TIMEOUT", "查询超时，请稍后重试"),
    TOOL_DATABASE_UNAVAILABLE("AI_TOOL_DATABASE_UNAVAILABLE", "库存数据暂时不可用"),
    TOOL_EXECUTION_FAILED("AI_TOOL_EXECUTION_FAILED", "库存查询暂时未完成"),
    KNOWLEDGE_UNAVAILABLE("AI_KNOWLEDGE_UNAVAILABLE", "知识库暂时不可用，请稍后重试。"),
    RETRIEVAL_DEGRADED("AI_RETRIEVAL_DEGRADED", "相似物品检索暂时不可用，请补充名称或编码后重试"),
    MODEL_OUTPUT_INVALID("AI_MODEL_OUTPUT_INVALID", "助手回复暂时不可用"),
    MODEL_RESULT_MISMATCH("AI_MODEL_RESULT_MISMATCH", "助手回复与查询结果不一致"),
    MODEL_UNAVAILABLE("AI_MODEL_UNAVAILABLE", "助手暂时不可用"),
    HISTORY_WRITE_FAILED("AI_HISTORY_WRITE_FAILED", "助手回复保存失败"),
    OBSERVATION_FAILED("AI_OBSERVATION_FAILED", "助手运行记录保存失败"),
    TERMINAL_CONFLICT("AI_TERMINAL_CONFLICT", "助手运行状态已被其他操作改变"),
    STREAM_DELIVERY_FAILED("AI_STREAM_DELIVERY_FAILED", "助手回复发送失败");

    private final String code;
    private final String message;

    AgentErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
