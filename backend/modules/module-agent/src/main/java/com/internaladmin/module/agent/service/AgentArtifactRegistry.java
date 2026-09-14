package com.internaladmin.module.agent.service;

import com.internaladmin.module.agent.api.AgentAdapterDescriptor;
import com.internaladmin.module.agent.api.AgentAdapterRegistry;
import com.internaladmin.module.agent.api.AgentErrorCode;
import com.internaladmin.module.agent.api.AgentRunContext;
import com.internaladmin.module.agent.api.AgentToolException;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * In-memory, run-scoped registry for immutable ToolArtifacts.
 *
 * <p>The registry deliberately knows only the static Tool contract and the
 * actor scope.  It never serializes or interprets a producer's private payload;
 * only the producing or consuming Tool receives that object.</p>
 */
public final class AgentArtifactRegistry implements AutoCloseable {
    private static final Duration MAX_TTL = Duration.ofMinutes(15);
    private static final int MAX_SUMMARY_CHARS = 2_000;
    private static final int MAX_ARTIFACTS = 20;
    private static final int MAX_PROJECTION_DEPTH = 4;
    private static final int MAX_PROJECTION_NODES = 64;
    private static final int MAX_PROJECTION_CHARS = 4_000;
    private static final Set<String> FORBIDDEN_PROJECTION_FIELDS = Set.of("artifactId", "privatePayload");

    private final String runId;
    private final Long userId;
    private final AgentAdapterRegistry contracts;
    private final Function<Long, AgentRunContext> actorResolver;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private boolean closed;

    /** Creates a registry using the immutable run actor as the default resolver. */
    public AgentArtifactRegistry(String runId, AgentRunContext actor, AgentAdapterRegistry contracts) {
        this(runId, actor, contracts, ignored -> actor);
    }

    /** Creates a registry whose consumer checks re-resolve the current actor by user ID. */
    public AgentArtifactRegistry(String runId, AgentRunContext actor,
                                 AgentAdapterRegistry contracts,
                                 Function<Long, AgentRunContext> actorResolver) {
        this.runId = requireText(runId, "runId");
        if (actor == null || actor.userId() == null) throw invalid("运行身份无效");
        this.userId = actor.userId();
        this.contracts = contracts == null ? AgentAdapterRegistry.empty() : contracts;
        this.actorResolver = actorResolver == null ? ignored -> actor : actorResolver;
    }

    /**
     * Registers a producer-owned private payload and returns only its safe
     * model-facing projection.  The producer Tool must declare the exact type.
     */
    public synchronized ArtifactRef produce(String producerToolName,
                                             AgentAdapterDescriptor.ArtifactType type,
                                             String producerStepId,
                                             Object privatePayload,
                                             String safeSummary,
                                             Object safeProjection,
                                             Duration ttl) {
        ensureOpen();
        AgentAdapterRegistry.ToolContract contract = contract(producerToolName);
        AgentAdapterDescriptor.ArtifactType declaredType = type == null ? null : contract.produces().stream()
                .filter(candidate -> candidate.key().equals(type.key())).findFirst().orElse(null);
        if (declaredType == null) {
            throw invalid("生产Tool未声明该Artifact类型");
        }
        if (privatePayload == null) throw invalid("Artifact私有载荷不能为空");
        if (entries.size() >= MAX_ARTIFACTS) throw invalid("本次运行Artifact数量超限");
        String step = requireText(producerStepId, "producerStepId");
        String summary = safeSummary == null ? "" : safeSummary;
        if (summary.length() > MAX_SUMMARY_CHARS) throw invalid("Artifact摘要超出预算");
        Duration effectiveTtl = ttl == null ? Duration.ofMinutes(5) : ttl;
        if (effectiveTtl.isNegative() || effectiveTtl.isZero() || effectiveTtl.compareTo(MAX_TTL) > 0) {
            throw invalid("Artifact有效期超出允许范围");
        }
        AgentRunContext actor = resolveActor();
        if (contracts.ownerOf(producerToolName).filter(owner -> owner.isAvailable(actor)).isEmpty()) {
            throw invalid("当前身份无权生产该Artifact");
        }
        Instant createdAt = Instant.now();
        ArtifactMetadata metadata = new ArtifactMetadata(UUID.randomUUID().toString(), runId,
                contract.adapterId(), producerToolName, step, type.type(), type.version(),
                actor.scopeFingerprint(), createdAt, createdAt.plus(effectiveTtl), summary,
                immutableProjection(safeProjection, declaredType.safeProjectionFields()));
        entries.put(metadata.artifactId(), new Entry(metadata, privatePayload));
        return new ArtifactRef(metadata.artifactId(), metadata.artifactType(), metadata.artifactTypeVersion(),
                metadata.safeSummary(), metadata.safeProjection());
    }

