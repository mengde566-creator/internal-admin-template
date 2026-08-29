package com.internaladmin.module.agent.api;

/** A module-owned tool failure that retains its stable error code at the advisor boundary. */
public final class AgentToolException extends RuntimeException {
    private final AgentErrorCode errorCode;

    public AgentToolException(AgentErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public AgentErrorCode getErrorCode() {
        return errorCode;
    }
}
