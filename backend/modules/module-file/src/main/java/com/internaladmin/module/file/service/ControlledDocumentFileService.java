package com.internaladmin.module.file.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.internaladmin.module.file.api.ControlledDocumentAsset;
import com.internaladmin.module.file.api.ControlledDocumentFileApi;
import com.internaladmin.module.file.api.ControlledDocumentRead;
import com.internaladmin.module.file.api.ControlledDocumentStoreRequest;
import com.internaladmin.module.file.api.DocumentFileLimitSnapshot;
import com.internaladmin.module.file.api.DocumentFilePurpose;
import com.internaladmin.module.file.api.DocumentFileStatus;
import com.internaladmin.module.file.api.DocumentImportLimitsProvider;
import com.internaladmin.module.file.mapper.ControlledDocumentAssetMapper;
import com.internaladmin.module.file.model.entity.ControlledDocumentAssetDO;
import com.internaladmin.platform.kernel.error.BusinessException;
import com.internaladmin.platform.kernel.error.ErrorCode;
import jakarta.annotation.PreDestroy;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.Duration;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.w3c.dom.Document;

/**
 * 受控业务文档文件服务。图片旧路径仍由 {@link FileStorageService} 独立负责。
 *
 * <p>服务先把输入写入非公开临时文件，再在线程数和队列均有界的执行器中校验实际格式、
 * 文本预算及 OOXML 结构；全部校验通过后才在业务事务内完成 STAGING 元数据、原子移动和 AVAILABLE 切换。</p>
 */
@Service
public class ControlledDocumentFileService implements ControlledDocumentFileApi {

    private static final Logger LOGGER = LoggerFactory.getLogger(ControlledDocumentFileService.class);

    private static final String DOCUMENT_DIRECTORY = "documents";
    private static final String TEMPORARY_DIRECTORY = ".document-tmp";
    private static final long HARD_MAX_FILE_SIZE = 10L * 1024 * 1024;
    private static final int HARD_MAX_SPREADSHEET_ROWS = 100_000;
    private static final int HARD_MAX_DOCUMENT_CHARACTERS = 1_000_000;
    private static final int HARD_MAX_DOCUMENT_CHUNKS = 2_000;
    private static final int ZIP_MAX_ENTRIES = 4_000;
    private static final long ZIP_MAX_ENTRY_BYTES = 20L * 1024 * 1024;
    private static final long ZIP_MAX_TOTAL_BYTES = 100L * 1024 * 1024;
    private static final double ZIP_MAX_RATIO = 100.0d;
    private static final int PROCESSING_THREADS = 2;
    private static final int PROCESSING_QUEUE = 8;
    private static final long PROCESSING_TIMEOUT_SECONDS = 10L;

    static {
        // POI 的全局限制是第二层保护；应用层仍执行自己的条目、解压和内容检查。
        ZipSecureFile.setMaxEntrySize(ZIP_MAX_ENTRY_BYTES);
        ZipSecureFile.setMaxFileCount(ZIP_MAX_ENTRIES);
        ZipSecureFile.setMinInflateRatio(1.0d / ZIP_MAX_RATIO);
    }

    private final ControlledDocumentAssetMapper assetMapper;
    private final DocumentImportLimitsProvider limitsProvider;
    private final Path storageRoot;
    private final Clock clock;
    private final ThreadPoolExecutor processingExecutor;
    private final Duration processingTimeout;
    /** 仅包住最终文件/元数据提交；校验和输入读取不持有数据库事务。 */
    private final PlatformTransactionManager transactionManager;
    private final AtomicBoolean accepting = new AtomicBoolean(true);

    /** 创建生产受控文档服务，使用固定线程和有限队列。 */
    @Autowired
    public ControlledDocumentFileService(ControlledDocumentAssetMapper assetMapper,
                                         DocumentImportLimitsProvider limitsProvider,
                                         @Value("${app.storage-root:./data/uploads}") String storageRoot,
                                         PlatformTransactionManager transactionManager) {
        this(assetMapper, limitsProvider, storageRoot, Clock.systemUTC(), newProcessingExecutor(),
                Duration.ofSeconds(PROCESSING_TIMEOUT_SECONDS), transactionManager);
    }

    ControlledDocumentFileService(ControlledDocumentAssetMapper assetMapper,
                                  DocumentImportLimitsProvider limitsProvider,
                                  String storageRoot,
                                  Clock clock,
                                  ThreadPoolExecutor processingExecutor) {
        this(assetMapper, limitsProvider, storageRoot, clock, processingExecutor,
                Duration.ofSeconds(PROCESSING_TIMEOUT_SECONDS), null);
    }

    ControlledDocumentFileService(ControlledDocumentAssetMapper assetMapper,
                                  DocumentImportLimitsProvider limitsProvider,
                                  String storageRoot,
                                  Clock clock,
                                  ThreadPoolExecutor processingExecutor,
                                  Duration processingTimeout) {
        this(assetMapper, limitsProvider, storageRoot, clock, processingExecutor, processingTimeout, null);
    }

