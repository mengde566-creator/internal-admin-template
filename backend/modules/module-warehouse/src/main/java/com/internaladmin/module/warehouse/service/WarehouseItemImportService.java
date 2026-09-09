package com.internaladmin.module.warehouse.service;

import com.internaladmin.module.file.api.ControlledDocumentAsset;
import com.internaladmin.module.file.api.ControlledDocumentFileApi;
import com.internaladmin.module.file.api.ControlledDocumentRead;
import com.internaladmin.module.file.api.ControlledDocumentStoreRequest;
import com.internaladmin.module.file.api.DocumentFilePurpose;
import com.internaladmin.module.iam.api.IamActorApi;
import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.warehouse.api.WarehouseItemImportApi;
import com.internaladmin.module.warehouse.api.WarehouseItemImportCategory;
import com.internaladmin.module.warehouse.api.WarehouseItemImportStatus;
import com.internaladmin.module.warehouse.mapper.ItemMapper;
import com.internaladmin.module.warehouse.mapper.WarehouseItemImportJobMapper;
import com.internaladmin.module.warehouse.mapper.WarehouseItemImportRowMapper;
import com.internaladmin.module.warehouse.model.entity.ItemDO;
import com.internaladmin.module.warehouse.model.entity.WarehouseItemImportJobDO;
import com.internaladmin.module.warehouse.model.entity.WarehouseItemImportRowDO;
import com.internaladmin.platform.kernel.error.BusinessException;
import com.internaladmin.platform.kernel.error.ErrorCode;
import jakarta.annotation.PreDestroy;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.DuplicateHeaderMode;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 物品文件的受控分析预览；本服务不写入 wh_item。 */
@Service
public class WarehouseItemImportService implements WarehouseItemImportApi {
    private static final Logger LOGGER = LoggerFactory.getLogger(WarehouseItemImportService.class);
    private static final int MAX_PAGE = 100;
    private static final int MAX_EXPORT_ROWS = 10_000;
    private static final int MAX_CONFIRM_ROWS = 100_000;
    private static final int BATCH = 200;
    private static final int ANALYSIS_QUEUE_CAPACITY = 16;
    private static final java.time.Duration ANALYSIS_STALE_AFTER = java.time.Duration.ofMinutes(5);
    private static final Pattern CODE = Pattern.compile("[A-Z0-9-]{1,64}");
    private static final Set<String> HEADER_CODE = Set.of("编码", "物品编码", "code", "itemcode");
    private static final Set<String> HEADER_NAME = Set.of("名称", "物品名称", "name", "itemname");
    private static final Set<String> HEADER_UNIT = Set.of("基本单位", "单位", "baseunit", "unit");
    private static final Set<String> HEADER_ENABLED = Set.of("启用状态", "状态", "enabled", "status");
    private final ControlledDocumentFileApi files;
    private final ItemMapper items;
    private final WarehouseService warehouse;
    private final WarehouseItemImportJobMapper jobs;
    private final WarehouseItemImportRowMapper rows;
    private final IamActorApi iamActorApi;
    private final TransactionTemplate transactionTemplate;
    private final ThreadPoolExecutor analysisExecutor;

    @Autowired
    public WarehouseItemImportService(ControlledDocumentFileApi files, ItemMapper items,
                                      WarehouseService warehouse,
                                      WarehouseItemImportJobMapper jobs, WarehouseItemImportRowMapper rows,
                                      IamActorApi iamActorApi, PlatformTransactionManager transactionManager) {
        this(files, items, warehouse, jobs, rows, iamActorApi, transactionManager, null);
    }

    /**
     * 使用可控执行器装配与生产同形的服务，供队列饱和回归测试使用。
     *
     * @param files 受控文件接口
     * @param items 物品Mapper
     * @param warehouse 仓储业务服务
     * @param jobs 导入作业Mapper
     * @param rows 导入行Mapper
     * @param iamActorApi 可信操作者接口
     * @param transactionManager 业务事务管理器
     * @param analysisExecutor 测试提供的分析执行器；为空时创建生产执行器
     */
    WarehouseItemImportService(ControlledDocumentFileApi files, ItemMapper items,
                                WarehouseService warehouse,
                                WarehouseItemImportJobMapper jobs, WarehouseItemImportRowMapper rows,
                                IamActorApi iamActorApi, PlatformTransactionManager transactionManager,
                                ThreadPoolExecutor analysisExecutor) {
        this.files = files; this.items = items; this.warehouse = warehouse;
        this.jobs = jobs; this.rows = rows; this.iamActorApi = iamActorApi;
        this.transactionTemplate = transactionManager == null ? null : new TransactionTemplate(transactionManager);
        this.analysisExecutor = analysisExecutor == null ? new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(ANALYSIS_QUEUE_CAPACITY), runnable -> {
                    Thread thread = new Thread(runnable, "warehouse-item-import-analysis");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy()) : analysisExecutor;
    }

