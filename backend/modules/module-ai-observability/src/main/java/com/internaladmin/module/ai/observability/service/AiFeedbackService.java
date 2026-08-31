package com.internaladmin.module.ai.observability.service;

import com.internaladmin.module.ai.observability.api.AiFeedbackApi;
import com.internaladmin.module.ai.observability.api.AiObservabilityQueryApi;
import com.internaladmin.module.ai.observability.api.FeedbackEligibilityApi;
import com.internaladmin.platform.kernel.error.BusinessException;
import com.internaladmin.platform.kernel.error.ErrorCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Owns online feedback and the bounded, content-free administrator view of
 * structured observations.  It deliberately depends on the Agent's narrow
 * eligibility contract rather than its tables.
 */
@Service
@ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
public class AiFeedbackService implements AiFeedbackApi, AiObservabilityQueryApi {
    public static final int FEEDBACK_RETENTION_DAYS = 180;
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_WINDOW_HOURS = 24;
    public static final int MAX_WINDOW_DAYS = 90;

    private static final Set<String> RATINGS = Set.of("HELPFUL", "NOT_HELPFUL");
    private static final Map<String, Set<String>> REASONS = Map.of(
            "HELPFUL", Set.of("ACCURATE", "CLEAR", "ACTIONABLE"),
            "NOT_HELPFUL", Set.of("INCORRECT", "NOT_RELEVANT", "UNCLEAR", "MISSING_INFORMATION"));
    private static final Set<String> STATUSES = Set.of("RUNNING", "SUCCESS", "PARTIAL", "FAILED", "CANCELLED");
    private static final Set<String> OUTCOMES = Set.of("ANSWERED", "CLARIFICATION", "NO_DATA", "NO_EVIDENCE",
            "POLICY_REFUSAL", "DEGRADED", "PARTIAL", "FAILED", "CANCELLED");

    private final JdbcTemplate jdbc;
    private final FeedbackEligibilityApi eligibility;

    public AiFeedbackService(@Qualifier("jdbcTemplate") JdbcTemplate jdbc,
                              FeedbackEligibilityApi eligibility) {
        this.jdbc = jdbc;
        this.eligibility = eligibility;
    }

    @Override
    public Optional<FeedbackSnapshot> findForAssistantMessage(String messageId, Long userId) {
        if (messageId == null || messageId.isBlank() || userId == null) return Optional.empty();
        List<FeedbackSnapshot> rows = jdbc.query("SELECT rating, reason, created_at, updated_at "
                        + "FROM ai_online_feedback WHERE assistant_message_id = ? AND user_id = ?",
                (rs, row) -> new FeedbackSnapshot(rs.getString("rating"), rs.getString("reason"),
                        readInstant(rs, "created_at"), readInstant(rs, "updated_at")), messageId, userId);
        return rows.stream().findFirst();
    }