    ControlledDocumentFileService(ControlledDocumentAssetMapper assetMapper,
                                  DocumentImportLimitsProvider limitsProvider,
                                  String storageRoot,
                                  Clock clock,
                                  ThreadPoolExecutor processingExecutor,
                                  Duration processingTimeout,
                                  PlatformTransactionManager transactionManager) {
        this.assetMapper = assetMapper;
        this.limitsProvider = limitsProvider;
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
        this.clock = clock;
        this.processingExecutor = processingExecutor;
        this.transactionManager = transactionManager;
        if (processingTimeout == null || processingTimeout.isZero() || processingTimeout.isNegative()) {
            throw new IllegalArgumentException("文件校验超时必须为正数");
        }
        this.processingTimeout = processingTimeout;
    }

    /**
     * 保存受控业务文档。
     *
     * <p>方法：{@code store}</p>
     *
     * <p>执行链路（共 6 步）：</p>
     * 1. 校验 owner、用途、文件名和声明媒体类型，并从 IAM 公开契约读取一次创建时限制快照；
     * 2. 把输入流按快照字节上限写入非公开临时文件；
     * 3. 仅提交纯校验至有界执行器，检查文本或 OOXML 的真实结构、资源预算和危险关系；
     * 4. 校验成功后在调用线程的受控提交边界内派生真实媒体类型、SHA-256、随机相对路径和 TTL；
     * 5. 同一事务先登记 {@code STAGING} 元数据，再原子移动并切换 {@code AVAILABLE}，提交期间不返回超时失败；
     * 6. 任一步失败回滚元数据并补偿文件（补偿失败可诊断），不留下可消费半成品。</p>
     *
     * @param request 由业务模块在自身权限校验后构造的受信请求
     * @return 已登记的受控文件元数据
     * @throws BusinessException 参数、格式、安全边界、队列/超时或存储失败时抛出
     */
    @Override
    public ControlledDocumentAsset store(ControlledDocumentStoreRequest request) {
        validateRequest(request);
        DocumentFileLimitSnapshot limits = loadCurrentLimits();
        Path temporaryFile = null;
        try {
            Files.createDirectories(storageRoot.resolve(TEMPORARY_DIRECTORY));
            temporaryFile = Files.createTempFile(storageRoot.resolve(TEMPORARY_DIRECTORY), "document-", ".tmp");
            copyBounded(request.content(), temporaryFile, limits.maxFileBytes());
            Path input = temporaryFile;
            String extension = extension(request.originalFilename());
            Future<ValidationResult> future;
            try {
                DocumentFileLimitSnapshot capturedLimits = limits;
                future = processingExecutor.submit(() -> validateContent(input, extension, request, capturedLimits));
            } catch (RejectedExecutionException e) {
                throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "文件处理队列已满，请稍后重试");
            }
            try {
                ValidationResult validation = future.get(processingTimeout.toMillis(), TimeUnit.MILLISECONDS);
                return commitInTransaction(input, request, limits, validation);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "文件处理超时，请稍后重试");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "文件处理被中断，请稍后重试");
            } catch (ExecutionException e) {
                throw unwrapFailure(e.getCause());
            } catch (CancellationException e) {
                throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "文件处理已取消，请稍后重试");
            }
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件暂存失败，请稍后重试");
        } finally {
            deleteQuietly(temporaryFile);
        }
    }

    /**
     * 读取受控文档正文。
     *
     * <p>方法：{@code read}</p>
     *
     * <p>执行链路（共 4 步）：</p>
     * 1. 按 assetId 查询元数据，不存在时统一返回不可见错误；
     * 2. 复核 owner、用途、AVAILABLE 状态和未过期 TTL，避免跨用户或跨业务读取；
     * 3. 解析系统生成的相对路径并确认仍位于受控根目录；
     * 4. 读取有界正文并返回元数据与副本，响应不含物理路径。</p>
     *
     * @param assetId 受控文件标识
     * @param ownerId 当前业务所有者
     * @param purpose 当前业务用途
     * @return 受权文件及元数据
     * @throws BusinessException 文件不存在、归属/用途不符、失效或读取失败时抛出
     */
    @Override
    public ControlledDocumentRead read(String assetId, Long ownerId, DocumentFilePurpose purpose) {
        ControlledDocumentAssetDO asset = assetMapper.selectById(assetId);
        if (asset == null || ownerId == null || !ownerId.equals(asset.getOwnerId())
                || purpose == null || !purpose.name().equals(asset.getPurpose())
                || !DocumentFileStatus.AVAILABLE.name().equals(asset.getStatus())
                || asset.getExpiresAt() == null || !asset.getExpiresAt().isAfter(now())
                || asset.getByteSize() < 1 || asset.getByteSize() > HARD_MAX_FILE_SIZE
                || asset.getMaxFileBytes() == null || asset.getByteSize() > asset.getMaxFileBytes()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "受控文件不存在或已失效");
        }
        Path file = storageRoot.resolve(asset.getRelativePath()).normalize();
        if (!file.startsWith(storageRoot)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "受控文件存储位置无效");
        }
        try {
            byte[] content = Files.readAllBytes(file);
            if (content.length != asset.getByteSize() || content.length > HARD_MAX_FILE_SIZE) {
                throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "受控文件内容已变化，请重新提交");
            }
            if (asset.getSha256() == null || !asset.getSha256().equalsIgnoreCase(sha256(content))) {
                throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "受控文件校验值不匹配，请重新提交");
            }
            return new ControlledDocumentRead(toAsset(asset), content);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "受控文件不存在或已失效");
        }
    }

    /**
     * 受信业务入口标记保留状态；仍复核所有者、用途、状态和 TTL。
     *
     * @param assetId 受控文件标识
     * @param ownerId 当前业务所有者
     * @param purpose 当前业务用途
     */
    @Override
    public void retain(String assetId, Long ownerId, DocumentFilePurpose purpose) {
        ControlledDocumentAssetDO asset = assetMapper.selectById(assetId);
        if (asset == null || ownerId == null || !ownerId.equals(asset.getOwnerId())
                || purpose == null || !purpose.name().equals(asset.getPurpose())
                || !DocumentFileStatus.AVAILABLE.name().equals(asset.getStatus())
                || asset.getExpiresAt() == null || !asset.getExpiresAt().isAfter(now())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "受控文件不存在或已失效");
        }
        asset.setRetained(true);
        if (assetMapper.updateById(asset) != 1) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "受控文件保留状态更新失败，请稍后诊断");
        }
    }

    @Override
    public void discard(String assetId, Long ownerId, DocumentFilePurpose purpose) {
        ControlledDocumentAssetDO asset = assetMapper.selectById(assetId);
        // Release is intentionally idempotent: once a trusted caller has
        // discarded an asset, a later bounded job-cleanup retry may see the
        // metadata already gone. No readable data is exposed by treating that
        // terminal absence as success.
        if (asset == null) return;
        if (ownerId == null || !ownerId.equals(asset.getOwnerId())
                || purpose == null || !purpose.name().equals(asset.getPurpose())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "受控文件不存在或已失效");
        }
        if (!DocumentFileStatus.REJECTED.name().equals(asset.getStatus())) {
            asset.setStatus(DocumentFileStatus.REJECTED.name());
            if (assetMapper.updateById(asset) != 1) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "受控文件释放状态登记失败，请稍后诊断");
            }
        }
        Path file = storageRoot.resolve(asset.getRelativePath()).normalize();
        if (!file.startsWith(storageRoot)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "受控文件存储位置无效");
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.error("受控文件释放删除失败 assetId={} relativePath={}", assetId, asset.getRelativePath(), e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "受控文件释放失败，请稍后诊断");
        }
        if (assetMapper.deleteById(assetId) != 1) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "受控文件释放元数据清理失败，请稍后诊断");
        }
    }

    /**
     * 有界清理过期文档资产。
     *
     * <p>方法：{@code cleanupExpired}</p>
     *
     * <p>执行链路（共 4 步）：</p>
     * 1. 校验当前时间和批次上限；
     * 2. 使用分页查询取得本模块少量 retained=false 且 expiresAt 已到期的 AVAILABLE、EXPIRED 或 REJECTED 记录；
     * 3. 先删除物理文件，成功后删除对应元数据；
     * 4. 任何失败都抛出可诊断错误，不把未清理结果报告为成功。</p>
     *
     * @param now 当前时间
     * @param batchSize 本次最多处理条数（1..100）
     * @return 实际删除的资产数
     * @throws BusinessException 批次非法或物理/数据库清理失败时抛出
     */
    @Override
    public int cleanupExpired(LocalDateTime now, int batchSize) {
        if (now == null || batchSize < 1 || batchSize > 100) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "清理批次必须在1至100之间");
        }
        Page<ControlledDocumentAssetDO> page = new Page<>(1, batchSize, false);
        assetMapper.selectPage(page, new LambdaQueryWrapper<ControlledDocumentAssetDO>()
                .le(ControlledDocumentAssetDO::getExpiresAt, now)
                .eq(ControlledDocumentAssetDO::getRetained, false)
                .in(ControlledDocumentAssetDO::getStatus, DocumentFileStatus.AVAILABLE.name(),
                        DocumentFileStatus.EXPIRED.name(), DocumentFileStatus.REJECTED.name())
                .orderByAsc(ControlledDocumentAssetDO::getExpiresAt));
        int deleted = 0;
        for (ControlledDocumentAssetDO asset : page.getRecords()) {
            // Keep the visibility boundary defensive even if a mapper implementation returns a stale row.
            if (!Set.of(DocumentFileStatus.AVAILABLE.name(), DocumentFileStatus.EXPIRED.name(),
                    DocumentFileStatus.REJECTED.name()).contains(asset.getStatus())) {
                continue;
            }
            Path file = storageRoot.resolve(asset.getRelativePath()).normalize();
            if (!file.startsWith(storageRoot)) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "受控文件存储位置无效");
            }
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件清理失败，请稍后诊断");
            }
            if (assetMapper.deleteById(asset.getAssetId()) != 1) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件元数据清理失败，请稍后诊断");
            }
            deleted++;
        }
        return deleted;
    }

    @PreDestroy
    void shutdown() {
        accepting.set(false);
        for (Runnable pending : processingExecutor.shutdownNow()) {
            if (pending instanceof Future<?> future) {
                future.cancel(false);
            }
        }
    }

    /**
     * 只在最终提交边界开启事务；校验线程永远不持有数据库事务。
     * REQUIRED 事务会加入调用方已有事务，因此外层回滚也会触发文件补偿。
     */
    private ControlledDocumentAsset commitInTransaction(Path temporaryFile,
                                                        ControlledDocumentStoreRequest request,
                                                        DocumentFileLimitSnapshot limits,
                                                        ValidationResult validation) {
        if (transactionManager == null) {
            // 纯模块单测没有 Spring 事务时仍走同一 STAGING 协议；生产装配总会提供该管理器。
            return commitValidated(temporaryFile, request, limits, validation);
        }
        ControlledDocumentAsset result = new TransactionTemplate(transactionManager)
                .execute(status -> commitValidated(temporaryFile, request, limits, validation));
        if (result == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "受控文件提交未返回结果，请稍后诊断");
        }
        return result;
    }

    /**
     * 元数据先登记为 STAGING，文件移动后再在同一事务中切换 AVAILABLE。
     * 外部读取只接受 AVAILABLE，因此不会消费“只有文件”或“只有元数据”的窗口。
     */
    private ControlledDocumentAsset commitValidated(Path temporaryFile,
                                                    ControlledDocumentStoreRequest request,
                                                    DocumentFileLimitSnapshot limits,
                                                    ValidationResult validation) {
        Path finalFile = null;
        ControlledDocumentAssetDO asset = null;
        boolean metadataInserted = false;
        boolean transactional = TransactionSynchronizationManager.isSynchronizationActive();
        try {
            ensureProcessingActive();
            LocalDateTime createdAt = now();
            int retention = request.purpose() == DocumentFilePurpose.IMPORT_RESULT
                    ? limits.resultRetentionDays() : limits.unconfirmedRetentionDays();
            String extension = extension(request.originalFilename());
            finalFile = createFinalPath(extension);
            Files.createDirectories(finalFile.getParent());

            asset = new ControlledDocumentAssetDO();
            asset.setAssetId(UUID.randomUUID().toString());
            asset.setOriginalFilename(normalizeFilename(request.originalFilename()));
            asset.setActualContentType(validation.contentType());
            asset.setByteSize(validation.byteSize());
            asset.setSha256(validation.sha256());
            asset.setOwnerId(request.ownerId());
            asset.setPurpose(request.purpose().name());
            asset.setStatus(DocumentFileStatus.STAGING.name());
            asset.setRelativePath(storageRoot.relativize(finalFile).toString().replace('\\', '/'));
            asset.setCreatedAt(createdAt);
            asset.setExpiresAt(createdAt.plusDays(retention));
            asset.setRetained(false);
            asset.setMaxFileBytes(limits.maxFileBytes());
            asset.setMaxSpreadsheetRows(limits.maxSpreadsheetRows());
            asset.setMaxDocumentCharacters(limits.maxDocumentCharacters());
            asset.setMaxDocumentChunks(limits.maxDocumentChunks());
            asset.setUnconfirmedRetentionDays(limits.unconfirmedRetentionDays());
            asset.setResultRetentionDays(limits.resultRetentionDays());
            if (assetMapper.insert(asset) != 1) {
                throw new IllegalStateException("文件元数据登记失败");
            }
            metadataInserted = true;
            if (transactional) {
                registerRollbackCleanup(finalFile, asset);
            }

            Files.move(temporaryFile, finalFile, StandardCopyOption.ATOMIC_MOVE);
            ensureProcessingActive();
            asset.setStatus(DocumentFileStatus.AVAILABLE.name());
            if (assetMapper.updateById(asset) != 1) {
                throw new IllegalStateException("文件元数据状态切换失败");
            }
            return toAsset(asset);
        } catch (IOException e) {
            handleCommitFailure(finalFile, asset, metadataInserted, transactional);
            throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "文件结构或存储校验失败，请检查文件后重试");
        } catch (BusinessException e) {
            handleCommitFailure(finalFile, asset, metadataInserted, transactional);
            throw e;
        } catch (RuntimeException e) {
            handleCommitFailure(finalFile, asset, metadataInserted, transactional);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "受控文件元数据登记失败，请稍后重试");
        }
    }

    private ValidationResult validateContent(Path file,
                                             String extension,
                                             ControlledDocumentStoreRequest request,
                                             DocumentFileLimitSnapshot limits) {
        try {
            ensureProcessingActive();
            byte[] bytes = Files.readAllBytes(file);
            ensureProcessingActive();
            String declared = normalizeContentType(request.declaredContentType());
            return switch (extension) {
                case "md" -> validateText(bytes, declared, "text/markdown", limits);
                case "txt" -> validateText(bytes, declared, "text/plain", limits);
                case "csv" -> validateCsv(bytes, declared, limits);
                case "xlsx" -> validateOffice(bytes, declared,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", false, limits);
                case "docx" -> validateOffice(bytes, declared,
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", true, limits);
                default -> throw reject("仅支持 xlsx、csv、docx、md、txt 文件");
            };
        } catch (IOException e) {
            throw reject("文件内容无法完整读取或解码");
        }
    }

    private ValidationResult validateText(byte[] bytes,
                                          String declared,
                                          String expectedType,
                                          DocumentFileLimitSnapshot limits) {
        if (!expectedType.equals(declared)) {
            throw reject("文件实际类型与声明不一致");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw reject("文本文件必须使用有效 UTF-8 编码");
        }
        validateCharacters(text);
        if (text.length() > limits.maxDocumentCharacters()) {
            throw reject("文档字符数超过当前限制");
        }
        return new ValidationResult(expectedType, bytes.length, sha256(bytes));
    }

    private ValidationResult validateCsv(byte[] bytes, String declared, DocumentFileLimitSnapshot limits) throws IOException {
        if (!"text/csv".equals(declared)) {
            throw reject("文件实际类型与声明不一致");
        }
        String text = decodeUtf8(bytes);
        validateCharacters(text);
        if (text.length() > limits.maxDocumentCharacters()) {
            throw reject("CSV 字符数超过当前限制");
        }
        int rowCount = 0;
        Integer columns = null;
        try (Reader reader = new java.io.StringReader(text);
             CSVParser parser = CSVParser.parse(reader, CSVFormat.RFC4180)) {
            for (CSVRecord row : parser) {
                rowCount++;
                if (rowCount > limits.maxSpreadsheetRows()) {
                    throw reject("电子表格数据行数超过当前限制");
                }
                if (columns == null) {
                    columns = row.size();
                } else if (!columns.equals(row.size())) {
                    throw reject("CSV 行结构不一致，请检查引号和分隔符");
                }
            }
        }
        if (rowCount == 0) {
            throw reject("CSV 文件不能为空");
        }
        return new ValidationResult("text/csv", bytes.length, sha256(bytes));
    }

    private ValidationResult validateOffice(byte[] bytes,
                                            String declared,
                                            String expectedType,
                                            boolean docx,
                                            DocumentFileLimitSnapshot limits) throws IOException {
        if (!expectedType.equals(declared) || !isZip(bytes)) {
            throw reject("文件实际类型与声明不一致或不是有效 OOXML 容器");
        }
        rejectEncryptedZip(bytes);
        Map<String, byte[]> entries = inspectZip(bytes);
        String contentTypes = findEntry(entries, "[content_types].xml");
        if (contentTypes == null) {
            throw reject("OOXML 缺少内容类型声明");
        }
        String requiredContentType = docx
                ? "wordprocessingml.document.main+xml"
                : "spreadsheetml.sheet.main+xml";
        if (!contentTypes.toLowerCase(Locale.ROOT).contains(requiredContentType)) {
            throw reject("OOXML 内容类型声明与实际文档不一致");
        }
        String required = docx ? "word/document.xml" : "xl/workbook.xml";
        if (!entries.containsKey(required)) {
            throw reject("OOXML 缺少必要正文结构");
        }
        if (docx) {
            int characters = xmlTextLength(entries.get(required));
            if (characters > limits.maxDocumentCharacters()) {
                throw reject("DOCX 字符数超过当前限制");
            }
        } else {
            int rows = entries.entrySet().stream()
                    .filter(entry -> entry.getKey().toLowerCase(Locale.ROOT).startsWith("xl/worksheets/"))
                    .mapToInt(entry -> countRows(entry.getValue()))
                    .sum();
            if (rows > limits.maxSpreadsheetRows()) {
                throw reject("电子表格数据行数超过当前限制");
            }
        }
        if (entries.keySet().stream().anyMatch(name -> name.toLowerCase(Locale.ROOT).contains("vbaproject")
                || name.toLowerCase(Locale.ROOT).contains("/embeddings/")
                || name.toLowerCase(Locale.ROOT).contains("/oleobjects/")
                || name.toLowerCase(Locale.ROOT).contains("/externalLinks/".toLowerCase(Locale.ROOT)))) {
            throw reject("不支持宏、OLE、嵌入对象或外部链接");
        }
        if (contentTypes.toLowerCase(Locale.ROOT).contains("macroenabled")) {
            throw reject("不支持宏文档");
        }
        entries.forEach((name, content) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            String xml = new String(content, StandardCharsets.UTF_8);
            if (lower.endsWith(".xml") || lower.endsWith(".rels")) {
                validateXml(content);
            }
            if (lower.endsWith(".rels")) {
                validateRelationships(content);
            }
            if (!docx && lower.startsWith("xl/worksheets/") && xml.matches("(?s).*<f(?:\\s|>).*") ) {
                throw reject("不支持包含公式的电子表格");
            }
        });
        if (!docx && limits.maxSpreadsheetRows() < 1) {
            throw reject("电子表格行数限制无效");
        }
        return new ValidationResult(expectedType, bytes.length, sha256(bytes));
    }

    /**
     * 只依据 Relationship 的 Target/TargetMode 属性判断外部关系。
     * Relationship Type 本身按 OOXML 合同是 URI，不能因为其中包含 http(s)
     * 就把合法的内部关系误判为外部链接。
     */
    private void validateRelationships(byte[] xml) {
        try {
            Document document = secureDocument(xml);
            var relationships = document.getElementsByTagNameNS("*", "Relationship");
            // Some producers emit relationship XML without a namespace.  The
            // namespace-aware lookup above intentionally does not rely on a
            // namespace being present, so retain a local-name fallback for
            // those valid OOXML files and for malformed test fixtures.
            if (relationships.getLength() == 0) {
                relationships = document.getElementsByTagName("Relationship");
            }
            for (int i = 0; i < relationships.getLength(); i++) {
                var relationship = (org.w3c.dom.Element) relationships.item(i);
                String targetMode = relationship.getAttribute("TargetMode").trim();
                String target = relationship.getAttribute("Target");
                if ("external".equalsIgnoreCase(targetMode) || isExternalRelationshipTarget(target)) {
                    throw reject("不支持外部关系");
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw reject("OOXML XML 结构损坏或不安全");
        }
    }

    private boolean isExternalRelationshipTarget(String target) {
        if (target == null) {
            return false;
        }
        String value = target.trim();
        if (value.isEmpty() || value.startsWith("//")) {
            return value.startsWith("//");
        }
        return value.matches("(?i)^[a-z][a-z0-9+.-]*:.*");
    }

    private Map<String, byte[]> inspectZip(byte[] bytes) throws IOException {
        Set<String> names = new HashSet<>();
        Map<String, byte[]> entries = new HashMap<>();
        long total = 0;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            int count = 0;
            byte[] buffer = new byte[8192];
            while ((entry = input.getNextEntry()) != null) {
                if (++count > ZIP_MAX_ENTRIES || !safeZipName(entry.getName()) || !names.add(entry.getName())) {
                    throw reject("OOXML 容器条目数量或路径不安全");
                }
                if (entry.getMethod() != ZipEntry.STORED && entry.getMethod() != ZipEntry.DEFLATED) {
                    throw reject("OOXML 容器压缩方式不受支持");
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                int read;
                long entrySize = 0;
                while ((read = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw reject("文件处理被中断");
                    }
                    entrySize += read;
                    total += read;
                    if (entrySize > ZIP_MAX_ENTRY_BYTES || total > ZIP_MAX_TOTAL_BYTES) {
                        throw reject("OOXML 解压资源超过安全上限");
                    }
                    output.write(buffer, 0, read);
                }
                long compressed = entry.getCompressedSize();
                if (compressed > 0 && entrySize > 1_048_576 && (double) entrySize / compressed > ZIP_MAX_RATIO) {
                    throw reject("OOXML 压缩比超过安全上限");
                }
                entries.put(entry.getName(), output.toByteArray());
                input.closeEntry();
            }
        }
        return entries;
    }

    private void validateXml(byte[] xml) {
        String text = new String(xml, StandardCharsets.UTF_8);
        if (text.contains("<!DOCTYPE") || text.contains("<!ENTITY")) {
            throw reject("OOXML XML 不允许外部实体");
        }
        try {
            var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw reject("OOXML XML 结构损坏或不安全");
        }
    }

    private int xmlTextLength(byte[] xml) {
        try {
            return secureDocument(xml).getDocumentElement().getTextContent().length();
        } catch (Exception e) {
            throw reject("OOXML XML 结构损坏或不安全");
        }
    }

    private int countRows(byte[] xml) {
        String text = new String(xml, StandardCharsets.UTF_8);
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("<row(?:\\s|>)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(text);
        int rows = 0;
        while (matcher.find()) {
            rows++;
        }
        return rows;
    }

    private Document secureDocument(byte[] xml) throws Exception {
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    private void rejectEncryptedZip(byte[] bytes) {
        for (int offset = 0; offset + 8 <= bytes.length; offset++) {
            boolean local = bytes[offset] == 'P' && bytes[offset + 1] == 'K'
                    && bytes[offset + 2] == 3 && bytes[offset + 3] == 4;
            boolean central = bytes[offset] == 'P' && bytes[offset + 1] == 'K'
                    && bytes[offset + 2] == 1 && bytes[offset + 3] == 2;
            int flagOffset = local ? offset + 6 : central ? offset + 8 : -1;
            if (flagOffset >= 0 && (bytes[flagOffset] & 0x01) != 0) {
                throw reject("不支持加密 OOXML 文件");
            }
        }
    }

    private String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw reject("文本文件必须使用有效 UTF-8 编码");
        }
    }

    private void validateCharacters(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\0' || (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) {
                throw reject("文本包含不允许的控制字符");
            }
        }
    }

    private void validateRequest(ControlledDocumentStoreRequest request) {
        if (request == null || request.ownerId() == null || request.ownerId() <= 0
                || request.purpose() == null || request.content() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "受控文件请求缺少所有者、用途或内容");
        }
        if (request.originalFilename() == null || request.originalFilename().isBlank()
                || request.originalFilename().length() > 255
                || request.originalFilename().contains("/") || request.originalFilename().contains("\\")) {
            throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "文件名无效或包含路径信息");
        }
        String extension = extension(request.originalFilename());
        if (!Set.of("xlsx", "csv", "docx", "md", "txt").contains(extension)) {
            throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "仅支持 xlsx、csv、docx、md、txt 文件");
        }
        if (request.declaredContentType() == null || request.declaredContentType().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "文件声明类型不能为空");
        }
    }

    private DocumentFileLimitSnapshot loadCurrentLimits() {
        if (limitsProvider == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "导入限制配置供应不可用，请稍后诊断");
        }
        final DocumentFileLimitSnapshot limits;
        try {
            limits = limitsProvider.currentSnapshot();
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "导入限制配置读取失败，请稍后重试");
        }
        validateLimits(limits);
        return limits;
    }

    private void validateLimits(DocumentFileLimitSnapshot limits) {
        if (limits == null
                || limits.maxFileBytes() < 1_024 || limits.maxFileBytes() > HARD_MAX_FILE_SIZE
                || limits.maxSpreadsheetRows() < 1 || limits.maxSpreadsheetRows() > HARD_MAX_SPREADSHEET_ROWS
                || limits.maxDocumentCharacters() < 1 || limits.maxDocumentCharacters() > HARD_MAX_DOCUMENT_CHARACTERS
                || limits.maxDocumentChunks() < 1 || limits.maxDocumentChunks() > HARD_MAX_DOCUMENT_CHUNKS
                || limits.unconfirmedRetentionDays() < 1 || limits.unconfirmedRetentionDays() > 90
                || limits.resultRetentionDays() < 1 || limits.resultRetentionDays() > 90) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "限制快照缺失、越界或超过系统硬上限");
        }
    }

    private void ensureProcessingActive() {
        if (!accepting.get() || Thread.currentThread().isInterrupted()) {
            throw reject("文件处理被中断");
        }
    }

    private void copyBounded(InputStream input, Path target, long maxBytes) throws IOException {
        long copied = 0;
        byte[] buffer = new byte[8192];
        try (InputStream source = input; var output = Files.newOutputStream(target)) {
            int read;
            while ((read = source.read(buffer)) != -1) {
                copied += read;
                if (copied > maxBytes) {
                    throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "文件大小超过创建时限制");
                }
                output.write(buffer, 0, read);
            }
        }
        if (copied == 0) {
            throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "文件不能为空");
        }
    }

    Path createFinalPath(String extension) {
        return storageRoot.resolve(DOCUMENT_DIRECTORY)
                .resolve(LocalDate.now(ZoneOffset.UTC).toString().replace("-", ""))
                .resolve(UUID.randomUUID() + "." + extension);
    }

    private void registerRollbackCleanup(Path finalFile, ControlledDocumentAssetDO asset) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    deleteFinalOrDiagnose(finalFile, asset.getAssetId(), asset.getRelativePath());
                }
            }
        });
    }

    private void handleCommitFailure(Path finalFile,
                                     ControlledDocumentAssetDO asset,
                                     boolean metadataInserted,
                                     boolean transactional) {
        if (transactional) {
            markCurrentTransactionRollbackOnly();
            // The transaction synchronization owns the final-file compensation after rollback.
            // It is deliberately not attempted here while the database transaction is still open.
            return;
        }
        if (finalFile != null) {
            RuntimeException finalCleanupFailure = null;
            boolean finalFileDeleted = true;
            try {
                deleteFinalOrDiagnose(finalFile,
                        asset == null ? null : asset.getAssetId(),
                        asset == null ? null : asset.getRelativePath());
            } catch (RuntimeException e) {
                finalCleanupFailure = e;
                finalFileDeleted = false;
            }
            if (metadataInserted && asset != null) {
                if (finalFileDeleted) {
                    try {
                        if (assetMapper.deleteById(asset.getAssetId()) != 1) {
                            throw new IllegalStateException("文件元数据补偿删除未影响记录");
                        }
                    } catch (RuntimeException e) {
                        LOGGER.error("受控文件元数据补偿删除失败 assetId={}", asset.getAssetId(), e);
                        if (finalCleanupFailure == null) {
                            finalCleanupFailure = e;
                        }
                    }
                } else {
                    // Preserve a non-consumable marker when the physical compensation failed.
                    // This is preferable to deleting the only diagnostic identity with the file.
                    try {
                        asset.setStatus(DocumentFileStatus.REJECTED.name());
                        if (assetMapper.updateById(asset) != 1) {
                            throw new IllegalStateException("文件失败状态登记未影响记录");
                        }
                    } catch (RuntimeException e) {
                        LOGGER.error("受控文件失败状态登记失败 assetId={}", asset.getAssetId(), e);
                        if (finalCleanupFailure == null) {
                            finalCleanupFailure = e;
                        }
                    }
                }
            }
            if (finalCleanupFailure != null) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "受控文件提交补偿失败，请联系管理员诊断");
            }
            return;
        }
        if (metadataInserted && asset != null) {
            try {
                if (assetMapper.deleteById(asset.getAssetId()) != 1) {
                    throw new IllegalStateException("文件元数据补偿删除未影响记录");
                }
            } catch (RuntimeException e) {
                LOGGER.error("受控文件元数据补偿删除失败 assetId={}", asset.getAssetId(), e);
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "受控文件提交补偿失败，请联系管理员诊断");
            }
        }
    }

    private void markCurrentTransactionRollbackOnly() {
        try {
            org.springframework.transaction.interceptor.TransactionAspectSupport
                    .currentTransactionStatus().setRollbackOnly();
        } catch (IllegalStateException ignored) {
            // A manually initialized synchronization (used by narrow module tests) has no proxy status.
        }
    }

    void deleteFinalOrDiagnose(Path path, String assetId, String relativePath) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOGGER.error("受控文件最终文件补偿删除失败 assetId={} relativePath={}",
                    assetId, relativePath == null ? diagnosticPath(path) : relativePath, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "受控文件最终文件补偿失败，请联系管理员诊断");
        }
    }

    private ControlledDocumentAsset toAsset(ControlledDocumentAssetDO asset) {
        DocumentFileLimitSnapshot limits = new DocumentFileLimitSnapshot(asset.getMaxFileBytes(), asset.getMaxSpreadsheetRows(),
                asset.getMaxDocumentCharacters(), asset.getMaxDocumentChunks(), asset.getUnconfirmedRetentionDays(),
                asset.getResultRetentionDays());
        return new ControlledDocumentAsset(asset.getAssetId(), asset.getOriginalFilename(), asset.getActualContentType(),
                asset.getByteSize(), asset.getSha256(), asset.getOwnerId(), DocumentFilePurpose.valueOf(asset.getPurpose()),
                DocumentFileStatus.valueOf(asset.getStatus()), asset.getCreatedAt(), asset.getExpiresAt(), limits);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static ThreadPoolExecutor newProcessingExecutor() {
        return new ThreadPoolExecutor(PROCESSING_THREADS, PROCESSING_THREADS, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(PROCESSING_QUEUE), new ThreadPoolExecutor.AbortPolicy());
    }

    private RuntimeException unwrapFailure(Throwable cause) {
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new BusinessException(ErrorCode.INTERNAL_ERROR, "文件处理失败，请稍后重试");
    }

    private String normalizeFilename(String filename) {
        String normalized = Normalizer.normalize(filename, Normalizer.Form.NFKC).replaceAll("[\\p{Cntrl}]", "_");
        return normalized.length() > 255 ? normalized.substring(0, 255) : normalized;
    }

    private String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 && dot < filename.length() - 1 ? filename.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private String normalizeContentType(String contentType) {
        int semicolon = contentType.indexOf(';');
        return (semicolon >= 0 ? contentType.substring(0, semicolon) : contentType).trim().toLowerCase(Locale.ROOT);
    }

    private boolean isZip(byte[] bytes) {
        return bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K'
                && (bytes[2] == 3 && bytes[3] == 4 || bytes[2] == 5 && bytes[3] == 6);
    }

    private boolean safeZipName(String name) {
        if (name == null || name.isBlank() || name.startsWith("/") || name.startsWith("\\") || name.contains("\\")) {
            return false;
        }
        for (String segment : name.split("/")) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                return false;
            }
        }
        return true;
    }

    private String findEntry(Map<String, byte[]> entries, String name) {
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (entry.getKey().toLowerCase(Locale.ROOT).equals(name)) {
                return new String(entry.getValue(), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private BusinessException reject(String message) {
        return new BusinessException(ErrorCode.BUSINESS_REJECTED, message);
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOGGER.warn("受控文件临时文件清理失败 relativePath={}", diagnosticPath(path), e);
        }
    }

    private String diagnosticPath(Path path) {
        try {
            if (path != null && path.toAbsolutePath().normalize().startsWith(storageRoot)) {
                return storageRoot.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
            }
        } catch (RuntimeException ignored) {
            // Use only the file name below; diagnostic logging must not expose an arbitrary physical path.
        }
        return path == null || path.getFileName() == null ? "<unknown>" : path.getFileName().toString();
    }

    private record ValidationResult(String contentType, long byteSize, String sha256) {
    }
}
