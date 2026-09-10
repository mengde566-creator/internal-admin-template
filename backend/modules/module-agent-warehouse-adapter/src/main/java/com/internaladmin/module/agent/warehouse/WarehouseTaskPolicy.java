package com.internaladmin.module.agent.warehouse;

import com.internaladmin.module.agent.api.AgentTaskPolicy;
import com.internaladmin.platform.kernel.error.BusinessException;
import com.internaladmin.platform.kernel.error.ErrorCode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Warehouse-owned Task candidate and resume semantics. */
public final class WarehouseTaskPolicy implements AgentTaskPolicy {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final Set<String> ITEM_INTENTS = Set.of("CURRENT_STOCK", "ITEM_LOCATIONS", "RECENT_MOVEMENTS");

    @Override
    public String adapterId() {
        return "warehouse";
    }

    @Override
    public boolean supportsIntent(String intent) {
        return ITEM_INTENTS.contains(intent) || "LOCATION_CONTENTS".equals(intent)
                || "KNOWLEDGE_DOCUMENT_READ".equals(intent);
    }

    @Override
    public boolean supportsCandidateKind(String intent, String candidateKind) {
        if (ITEM_INTENTS.contains(intent)) return "ITEM".equals(candidateKind);
        if ("LOCATION_CONTENTS".equals(intent)) return "LOCATION".equals(candidateKind);
        if ("KNOWLEDGE_DOCUMENT_READ".equals(intent)) return "DOCUMENT".equals(candidateKind);
        return false;
    }

    @Override
    public Optional<Selection> select(String intent, String candidatesJson,
                                      String previousConditions, String optionToken) {
        if (!supportsIntent(intent) || optionToken == null || optionToken.isBlank()
                || candidatesJson == null || candidatesJson.isBlank()) return Optional.empty();
        try {
            JsonNode root = JSON.readTree(candidatesJson);
            if (root == null || !root.isArray() || root.size() == 0 || root.size() > 20) invalid("候选已失效，请重新选择");
            for (JsonNode candidate : root) {
                if (candidate == null || !candidate.isObject()) invalid("候选格式无效，请重新查询");
                Set<String> names = names(candidate);
                if (!names.equals(Set.of("optionToken", "code", "name", "baseUnit"))
                        && !names.equals(Set.of("optionToken", "code", "name", "baseUnit", "warehouseCode", "warehouseName"))
                        && !names.equals(Set.of("optionToken", "code", "name", "baseUnit", "mention", "resolved"))
                        && !names.equals(Set.of("optionToken", "code", "name", "versionCode"))
                        && !names.equals(Set.of("optionToken", "code", "name", "versionCode", "versionUpdatedAt", "indexedAt"))) {
                    invalid("候选格式无效，请重新查询");
                }
                String token = text(candidate, "optionToken", 256, false);
                String code = text(candidate, "code", 128, false);
                String name = text(candidate, "name", 256, false);
                String baseUnit = text(candidate, "baseUnit", 64, true);
                if (!optionToken.equals(token)) continue;
                String warehouseCode = text(candidate, "warehouseCode", 128, true);
                String warehouseName = text(candidate, "warehouseName", 256, true);
                String type = candidateType(intent);
                boolean hasWarehouseFields = names.contains("warehouseCode") || names.contains("warehouseName");
                String mention = names.contains("mention") ? text(candidate, "mention", 256, false) : null;
                boolean resolved = !names.contains("resolved") || Boolean.TRUE.equals(candidate.get("resolved").asBoolean());
                if (names.contains("mention") && (!names.contains("resolved") || candidate.get("resolved") == null
                        || !candidate.get("resolved").isBoolean())) invalid("候选格式无效，请重新查询");
                if ("LOCATION".equals(type) && (!hasWarehouseFields || warehouseCode == null || warehouseName == null)) invalid("候选与库位任务不匹配，请重新查询");
                if ("ITEM".equals(type) && hasWarehouseFields) invalid("候选与物品任务不匹配，请重新查询");
                if ("DOCUMENT".equals(type) && hasWarehouseFields) invalid("候选与知识任务不匹配，请重新查询");

                Map<String, Object> confirmed = new LinkedHashMap<>();
                confirmed.put("type", type);
                confirmed.put("intent", intent);
                if (mention != null) confirmed.put("mention", mention);
                if (resolved) {
                    confirmed.put("code", code);
                    confirmed.put("name", name);
                    confirmed.put("baseUnit", baseUnit == null ? "" : baseUnit);
                }
                if ("DOCUMENT".equals(type)) {
                    confirmed.put("documentCode", code);
                    confirmed.put("title", name);
                    confirmed.put("versionCode", text(candidate, "versionCode", 64, false));
                    if (names.contains("versionUpdatedAt")) {
                        confirmed.put("versionUpdatedAt", text(candidate, "versionUpdatedAt", 64, false));
                        confirmed.put("indexedAt", text(candidate, "indexedAt", 64, false));
                    }
                }
                if (warehouseCode != null) {
                    confirmed.put("warehouseCode", warehouseCode);
                    confirmed.put("warehouseName", warehouseName == null ? "" : warehouseName);
                }
                appendPending(previousConditions, mention, confirmed);
                return Optional.of(new Selection(JSON.writeValueAsString(confirmed), effectiveMessage(intent, type,
                        resolved, code, name, mention, warehouseName)));
            }
            throw new BusinessException(ErrorCode.CONFLICT, "候选已失效，请重新选择");
        } catch (BusinessException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new BusinessException(ErrorCode.CONFLICT, "候选格式无效，请重新查询");
        }
    }