    /** Convenience producer overload using the bounded default TTL. */
    public ArtifactRef produce(String producerToolName, AgentAdapterDescriptor.ArtifactType type,
                               String producerStepId, Object privatePayload,
                               String safeSummary, Object safeProjection) {
        return produce(producerToolName, type, producerStepId, privatePayload, safeSummary,
                safeProjection, Duration.ofMinutes(5));
    }

    /**
     * Validates a model-supplied opaque reference before exposing its private
     * payload to the explicitly declared consumer Tool.
     */
    public synchronized ArtifactConsumption consume(String consumerToolName,
                                                     String artifactId,
                                                     AgentAdapterDescriptor.ArtifactType expectedType) {
        ensureOpen();
        AgentAdapterRegistry.ToolContract contract = contract(consumerToolName);
        if (artifactId == null || artifactId.isBlank() || expectedType == null
                || contract.consumes().stream().noneMatch(type -> type.key().equals(expectedType.key()))) {
            throw invalid("消费Tool未声明该Artifact类型");
        }
        Entry entry = entries.get(artifactId);
        if (entry == null) throw invalid("Artifact不属于当前运行");
        ArtifactMetadata metadata = entry.metadata;
        if (!runId.equals(metadata.runId())) throw invalid("Artifact不属于当前运行");
        if (contracts.artifactProducer(expectedType)
                .filter(metadata.producerToolName()::equals).isEmpty()) {
            throw invalid("Artifact生产Tool不匹配");
        }
        if (!metadata.artifactType().equals(expectedType.type())
                || !metadata.artifactTypeVersion().equals(expectedType.version())) {
            throw invalid("Artifact类型或版本不匹配");
        }
        if (Instant.now().isAfter(metadata.expiresAt())) throw invalid("Artifact已过期");
        AgentRunContext actor = resolveActor();
        if (contracts.ownerOf(consumerToolName).filter(owner -> owner.isAvailable(actor)).isEmpty()) {
            throw invalid("当前身份无权消费该Artifact");
        }
        if (!Objects.equals(metadata.scopeFingerprint(), actor.scopeFingerprint())) {
            throw invalid("Artifact权限范围已变化");
        }
        return new ArtifactConsumption(metadata, entry.privatePayload);
    }

    /** Convenience consumer overload for callers that hold type and version separately. */
    public ArtifactConsumption consume(String consumerToolName, String artifactId,
                                       String artifactType, String artifactTypeVersion) {
        return consume(consumerToolName, artifactId,
                new AgentAdapterDescriptor.ArtifactType(artifactType, artifactTypeVersion));
    }

    /** Returns the number of live entries; private payloads are never exposed. */
    public synchronized int size() {
        return entries.size();
    }

    /** Returns whether the registry has entered its terminal closed state. */
    public synchronized boolean isClosed() {
        return closed;
    }

    /** Closes the run registry and releases all private payload references. */
    @Override
    public synchronized void close() {
        closed = true;
        entries.clear();
    }

    private AgentAdapterRegistry.ToolContract contract(String toolName) {
        return contracts.toolContract(toolName).orElseThrow(() -> invalid("Tool未注册Artifact契约"));
    }

    private AgentRunContext resolveActor() {
        AgentRunContext actor;
        try {
            actor = actorResolver.apply(userId);
        } catch (RuntimeException exception) {
            throw invalid("当前身份无法重新解析");
        }
        if (actor == null || !userId.equals(actor.userId())) throw invalid("当前身份无效");
        return actor;
    }

    private void ensureOpen() {
        if (closed) throw invalid("Artifact注册表已关闭");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw invalid(name + "不能为空");
        return value;
    }

