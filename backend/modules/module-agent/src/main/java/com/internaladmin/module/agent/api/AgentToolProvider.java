package com.internaladmin.module.agent.api;

import org.springframework.ai.tool.ToolCallback;

/** One narrow adapter registration point for the current Gate tool set. */
public interface AgentToolProvider {
    ToolCallback[] getToolCallbacks();
}
