package com.internaladmin.module.agent.model.dto;

import java.util.List;

/** 当前 Conversation 中仍有效或可恢复的澄清任务快照。 */
public record ClarificationTaskDTO(String clarificationId, long revision, String status,
                                   String candidateKind, String candidateIntent,
                                   String selectedCode, String selectedName,
                                   String selectedScopeCode, String selectedScopeName,
                                   List<ClarificationOptionDTO> options) {
    public ClarificationTaskDTO {
        options = options == null ? List.of() : List.copyOf(options);
    }

    public ClarificationTaskDTO(String clarificationId, long revision, List<ClarificationOptionDTO> options) {
        this(clarificationId, revision, "READY", null, null, null, null, null, null, options);
    }
}