    @Override
    public WarehouseItemImportJobView submit(Long userId, String clientRequestId, String filename,
                                             String contentType, java.io.InputStream content) {
        requireManager(userId); clientRequestId = normalizeRequestId(clientRequestId);
        WarehouseItemImportJobDO existing = jobs.findByRequest(userId, clientRequestId);
        if (existing != null) return view(owned(userId, existing.getJobId()));
        if (content == null || filename == null || filename.isBlank()) throw bad("文件不能为空");
        String lower = filename.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".xlsx") || lower.endsWith(".csv"))) throw bad("仅支持 xlsx 或 csv 文件");
        ControlledDocumentAsset asset;
        try {
            asset = files.store(new ControlledDocumentStoreRequest(userId, DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT,
                    filename, contentType, content));
        } catch (RuntimeException ex) { throw ex; }
        LocalDateTime now = LocalDateTime.now();
        WarehouseItemImportJobDO job = new WarehouseItemImportJobDO();
        job.setJobId(UUID.randomUUID().toString()); job.setClientRequestId(clientRequestId); job.setCreatorUserId(userId);
        job.setFileAssetId(asset.assetId()); job.setFileSha256(asset.sha256()); job.setStatus(WarehouseItemImportStatus.RECEIVED.name());
        job.setRevision(0); job.setCreatedAt(now); job.setUpdatedAt(now);
        job.setExpiresAt(asset.expiresAt()); job.setTotalRows(0); zeroCounts(job);
        try {
            if (jobs.insert(job) != 1) throw conflict("导入作业创建失败");
        } catch (DataIntegrityViolationException race) {
            files.discard(asset.assetId(), userId, DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT);
            // A concurrent request with the same owner/request key may have won the
            // database unique constraint after the initial read. Return that winner
            // rather than turning an idempotent retry into an avoidable error.
            WarehouseItemImportJobDO winner = jobs.findByRequest(userId, clientRequestId);
            if (winner != null) return view(owned(userId, winner.getJobId()));
            throw race;
        } catch (RuntimeException failure) {
            files.discard(asset.assetId(), userId, DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT);
            throw failure;
        }
        LOGGER.info("warehouse_item_import stage=job_created jobId={} revision={} assetId={} format={} byteSize={}",
                job.getJobId(), job.getRevision(), asset.assetId(), formatOf(asset.actualContentType()), asset.byteSize());
        LocalDateTime claimedAt = LocalDateTime.now();
        int claimedRevision = job.getRevision() + 1;
        if (jobs.claimAnalysis(job.getJobId(), userId, job.getRevision(), claimedAt) == 1) {
            // The mapper increments revision in the database; keep the local return value aligned
            // so an in-process test double and the real re-read observe the same claimed token.
            job.setStatus(WarehouseItemImportStatus.ANALYZING.name());
            job.setRevision(claimedRevision);
            if (enqueueAnalysis(userId, job.getJobId(), claimedRevision)) {
                LOGGER.info("warehouse_item_import stage=analysis_queued jobId={} revision={} assetId={}",
                        job.getJobId(), claimedRevision, asset.assetId());
            }
        }
        return view(jobs.findOwned(job.getJobId(), userId));
    }

    private boolean enqueueAnalysis(Long userId, String jobId, int revision) {
        try {
            analysisExecutor.execute(() -> analyze(userId, jobId, revision));
            return true;
        } catch (RejectedExecutionException ex) {
            // Queue pressure is recoverable. Return the claim to RECEIVED so a later
            // bounded maintenance pass or the owner can retry it; only validation/fact
            // failures become ANALYSIS_FAILED.
            if (jobs.releaseAnalysisClaimAfterQueueRejection(jobId, userId, revision, LocalDateTime.now()) != 1) {
                throw conflict("分析队列状态已变化，请刷新后重试");
            }
            LOGGER.warn("warehouse_item_import stage=analysis_enqueue_rejected jobId={} revision={} errorCode={} exceptionClass={}",
                    jobId, revision, "IMPORT_ANALYSIS_QUEUE_FULL", ex.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Explicitly retries an interrupted analysis from the current user's page.
     * No background scanner claims jobs: the caller supplies the last revision
     * and the service performs the same owner, permission and TTL checks before
     * a single CAS claim.
     */
    @Override
    public WarehouseItemImportJobView reanalyze(Long userId, String jobId, int revision) {
        requireManager(userId);
        WarehouseItemImportJobDO job = jobs.findOwned(jobId, userId);
        if (job == null) throw new BusinessException(ErrorCode.NOT_FOUND, "导入作业不存在");
        LocalDateTime now = LocalDateTime.now();
        if (job.getExpiresAt() == null || !job.getExpiresAt().isAfter(now)) throw bad("作业已过期");
        int claimRevision = revision;
        if (WarehouseItemImportStatus.ANALYZING.name().equals(job.getStatus())) {
            if (job.getUpdatedAt() == null || !job.getUpdatedAt().isBefore(now.minus(ANALYSIS_STALE_AFTER))) {
                throw conflict("作业仍在分析，请稍后刷新");
            }
        } else if (!WarehouseItemImportStatus.RECEIVED.name().equals(job.getStatus())
                && !WarehouseItemImportStatus.NEEDS_REPREVIEW.name().equals(job.getStatus())) {
            throw conflict("当前作业不可重新分析，请重新上传");
        }
        try {
            // Validate the retained 06A asset before changing the job claim. The
            // worker repeats this owner/purpose/TTL check immediately before
            // parsing, so a revoked or invalid asset never reaches business facts.
            files.read(job.getFileAssetId(), userId, DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT);
        } catch (RuntimeException unavailable) {
            throw bad("受控文件不可用，请重新上传");
        }
        if (WarehouseItemImportStatus.ANALYZING.name().equals(job.getStatus())) {
            if (jobs.recoverStaleAnalysis(jobId, userId, revision, now,
                    now.minus(ANALYSIS_STALE_AFTER)) != 1) throw conflict("作业状态已变化，请刷新后重试");
            claimRevision++;
        }
        if (jobs.claimAnalysis(jobId, userId, claimRevision, now) != 1) {
            throw conflict("作业状态已变化，请刷新后重试");
        }
        int runningRevision = claimRevision + 1;
        if (enqueueAnalysis(userId, jobId, runningRevision)) {
            LOGGER.info("warehouse_item_import stage=analysis_queued jobId={} revision={} assetId={}",
                    jobId, runningRevision, job.getFileAssetId());
        }
        return view(jobs.findOwned(jobId, userId));
    }

    @Override
    public WarehouseItemImportJobView confirm(Long userId, String jobId,
                                              WarehouseItemImportConfirmRequest request) {
        requireManager(userId);
        if (request == null || !request.confirmed()) throw bad("请确认导入摘要");
        if (request.revision() < 0) throw bad("作业版本不合法");
        String confirmRequestId = normalizeRequestId(request.clientRequestId());
        long confirmationStartedAt = System.nanoTime();
        LOGGER.info("warehouse_item_import stage=confirmation_started jobId={} revision={}", jobId, request.revision());
        try {
            WarehouseItemImportJobView result = transactionTemplate == null
                    ? confirmInTransaction(userId, jobId, request, confirmRequestId)
                    : transactionTemplate.execute(status -> confirmInTransaction(userId, jobId, request, confirmRequestId));
            if (result == null) throw new IllegalStateException("确认结果为空");
            // Response serialization happens after commit.  No post-commit
            // failure is allowed to mutate a completed job.
            LOGGER.info("warehouse_item_import stage=confirmation_finished jobId={} revision={} status={} elapsedMs={}",
                    jobId, result.revision(), result.status(), elapsedMs(confirmationStartedAt));
            return result;
        } catch (ConfirmationClaimLostException race) {
            return resolveConfirmationCompetition(userId, jobId, request.revision(), confirmRequestId);
        } catch (ConfirmationWorkException work) {
            RuntimeException failure = work.runtimeCause();
            if (failure instanceof WarehouseItemImportPreconditionException
                    || failure instanceof WarehouseService.ItemImportPreconditionException) {
                return markConfirmationFailure(userId, jobId, request.revision(), confirmRequestId,
                        WarehouseItemImportStatus.NEEDS_REPREVIEW.name(), "IMPORT_REPREVIEW_REQUIRED");
            }
            if (failure instanceof DataAccessException) {
                return markConfirmationFailure(userId, jobId, request.revision(), confirmRequestId,
                        WarehouseItemImportStatus.EXECUTION_FAILED.name(), "IMPORT_DATABASE_UNAVAILABLE");
            }
            return markConfirmationFailure(userId, jobId, request.revision(), confirmRequestId,
                    WarehouseItemImportStatus.EXECUTION_FAILED.name(), confirmationErrorCode(failure));
        } catch (DataAccessException failure) {
            // A failure before the callback can wrap the cause (for example a
            // SQLite write-lock/claim error).  Resolve it through the same
            // PREVIEW_READY CAS; a concurrent COMPLETED result wins, while an
            // unchanged preview receives a visible database failure state.
            return markConfirmationFailure(userId, jobId, request.revision(), confirmRequestId,
                    WarehouseItemImportStatus.EXECUTION_FAILED.name(), "IMPORT_DATABASE_UNAVAILABLE");
        } catch (org.springframework.transaction.TransactionException failure) {
            // This also covers commit/rollback boundary failures.  The CAS
            // helper never overwrites a success that may have committed.
            return markConfirmationFailure(userId, jobId, request.revision(), confirmRequestId,
                    WarehouseItemImportStatus.EXECUTION_FAILED.name(), "IMPORT_DATABASE_UNAVAILABLE");
        }
    }

    private WarehouseItemImportJobView confirmInTransaction(Long userId, String jobId,
                                                             WarehouseItemImportConfirmRequest request,
                                                             String confirmRequestId) {
        WarehouseItemImportJobDO job = owned(userId, jobId);
        String status = job.getStatus();
        if (WarehouseItemImportStatus.COMPLETED.name().equals(status)) return view(job);
        if ((WarehouseItemImportStatus.EXECUTING.name().equals(status)
                || WarehouseItemImportStatus.NEEDS_REPREVIEW.name().equals(status)
                || WarehouseItemImportStatus.EXECUTION_FAILED.name().equals(status))
                && confirmRequestId.equals(job.getConfirmRequestId())) return view(job);
        if (!WarehouseItemImportStatus.PREVIEW_READY.name().equals(status)) {
            throw conflict("当前作业不可确认，请先完成预览");
        }
        if (isExpired(job, LocalDateTime.now())) throw bad("作业已过期");
        if (nz(job.getInvalidCount()) > 0 || nz(job.getConflictCount()) > 0) {
            throw bad("仍有未处理异常行，不能确认导入");
        }
        if (jobs.claimConfirmation(jobId, userId, request.revision(), confirmRequestId, LocalDateTime.now()) != 1) {
            throw new ConfirmationClaimLostException();
        }
        int executionRevision = request.revision() + 1;
        try {
            requireManager(userId);
            ControlledDocumentRead read = files.read(job.getFileAssetId(), userId, DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT);
            if (read == null || read.metadata() == null || !job.getFileSha256().equals(read.metadata().sha256())) {
                throw new WarehouseItemImportPreconditionException("受控文件内容已变化");
            }
            List<WarehouseItemImportRowDO> activeRows = rows.findActiveForConfirm(jobId);
            if (activeRows == null || activeRows.size() > MAX_CONFIRM_ROWS) {
                throw new WarehouseItemImportPreconditionException("预览行范围无效");
            }
            Counts counts = Counts.of(activeRows);
            int persistedActive = nz(job.getCreateCount()) + nz(job.getUpdateCount())
                    + nz(job.getDisableCount()) + nz(job.getUnchangedCount())
                    + nz(job.getInvalidCount()) + nz(job.getConflictCount());
            if (activeRows.size() != persistedActive) {
                throw new WarehouseItemImportPreconditionException("预览行摘要已变化");
            }
            if (counts.invalid > 0 || counts.conflict > 0) {
                throw new WarehouseItemImportPreconditionException("仍有未处理异常行");
            }
            List<WarehouseService.ItemImportCommand> commands = activeRows.stream()
                    .map(r -> new WarehouseService.ItemImportCommand(r.getCategory(), r.getCode(), r.getName(),
                            r.getBaseUnit(), Integer.valueOf(1).equals(r.getEnabled()), r.getCurrentVersion(), r.getCurrentEnabled()))
                    .toList();
            requireManager(userId);
            warehouse.executeItemImportBatch(userId, commands);
            // The retain update uses the same business DataSource/transaction.
            // Filesystem state is never treated as a database commit marker.
            files.retain(job.getFileAssetId(), userId, DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT);
            LocalDateTime completedAt = LocalDateTime.now();
            if (jobs.finishConfirmation(jobId, userId, executionRevision,
                    WarehouseItemImportStatus.COMPLETED.name(), completedAt, completedAt, null,
                    nz(job.getTotalRows()), counts.create, counts.update, counts.disable,
                    counts.unchanged, counts.invalid, counts.conflict) != 1) {
                throw conflict("导入作业状态已变化");
            }
            return view(jobs.findOwned(jobId, userId));
        } catch (RuntimeException failure) {
            throw new ConfirmationWorkException(failure);
        }
    }

    private WarehouseItemImportJobView resolveConfirmationCompetition(Long userId, String jobId, int expectedRevision,
                                                                       String confirmRequestId) {
        WarehouseItemImportJobDO latest = jobs.findOwned(jobId, userId);
        if (latest == null) throw new BusinessException(ErrorCode.NOT_FOUND, "导入作业不存在");
        if (WarehouseItemImportStatus.COMPLETED.name().equals(latest.getStatus())
                || ((WarehouseItemImportStatus.NEEDS_REPREVIEW.name().equals(latest.getStatus())
                || WarehouseItemImportStatus.EXECUTION_FAILED.name().equals(latest.getStatus()))
                && confirmRequestId.equals(latest.getConfirmRequestId()))) {
            return view(latest);
        }
        if (WarehouseItemImportStatus.PREVIEW_READY.name().equals(latest.getStatus())
                && Integer.valueOf(expectedRevision).equals(latest.getRevision())) {
            // The competing transaction rolled back; the original request may
            // safely be retried from the unchanged PREVIEW_READY revision.
            return view(latest);
        }
        throw conflict("导入确认正在被其他请求处理，请刷新后重试");
    }

    private WarehouseItemImportJobView markConfirmationFailure(Long userId, String jobId, int revision,
                                                                String confirmRequestId, String status,
                                                                String errorCode) {
        LocalDateTime now = LocalDateTime.now();
        int updated = jobs.markConfirmationFailure(jobId, userId, revision, confirmRequestId, status, now, errorCode);
        WarehouseItemImportJobDO current = jobs.findOwned(jobId, userId);
        if (updated == 1) {
            LOGGER.warn("warehouse_item_import stage=confirmation_failed jobId={} revision={} status={} errorCode={}",
                    jobId, revision, status, errorCode);
            return view(current);
        }
        if (current != null && WarehouseItemImportStatus.COMPLETED.name().equals(current.getStatus())) {
            // Another executor committed successfully; never overwrite it with
            // a late failure from this request.
            return view(current);
        }
        if (current != null && (WarehouseItemImportStatus.NEEDS_REPREVIEW.name().equals(current.getStatus())
                || WarehouseItemImportStatus.EXECUTION_FAILED.name().equals(current.getStatus()))
                && confirmRequestId.equals(current.getConfirmRequestId())) return view(current);
        // Any other state means another owner or lifecycle transition won the
        // race. Do not return a stale EXECUTING/CANCELLED view as if this
        // request had a terminal result; make the competition explicit.
        if (current != null && !WarehouseItemImportStatus.PREVIEW_READY.name().equals(current.getStatus())) {
            throw conflict("导入确认状态已变化，请刷新后重试");
        }
        throw conflict("导入确认状态已变化，请刷新后重试");
    }

    private static final class ConfirmationClaimLostException extends RuntimeException { }

    private static final class ConfirmationWorkException extends RuntimeException {
        private ConfirmationWorkException(Throwable cause) { super(cause); }
        private RuntimeException runtimeCause() {
            return getCause() instanceof RuntimeException runtime ? runtime : new IllegalStateException(getCause());
        }
    }

    private static String confirmationErrorCode(RuntimeException failure) {
        if (failure instanceof BusinessException business
                && business.getErrorCode() == ErrorCode.FORBIDDEN) return "IMPORT_PERMISSION_REVOKED";
        String message = failure.getMessage() == null ? "" : failure.getMessage();
        if (message.contains("受控文件") || message.contains("文件内容")) return "IMPORT_FILE_UNAVAILABLE";
        if (message.contains("权限") || message.contains("登录")) return "IMPORT_PERMISSION_REVOKED";
        return "IMPORT_EXECUTION_FAILED";
    }

    private static final class WarehouseItemImportPreconditionException extends RuntimeException {
        private WarehouseItemImportPreconditionException(String message) { super(message); }
    }

    private void analyze(Long userId, String jobId, int revision) {
        long startedAt = System.nanoTime();
        String assetId = null;
        String format = "UNKNOWN";
        String analysisStage = "job_state";
        List<SourceRow> source;
        try {
            WarehouseItemImportJobDO job = jobs.findOwned(jobId, userId);
            if (job == null || !WarehouseItemImportStatus.ANALYZING.name().equals(job.getStatus()) || job.getRevision() != revision) return;
            assetId = job.getFileAssetId();
            LOGGER.info("warehouse_item_import stage=analysis_started jobId={} revision={} assetId={}", jobId, revision, assetId);
            analysisStage = "authorization";
            try {
                // The creator's persisted identity is not an authorization
                // snapshot. Re-resolve IAM immediately before reading the file
                // and again no later than the next business-fact boundary.
                requireManager(userId);
            } catch (BusinessException revoked) {
                markAnalysisFailure(jobId, userId, revision, assetId, "authorization", "IMPORT_PERMISSION_REVOKED", revoked, startedAt);
                return;
            } catch (RuntimeException iamFailure) {
                markAnalysisFailure(jobId, userId, revision, assetId, "authorization", "IMPORT_PERMISSION_UNAVAILABLE", iamFailure, startedAt);
                return;
            }
            analysisStage = "file_read";
            ControlledDocumentRead read = files.read(assetId, userId, DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT);
            format = read.metadata().originalFilename().toLowerCase(Locale.ROOT).endsWith(".csv") ? "CSV" : "XLSX";
            LOGGER.info("warehouse_item_import stage=file_read jobId={} revision={} assetId={} format={} byteSize={}",
                    jobId, revision, assetId, format, read.content().length);
            analysisStage = "file_parse";
            source = "CSV".equals(format)
                    ? parseCsv(read.content(), jobId, revision, assetId) : parseXlsx(read.content(), jobId, revision, assetId);
            if (source.isEmpty()) throw bad("文件没有可分析的数据行");
            LOGGER.info("warehouse_item_import stage=parsed jobId={} revision={} assetId={} format={} totalRows={} elapsedMs={}",
                    jobId, revision, assetId, format, source.size(), elapsedMs(startedAt));
        } catch (DataAccessException ex) {
            markAnalysisFailure(jobId, userId, revision, assetId, analysisStage, "IMPORT_DATABASE_UNAVAILABLE", ex, startedAt);
            return;
        } catch (RuntimeException ex) {
            markAnalysisFailure(jobId, userId, revision, assetId, analysisStage, analysisErrorCode("FILE", ex), ex, startedAt);
            return;
        }
        List<WarehouseItemImportRowDO> analyzed;
        try {
            // Authorization can be revoked while parsing. Do not query item,
            // stock or movement facts until the current actor is still valid.
            requireManager(userId);
        } catch (BusinessException revoked) {
            markAnalysisFailure(jobId, userId, revision, assetId, "authorization", "IMPORT_PERMISSION_REVOKED", revoked, startedAt);
            return;
        } catch (RuntimeException ex) {
            markAnalysisFailure(jobId, userId, revision, assetId, "authorization", "IMPORT_PERMISSION_UNAVAILABLE", ex, startedAt);
            return;
        }
        try {
            analyzed = classify(jobId, userId, source);
        } catch (DataAccessException ex) {
            markAnalysisFailure(jobId, userId, revision, assetId, "classification", "IMPORT_DATABASE_UNAVAILABLE", ex, startedAt);
            return;
        } catch (RuntimeException ex) {
            markAnalysisFailure(jobId, userId, revision, assetId, "classification", "IMPORT_FACTS_UNAVAILABLE", ex, startedAt);
            return;
        }
        try {
            Counts c = Counts.of(analyzed);
            LOGGER.info("warehouse_item_import stage=classified jobId={} revision={} assetId={} totalRows={} createCount={} updateCount={} disableCount={} unchangedCount={} invalidCount={} conflictCount={} elapsedMs={}",
                    jobId, revision, assetId, analyzed.size(), c.create, c.update, c.disable, c.unchanged, c.invalid, c.conflict, elapsedMs(startedAt));
            String status = c.invalid + c.conflict > 0 ? WarehouseItemImportStatus.NEEDS_ATTENTION.name() : WarehouseItemImportStatus.PREVIEW_READY.name();
            persistAnalysis(jobId, userId, revision, analyzed, c);
            LOGGER.info("warehouse_item_import stage=preview_persisted jobId={} revision={} assetId={} status={} totalRows={} createCount={} updateCount={} disableCount={} unchangedCount={} invalidCount={} conflictCount={} elapsedMs={}",
                    jobId, revision, assetId, status, analyzed.size(), c.create, c.update, c.disable, c.unchanged, c.invalid, c.conflict, elapsedMs(startedAt));
        } catch (DataAccessException ex) {
            markAnalysisFailure(jobId, userId, revision, assetId, "preview_persistence", "IMPORT_DATABASE_UNAVAILABLE", ex, startedAt);
        } catch (RuntimeException ex) {
            markAnalysisFailure(jobId, userId, revision, assetId, "preview_persistence", "IMPORT_PERSISTENCE_FAILED", ex, startedAt);
        }
    }

    private void persistAnalysis(String jobId, Long userId, int revision, List<WarehouseItemImportRowDO> analyzed, Counts c) {
        Runnable persist = () -> {
            // A reanalysis of NEEDS_REPREVIEW replaces the prior preview as one
            // transaction.  If parsing/facts/row insertion fails, the old
            // preview remains intact instead of leaving a partial mix.
            rows.deleteByJobId(jobId);
            for (int i = 0; i < analyzed.size(); i += BATCH) {
                int inserted = rows.insertBatch(analyzed.subList(i, Math.min(i + BATCH, analyzed.size())));
                if (inserted != Math.min(BATCH, analyzed.size() - i)) throw new IllegalStateException("预览行写入数量不一致");
            }
            String status = c.invalid + c.conflict > 0 ? WarehouseItemImportStatus.NEEDS_ATTENTION.name() : WarehouseItemImportStatus.PREVIEW_READY.name();
            if (jobs.updateAnalysis(jobId, userId, revision, status, LocalDateTime.now(), null,
                    analyzed.size(), c.create, c.update, c.disable, c.unchanged, c.invalid, c.conflict) != 1) throw conflict("导入作业已被修改");
        };
        if (transactionTemplate == null) persist.run();
        else transactionTemplate.executeWithoutResult(status -> persist.run());
    }

    private void markAnalysisFailure(String jobId, Long userId, int revision, String assetId, String stage,
                                     String code, Throwable failure, long startedAt) {
        LOGGER.warn("warehouse_item_import stage=analysis_failed jobId={} revision={} assetId={} failureStage={} errorCode={} exceptionClass={} elapsedMs={} stateUpdate=attempted",
                jobId, revision, assetId, stage, code,
                failure == null ? "Unknown" : failure.getClass().getSimpleName(), elapsedMs(startedAt));
        if (jobs.markAnalysisFailed(jobId, userId, revision, LocalDateTime.now(), code) == 1) {
            releaseAssetAfterFailure(userId, jobId);
        }
    }

    private void releaseAsset(Long userId, String jobId) {
        WarehouseItemImportJobDO job = jobs.findOwned(jobId, userId);
        if (job != null && job.getFileAssetId() != null) {
            // 06A marks the asset REJECTED before deleting it; any compensation failure is
            // observable there and the asset is never readable as AVAILABLE.
            files.discard(job.getFileAssetId(), userId, DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT);
        }
    }

    /** Analysis failures release once; later retention handling/retry is owned by 06F. */
    private void releaseAssetAfterFailure(Long userId, String jobId) {
        try {
            releaseAsset(userId, jobId);
        } catch (RuntimeException releaseFailure) {
            // The job remains terminal and keeps its asset reference. The
            // failure is deliberately visible in logs; 06F owns any retry.
            LOGGER.error("物品导入资产释放失败，保留作业与资产引用供06F处理 jobId={} exceptionClass={}",
                    jobId, releaseFailure.getClass().getSimpleName());
        }
    }

    private List<WarehouseItemImportRowDO> classify(String jobId, Long userId, List<SourceRow> source) {
        Set<String> codes = source.stream().map(SourceRow::code).filter(c -> c != null && !c.isBlank()).collect(Collectors.toCollection(HashSet::new));
        requireManager(userId);
        Map<String, ItemDO> existing = loadExistingItems(codes);
        Set<String> duplicate = source.stream().map(SourceRow::code).filter(c -> c != null).collect(Collectors.groupingBy(x -> x, Collectors.counting())).entrySet().stream().filter(e -> e.getValue() > 1).map(Map.Entry::getKey).collect(Collectors.toSet());
        Set<Long> ids = existing.values().stream().map(ItemDO::getId).collect(Collectors.toSet());
        WarehouseService.ItemImportFacts facts = inspectFactsInBatches(userId, ids);
        Set<Long> positiveStock = facts.positiveStockItemIds();
        Set<Long> moved = facts.movementItemIds();
        List<WarehouseItemImportRowDO> out = new ArrayList<>();
        for (SourceRow r : source) {
            WarehouseItemImportRowDO row = new WarehouseItemImportRowDO(); row.setRowId(UUID.randomUUID().toString()); row.setJobId(jobId); row.setSourceRowNo(r.rowNo);
            row.setCode(r.code); row.setName(r.name); row.setBaseUnit(r.unit); row.setEnabled(r.enabled); row.setCreatedAt(LocalDateTime.now());
            ItemDO old = existing.get(r.code); row.setCurrentVersion(old == null ? null : old.getVersion()); row.setCurrentEnabled(old == null ? null : old.getEnabled());
            if (r.error != null) { row.setCategory(WarehouseItemImportCategory.INVALID.name()); row.setErrorCode(r.error); row.setRecommendation("修正该行后重新上传"); }
            else if (duplicate.contains(r.code)) { row.setCategory(WarehouseItemImportCategory.CONFLICT.name()); row.setErrorCode("DUPLICATE_ITEM_CODE"); row.setRecommendation("文件内编码必须唯一"); }
            else if (old == null) { row.setCategory(WarehouseItemImportCategory.CREATE.name()); row.setRecommendation("下一阶段可创建物品"); }
            else if (r.enabled == 0 && old.getEnabled() != null && old.getEnabled() == 1 && positiveStock.contains(old.getId())) { row.setCategory(WarehouseItemImportCategory.CONFLICT.name()); row.setErrorCode("ITEM_DISABLE_HAS_STOCK"); row.setRecommendation("存在非零库存，不能停用"); }
            else if (r.unit != null && !r.unit.equals(old.getBaseUnit()) && moved.contains(old.getId())) { row.setCategory(WarehouseItemImportCategory.CONFLICT.name()); row.setErrorCode("BASE_UNIT_HAS_MOVEMENTS"); row.setRecommendation("存在流水，不能修改基本单位"); }
            else if (r.enabled == 0 && old.getEnabled() != null && old.getEnabled() == 1) row.setCategory(WarehouseItemImportCategory.DISABLE.name());
            else if (same(r, old)) row.setCategory(WarehouseItemImportCategory.UNCHANGED.name());
            else row.setCategory(WarehouseItemImportCategory.UPDATE.name());
            out.add(row);
        }
        return out;
    }

    private Map<String, ItemDO> loadExistingItems(Set<String> codes) {
        if (codes.isEmpty()) return Map.of();
        Map<String, ItemDO> existing = new LinkedHashMap<>();
        List<String> ordered = new ArrayList<>(codes);
        ordered.sort(String::compareTo);
        for (int offset = 0; offset < ordered.size(); offset += BATCH) {
            List<String> batch = ordered.subList(offset, Math.min(offset + BATCH, ordered.size()));
            for (ItemDO item : items.selectByCodes(batch)) {
                if (item != null && item.getCode() != null) existing.putIfAbsent(item.getCode(), item);
            }
        }
        return existing;
    }

    private WarehouseService.ItemImportFacts inspectFactsInBatches(Long userId, Set<Long> ids) {
        if (ids.isEmpty()) return new WarehouseService.ItemImportFacts(Set.of(), Set.of());
        Set<Long> positiveStock = new HashSet<>();
        Set<Long> movements = new HashSet<>();
        List<Long> ordered = new ArrayList<>(ids);
        ordered.sort(Long::compareTo);
        for (int offset = 0; offset < ordered.size(); offset += BATCH) {
            Set<Long> batch = new java.util.LinkedHashSet<>(ordered.subList(offset, Math.min(offset + BATCH, ordered.size())));
            requireManager(userId);
            WarehouseService.ItemImportFacts facts = warehouse.inspectItemImportFacts(batch);
            positiveStock.addAll(facts.positiveStockItemIds());
            movements.addAll(facts.movementItemIds());
        }
        return new WarehouseService.ItemImportFacts(Set.copyOf(positiveStock), Set.copyOf(movements));
    }

    private static boolean same(SourceRow r, ItemDO old) { return old != null && r.name.equals(old.getName()) && r.unit.equals(old.getBaseUnit()) && r.enabled == (old.getEnabled() == null ? 1 : old.getEnabled()); }
    private List<SourceRow> parseCsv(byte[] bytes, String jobId, int revision, String assetId) {
        String text = decodeUtf8(bytes); List<SourceRow> out = new ArrayList<>();
        try (CSVParser parser = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true)
                .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW).build()
                .parse(new InputStreamReader(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8))) {
            Map<String,String> mapped = headers(parser.getHeaderMap().keySet());
            Map<String,Integer> indices = new HashMap<>();
            for (Map.Entry<String,String> e : mapped.entrySet()) indices.put(e.getKey(), parser.getHeaderMap().entrySet().stream().filter(x -> norm(x.getKey()).equals(e.getValue())).map(Map.Entry::getValue).findFirst().orElseThrow());
            logHeaderMapping(jobId, revision, assetId, indices);
            for (CSVRecord r : parser) {
                boolean blank = true;
                for (String value : r) {
                    if (!norm(value).isBlank()) {
                        blank = false;
                        break;
                    }
                }
                if (!blank) out.add(sourceCsv((int) r.getRecordNumber() + 1, r, indices));
            }
        } catch (IOException | IllegalArgumentException ex) { throw bad("CSV结构无效"); }
        return out;
    }
    private List<SourceRow> parseXlsx(byte[] bytes, String jobId, int revision, String assetId) {
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            if (wb.getNumberOfSheets() != 1) throw bad("XLSX只能包含一个数据Sheet");
            Sheet s = wb.getSheetAt(0); if (wb.getSheetVisibility(0) != org.apache.poi.ss.usermodel.SheetVisibility.VISIBLE) throw bad("数据Sheet不可见");
            if (s.getNumMergedRegions() > 0) throw bad("不允许合并单元格");
            Row head = s.getRow(0); if (head == null) throw bad("缺少表头");
            Map<String,Integer> h = new LinkedHashMap<>();
            if (head.getZeroHeight()) throw bad("不允许隐藏行");
            int maxColumns = head.getLastCellNum();
            for (int rowIndex = 1; rowIndex <= s.getLastRowNum(); rowIndex++) {
                Row row = s.getRow(rowIndex);
                if (row != null) maxColumns = Math.max(maxColumns, row.getLastCellNum());
            }
            for (int columnIndex = 0; columnIndex < Math.max(0, maxColumns); columnIndex++) {
                if (s.isColumnHidden(columnIndex)) throw bad("不允许隐藏列");
            }
            for (Cell c : head) { if (s.isColumnHidden(c.getColumnIndex())) throw bad("不允许隐藏列"); if (c.getCellType() == CellType.FORMULA) throw bad("表头不能是公式"); String n = norm(c.getStringCellValue()); if (!n.isBlank()) { if (h.put(n, c.getColumnIndex()) != null) throw bad("表头重复"); } }
            Map<String,String> mapped = headers(h.keySet());
            Map<String,Integer> canonicalIndices = new HashMap<>();
            for (Map.Entry<String,String> entry : mapped.entrySet()) {
                Integer index = h.get(entry.getValue());
                if (index != null) canonicalIndices.put(entry.getKey(), index);
            }
            logHeaderMapping(jobId, revision, assetId, canonicalIndices);
            List<SourceRow> out = new ArrayList<>(); DataFormatter f = new DataFormatter();
            for (int i=1;i<=s.getLastRowNum();i++) {
                Row r=s.getRow(i);
                if(r==null) continue;
                if(r.getZeroHeight()) throw bad("不允许隐藏行");
                boolean blank = true;
                for(Cell c:r) {
                    if(s.isColumnHidden(c.getColumnIndex())) throw bad("不允许隐藏列");
                    if(c.getCellType()==CellType.FORMULA) throw bad("不允许公式单元格");
                    if(!f.formatCellValue(c).isBlank()) blank = false;
                }
                if (!blank) out.add(source(i+1,r,h,mapped,f));
            }
            return out;
        } catch (IOException ex) { throw bad("XLSX结构无效"); }
        catch (BusinessException ex) { throw ex; }
        catch (RuntimeException ex) { throw bad("XLSX结构无效"); }
    }
    private static void logHeaderMapping(String jobId, int revision, String assetId, Map<String,Integer> indices) {
        LOGGER.debug("warehouse_item_import stage=header_mapped jobId={} revision={} assetId={} mapping={}",
                jobId, revision, assetId, indices.entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .map(e -> e.getKey() + "->" + e.getValue()).collect(Collectors.joining(",")));
    }
    private static Map<String,String> headers(Collection<String> input) {
        Map<String,String> result = new HashMap<>();
        for (String raw : input) { String n=norm(raw); String field = HEADER_CODE.contains(n)?"code":HEADER_NAME.contains(n)?"name":HEADER_UNIT.contains(n)?"unit":HEADER_ENABLED.contains(n)?"enabled":null; if(field!=null && result.put(field,n)!=null) throw bad("列映射有歧义"); }
        if (!result.containsKey("code") || !result.containsKey("name") || !result.containsKey("unit") || !result.containsKey("enabled")) throw bad("缺少必填列");
        return result;
    }
    private static SourceRow sourceCsv(int no, CSVRecord r, Map<String,Integer> indices) { return source(no, x -> { Integer i=indices.get(x); return i != null && i < r.size() ? r.get(i) : ""; }, Map.of("code","code","name","name","unit","unit","enabled","enabled")); }
    private static SourceRow source(int no, Row r, Map<String,Integer> raw, Map<String,String> h, DataFormatter f) {
        Map<String,Integer> fieldIndices = new HashMap<>();
        for (Map.Entry<String,String> entry : h.entrySet()) {
            Integer index = raw.get(entry.getValue());
            if (index != null) fieldIndices.put(entry.getKey(), index);
        }
        return source(no, x -> {
            Integer i = fieldIndices.get(x);
            return i == null ? "" : f.formatCellValue(r.getCell(i));
        }, Map.of("code", "code", "name", "name", "unit", "unit", "enabled", "enabled"));
    }
    private interface Value { String get(String name); }
    private static SourceRow source(int no, Value v, Map<String,String> h) {
        String rawCode=v.get(h.get("code")), rawName=v.get(h.get("name")), rawUnit=v.get(h.get("unit")), rawState=v.get(h.get("enabled"));
        // NFKC is intentionally limited to headers. Business cell values are only
        // trimmed/collapsed; silently converting a full-width code or unit would
        // change the user's submitted business value.
        String code=cellNorm(rawCode), name=cellNorm(rawName), unit=cellNorm(rawUnit), state=cellNorm(rawState); Integer enabled = parseEnabled(state);
        if (isFormula(rawCode) || isFormula(rawName) || isFormula(rawUnit) || isFormula(rawState)) return new SourceRow(no,code,name,unit,enabled,"FORMULA_CELL");
        String error=null; if(code.isBlank()||!CODE.matcher(code).matches()) error="INVALID_ITEM_CODE"; else if(name.isBlank()||name.length()>120) error="INVALID_ITEM_NAME"; else if(unit.isBlank()||unit.length()>32) error="INVALID_BASE_UNIT"; else if(enabled==null) error="INVALID_ENABLED";
        return new SourceRow(no,code,name,unit,enabled,error);
    }
    private static boolean isFormula(String s){return s != null && !s.isBlank() && "=+-@".indexOf(s.trim().charAt(0)) >= 0;}
    private static Integer parseEnabled(String state){ if ("启用".equals(state)||"1".equals(state)||"true".equalsIgnoreCase(state)) return Integer.valueOf(1); if ("停用".equals(state)||"0".equals(state)||"false".equalsIgnoreCase(state)) return Integer.valueOf(0); return null; }
    private static String norm(String s) {
        if (s == null) return "";
        String value = s.startsWith("\uFEFF") ? s.substring(1) : s;
        return Normalizer.normalize(value, Normalizer.Form.NFKC).trim().replaceAll("\\s+", " ");
    }
    private static String cellNorm(String s) { return s == null ? "" : s.trim().replaceAll("\\s+", " "); }
    private static String decodeUtf8(byte[] bytes) { try { String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString(); if (text.indexOf('\0') >= 0 || text.chars().anyMatch(c -> c < 0x20 && c != '\r' && c != '\n' && c != '\t')) throw bad("CSV包含非法控制字符"); return text; } catch (CharacterCodingException ex) { throw bad("CSV必须使用UTF-8编码"); } }

    @Override public WarehouseItemImportJobView get(Long userId,String jobId){ requireManager(userId); return view(owned(userId,jobId)); }
    @Override public List<WarehouseItemImportJobView> list(Long userId,int page,int size){
        requireManager(userId);
        int p=Math.max(1,page), s=Math.min(MAX_PAGE,Math.max(1,size));
        // Listing is a read-only user view. Reanalysis and retention handling
        // are explicit operations and never a side effect of opening a page.
        return jobs.pageOwned(userId,(p-1)*s,s).stream().map(this::view).toList();
    }
    @Override public List<WarehouseItemImportRowView> rows(Long userId,String jobId,WarehouseItemImportCategory category,int page,int size){
        requireManager(userId);
        WarehouseItemImportJobDO job = owned(userId,jobId);
        if (isExpired(job, LocalDateTime.now())) throw bad("作业已过期");
        if (!WarehouseItemImportStatus.PREVIEW_READY.name().equals(job.getStatus())
                && !WarehouseItemImportStatus.NEEDS_ATTENTION.name().equals(job.getStatus())) {
            throw bad("当前作业尚无可查看的预览");
        }
        int p=Math.max(1,page),s=Math.min(MAX_PAGE,Math.max(1,size));
        return rows.page(jobId,category==null?null:category.name(),(p-1)*s,s).stream().map(this::rowView).toList();
    }
    @Override public WarehouseItemImportJobView cancel(Long userId,String jobId,int revision){
        requireManager(userId);
        WarehouseItemImportJobDO j=owned(userId,jobId);
        if(isExpired(j, LocalDateTime.now())) throw bad("作业已过期");
        LocalDateTime cancelledAt = LocalDateTime.now();
        if(jobs.cancelCas(jobId,userId,revision,cancelledAt)!=1) throw conflict("作业状态已变化");
        try {
            releaseAsset(userId, jobId);
        } catch (RuntimeException releaseFailure) {
            // Cancellation is already terminal and must remain so. Persist a
            // stable, user-visible diagnostic through a revision CAS instead
            // of logging the failure and returning an opaque generic error.
            WarehouseItemImportJobDO cancelled = jobs.findOwned(jobId, userId);
            int cancelledRevision = cancelled == null || cancelled.getRevision() == null
                    ? revision + 1 : cancelled.getRevision();
            int marked = jobs.markFileReleaseFailed(jobId, userId, cancelledRevision,
                    LocalDateTime.now(), "IMPORT_FILE_RELEASE_FAILED");
            if (marked != 1) {
                LOGGER.error("取消作业后资产释放失败且无法记录稳定错误码 jobId={} exceptionClass={}",
                        jobId, releaseFailure.getClass().getSimpleName());
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件释放失败，作业状态请稍后刷新");
            }
            return view(owned(userId, jobId));
        }
        return view(owned(userId,jobId));
    }
    @Override @Transactional
    public WarehouseItemImportJobView excludeRow(Long userId, String jobId, int sourceRowNo, int revision) {
        requireManager(userId);
        WarehouseItemImportJobDO job = owned(userId, jobId);
        if (!WarehouseItemImportStatus.NEEDS_ATTENTION.name().equals(job.getStatus())) throw bad("当前作业没有可排除的异常行");
        LocalDateTime now = LocalDateTime.now();
        if (isExpired(job, now)) throw bad("作业已过期");
        if (jobs.bumpRevisionForExclusion(jobId, userId, revision, now) != 1) throw conflict("作业状态已变化");
        if (rows.markExcluded(jobId, sourceRowNo) != 1) throw conflict("异常行已处理或不存在");
        Counts effective = Counts.ofActive(rows.countActiveByCategory(jobId));
        String status = effective.invalid + effective.conflict > 0 ? WarehouseItemImportStatus.NEEDS_ATTENTION.name() : WarehouseItemImportStatus.PREVIEW_READY.name();
        if (jobs.updateExclusionSummary(jobId, userId, revision + 1, status, now, job.getTotalRows() == null ? 0 : job.getTotalRows(),
                effective.create, effective.update, effective.disable, effective.unchanged, effective.invalid, effective.conflict) != 1) {
            throw conflict("作业状态已变化");
        }
        return view(owned(userId, jobId));
    }

    @Override public byte[] template(Long userId){ requireManager(userId); try(Workbook wb=new XSSFWorkbook(); ByteArrayOutputStream out=new ByteArrayOutputStream()){ Sheet s=wb.createSheet("物品数据"); String[] h={"物品编码","物品名称","基本单位","启用状态"}; String[] d={"大写字母/数字/短横线","业务名称，可重名","例如：件","启用或停用"}; Drawing<?> drawing=s.createDrawingPatriarch(); for(int i=0;i<h.length;i++){hrCell(s,i,h[i],d[i],drawing); drCell(s,i);} s.createFreezePane(0,1); wb.write(out); return out.toByteArray(); } catch(IOException ex){throw bad("模板生成失败");} }
    private static void hrCell(Sheet s,int i,String header,String description,Drawing<?> drawing){Row hr=s.getRow(0);if(hr==null)hr=s.createRow(0);Cell cell=hr.createCell(i);cell.setCellValue(header);org.apache.poi.ss.usermodel.ClientAnchor anchor=new org.apache.poi.xssf.usermodel.XSSFClientAnchor(0,0,0,0,i,0,i+2,2);Comment c=drawing.createCellComment(anchor);c.setString(new org.apache.poi.xssf.usermodel.XSSFRichTextString(description));cell.setCellComment(c);s.setColumnWidth(i,Math.max(14,header.length()*2)*256);}
    private static void drCell(Sheet s,int i){Row dr=s.getRow(1);if(dr==null)dr=s.createRow(1);dr.createCell(i).setCellValue("");}
    @Override public byte[] export(Long userId,String keyword){
        requireManager(userId);
        String k=norm(keyword);
        long total=items.countExport(k.isBlank()?null:k,"%"+escape(k)+"%");
        if(total==0) throw bad("当前筛选没有可导出的物品");
        if(total>MAX_EXPORT_ROWS) throw bad("导出数据超过上限，请缩小筛选范围");
        StringBuilder b=new StringBuilder("物品编码,物品名称,基本单位,启用状态\r\n");
        long emitted = 0;
        for(int off=0;off<total;off+=BATCH) {
            int expected = Math.min(BATCH, (int) total - off);
            List<ItemDO> page = items.selectExportPage(k.isBlank()?null:k,"%"+escape(k)+"%",off,expected);
            if (page.size() != expected) throw conflict("导出数据在读取期间发生变化，请重试");
            for(ItemDO i:page) {
                b.append(csv(safe(i.getCode()))).append(',').append(csv(safe(i.getName()))).append(',')
                        .append(csv(safe(i.getBaseUnit()))).append(',')
                        .append(i.getEnabled()!=null&&i.getEnabled()==1?"启用":"停用").append("\r\n");
                emitted++;
            }
        }
        if (emitted != total) throw conflict("导出数据在读取期间发生变化，请重试");
        // 导出是受控同步下载：本切片不创建结果资产，避免产生无下载句柄的残留。
        return ("\uFEFF"+b).getBytes(StandardCharsets.UTF_8);
    }
    private static String escape(String s){return s.replace("!","!!").replace("%","!%").replace("_","!_");}
    /** Protect spreadsheet exports even when a dangerous prefix is hidden behind whitespace/control characters. */
    private static String safe(String s){
        if (s == null || s.isEmpty()) return s;
        int i = 0;
        while (i < s.length() && (Character.isWhitespace(s.charAt(i)) || Character.isISOControl(s.charAt(i)))) i++;
        return i < s.length() && "=+-@".indexOf(s.charAt(i)) >= 0 ? "'" + s : s;
    }
    private static String csv(String s){return "\""+s.replace("\"","\"\"")+"\"";}
    private WarehouseItemImportJobDO owned(Long userId,String id){
        requireUser(userId);
        WarehouseItemImportJobDO j=jobs.findOwned(id,userId);
        if(j==null) throw new BusinessException(ErrorCode.NOT_FOUND,"导入作业不存在");
        return j;
    }
    private void requireManager(Long id){ requireUser(id); var actor=iamActorApi.resolve(id); if(actor==null||!id.equals(actor.getUserId())||!actor.getAuthorities().contains(PermissionCodes.WAREHOUSE_MASTER_MANAGE)) throw new BusinessException(ErrorCode.FORBIDDEN,"缺少仓储主数据管理权限"); }
    private static void requireUser(Long id){if(id==null||id<=0) throw new BusinessException(ErrorCode.UNAUTHORIZED,"未登录或登录已失效");} private static String normalizeRequestId(String id){if(id==null||!id.matches("[A-Za-z0-9._:-]{1,100}")) throw bad("clientRequestId不合法");return id;} private static BusinessException bad(String m){return new BusinessException(ErrorCode.PARAM_ERROR,m);} private static BusinessException conflict(String m){return new BusinessException(ErrorCode.CONFLICT,m);}
    private static String analysisErrorCode(String phase, RuntimeException ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage();
        if ("FILE".equals(phase) && (message.contains("受控文件") || message.contains("已失效") || message.contains("不存在"))) return "FILE_READ_FAILED";
        if (message.contains("UTF-8")) return "FILE_ENCODING_INVALID";
        if (message.contains("公式")) return "FILE_FORMULA_REJECTED";
        if (message.contains("列") || message.contains("表头") || message.contains("Sheet") || message.contains("CSV结构")) return "FILE_STRUCTURE_INVALID";
        if (message.contains("文件") || message.contains("XLSX") || message.contains("CSV")) return "FILE_CONTENT_INVALID";
        return "FILE_READ_FAILED";
    }
    private static long elapsedMs(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
    private static String formatOf(String contentType) {
        if (contentType == null) return "UNKNOWN";
        String type = contentType.toLowerCase(Locale.ROOT);
        if (type.contains("spreadsheet") || type.contains("excel")) return "XLSX";
        if (type.contains("csv") || type.contains("comma-separated")) return "CSV";
        return "UNKNOWN";
    }
    private static void zeroCounts(WarehouseItemImportJobDO j){j.setCreateCount(0);j.setUpdateCount(0);j.setDisableCount(0);j.setUnchangedCount(0);j.setInvalidCount(0);j.setConflictCount(0);}
    private WarehouseItemImportJobView view(WarehouseItemImportJobDO j){
        if(j==null) throw new BusinessException(ErrorCode.NOT_FOUND,"导入作业不存在");
        try { WarehouseItemImportStatus.valueOf(j.getStatus()); }
        catch (RuntimeException ex) { throw new BusinessException(ErrorCode.INTERNAL_ERROR,"导入作业状态无效，请稍后诊断"); }
        LocalDateTime now = LocalDateTime.now();
        String status = visibleStatus(j, now);
        boolean reanalyzeAvailable = !isExpired(j, now)
                && (WarehouseItemImportStatus.RECEIVED.name().equals(j.getStatus())
                || WarehouseItemImportStatus.NEEDS_REPREVIEW.name().equals(j.getStatus())
                || (WarehouseItemImportStatus.ANALYZING.name().equals(j.getStatus())
                && j.getUpdatedAt() != null
                && j.getUpdatedAt().isBefore(now.minus(ANALYSIS_STALE_AFTER))));
        String errorCode = j.getErrorCode();
        if (WarehouseItemImportStatus.EXPIRED.name().equals(status)
                && !WarehouseItemImportStatus.EXPIRED.name().equals(j.getStatus())
                && errorCode == null) {
            errorCode = "IMPORT_JOB_EXPIRED";
        }
        int total = nz(j.getTotalRows());
        int excluded = Math.max(0, total - nz(j.getCreateCount()) - nz(j.getUpdateCount()) - nz(j.getDisableCount())
                - nz(j.getUnchangedCount()) - nz(j.getInvalidCount()) - nz(j.getConflictCount()));
        return new WarehouseItemImportJobView(j.getJobId(),status,j.getRevision(),total,nz(j.getCreateCount()),nz(j.getUpdateCount()),nz(j.getDisableCount()),nz(j.getUnchangedCount()),nz(j.getInvalidCount()),nz(j.getConflictCount()),errorCode,j.getCreatedAt(),j.getExpiresAt(),reanalyzeAvailable,excluded,j.getCompletedAt());
    }
    private static boolean isExpired(WarehouseItemImportJobDO job, LocalDateTime now) {
        return job.getExpiresAt() == null || !job.getExpiresAt().isAfter(now);
    }
    private static String visibleStatus(WarehouseItemImportJobDO job, LocalDateTime now) {
        if (isExpired(job, now)
                && !WarehouseItemImportStatus.CANCELLED.name().equals(job.getStatus())
                && !WarehouseItemImportStatus.EXPIRED.name().equals(job.getStatus())) {
            return WarehouseItemImportStatus.EXPIRED.name();
        }
        return job.getStatus();
    }
    private WarehouseItemImportRowView rowView(WarehouseItemImportRowDO r){
        try { WarehouseItemImportCategory.valueOf(r.getCategory()); }
        catch (RuntimeException ex) { throw new BusinessException(ErrorCode.INTERNAL_ERROR,"预览分类无效，请稍后诊断"); }
        return new WarehouseItemImportRowView(r.getSourceRowNo(),r.getCode(),r.getName(),r.getBaseUnit(),r.getEnabled()==null?null:r.getEnabled()==1,r.getCategory(),r.getErrorCode(),r.getRecommendation(),r.getCurrentVersion(),r.getCurrentEnabled()==null?null:r.getCurrentEnabled()==1,r.getExcluded()!=null&&r.getExcluded()==1);
    }
    private static int nz(Integer v){return v==null?0:v;}
    private record SourceRow(int rowNo,String code,String name,String unit,Integer enabled,String error){}
    private record Counts(int create,int update,int disable,int unchanged,int invalid,int conflict){
        static Counts of(List<WarehouseItemImportRowDO> rs){int[] c=new int[6];for(WarehouseItemImportRowDO r:rs)c[WarehouseItemImportCategory.valueOf(r.getCategory()).ordinal()]++;return new Counts(c[0],c[1],c[2],c[3],c[4],c[5]);}
        static Counts ofActive(List<com.internaladmin.module.warehouse.model.dto.WarehouseItemImportRowCount> rs){
            int[] c = new int[6];
            for (var row : rs) {
                if (row == null || row.getCategory() == null) continue;
                try { c[WarehouseItemImportCategory.valueOf(row.getCategory()).ordinal()] = row.getRowCount() == null ? 0 : row.getRowCount(); }
                catch (IllegalArgumentException ignored) { throw new IllegalStateException("未知预览分类"); }
            }
            return new Counts(c[0],c[1],c[2],c[3],c[4],c[5]);
        }
    }

    /**
     * 执行一次06F有界仓储导入维护。
     *
     * 方法：{@code maintainOnce}
     *
     * 执行链路（共 4 步）：
     * 1. 校验维护时间存在且批次大小处于1至100；非法输入抛出 {@link BusinessException}，不扫描数据。
     * 2. 调用 {@link #recoverPending(LocalDateTime, int)} 有界领取待处理或陈旧分析作业，并将解析交给既有单线程队列。
     * 3. 调用 {@link #cleanupExpiredJobs(LocalDateTime, int)} 以修订号CAS收口到期作业、释放未确认资产并删除作业事实。
     * 4. 汇总恢复数、删除数、释放失败数、剩余到期事实及所有权不确定状态并返回，不扩展跨实例治理。
     *
     * @param now 维护时钟
     * @param batchSize 本轮最多扫描的作业数，范围1..100
     * @return 本轮领取分析数、清理作业数及失败释放数
     * @throws BusinessException 维护时间为空或批次大小超出1至100时抛出
     */
    public MaintenanceResult maintainOnce(LocalDateTime now, int batchSize) {
        if (now == null || batchSize < 1 || batchSize > 100) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "维护批次必须在1至100之间");
        }
        int recovered = recoverPending(now, batchSize);
        CleanupCounts cleanup = cleanupExpiredJobs(now, batchSize);
        return new MaintenanceResult(recovered, cleanup.deleted(), cleanup.releaseFailures(), cleanup.moreExpired(),
                cleanup.ownershipUncertain());
    }

    /**
     * 有界恢复待处理及陈旧分析作业。
     *
     * 方法：{@code recoverPending}
     *
     * 执行链路（共 4 步）：
     * 1. 调用 {@link WarehouseItemImportJobMapper#pageMaintenanceCandidates(LocalDateTime, LocalDateTime, int)}
     *    按创建顺序读取本批候选，不读取文件或物品事实。
     * 2. 跳过缺少标识、所有者或修订号的异常投影；陈旧ANALYZING候选先调用
     *    {@link WarehouseItemImportJobMapper#recoverStaleAnalysis(String, Long, int, LocalDateTime, LocalDateTime)}
     *    CAS恢复为RECEIVED，其他非RECEIVED状态直接跳过。
     * 3. 调用 {@link WarehouseItemImportJobMapper#claimAnalysis(String, Long, int, LocalDateTime)} 领取仍有效的作业，
     *    成功后调用 {@link #enqueueAnalysis(Long, String, int)} 提交既有异步解析；队列拒绝时作业恢复为可重试事实。
     * 4. 仅统计成功领取且成功入队的作业并返回。
     *
     * @param now 当前维护时间，用于到期和陈旧边界判断
     * @param batchSize 本轮最多读取的候选数
     * @return 成功领取并提交异步解析的作业数
     * @throws BusinessException 队列拒绝后的作业状态已并发变化、无法恢复为可重试状态时抛出
     */
    private int recoverPending(LocalDateTime now, int batchSize) {
        List<WarehouseItemImportJobDO> candidates = jobs.pageMaintenanceCandidates(now,
                now.minus(ANALYSIS_STALE_AFTER), batchSize);
        int recovered = 0;
        for (WarehouseItemImportJobDO candidate : candidates) {
            if (candidate == null || candidate.getJobId() == null || candidate.getCreatorUserId() == null
                    || candidate.getRevision() == null) continue;
            int expectedRevision = candidate.getRevision();
            if (WarehouseItemImportStatus.ANALYZING.name().equals(candidate.getStatus())) {
                if (jobs.recoverStaleAnalysis(candidate.getJobId(), candidate.getCreatorUserId(), expectedRevision,
                        now, now.minus(ANALYSIS_STALE_AFTER)) != 1) continue;
                expectedRevision++;
            } else if (!WarehouseItemImportStatus.RECEIVED.name().equals(candidate.getStatus())) {
                continue;
            }
            if (jobs.claimAnalysis(candidate.getJobId(), candidate.getCreatorUserId(), expectedRevision, now) == 1
                    && enqueueAnalysis(candidate.getCreatorUserId(), candidate.getJobId(), expectedRevision + 1)) {
                recovered++;
            }
        }
        return recovered;
    }

    /**
     * 有界清理到期仓储导入作业及其未确认资产。
     *
     * 方法：{@code cleanupExpiredJobs}
     *
     * 执行链路（共 6 步）：
     * 1. 调用 {@link WarehouseItemImportJobMapper#pageExpiredForMaintenance(LocalDateTime, int)} 多读取一条前瞻记录，
     *    判断本批结束后是否仍有到期所有者事实。
     * 2. 逐项重新读取所有者作业并复核标识、所有者和到期时间；事实缺失或变化时记录所有权不确定并跳过。
     * 3. COMPLETED作业调用 {@link #deleteCompletedExpired(WarehouseItemImportJobDO, LocalDateTime)} 仅删除预览行和作业，
     *    保留已转为结果资产的原文件。
     * 4. 其他到期作业调用 {@link WarehouseItemImportJobMapper#claimExpiredForMaintenance(String, Long, int, LocalDateTime)}
     *    CAS收口为EXPIRED；领取竞争失败时不释放资产。
     * 5. 领取成功后调用 {@link #releaseAsset(Long, String)} 释放未确认资产；失败时调用
     *    {@link WarehouseItemImportJobMapper#markMaintenanceReleaseFailed(String, Long, int, LocalDateTime)}
     *    保留引用和稳定诊断，供后续轮次重试。
     * 6. 资产释放成功后调用 {@link #deleteExpiredJob(WarehouseItemImportJobDO, int)} 删除预览行和作业，
     *    最终返回删除数、释放失败数、前瞻状态和所有权不确定状态。
     *
     * @param now 当前维护时间，用于到期复核和CAS
     * @param batchSize 本轮最多处理的到期作业数
     * @return 本轮清理计数及是否仍有未收口所有者事实
     * @throws IllegalStateException 已领取作业在同一删除事务中发生并发变化时抛出
     */
    private CleanupCounts cleanupExpiredJobs(LocalDateTime now, int batchSize) {
        int deleted = 0;
        int releaseFailures = 0;
        boolean ownershipUncertain = false;
        // Read one bounded look-ahead row so the app layer can avoid file-module
        // cleanup while an expired job still owns an asset outside this batch.
        List<WarehouseItemImportJobDO> candidates = jobs.pageExpiredForMaintenance(now, Math.min(101, batchSize + 1));
        boolean moreExpired = candidates.size() > batchSize;
        for (WarehouseItemImportJobDO candidate : candidates) {
            if (deleted + releaseFailures >= batchSize) break;
            if (candidate == null || candidate.getJobId() == null || candidate.getCreatorUserId() == null) {
                ownershipUncertain = true;
                continue;
            }
            WarehouseItemImportJobDO current = jobs.findOwned(candidate.getJobId(), candidate.getCreatorUserId());
            if (current == null || current.getExpiresAt() == null || current.getExpiresAt().isAfter(now)) {
                ownershipUncertain = true;
                continue;
            }
            if (WarehouseItemImportStatus.COMPLETED.name().equals(current.getStatus())) {
                if (deleteCompletedExpired(current, now)) deleted++;
                else ownershipUncertain = true;
                continue;
            }
            if (current.getRevision() == null || jobs.claimExpiredForMaintenance(current.getJobId(),
                    current.getCreatorUserId(), current.getRevision(), now) != 1) {
                ownershipUncertain = true;
                continue;
            }
            int cleanupRevision = current.getRevision() + 1;
            try {
                releaseAsset(current.getCreatorUserId(), current.getJobId());
            } catch (RuntimeException releaseFailure) {
                releaseFailures++;
                try {
                    jobs.markMaintenanceReleaseFailed(current.getJobId(), current.getCreatorUserId(),
                            cleanupRevision, now);
                } catch (RuntimeException markFailure) {
                    releaseFailure.addSuppressed(markFailure);
                }
                LOGGER.error("06F仓储导入过期资产释放失败，保留作业引用供下一轮诊断 jobId={} exceptionClass={}",
                        current.getJobId(), releaseFailure.getClass().getSimpleName());
                continue;
            }
            if (deleteExpiredJob(current, cleanupRevision)) deleted++;
        }
        return new CleanupCounts(deleted, releaseFailures, moreExpired, ownershipUncertain);
    }

    /**
     * 在业务事务中删除已完成且到期的作业事实。
     *
     * 方法：{@code deleteCompletedExpired}
     *
     * 执行链路（共 3 步）：
     * 1. 调用 {@link WarehouseItemImportRowMapper#deleteByJobId(String)} 删除该作业的预览行。
     * 2. 调用 {@link WarehouseItemImportJobMapper#deleteCompletedExpired(String, Long, LocalDateTime)} 删除仍为COMPLETED且到期的作业；
     *    删除竞争失败时抛出异常并回滚预览行删除。
     * 3. 有事务管理器时通过 {@link TransactionTemplate#executeWithoutResult(java.util.function.Consumer)} 执行同一事务，
     *    无事务测试装配时同步执行，并在成功后返回true。
     *
     * @param job 已重新读取并确认到期的COMPLETED作业
     * @param now 当前维护时间，用于删除条件复核
     * @return 删除事务完成时返回true
     * @throws IllegalStateException 作业状态或到期事实并发变化导致主记录未删除时抛出
     */
    private boolean deleteCompletedExpired(WarehouseItemImportJobDO job, LocalDateTime now) {
        Runnable delete = () -> {
            rows.deleteByJobId(job.getJobId());
            if (jobs.deleteCompletedExpired(job.getJobId(), job.getCreatorUserId(), now) != 1) {
                throw new IllegalStateException("过期导入作业删除竞争失败");
            }
        };
        if (transactionTemplate == null) {
            delete.run();
        } else {
            transactionTemplate.executeWithoutResult(status -> delete.run());
        }
        return true;
    }

    /**
     * 在业务事务中删除已释放资产的EXPIRED作业事实。
     *
     * 方法：{@code deleteExpiredJob}
     *
     * 执行链路（共 3 步）：
     * 1. 调用 {@link WarehouseItemImportRowMapper#deleteByJobId(String)} 删除该作业的预览行。
     * 2. 调用 {@link WarehouseItemImportJobMapper#deleteExpiredForMaintenance(String, Long, int)} 按所有者、修订号和EXPIRED状态删除作业；
     *    删除竞争失败时抛出异常并回滚预览行删除。
     * 3. 有事务管理器时通过 {@link TransactionTemplate#executeWithoutResult(java.util.function.Consumer)} 执行同一事务，
     *    无事务测试装配时同步执行，并在成功后返回true。
     *
     * @param job 已完成资产释放的到期作业
     * @param revision 资产释放前CAS领取产生的预期修订号
     * @return 删除事务完成时返回true
     * @throws IllegalStateException 作业状态或修订号并发变化导致主记录未删除时抛出
     */
    private boolean deleteExpiredJob(WarehouseItemImportJobDO job, int revision) {
        Runnable delete = () -> {
            rows.deleteByJobId(job.getJobId());
            if (jobs.deleteExpiredForMaintenance(job.getJobId(), job.getCreatorUserId(), revision) != 1) {
                throw new IllegalStateException("过期导入作业删除竞争失败");
            }
        };
        if (transactionTemplate == null) {
            delete.run();
        } else {
            transactionTemplate.executeWithoutResult(status -> delete.run());
        }
        return true;
    }

    /** 06F维护一轮的脱敏计数。 */
    public record MaintenanceResult(int recovered, int deleted, int releaseFailures, boolean moreExpired,
                                    boolean ownershipUncertain) {
        public MaintenanceResult(int recovered, int deleted, int releaseFailures, boolean moreExpired) {
            this(recovered, deleted, releaseFailures, moreExpired, false);
        }
    }

    private record CleanupCounts(int deleted, int releaseFailures, boolean moreExpired, boolean ownershipUncertain) { }

    @PreDestroy
    void shutdown(){ analysisExecutor.shutdownNow(); }
}