    private static AgentToolException invalid(String message) {
        return new AgentToolException(AgentErrorCode.ARTIFACT_INVALID, message);
    }

    private static Object immutableProjection(Object projection, Set<String> allowedFields) {
        if (projection == null) return null;
        if (allowedFields == null || allowedFields.isEmpty()) {
            throw invalid("Artifact安全投影必须声明字段白名单");
        }
        if (!(projection instanceof Map<?, ?>) && !projection.getClass().isRecord()) {
            throw invalid("Artifact安全投影顶层必须是对象");
        }
        ProjectionBudget budget = new ProjectionBudget();
        return sanitizeProjection(projection, Set.copyOf(allowedFields), 0, budget, null);
    }

    private static Object sanitizeProjection(Object value, Set<String> allowedFields,
                                             int depth, ProjectionBudget budget, String fieldName) {
        if (depth > MAX_PROJECTION_DEPTH || ++budget.nodes > MAX_PROJECTION_NODES) {
            throw invalid("Artifact安全投影超出结构预算");
        }
        if (fieldName != null) {
            if (FORBIDDEN_PROJECTION_FIELDS.contains(fieldName) || !allowedFields.contains(fieldName)) {
                throw invalid("Artifact安全投影包含未声明字段");
            }
            budget.chars += fieldName.length();
        }
        if (value == null || value instanceof String || value instanceof Boolean) {
            if (value instanceof String string) budget.chars += string.length();
            ensureProjectionBudget(budget);
            return value;
        }
        if (value instanceof Number number) {
            if (number instanceof Double d && !Double.isFinite(d)
                    || number instanceof Float f && !Float.isFinite(f)) {
                throw invalid("Artifact安全投影包含非有限数字");
            }
            budget.chars += number.toString().length();
            ensureProjectionBudget(budget);
            return number;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw invalid("Artifact安全投影字段名必须为字符串");
                }
                copy.put(key, sanitizeProjection(entry.getValue(), allowedFields, depth + 1, budget, key));
            }
            ensureProjectionBudget(budget);
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> copy = new java.util.ArrayList<>();
            for (Object item : collection) {
                copy.add(sanitizeProjection(item, allowedFields, depth + 1, budget, null));
            }
            ensureProjectionBudget(budget);
            return List.copyOf(copy);
        }
        if (value.getClass().isRecord()) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (java.lang.reflect.RecordComponent component : value.getClass().getRecordComponents()) {
                try {
                    var accessor = component.getAccessor();
                    if (!accessor.canAccess(value)) accessor.setAccessible(true);
                    Object componentValue = accessor.invoke(value);
                    copy.put(component.getName(), sanitizeProjection(componentValue, allowedFields,
                            depth + 1, budget, component.getName()));
                } catch (ReflectiveOperationException exception) {
                    throw invalid("Artifact安全投影无法读取字段");
                }
            }
            ensureProjectionBudget(budget);
            return Collections.unmodifiableMap(copy);
        }
        throw invalid("Artifact安全投影类型不受支持");
    }

    private static void ensureProjectionBudget(ProjectionBudget budget) {
        if (budget.chars > MAX_PROJECTION_CHARS) throw invalid("Artifact安全投影超出大小预算");
    }

    private static final class ProjectionBudget {
        private int nodes;
        private int chars;
    }

    private record Entry(ArtifactMetadata metadata, Object privatePayload) { }

    /** Safe reference returned to a Tool result; it contains no private payload. */
    public record ArtifactRef(String artifactId, String artifactType, String artifactTypeVersion,
                              String safeSummary, Object safeProjection) { }

    /** Metadata visible to a consumer implementation, excluding private payload. */
    public record ArtifactMetadata(String artifactId, String runId, String producerAdapterId,
                                   String producerToolName, String producerStepId,
                                   String artifactType, String artifactTypeVersion,
                                   String scopeFingerprint, Instant createdAt, Instant expiresAt,
                                   String safeSummary, Object safeProjection) { }

    /** Consumer-only view combining safe metadata with the opaque private object. */
    public record ArtifactConsumption(ArtifactMetadata metadata, Object privatePayload) { }
}
