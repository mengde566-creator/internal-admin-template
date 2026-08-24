package com.internaladmin.module.agent.model.dto;

import java.util.List;

/** 当前 Conversation 中仍有效的澄清任务快照。 */
public record ClarificationTaskDTO(String clarificationId, long revision,
                                   List<ClarificationOptionDTO> options) {
    public ClarificationTaskDTO {
        options = List.copyOf(options);
    }
}
