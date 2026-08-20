package com.internaladmin.module.agent.api;

import java.util.List;

/** Immutable actor snapshot captured on the servlet thread for one run. */
public record AgentRunContext(Long userId, Long departmentId, boolean allDepartments,
                              List<String> authorities) {
    public AgentRunContext {
        authorities = List.copyOf(authorities);
    }

    public boolean hasAuthority(String authority) {
        return authorities.contains(authority);
    }
}