    @Override
    public Optional<Continuation> continuation(String intent, String confirmedConditions) {
        if (!supportsIntent(intent) || confirmedConditions == null || confirmedConditions.isBlank()) {
            return Optional.empty();
        }
        List<String> pending = pendingMentions(confirmedConditions);
        if (pending.isEmpty()) return Optional.empty();
        List<Map<String, Object>> pendingOptions = pendingOptions(confirmedConditions);
        List<Map<String, Object>> visible = pendingOptions.stream()
                .filter(option -> pending.contains(String.valueOf(option.get("mention"))))
                .toList();
        String candidates = visible.isEmpty()
                ? pendingCandidateJsonFromMentions(pending)
                : pendingCandidateJson(visible);
        return Optional.of(new Continuation("READY", intent, candidates));
    }

    @Override
    public Optional<CandidateConditions> candidateConditions(String intent, String candidateKind,
                                                             String optionsJson, String pendingMentionsJson,
                                                             String previousConditions) {
        if (!supportsCandidateKind(intent, candidateKind) || optionsJson == null || optionsJson.isBlank()) {
            return Optional.empty();
        }
        String effectiveOptions = optionsJson;
        String effectivePendingMentions = pendingMentionsJson;
        try {
            JsonNode previous = previousConditions == null || previousConditions.isBlank()
                    ? null : JSON.readTree(previousConditions);
            JsonNode selectedMention = previous == null ? null : previous.get("mention");
            JsonNode pending = previous == null ? null : previous.get("pendingMentions");
            JsonNode storedPending = previous == null ? null : previous.get("pendingOptions");
            if (effectivePendingMentions == null && previous != null && "ITEM".equals(previous.path("type").asText())
                    && intent.equals(previous.path("intent").asText())
                    && selectedMention != null && selectedMention.isTextual()
                    && !selectedMention.asText().isBlank()
                    && pending != null && pending.isArray() && pending.size() > 0 && pending.size() <= 5) {
                List<Map<String, Object>> options = JSON.readValue(effectiveOptions, List.class);
                for (Map<String, Object> option : options) {
                    option.put("mention", selectedMention.asText());
                    option.put("resolved", Boolean.TRUE);
                }
                effectiveOptions = JSON.writeValueAsString(options);
                effectivePendingMentions = pending.toString();
                if (storedPending != null && storedPending.isArray()
                        && storedPending.size() > 0 && storedPending.size() <= 5) {
                    List<Map<String, Object>> allOptions = new ArrayList<>(options);
                    allOptions.addAll(JSON.readValue(storedPending.toString(), List.class));
                    String pendingOptions = JSON.writeValueAsString(allOptions);
                    return Optional.of(new CandidateConditions(effectiveOptions,
                            "{\"intent\":\"" + intent + "\",\"pendingMentions\":"
                                    + effectivePendingMentions + ",\"pendingOptions\":" + pendingOptions + "}"));
                }
            }
            return Optional.of(new CandidateConditions(effectiveOptions,
                    "{\"intent\":\"" + intent + "\""
                            + (effectivePendingMentions == null ? "" : ",\"pendingMentions\":"
                            + effectivePendingMentions + ",\"pendingOptions\":" + effectiveOptions)
                            + "}"));
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<Clarification> clarification(String status, String intent, long revision,
                                                 String taskId, String candidatesJson,
                                                 String confirmedConditions, String activeRunId,
                                                 String latestRunStatus) {
        if (!supportsIntent(intent) || taskId == null || taskId.isBlank()) return Optional.empty();
        String kind = candidateKind(intent);
        if ("READY".equals(status) && candidatesJson != null && !candidatesJson.isBlank()) {
            try {
                JsonNode root = JSON.readTree(candidatesJson);
                if (root == null || !root.isArray() || root.size() < 1 || root.size() > 20) return Optional.empty();
                List<Option> options = new ArrayList<>();
                for (JsonNode candidate : root) {
                    Set<String> fields = names(candidate);
                    if (!fields.equals(Set.of("optionToken", "code", "name", "baseUnit"))
                            && !fields.equals(Set.of("optionToken", "code", "name", "baseUnit", "warehouseCode", "warehouseName"))
                            && !fields.equals(Set.of("optionToken", "code", "name", "baseUnit", "mention", "resolved"))
                            && !fields.equals(Set.of("optionToken", "code", "name", "versionCode"))
                            && !fields.equals(Set.of("optionToken", "code", "name", "versionCode", "versionUpdatedAt", "indexedAt"))) return Optional.empty();
                    String token = text(candidate, "optionToken", 256, false);
                    String code = text(candidate, "code", 128, false);
                    String name = text(candidate, "name", 256, false);
                    String unit = text(candidate, "baseUnit", 64, true);
                    String scopeCode = text(candidate, "warehouseCode", 128, true);
                    String scopeName = text(candidate, "warehouseName", 256, true);
                    String mention = text(candidate, "mention", 256, true);
                    JsonNode resolvedNode = candidate.get("resolved");
                    if (mention != null && (resolvedNode == null || !resolvedNode.isBoolean())) return Optional.empty();
                    options.add(new Option(code, name, unit, token, scopeCode, scopeName,
                            text(candidate, "versionCode", 64, true),
                            text(candidate, "versionUpdatedAt", 64, true),
                            text(candidate, "indexedAt", 64, true)));
                }
                return Optional.of(new Clarification("READY", kind, intent, null, null, null, null, options));
            } catch (RuntimeException invalid) {
                return Optional.empty();
            }
        }
        if ("COLLECTING".equals(status) && confirmedConditions != null && !confirmedConditions.isBlank()
                && activeRunId == null && ("FAILED".equalsIgnoreCase(latestRunStatus)
                || "PARTIAL".equalsIgnoreCase(latestRunStatus) || "CANCELLED".equalsIgnoreCase(latestRunStatus))) {
            try {
                JsonNode conditions = JSON.readTree(confirmedConditions);
                String selectedCode = conditions == null ? null : conditions.path("code").asText(null);
                String selectedName = conditions == null ? null : conditions.path("name").asText(null);
                String scopeCode = conditions == null ? null : conditions.path("warehouseCode").asText(null);
                String scopeName = conditions == null ? null : conditions.path("warehouseName").asText(null);
                if ("LOCATION".equals(kind) && (scopeCode == null || scopeCode.isBlank()
                        || scopeName == null || scopeName.isBlank())) return Optional.empty();
                if ((selectedCode != null && !selectedCode.isBlank()) || (selectedName != null && !selectedName.isBlank())) {
                    return Optional.of(new Clarification("FAILED_RETRYABLE", kind, intent, selectedCode, selectedName,
                            scopeCode, scopeName, List.of()));
                }
            } catch (RuntimeException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<CandidateCard> validateCandidateCard(String cardJson) {
        if (cardJson == null || cardJson.isBlank()) return Optional.empty();
        try {
            JsonNode root = JSON.readTree(cardJson);
            if (root == null || !root.isObject() || !"clarification-choice".equals(root.path("cardType").asText())) {
                return Optional.empty();
            }
            if (!"CLARIFICATION".equals(root.path("outcome").asText()) || root.get("options") == null
                    || !root.get("options").isArray() || root.get("options").size() < 1
                    || root.get("options").size() > 20 || root.path("allowFreeText").asBoolean(true)) {
                invalid("候选卡片无效，请重新查询");
            }
            String kind = root.path("candidateKind").asText(null);
            String intent = root.path("candidateIntent").asText(null);
            if (!supportsCandidateKind(intent, kind)) invalid("候选任务类型无效，请重新查询");
            validateCandidates(root.get("options"));
            String pending = null;
            JsonNode pendingMentions = root.get("pendingMentions");
            if (pendingMentions != null) {
                if (!pendingMentions.isArray() || pendingMentions.size() < 2 || pendingMentions.size() > 5) {
                    invalid("待查询物品线索无效，请重新查询");
                }
                for (JsonNode mention : pendingMentions) {
                    if (mention == null || !mention.isTextual() || mention.asText().isBlank() || mention.asText().length() > 256) {
                        invalid("待查询物品线索无效，请重新查询");
                    }
                }
                pending = pendingMentions.toString();
            }
            return Optional.of(new CandidateCard(kind, intent, root.get("options").toString(), pending));
        } catch (BusinessException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new BusinessException(ErrorCode.CONFLICT, "候选卡片无效，请重新查询");
        }
    }

    @Override
    public Optional<String> pendingClarificationCard(String taskId, long revision,
                                                     String intent, String candidatesJson) {
        if (!supportsIntent(intent) || candidatesJson == null || candidatesJson.isBlank()) return Optional.empty();
        try {
            JsonNode options = JSON.readTree(candidatesJson);
            if (options == null || !options.isArray() || options.size() == 0) return Optional.empty();
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("cardId", taskId);
            card.put("revision", revision);
            card.put("cardType", "clarification-choice");
            card.put("clarificationId", taskId);
            card.put("question", "请先选择要查询的物品");
            card.put("candidateKind", "ITEM");
            card.put("candidateIntent", intent);
            card.put("selectionMode", "SINGLE");
            card.put("allowFreeText", false);
            card.put("resultCount", options.size());
            card.put("truncated", false);
            card.put("status", "CANDIDATES");
            card.put("outcome", "CLARIFICATION");
            card.put("queriedAt", java.time.Instant.now());
            card.put("rows", List.of());
            card.put("options", JSON.readValue(options.toString(), List.class));
            return Optional.of(JSON.writeValueAsString(card));
        } catch (Exception invalid) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<Reference> trustedReference(String taskId, long revision,
                                                 String conditions, String scopeFingerprint,
                                                 java.time.Instant expiresAt) {
        if (conditions == null || conditions.isBlank() || scopeFingerprint == null
                || expiresAt == null || !expiresAt.isAfter(java.time.Instant.now())) return Optional.empty();
        try {
            JsonNode root = JSON.readTree(conditions);
            if (root == null || !"ITEM".equals(root.path("type").asText())
                    || !root.path("code").isTextual() || !root.path("name").isTextual()) return Optional.empty();
            return Optional.of(new Reference(taskId, revision, scopeFingerprint, expiresAt,
                    root.path("code").asText(), root.path("name").asText(), root.path("baseUnit").asText("")));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private static void validateCandidates(JsonNode options) {
        for (JsonNode option : options) {
            if (option == null || !option.isObject()) invalid("候选格式无效，请重新查询");
            Set<String> fields = names(option);
            if (!fields.equals(Set.of("optionToken", "code", "name", "baseUnit"))
                    && !fields.equals(Set.of("optionToken", "code", "name", "baseUnit", "warehouseCode", "warehouseName"))
                    && !fields.equals(Set.of("optionToken", "code", "name", "baseUnit", "mention", "resolved"))
                    && !fields.equals(Set.of("optionToken", "code", "name", "versionCode"))
                    && !fields.equals(Set.of("optionToken", "code", "name", "versionCode", "versionUpdatedAt", "indexedAt"))) invalid("候选格式无效，请重新查询");
            text(option, "optionToken", 256, false);
            text(option, "code", 128, false);
            text(option, "name", 256, false);
            text(option, "baseUnit", 64, true);
            text(option, "warehouseCode", 128, true);
            text(option, "warehouseName", 256, true);
            JsonNode mention = option.get("mention");
            if (mention != null) text(option, "mention", 256, false);
            if (mention != null && (option.get("resolved") == null || !option.get("resolved").isBoolean())) invalid("候选格式无效，请重新查询");
            text(option, "versionCode", 64, true);
            text(option, "versionUpdatedAt", 64, true);
            text(option, "indexedAt", 64, true);
        }
    }

    @Override
    public Optional<String> intentForTool(String toolName) {
        return Optional.ofNullable(switch (toolName) {
            case WarehouseInventoryToolProvider.CURRENT_STOCK_TOOL -> "CURRENT_STOCK";
            case WarehouseInventoryToolProvider.ITEM_LOCATIONS_TOOL -> "ITEM_LOCATIONS";
            case WarehouseInventoryToolProvider.RECENT_MOVEMENTS_TOOL -> "RECENT_MOVEMENTS";
            case WarehouseInventoryToolProvider.LOCATION_CONTENTS_TOOL -> "LOCATION_CONTENTS";
            default -> null;
        });
    }

    private static String candidateType(String intent) {
        if (ITEM_INTENTS.contains(intent)) return "ITEM";
        if ("LOCATION_CONTENTS".equals(intent)) return "LOCATION";
        if ("KNOWLEDGE_DOCUMENT_READ".equals(intent)) return "DOCUMENT";
        invalid("候选任务类型无效，请重新查询");
        return "";
    }

    private static String candidateKind(String intent) {
        return candidateType(intent);
    }

    private static String effectiveMessage(String intent, String type, boolean resolved,
                                           String code, String name, String mention, String warehouseName) {
        if ("CURRENT_STOCK".equals(intent)) return resolved ? "查询物品「" + name + "」（" + code + "）的当前库存" : "查询物品线索「" + mention + "」的当前库存";
        if ("ITEM_LOCATIONS".equals(intent)) return resolved ? "查询物品「" + name + "」（" + code + "）所在的位置" : "查询物品线索「" + mention + "」所在的位置";
        if ("RECENT_MOVEMENTS".equals(intent)) return resolved ? "查询物品「" + name + "」（" + code + "）的近期库存变化" : "查询物品线索「" + mention + "」的近期库存变化";
        if ("LOCATION_CONTENTS".equals(intent)) return "查询仓库「" + warehouseName + "」的库位「" + name + "」有哪些库存";
        if ("KNOWLEDGE_DOCUMENT_READ".equals(intent)) return "读取知识资料「" + name + "」（" + code + "）的全部内容";
        invalid("候选任务类型无效，请重新查询");
        return "";
    }

    private static void appendPending(String serialized, String selectedMention, Map<String, Object> confirmed) {
        if (serialized == null || serialized.isBlank()) return;
        try {
            JsonNode existing = JSON.readTree(serialized);
            List<String> pending = new ArrayList<>();
            JsonNode values = existing == null ? null : existing.get("pendingMentions");
            if (values != null && values.isArray()) {
                for (JsonNode item : values) if (item != null && item.isTextual()
                        && (selectedMention == null || !selectedMention.equals(item.asText()))) pending.add(item.asText());
            }
            if (!pending.isEmpty()) confirmed.put("pendingMentions", pending);
            JsonNode pendingOptions = existing == null ? null : existing.get("pendingOptions");
            if (pendingOptions != null && pendingOptions.isArray() && pendingOptions.size() > 0) {
                confirmed.put("pendingOptions", JSON.readValue(pendingOptions.toString(), List.class));
            }
        } catch (RuntimeException ignored) {
            // Invalid previous state is rejected by the storage CAS boundary.
        }
    }

    private static List<String> pendingMentions(String conditions) {
        try {
            JsonNode root = JSON.readTree(conditions);
            JsonNode pending = root == null ? null : root.get("pendingMentions");
            if (pending == null || !pending.isArray() || pending.size() == 0 || pending.size() > 5) return List.of();
            List<String> result = new ArrayList<>();
            for (JsonNode value : pending) {
                if (value == null || !value.isTextual() || value.asText().isBlank() || value.asText().length() > 256) {
                    return List.of();
                }
                result.add(value.asText());
            }
            return List.copyOf(result);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private static List<Map<String, Object>> pendingOptions(String conditions) {
        try {
            JsonNode root = JSON.readTree(conditions);
            JsonNode pending = root == null ? null : root.get("pendingOptions");
            if (pending == null || !pending.isArray() || pending.size() == 0 || pending.size() > 5) return List.of();
            List<Map<String, Object>> result = new ArrayList<>();
            for (JsonNode option : pending) {
                if (option == null || !option.isObject()
                        || !option.path("mention").isTextual() || option.path("mention").asText().isBlank()
                        || option.path("mention").asText().length() > 256
                        || !option.path("resolved").isBoolean()) return List.of();
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("mention", option.path("mention").asText());
                value.put("resolved", option.path("resolved").asBoolean());
                value.put("code", option.path("code").asText(option.path("mention").asText()));
                value.put("name", option.path("name").asText(option.path("mention").asText()));
                value.put("baseUnit", option.path("baseUnit").asText(""));
                result.add(value);
            }
            return List.copyOf(result);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private static String pendingCandidateJsonFromMentions(List<String> pending) {
        List<Map<String, Object>> options = new ArrayList<>();
        for (String mention : pending) {
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("code", mention);
            option.put("name", mention);
            option.put("baseUnit", "");
            option.put("mention", mention);
            option.put("resolved", false);
            options.add(option);
        }
        return pendingCandidateJson(options);
    }

    private static String pendingCandidateJson(List<Map<String, Object>> pendingOptions) {
        try {
            List<Map<String, Object>> options = new ArrayList<>();
            for (Map<String, Object> source : pendingOptions) {
                Map<String, Object> option = new LinkedHashMap<>();
                option.put("optionToken", java.util.UUID.randomUUID().toString());
                option.put("code", source.getOrDefault("code", source.get("mention")));
                option.put("name", source.getOrDefault("name", source.get("mention")));
                option.put("baseUnit", source.getOrDefault("baseUnit", ""));
                option.put("mention", source.get("mention"));
                option.put("resolved", Boolean.TRUE.equals(source.get("resolved")));
                options.add(option);
            }
            return JSON.writeValueAsString(options);
        } catch (Exception failure) {
            throw new IllegalStateException("候选生成失败", failure);
        }
    }

    private static Set<String> names(JsonNode object) {
        Set<String> values = new java.util.HashSet<>();
        object.propertyNames().forEach(values::add);
        return values;
    }

    private static String text(JsonNode object, String name, int maxLength, boolean optional) {
        JsonNode value = object.get(name);
        if (value == null || value.isNull()) {
            if (optional) return null;
            invalid("候选格式无效，请重新查询");
        }
        if (!value.isTextual() || (!optional && value.asText().isBlank()) || value.asText().length() > maxLength) {
            invalid("候选格式无效，请重新查询");
        }
        return value.asText();
    }

    private static void invalid(String message) {
        throw new BusinessException(ErrorCode.CONFLICT, message);
    }
}