    @Override
    public Map<String, FeedbackSnapshot> findForAssistantMessages(List<String> messageIds, Long userId) {
        if (userId == null || messageIds == null || messageIds.isEmpty()) return Map.of();
        List<String> ids = messageIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (ids.isEmpty()) return Map.of();
        if (ids.size() > 100 || ids.stream().anyMatch(id -> id.length() > 128)) {
            throw new IllegalArgumentException("反馈查询消息数量或标识超出范围");
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>(ids);
        args.add(0, userId);
        Map<String, FeedbackSnapshot> result = new LinkedHashMap<>();
        jdbc.query("SELECT assistant_message_id, rating, reason, created_at, updated_at "
                        + "FROM ai_online_feedback WHERE user_id = ? AND assistant_message_id IN ("
                        + placeholders + ") ORDER BY assistant_message_id",
                (rs, row) -> {
                    result.put(rs.getString("assistant_message_id"), new FeedbackSnapshot(
                            rs.getString("rating"), rs.getString("reason"),
                            readInstant(rs, "created_at"), readInstant(rs, "updated_at")));
                    return null;
                }, args.toArray());
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    @Override
    @Transactional
    public FeedbackSnapshot upsert(Long userId, String messageId, String rating, String reason) {
        validateRatingReason(rating, reason);
        if (eligibility == null || userId == null || messageId == null || messageId.isBlank()) {
            throw forbiddenTarget();
        }
        FeedbackEligibilityApi.EligibleMessage target = eligibility
                .findEligibleAssistantMessage(messageId, userId).orElseThrow(this::forbiddenTarget);
        Instant now = Instant.now();
        Timestamp timestamp = Timestamp.from(now);
        int updated = jdbc.update("UPDATE ai_online_feedback SET rating = ?, reason = ?, updated_at = ? "
                        + "WHERE user_id = ? AND assistant_message_id = ?",
                rating, reason, timestamp, userId, messageId);
        if (updated == 0) {
            try {
                jdbc.update("INSERT INTO ai_online_feedback(id, run_id, assistant_message_id, conversation_id, user_id, "
                                + "rating, reason, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        UUID.randomUUID().toString(), target.runId(), target.messageId(), target.conversationId(),
                        userId, rating, reason, timestamp, timestamp);
            } catch (DataAccessException race) {
                // A concurrent PUT may have won the unique user/message key.
                // Re-read and update that one row; no duplicate is ever created.
                if (jdbc.update("UPDATE ai_online_feedback SET rating = ?, reason = ?, updated_at = ? "
                                + "WHERE user_id = ? AND assistant_message_id = ?",
                        rating, reason, timestamp, userId, messageId) != 1) {
                    throw race;
                }
            }
        }
        return findForAssistantMessage(messageId, userId).orElseThrow(
                () -> new IllegalStateException("反馈写入后无法读取"));
    }

    @Override
    @Transactional
    public void delete(Long userId, String messageId) {
        if (eligibility == null || userId == null || messageId == null || messageId.isBlank()) {
            throw forbiddenTarget();
        }
        eligibility.findEligibleAssistantMessage(messageId, userId).orElseThrow(this::forbiddenTarget);
        jdbc.update("DELETE FROM ai_online_feedback WHERE user_id = ? AND assistant_message_id = ?",
                userId, messageId);
    }

    public CleanupResult cleanupFeedback(Instant now, int batchSize) {
        if (now == null || batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("反馈清理参数无效");
        Instant cutoff = now.minus(FEEDBACK_RETENTION_DAYS, ChronoUnit.DAYS);
        List<String> ids = jdbc.query(connection -> {
            var statement = connection.prepareStatement("SELECT id FROM ai_online_feedback WHERE updated_at < ? "
                    + "ORDER BY updated_at, id");
            statement.setTimestamp(1, Timestamp.from(cutoff));
            statement.setMaxRows(batchSize);
            return statement;
        }, (org.springframework.jdbc.core.RowMapper<String>) (rs, row) -> rs.getString(1));
        int deleted = 0;
        for (String id : ids) {
            deleted += jdbc.update("DELETE FROM ai_online_feedback WHERE id = ? AND updated_at < ?",
                    id, Timestamp.from(cutoff));
        }
        return new CleanupResult(deleted, cutoff);
    }

    /** Daily bounded cleanup owned solely by the feedback table. */
    @Scheduled(fixedDelay = 86_400_000L, initialDelay = 86_400_000L)
    public void scheduledFeedbackCleanup() {
        cleanupFeedback(Instant.now(), 200);
    }

    @Override
    public Overview overview(RunFilter input) {
        RunFilter filter = normalizeFilter(input);
        List<Object> args = new ArrayList<>();
        String where = buildWhere(filter, args);
        List<Aggregate> aggregates = jdbc.query("SELECT r.status, r.business_outcome, r.error_source, r.error_code, COUNT(*) AS n "
                        + "FROM ai_observation_run r WHERE " + where
                        + " GROUP BY status, business_outcome, error_source, error_code",
                (rs, row) -> new Aggregate(rs.getString("status"), rs.getString("business_outcome"),
                        rs.getString("error_source"), rs.getString("error_code"), rs.getLong("n")), args.toArray());
        Map<String, Long> statuses = new LinkedHashMap<>();
        Map<String, Long> outcomes = new LinkedHashMap<>();
        Map<String, Long> sources = new LinkedHashMap<>();
        Map<String, Long> codes = new LinkedHashMap<>();
        long total = 0;
        for (Aggregate row : aggregates) {
            total += row.count();
            increment(statuses, row.status(), row.count());
            increment(outcomes, row.outcome(), row.count());
            increment(sources, row.errorSource(), row.count());
            increment(codes, row.errorCode(), row.count());
        }
        return new Overview(total, statuses, outcomes, sources, codes);
    }

    @Override
    public RunPage pageRuns(RunFilter input, long page, long size) {
        RunFilter filter = normalizeFilter(input);
        long boundedPage = Math.max(1, page);
        long boundedSize = Math.max(1, Math.min(size <= 0 ? DEFAULT_PAGE_SIZE : size, MAX_PAGE_SIZE));
        List<Object> countArgs = new ArrayList<>();
        String where = buildWhere(filter, countArgs);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM ai_observation_run r WHERE " + where,
                Long.class, countArgs.toArray());
        List<Object> queryArgs = new ArrayList<>(countArgs);
        queryArgs.add(boundedSize);
        queryArgs.add((boundedPage - 1) * boundedSize);
        List<RunSummary> records = jdbc.query("SELECT r.run_id, r.started_at, r.completed_at, r.status, r.business_outcome, "
                        + "r.provider, r.model, r.error_source, r.error_code, r.retry_of_run_id "
                        + "FROM ai_observation_run r "
                        + "WHERE " + where + " ORDER BY r.started_at DESC, r.run_id DESC LIMIT ? OFFSET ?",
                (rs, row) -> new RunSummary(rs.getString("run_id"), readInstant(rs, "started_at"),
                        readInstant(rs, "completed_at"), duration(rs, "started_at", "completed_at"), rs.getString("status"),
                        rs.getString("business_outcome"), rs.getString("provider"), rs.getString("model"),
                        rs.getString("error_source"), rs.getString("error_code"), rs.getString("retry_of_run_id") != null,
                        rs.getString("retry_of_run_id"), null),
                queryArgs.toArray());
        Map<String, FeedbackSummary> feedback = loadFeedbackSummaries(records.stream().map(RunSummary::runId).toList());
        List<RunSummary> enriched = records.stream()
                .map(run -> new RunSummary(run.runId(), run.startedAt(), run.completedAt(), run.durationMs(), run.status(),
                        run.businessOutcome(), run.provider(), run.model(), run.errorSource(), run.errorCode(), run.retry(),
                        run.retryOfRunId(), feedback.get(run.runId())))
                .toList();
        return new RunPage(enriched, total == null ? 0 : total, boundedPage, boundedSize);
    }

    @Override
    public RunTimeline runTimeline(String runId) {
        if (runId == null || runId.isBlank() || runId.length() > 128) throw new IllegalArgumentException("Run标识无效");
        List<RunTimeline> runs = jdbc.query("SELECT r.run_id, r.started_at, r.completed_at, r.status, r.business_outcome, "
                        + "r.provider, r.model, r.error_source, r.error_code "
                        + "FROM ai_observation_run r WHERE r.run_id = ?",
                (rs, row) -> new RunTimeline(rs.getString("run_id"), readInstant(rs, "started_at"),
                        readInstant(rs, "completed_at"), rs.getString("status"), rs.getString("business_outcome"),
                        rs.getString("provider"), rs.getString("model"), rs.getString("error_source"),
                        rs.getString("error_code"), new ArrayList<>(), null), runId);
        if (runs.isEmpty()) throw new BusinessException(ErrorCode.PARAM_ERROR, "运行记录不存在");
        RunTimeline run = runs.get(0);
        FeedbackSummary runFeedback = loadFeedbackSummaries(List.of(runId)).get(runId);
        List<StepTimeline> steps = jdbc.query("SELECT step_id,parent_step_id,sequence_no,step_type,name,iteration_no,tool_name,"
                        + "retrieval_stage,candidate_count,index_version,reference_document_code,reference_version_code,"
                        + "reference_chunk_no,status,duration_ms,error_source,error_code FROM ai_observation_step "
                        + "WHERE run_id = ? ORDER BY sequence_no, step_id",
                (rs, row) -> new StepTimeline(rs.getString("step_id"), rs.getString("parent_step_id"), rs.getInt("sequence_no"),
                        rs.getString("step_type"), rs.getString("name"), nullableInt(rs, "iteration_no"), rs.getString("tool_name"),
                        rs.getString("retrieval_stage"), nullableInt(rs, "candidate_count"), rs.getString("index_version"),
                        rs.getString("reference_document_code"), rs.getString("reference_version_code"), nullableInt(rs, "reference_chunk_no"),
                        rs.getString("status"), nullableLong(rs, "duration_ms"), rs.getString("error_source"),
                        rs.getString("error_code"), new ArrayList<>()), runId);
        if (steps.isEmpty()) return new RunTimeline(run.runId(), run.startedAt(), run.completedAt(), run.status(),
                run.businessOutcome(), run.provider(), run.model(), run.errorSource(), run.errorCode(), List.of(), runFeedback);
        String placeholders = String.join(",", java.util.Collections.nCopies(steps.size(), "?"));
        Object[] stepIds = steps.stream().map(StepTimeline::stepId).toArray();
        Map<String, List<AttemptTimeline>> attempts = new HashMap<>();
        jdbc.query("SELECT attempt_id,step_id,attempt_no,status,duration_ms,error_code FROM ai_observation_attempt "
                        + "WHERE step_id IN (" + placeholders + ") ORDER BY attempt_no, attempt_id",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> attempts.computeIfAbsent(rs.getString("step_id"), ignored -> new ArrayList<>())
                        .add(new AttemptTimeline(rs.getString("attempt_id"), rs.getInt("attempt_no"), rs.getString("status"),
                                nullableLong(rs, "duration_ms"), rs.getString("error_code"))), stepIds);
        List<StepTimeline> enriched = steps.stream().map(step -> new StepTimeline(step.stepId(), step.parentStepId(),
                step.sequenceNo(), step.stepType(), step.name(), step.iterationNo(), step.toolName(), step.retrievalStage(),
                step.candidateCount(), step.indexVersion(), step.referenceDocumentCode(), step.referenceVersionCode(),
                step.referenceChunkNo(), step.status(), step.durationMs(), step.errorSource(), step.errorCode(),
                attempts.getOrDefault(step.stepId(), List.of()))).toList();
        return new RunTimeline(run.runId(), run.startedAt(), run.completedAt(), run.status(), run.businessOutcome(),
                run.provider(), run.model(), run.errorSource(), run.errorCode(), enriched, runFeedback);
    }

    private RunFilter normalizeFilter(RunFilter filter) {
        Instant now = Instant.now();
        Instant to = filter == null || filter.to() == null ? now : filter.to();
        Instant from = filter == null || filter.from() == null ? to.minus(DEFAULT_WINDOW_HOURS, ChronoUnit.HOURS) : filter.from();
        if (from.isAfter(to) || from.isBefore(to.minus(MAX_WINDOW_DAYS, ChronoUnit.DAYS))) {
            throw new IllegalArgumentException("观测时间范围必须在90天内");
        }
        List<String> statuses = filter == null ? List.of() : validateValues(filter.statuses(), STATUSES, "Run状态");
        List<String> outcomes = filter == null ? List.of() : validateValues(filter.businessOutcomes(), OUTCOMES, "业务结果");
        return new RunFilter(from, to, statuses, outcomes, bounded(filter == null ? null : filter.errorSource(), 64),
                bounded(filter == null ? null : filter.errorCode(), 64), bounded(filter == null ? null : filter.provider(), 64),
                bounded(filter == null ? null : filter.model(), 128), bounded(filter == null ? null : filter.toolName(), 128),
                bounded(filter == null ? null : filter.retrievalStage(), 64));
    }

    private String buildWhere(RunFilter filter, List<Object> args) {
        List<String> clauses = new ArrayList<>();
        clauses.add("r.started_at >= ? AND r.started_at < ?");
        args.add(Timestamp.from(filter.from()));
        args.add(Timestamp.from(filter.to()));
        addIn(clauses, args, "r.status", filter.statuses());
        addIn(clauses, args, "r.business_outcome", filter.businessOutcomes());
        addEquals(clauses, args, "r.error_source", filter.errorSource());
        addEquals(clauses, args, "r.error_code", filter.errorCode());
        addEquals(clauses, args, "r.provider", filter.provider());
        addEquals(clauses, args, "r.model", filter.model());
        if (filter.toolName() != null) {
            clauses.add("EXISTS (SELECT 1 FROM ai_observation_step fs WHERE fs.run_id = r.run_id AND fs.tool_name = ?)");
            args.add(filter.toolName());
        }
        if (filter.retrievalStage() != null) {
            clauses.add("EXISTS (SELECT 1 FROM ai_observation_step fr WHERE fr.run_id = r.run_id AND fr.retrieval_stage = ?)");
            args.add(filter.retrievalStage());
        }
        return String.join(" AND ", clauses);
    }

    private static void addIn(List<String> clauses, List<Object> args, String column, List<String> values) {
        if (values == null || values.isEmpty()) return;
        clauses.add(column + " IN (" + String.join(",", java.util.Collections.nCopies(values.size(), "?")) + ")");
        args.addAll(values);
    }

    private static void addEquals(List<String> clauses, List<Object> args, String column, String value) {
        if (value != null && !value.isBlank()) {
            clauses.add(column + " = ?");
            args.add(value);
        }
    }

    private static List<String> validateValues(List<String> values, Set<String> allowed, String label) {
        if (values == null) return List.of();
        if (values.size() > 10 || values.stream().anyMatch(value -> value == null || !allowed.contains(value))) {
            throw new IllegalArgumentException(label + "筛选值无效");
        }
        return List.copyOf(values);
    }

    private static String bounded(String value, int max) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > max) throw new IllegalArgumentException("筛选值过长");
        return value;
    }

