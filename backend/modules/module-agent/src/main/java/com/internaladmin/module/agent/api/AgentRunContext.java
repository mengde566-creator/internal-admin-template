package com.internaladmin.module.agent.api;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Immutable actor snapshot captured on the servlet thread for one run. */
public record AgentRunContext(Long userId, Long departmentId, boolean allDepartments,
                              List<String> authorities) {
    public AgentRunContext {
        authorities = List.copyOf(authorities);
    }

    public boolean hasAuthority(String authority) {
        return authorities.contains(authority);
    }

    /**
     * Stable, opaque snapshot of the authorization scope used to isolate model memory.
     * The value is persisted with messages; no raw authority or department data is exposed.
     */
    public String scopeFingerprint() {
        String canonical = userId + "|" + departmentId + "|" + allDepartments + "|"
                + authorities.stream().sorted().reduce((left, right) -> left + "," + right).orElse("");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        }
        catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("无法生成权限范围指纹", exception);
        }
    }
}
