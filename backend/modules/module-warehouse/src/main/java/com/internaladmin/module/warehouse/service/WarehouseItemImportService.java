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

    public WarehouseItemImportService(ControlledDocumentFileApi files, ItemMapper items,
                                      WarehouseService warehouse,
                                      WarehouseItemImportJobMapper jobs, WarehouseItemImportRowMapper rows,
                                      IamActorApi iamActorApi, PlatformTransactionManager transactionManager) {
        this.files = files; this.items = items; this.warehouse = warehouse;
        this.jobs = jobs; this.rows = rows; this.iamActorApi = iamActorApi;
        this.transactionTemplate = transactionManager == null ? null : new TransactionTemplate(transactionManager);
        this.analysisExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(ANALYSIS_QUEUE_CAPACITY), runnable -> {
                    Thread thread = new Thread(runnable, "warehouse-item-import-analysis");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
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
        LocalDateTime claimedAt = LocalDateTime.now();
        int claimedRevision = job.getRevision() + 1;
        if (jobs.claimAnalysis(job.getJobId(), userId, job.getRevision(), claimedAt) == 1) {
            // The mapper increments revision in the database; keep the local return value aligned
            // so an in-process test double and the real re-read observe the same claimed token.
            job.setStatus(WarehouseItemImportStatus.ANALYZING.name());
            job.setRevision(claimedRevision);
            enqueueAnalysis(userId, job.getJobId(), claimedRevision);
        }
        return view(jobs.findOwned(job.getJobId(), userId));
    }

    private void enqueueAnalysis(Long userId, String jobId, int revision) {
        try {
            analysisExecutor.execute(() -> analyze(userId, jobId, revision));
        } catch (RejectedExecutionException ex) {
            // Queue pressure is recoverable. Return the claim to RECEIVED so the
            // owner can explicitly retry it; only validation/fact failures become
            // ANALYSIS_FAILED.
            jobs.releaseAnalysisClaim(jobId, userId, revision, LocalDateTime.now());
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
        } else if (!WarehouseItemImportStatus.RECEIVED.name().equals(job.getStatus())) {
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
        enqueueAnalysis(userId, jobId, runningRevision);
        return view(jobs.findOwned(jobId, userId));
    }

    private void analyze(Long userId, String jobId, int revision) {
        List<SourceRow> source;
        try {
            WarehouseItemImportJobDO job = jobs.findOwned(jobId, userId);
            if (job == null || !WarehouseItemImportStatus.ANALYZING.name().equals(job.getStatus()) || job.getRevision() != revision) return;
            try {
                // The creator's persisted identity is not an authorization
                // snapshot. Re-resolve IAM immediately before reading the file
                // and again no later than the next business-fact boundary.
                requireManager(userId);
            } catch (BusinessException revoked) {
                markAnalysisFailure(jobId, userId, revision, "IMPORT_PERMISSION_REVOKED");
                return;
            } catch (RuntimeException iamFailure) {
                markAnalysisFailure(jobId, userId, revision, "IMPORT_PERMISSION_UNAVAILABLE");
                return;
            }
            ControlledDocumentRead read = files.read(job.getFileAssetId(), userId, DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT);
            source = read.metadata().originalFilename().toLowerCase(Locale.ROOT).endsWith(".csv")
                    ? parseCsv(read.content()) : parseXlsx(read.content());
            if (source.isEmpty()) throw bad("文件没有可分析的数据行");
        } catch (DataAccessException ex) {
            markAnalysisFailure(jobId, userId, revision, "IMPORT_DATABASE_UNAVAILABLE");
            return;
        } catch (RuntimeException ex) {
            markAnalysisFailure(jobId, userId, revision, analysisErrorCode("FILE", ex));
            return;
        }
        List<WarehouseItemImportRowDO> analyzed;
        try {
            // Authorization can be revoked while parsing. Do not query item,
            // stock or movement facts until the current actor is still valid.
            requireManager(userId);
        } catch (BusinessException revoked) {
            markAnalysisFailure(jobId, userId, revision, "IMPORT_PERMISSION_REVOKED");
            return;
        } catch (RuntimeException ex) {
            markAnalysisFailure(jobId, userId, revision, "IMPORT_PERMISSION_UNAVAILABLE");
            return;
        }
        try {
            analyzed = classify(jobId, userId, source);
        } catch (DataAccessException ex) {
            markAnalysisFailure(jobId, userId, revision, "IMPORT_DATABASE_UNAVAILABLE");
            return;
        } catch (RuntimeException ex) {
            markAnalysisFailure(jobId, userId, revision, "IMPORT_FACTS_UNAVAILABLE");
            return;
        }
        try {
            Counts c = Counts.of(analyzed);
            persistAnalysis(jobId, userId, revision, analyzed, c);
        } catch (DataAccessException ex) {
            markAnalysisFailure(jobId, userId, revision, "IMPORT_DATABASE_UNAVAILABLE");
        } catch (RuntimeException ex) {
            markAnalysisFailure(jobId, userId, revision, "IMPORT_PERSISTENCE_FAILED");
        }
    }

    private void persistAnalysis(String jobId, Long userId, int revision, List<WarehouseItemImportRowDO> analyzed, Counts c) {
        Runnable persist = () -> {
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

    private void markAnalysisFailure(String jobId, Long userId, int revision, String code) {
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

    /** Analysis failures release once; later cleanup/retry is owned by 06F. */
    private void releaseAssetAfterFailure(Long userId, String jobId) {
        try {
            releaseAsset(userId, jobId);
        } catch (RuntimeException releaseFailure) {
            // The job remains terminal and keeps its asset reference. The
            // failure is deliberately visible in logs; 06F owns any retry.
            LOGGER.error("物品导入资产释放失败，保留作业与资产引用供06F处理 jobId={}", jobId, releaseFailure);
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
    private List<SourceRow> parseCsv(byte[] bytes) {
        String text = decodeUtf8(bytes); List<SourceRow> out = new ArrayList<>();
        try (CSVParser parser = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true)
                .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW).build()
                .parse(new InputStreamReader(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8))) {
            Map<String,String> mapped = headers(parser.getHeaderMap().keySet());
            Map<String,Integer> indices = new HashMap<>();
            for (Map.Entry<String,String> e : mapped.entrySet()) indices.put(e.getKey(), parser.getHeaderMap().entrySet().stream().filter(x -> norm(x.getKey()).equals(e.getValue())).map(Map.Entry::getValue).findFirst().orElseThrow());
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
    private List<SourceRow> parseXlsx(byte[] bytes) {
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
            Map<String,String> mapped = headers(h.keySet()); List<SourceRow> out = new ArrayList<>(); DataFormatter f = new DataFormatter();
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
    private static Map<String,String> headers(Collection<String> input) {
        Map<String,String> result = new HashMap<>();
        for (String raw : input) { String n=norm(raw); String field = HEADER_CODE.contains(n)?"code":HEADER_NAME.contains(n)?"name":HEADER_UNIT.contains(n)?"unit":HEADER_ENABLED.contains(n)?"enabled":null; if(field!=null && result.put(field,n)!=null) throw bad("列映射有歧义"); }
        if (!result.containsKey("code") || !result.containsKey("name") || !result.containsKey("unit") || !result.containsKey("enabled")) throw bad("缺少必填列");
        return result;
    }
    private static SourceRow sourceCsv(int no, CSVRecord r, Map<String,Integer> indices) { return source(no, x -> { Integer i=indices.get(x); return i != null && i < r.size() ? r.get(i) : ""; }, Map.of("code","code","name","name","unit","unit","enabled","enabled")); }
    private static SourceRow source(int no, Row r, Map<String,Integer> raw, Map<String,String> h, DataFormatter f) { return source(no, x -> { Integer i=raw.entrySet().stream().filter(e->e.getKey().equals(h.get(x))).map(Map.Entry::getValue).findFirst().orElse(null); return i==null?"":f.formatCellValue(r.getCell(i)); }, h); }
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
        // Listing is a read-only user view. Recovery and cleanup are explicit
        // operations and never a side effect of opening a page.
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
                LOGGER.error("取消作业后资产释放失败且无法记录稳定错误码 jobId={}", jobId, releaseFailure);
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
    private static void zeroCounts(WarehouseItemImportJobDO j){j.setCreateCount(0);j.setUpdateCount(0);j.setDisableCount(0);j.setUnchangedCount(0);j.setInvalidCount(0);j.setConflictCount(0);}
    private WarehouseItemImportJobView view(WarehouseItemImportJobDO j){
        if(j==null) throw new BusinessException(ErrorCode.NOT_FOUND,"导入作业不存在");
        try { WarehouseItemImportStatus.valueOf(j.getStatus()); }
        catch (RuntimeException ex) { throw new BusinessException(ErrorCode.INTERNAL_ERROR,"导入作业状态无效，请稍后诊断"); }
        LocalDateTime now = LocalDateTime.now();
        String status = visibleStatus(j, now);
        boolean reanalyzeAvailable = !isExpired(j, now)
                && (WarehouseItemImportStatus.RECEIVED.name().equals(j.getStatus())
                || (WarehouseItemImportStatus.ANALYZING.name().equals(j.getStatus())
                && j.getUpdatedAt() != null
                && j.getUpdatedAt().isBefore(now.minus(ANALYSIS_STALE_AFTER))));
        String errorCode = j.getErrorCode();
        if (WarehouseItemImportStatus.EXPIRED.name().equals(status)
                && !WarehouseItemImportStatus.EXPIRED.name().equals(j.getStatus())
                && errorCode == null) {
            errorCode = "IMPORT_JOB_EXPIRED";
        }
        return new WarehouseItemImportJobView(j.getJobId(),status,j.getRevision(),nz(j.getTotalRows()),nz(j.getCreateCount()),nz(j.getUpdateCount()),nz(j.getDisableCount()),nz(j.getUnchangedCount()),nz(j.getInvalidCount()),nz(j.getConflictCount()),errorCode,j.getCreatedAt(),j.getExpiresAt(),reanalyzeAvailable);
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
    @PreDestroy
    void shutdown(){ analysisExecutor.shutdownNow(); }
}
