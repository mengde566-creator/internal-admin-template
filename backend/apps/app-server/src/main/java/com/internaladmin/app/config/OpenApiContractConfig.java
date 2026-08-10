package com.internaladmin.app.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 专用契约 profile 的 OpenAPI 元数据。
 *
 * <p>生产 profile 不创建此配置，且 {@code springdoc.api-docs.enabled} 默认关闭；
 * 规范只能由 {@code contract} profile 的运行时控制器和 DTO 生成。</p>
 */
@Configuration
@Profile("contract")
public class OpenApiContractConfig {

    private static final Set<String> STRING_ID_PROPERTIES = Set.of(
            "id", "userId", "fileId", "heroFileId", "roleIds"
    );

    private static final Map<String, List<String>> ENUM_PROPERTIES = Map.of(
            "colorScheme", List.of("GRAPHITE", "AZURE"),
            "layoutCode", List.of("GRID_SPLIT", "BANNER_SPLIT"),
            "sectionType", List.of("ABOUT", "SERVICE", "NEWS", "CONTACT")
    );

    /**
     * 声明运行时生成规范的元数据。
     *
     * <p>方法：{@code openApi}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 声明当前 0.1 API 的名称与版本；
     * 2. 写入机器生成来源标记，供契约脚本拒绝手写规范。</p>
     *
     * @return 仅在 {@code contract} profile 生效的 OpenAPI 根对象
     */
    @Bean
    public OpenAPI openApi() {
        OpenAPI openApi = new OpenAPI()
                .info(new Info()
                        .title("Internal Admin Template API")
                        .version("0.1")
                        .description("由 contract profile 在运行时生成的 0.1 API 契约。"));
        openApi.addExtension("x-generated-by", "springdoc-openapi 3.1.0; do not edit manually");
        return openApi;
    }

    /**
     * 对齐运行时 JSON 与自动推导的 schema。
     *
     * <p>方法：{@code contractSchemaCustomizer}</p>
     *
     * <p>执行链路（共 3 步）：</p>
     * 1. 读取 springdoc 从 Controller 与 DTO 推导出的组件 schema；
     * 2. 将 64 位 ID、受限字符串枚举和统一响应的空数据语义调整为项目已确认契约；
     * 3. 将路径 ID 参数同步为字符串，避免前端按数字传输而产生精度风险。</p>
     *
     * @return 仅在 {@code contract} profile 执行的 schema 定制器
     */
    @Bean
    public OpenApiCustomizer contractSchemaCustomizer() {
        return openApi -> {
            if (openApi.getComponents() != null && openApi.getComponents().getSchemas() != null) {
                openApi.getComponents().getSchemas().forEach(this::customizeComponentSchema);
            }
            if (openApi.getPaths() != null) {
                openApi.getPaths().values().forEach(pathItem -> pathItem.readOperations()
                        .forEach(operation -> {
                            if (operation.getParameters() != null) {
                                operation.getParameters().forEach(this::customizeIdParameter);
                            }
                            customizeApiResponseMediaTypes(operation.getResponses());
                        }));
            }
        };
    }

    /**
     * 修正单个组件 schema 的传输语义。
     *
     * <p>方法：{@code customizeComponentSchema}</p>
     *
     * <p>执行链路（共 3 步）：</p>
     * 1. 遍历 DTO 的属性并将已确认的 ID 属性调整为字符串；
     * 2. 为固定代码值补充可用枚举，避免前端把受限字符串误作自由输入；
     * 3. 对统一响应的 {@code data} 属性标注可为空，覆盖空成功响应和失败响应。</p>
     *
     * @param schemaName springdoc 生成的组件名称
     * @param schema     对应组件 schema
     */
    private void customizeComponentSchema(String schemaName, Schema<?> schema) {
        if (schema.getProperties() == null) {
            return;
        }
        for (Map.Entry<String, Schema> property : schema.getProperties().entrySet()) {
            String propertyName = property.getKey();
            Schema<?> propertySchema = property.getValue();
            if (STRING_ID_PROPERTIES.contains(propertyName)) {
                property.setValue(toStringIdSchema(propertySchema));
                propertySchema = property.getValue();
            }
            List<String> allowedValues = ENUM_PROPERTIES.get(propertyName);
            if (allowedValues != null) {
                setStringEnum(propertySchema, allowedValues);
            }
            if ("data".equals(propertyName) && schemaName.startsWith("ApiResponse")) {
                property.setValue(toNullableSchema(propertySchema));
            }
        }
    }

