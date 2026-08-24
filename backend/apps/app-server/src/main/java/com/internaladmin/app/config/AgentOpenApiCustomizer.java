package com.internaladmin.app.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

/** 将 Servlet 的 SseEmitter 传输类型收敛为可生成、可校验的业务事件信封。 */
@Component
public class AgentOpenApiCustomizer implements OpenApiCustomizer {
    private static final String EVENT_SCHEMA = "AgentSseEventDTO";

    @Override
    public void customise(OpenAPI openApi) {
        var path = openApi.getPaths() == null ? null
                : openApi.getPaths().get("/api/ai/conversations/{conversationId}/runs");
        var operation = path == null ? null : path.getPost();
        if (operation == null || operation.getResponses() == null) return;
        ApiResponse response = operation.getResponses().get("200");
        if (response == null) return;
        response.setContent(new io.swagger.v3.oas.models.media.Content().addMediaType(
                "text/event-stream", new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + EVENT_SCHEMA))));
        Components components = openApi.getComponents();
        if (components == null) {
            components = new Components();
            openApi.setComponents(components);
        }
        ObjectSchema event = new ObjectSchema();
        event.addProperties("version", new StringSchema());
        event.addProperties("eventId", new StringSchema());
        event.addProperties("sequence", new IntegerSchema().format("int64"));
        event.addProperties("occurredAt", new StringSchema().format("date-time"));
        event.addProperties("runId", new StringSchema());
        event.addProperties("conversationId", new StringSchema());
        event.addProperties("memorySegmentId", new StringSchema());
        event.addProperties("messageId", new StringSchema().nullable(true));
        event.addProperties("type", new StringSchema());
        event.addProperties("payload", new ObjectSchema());
        event.setRequired(java.util.List.of("version", "eventId", "sequence", "occurredAt",
                "runId", "conversationId", "memorySegmentId", "messageId", "type", "payload"));
        components.addSchemas(EVENT_SCHEMA, event);
        if (components.getSchemas() != null) {
            components.getSchemas().remove("SseEmitter");
        }
    }
}
