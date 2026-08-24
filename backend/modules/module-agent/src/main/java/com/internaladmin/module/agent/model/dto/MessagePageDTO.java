package com.internaladmin.module.agent.model.dto;

import java.util.List;

/** 有界的 Conversation History 分页结果。 */
public record MessagePageDTO(List<MessageDTO> records, long total, long page, long size,
                             ClarificationTaskDTO activeClarification) {
    public MessagePageDTO {
        records = List.copyOf(records);
    }

    public MessagePageDTO(List<MessageDTO> records, long total, long page, long size) {
        this(records, total, page, size, null);
    }
}