    /**
     * 将属性或数组元素设为字符串 ID。
     *
     * <p>方法：{@code toStringIdSchema}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 数组属性时替换其元素 schema，保留数组结构；
     * 2. 非数组属性时创建无数值格式的字符串 schema。</p>
     *
     * @param schema 待修正的属性 schema
     * @return 保留必要元数据的字符串 ID schema
     */
    private Schema<?> toStringIdSchema(Schema<?> schema) {
        if ((schema instanceof ArraySchema || schema.getItems() != null) && schema.getItems() != null) {
            ArraySchema arraySchema = new ArraySchema();
            copySchemaMetadata(schema, arraySchema);
            arraySchema.setItems(toStringIdSchema(schema.getItems()));
            return arraySchema;
        }
        StringSchema stringSchema = new StringSchema();
        copySchemaMetadata(schema, stringSchema);
        return stringSchema;
    }

    /**
     * 保留 schema 的非类型元数据。
     *
     * <p>方法：{@code copySchemaMetadata}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 复制 description、example 与 nullable，保留接口文档和空值语义；
     * 2. 复制 default，避免 ID 类型替换意外丢失已声明的默认传输值。</p>
     *
     * @param source 原始 springdoc schema
     * @param target 替换后的 schema
     */
    private void copySchemaMetadata(Schema<?> source, Schema<?> target) {
        if (source.getDescription() != null) {
            target.setDescription(source.getDescription());
        }
        if (source.getExample() != null) {
            target.setExample(source.getExample());
        }
        if (source.getNullable() != null) {
            target.setNullable(source.getNullable());
        }
        if (source.getDefault() != null) {
            target.setDefault(source.getDefault());
        }
    }

    /**
     * 将统一响应数据表示为 OpenAPI 3.1 的可空 schema。
     *
     * <p>方法：{@code toNullableSchema}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 无业务数据的响应直接限定为 JSON null；
     * 2. 有业务数据的响应保留原 schema，并通过 anyOf 显式加入 JSON null 分支。</p>
     *
     * @param schema springdoc 推导的原始 data schema
     * @return 符合 OpenAPI 3.1 JSON Schema 语义的可空 schema
     */
    private Schema<?> toNullableSchema(Schema<?> schema) {
        Schema<Object> nullSchema = new Schema<>();
        nullSchema.setTypes(Set.of("null"));
        if (schema.get$ref() == null && schema.getType() == null && schema.getItems() == null) {
            return nullSchema;
        }
        Schema<Object> nullableSchema = new Schema<>();
        nullableSchema.setAnyOf(List.of(schema, nullSchema));
        return nullableSchema;
    }

    /**
     * 将 schema 限定为一组字符串枚举值。
     *
     * <p>方法：{@code setStringEnum}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 将 springdoc 推导的属性类型固定为字符串；
     * 2. 写入已确认的可选编码，避免自由文本与实际服务端规则不一致。</p>
     *
     * @param schema        待修正的属性 schema
     * @param allowedValues 已确认的字符串枚举值
     */
    @SuppressWarnings("unchecked")
    private void setStringEnum(Schema<?> schema, List<String> allowedValues) {
        Schema<String> stringSchema = (Schema<String>) schema;
        stringSchema.setType("string");
        stringSchema.setEnum(allowedValues);
    }

    /**
     * 将路径中的 ID 参数对齐为字符串。
     *
     * <p>方法：{@code customizeIdParameter}</p>
     *
     * <p>执行链路（共 1 步）：</p>
     * 1. 参数名以 {@code id} 结尾时，将其 schema 调整为字符串并移除数值格式。</p>
     *
     * @param parameter OpenAPI 路径或操作参数
     */
    private void customizeIdParameter(Parameter parameter) {
        if (parameter.getName() != null && parameter.getName().toLowerCase().endsWith("id")
                && parameter.getSchema() != null) {
            parameter.setSchema(toStringIdSchema(parameter.getSchema()));
        }
    }

    /**
     * 将统一 JSON 响应的媒体类型固定为 application/json。
     *
     * <p>方法：{@code customizeApiResponseMediaTypes}</p>
     *
     * <p>执行链路（共 3 步）：</p>
     * 1. 遍历单个操作的 OpenAPI 响应；
     * 2. 仅识别 springdoc 已推导为 {@code ApiResponse} 的通配媒体类型响应内容；
     * 3. 将该内容移动到 {@code application/json}，使其与 Controller 的 Jackson JSON 响应和前端类型索引一致。</p>
     *
     * @param responses 当前操作的全部 OpenAPI 响应
     */
    private void customizeApiResponseMediaTypes(Map<String, ApiResponse> responses) {
        if (responses == null) {
            return;
        }
        responses.values().forEach(response -> {
            if (response.getContent() == null || response.getContent().get("*/*") == null) {
                return;
            }
            Schema<?> schema = response.getContent().get("*/*").getSchema();
            if (schema != null && schema.get$ref() != null && schema.get$ref().contains("ApiResponse")) {
                response.getContent().addMediaType("application/json", response.getContent().remove("*/*"));
            }
        });
    }
}