    private static void validateRatingReason(String rating, String reason) {
        if (rating == null || !RATINGS.contains(rating) || reason == null
                || !REASONS.getOrDefault(rating, Set.of()).contains(reason)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "反馈选项无效");
        }
    }

    private BusinessException forbiddenTarget() {
        return new BusinessException(ErrorCode.FORBIDDEN, "只能评价本人已完成的助手回答");
    }

    private static void increment(Map<String, Long> values, String key, long count) {
        if (key != null && !key.isBlank()) values.merge(key, count, Long::sum);
    }

    private Map<String, FeedbackSummary> loadFeedbackSummaries(List<String> runIds) {
        if (runIds == null || runIds.isEmpty()) return Map.of();
        List<String> ids = runIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        if (ids.size() > MAX_PAGE_SIZE || ids.stream().anyMatch(id -> id.length() > 128)) {
            throw new IllegalArgumentException("反馈聚合范围无效");
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<FeedbackAggregateRow> rows = jdbc.query(
                "SELECT run_id, rating, reason, COUNT(*) AS n FROM ai_online_feedback "
                        + "WHERE run_id IN (" + placeholders + ") GROUP BY run_id, rating, reason "
                        + "ORDER BY run_id, rating, reason",
                (rs, row) -> new FeedbackAggregateRow(rs.getString("run_id"), rs.getString("rating"),
                        rs.getString("reason"), rs.getLong("n")), ids.toArray());
        Map<String, FeedbackAccumulator> accumulators = new LinkedHashMap<>();
        for (FeedbackAggregateRow row : rows) {
            FeedbackAccumulator accumulator = accumulators.computeIfAbsent(row.runId(), ignored -> new FeedbackAccumulator());
            if ("HELPFUL".equals(row.rating())) accumulator.helpful += row.count();
            if ("NOT_HELPFUL".equals(row.rating())) accumulator.notHelpful += row.count();
            if (row.reason() != null && !row.reason().isBlank()) accumulator.reasons.merge(row.reason(), row.count(), Long::sum);
        }
        Map<String, FeedbackSummary> summaries = new LinkedHashMap<>();
        accumulators.forEach((runId, accumulator) -> summaries.put(runId,
                new FeedbackSummary(accumulator.helpful, accumulator.notHelpful,
                        java.util.Collections.unmodifiableMap(new LinkedHashMap<>(accumulator.reasons)))));
        return java.util.Collections.unmodifiableMap(summaries);
    }

    private record FeedbackAggregateRow(String runId, String rating, String reason, long count) {
    }

    private static final class FeedbackAccumulator {
        private long helpful;
        private long notHelpful;
        private final Map<String, Long> reasons = new LinkedHashMap<>();
    }

    private static Instant readInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Object raw = rs.getObject(column);
        if (raw == null) return null;
        if (raw instanceof Timestamp timestamp) return timestamp.toInstant();
        if (raw instanceof Number number) return Instant.ofEpochMilli(number.longValue());
        String text = raw.toString();
        try {
            return Instant.parse(text);
        } catch (java.time.format.DateTimeParseException ignored) {
            try {
                return Instant.ofEpochMilli(Long.parseLong(text));
            } catch (NumberFormatException invalid) {
                throw new java.sql.SQLException("无法解析观测时间");
            }
        }
    }

    private static Long duration(java.sql.ResultSet rs, String start, String end) throws java.sql.SQLException {
        Instant started = readInstant(rs, start);
        Instant completed = readInstant(rs, end);
        return started == null || completed == null ? null : Math.max(0L, Duration.between(started, completed).toMillis());
    }

    private static Integer nullableInt(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private record Aggregate(String status, String outcome, String errorSource, String errorCode, long count) {
    }

    public record CleanupResult(int deleted, Instant cutoff) {
    }
}
