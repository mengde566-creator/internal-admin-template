package com.internaladmin.module.agent.model.dto;

import java.util.List;

/** 有界的本人对话分页结果。 */
public record ConversationPageDTO(List<ConversationDTO> records, long total, long page, long size) {
    public ConversationPageDTO {
        records = List.copyOf(records);
    }
}
