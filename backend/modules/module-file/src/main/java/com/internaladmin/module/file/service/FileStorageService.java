package com.internaladmin.module.file.service;

import com.internaladmin.module.file.mapper.FileAssetMapper;
import com.internaladmin.module.file.model.entity.FileAssetDO;
import com.internaladmin.platform.kernel.error.BusinessException;
import com.internaladmin.platform.kernel.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;

/**
 * 本地展示图片存储服务：在受控临时文件中验证真实内容，再登记可访问的最终文件。
 */
@Service
public class FileStorageService {

    /** 最大上传字节数：10MB（REQ-V01-006 已确认）。 */
    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;

    /** 图片单边最大像素数。 */
    private static final int MAX_IMAGE_SIDE = 8192;

    /** 图片最大总像素数。 */
    private static final long MAX_IMAGE_PIXELS = 40_000_000L;

    /** 受控临时上传目录名，不作为任何读取接口的公开路径。 */
    private static final String TEMPORARY_DIRECTORY = ".upload-tmp";

    private final FileAssetMapper fileAssetMapper;
    private final Path storageRoot;

    public FileStorageService(FileAssetMapper fileAssetMapper,
                              @Value("${app.storage-root:./data/uploads}") String storageRoot) {
        this.fileAssetMapper = fileAssetMapper;
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
    }

    /**
     * 存储通过真实内容校验的展示图片。
     *
     * <p>方法：{@code store}</p>
     *
     * <p>执行链路（共 7 步）：</p>
     * 1. 校验上传对象存在，并调用 {@link #ensureStorageRoot()} 准备受控存储根目录；
     * 2. 在非公开的临时目录创建文件，调用 {@link #copyToTemporaryFile(MultipartFile, Path)} 按实际字节流执行 10MB 上限；
     * 3. 调用 {@link #validateImage(Path)} 识别实际格式、校验尺寸、总像素、完整首帧解码和单帧策略；
     * 4. 调用 {@link #validateClientDeclaration(MultipartFile, ImageFormat)} 拒绝与实际格式不一致的客户端 MIME 或扩展名；
     * 5. 调用 {@link #createFinalPath(ImageFormat)} 从实际格式派生随机最终文件名和扩展名，并将已验证临时文件移入最终路径；
     * 6. 创建 {@link FileAssetDO}，将解码器派生的 Content-Type 与相对路径写入 {@link FileAssetMapper}；
     * 7. 任一写入、验证或登记步骤失败时调用 {@link #cleanupFailedFiles(Path, Path)} 清理临时及最终文件，成功时返回新文件 ID。
     *
     * @param file 管理端提交的展示图片文件
     * @return 新登记文件的应用生成 ID
     * @throws BusinessException 文件为空、超限、非允许图片、声明不一致或存储失败时抛出
     */
    public Long store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "上传文件不能为空");
        }

        Path temporaryFile = null;
        Path finalFile = null;
        boolean stored = false;
        try {
            ensureStorageRoot();
            Path temporaryDirectory = storageRoot.resolve(TEMPORARY_DIRECTORY);
            Files.createDirectories(temporaryDirectory);
            temporaryFile = Files.createTempFile(temporaryDirectory, "upload-", ".tmp");
            copyToTemporaryFile(file, temporaryFile);

            ImageFormat imageFormat = validateImage(temporaryFile);
            validateClientDeclaration(file, imageFormat);
            finalFile = createFinalPath(imageFormat);
            Files.createDirectories(finalFile.getParent());
            Files.move(temporaryFile, finalFile);
            temporaryFile = null;

            FileAssetDO asset = new FileAssetDO();
            asset.setRelativePath(storageRoot.relativize(finalFile).toString().replace('\\', '/'));
            asset.setContentType(imageFormat.contentType());
            fileAssetMapper.insert(asset);
            stored = true;
            return asset.getId();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件存储失败，请稍后重试");
        } finally {
            if (!stored) {
                cleanupFailedFiles(temporaryFile, finalFile);
            }
        }
    }

    /**
     * 将上传流写入受控临时文件并按实际读取字节限制大小。
     *
     * <p>方法：{@code copyToTemporaryFile}</p>
     *
     * <p>执行链路（共 3 步）：</p>
     * 1. 打开 {@link MultipartFile#getInputStream()} 与临时文件输出流，避免直接写入最终公开路径；
     * 2. 分块读取并累计真实字节数，超过 10MB 时立即拒绝，不信任客户端报告的大小；
     * 3. 写完后拒绝零字节输入，资源由 try-with-resources 关闭。
     *
     * @param file 上传文件
     * @param temporaryFile 已创建的受控临时文件
     * @throws IOException 输入流或临时文件写入失败时抛出
     * @throws BusinessException 文件实际字节数超限或为空时抛出
     */
    private void copyToTemporaryFile(MultipartFile file, Path temporaryFile) throws IOException {
        long copiedBytes = 0;
        byte[] buffer = new byte[8192];
        try (InputStream input = file.getInputStream(); OutputStream output = Files.newOutputStream(temporaryFile)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                copiedBytes += read;
                if (copiedBytes > MAX_FILE_SIZE) {
                    throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "文件大小不能超过 10MB");
                }
                output.write(buffer, 0, read);
            }
        }
        if (copiedBytes == 0) {
            throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "上传文件不能为空");
        }
    }

    /**
     * 通过 ImageIO SPI 识别并完整解码临时图片。
     *
     * <p>方法：{@code validateImage}</p>
     *
     * <p>执行链路（共 6 步）：</p>
     * 1. 调用 {@link ImageIO#createImageInputStream(Object)} 打开临时文件，无法建立图片输入流时明确拒绝；
     * 2. 调用 {@link ImageIO#getImageReaders(Object)} 取得可解码 reader，未知格式或互相矛盾的格式时拒绝；
     * 3. 根据 {@link ImageReader#getFormatName()} 映射 JPEG、PNG 或 WebP，拒绝非 0.1 允许的实际格式；
     * 4. 调用 {@link ImageReader#getNumImages(boolean)} 校验恰好一帧，拒绝动画或多帧图片；
     * 5. 调用 {@link ImageReader#getWidth(int)} 和 {@link ImageReader#getHeight(int)} 校验正尺寸、单边和总像素上限；
     * 6. 调用 {@link ImageReader#read(int)} 完整解码首帧，解码失败或返回 null 时拒绝，并返回已识别格式。
     *
     * @param temporaryFile 已按字节数限制写入的临时文件
     * @return 由解码器识别的允许图片格式
     * @throws BusinessException 格式、帧数、尺寸或完整解码不符合要求时抛出
     */
    private ImageFormat validateImage(Path temporaryFile) {
        ImageReader selectedReader = null;
        try (ImageInputStream input = ImageIO.createImageInputStream(temporaryFile.toFile())) {
            if (input == null) {
                throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "无法识别图片真实格式");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            ImageFormat imageFormat = null;
            while (readers.hasNext()) {
                ImageReader candidate = readers.next();
                ImageFormat candidateFormat = ImageFormat.fromReaderFormat(candidate.getFormatName());
                if (selectedReader != null && imageFormat != null
                        && candidateFormat != null && imageFormat != candidateFormat) {
                    candidate.dispose();
                    throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "图片格式识别结果不一致");
                }
                if (selectedReader == null) {
                    selectedReader = candidate;
                    imageFormat = candidateFormat;
                } else {
                    candidate.dispose();
                }
            }
            if (selectedReader == null || imageFormat == null) {
                throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "仅支持 jpg/jpeg/png/webp 图片");
            }

            selectedReader.setInput(input, false, false);
            if (selectedReader.getNumImages(true) != 1) {
                throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "仅支持单帧图片");
            }
            int width = selectedReader.getWidth(0);
            int height = selectedReader.getHeight(0);
            if (width <= 0 || height <= 0 || width > MAX_IMAGE_SIDE || height > MAX_IMAGE_SIDE
                    || (long) width * height > MAX_IMAGE_PIXELS) {
                throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "图片尺寸或总像素超过限制");
            }
            BufferedImage decodedImage = selectedReader.read(0);
            if (decodedImage == null) {
                throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "图片内容无法完整解码");
            }
            return imageFormat;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "图片内容无法完整解码");
        } finally {
            if (selectedReader != null) {
                selectedReader.dispose();
            }
        }
    }

    /**
     * 校验客户端声明仅与已识别真实格式一致。
     *
     * <p>方法：{@code validateClientDeclaration}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 调用 {@link #normalizeContentType(String)} 和 {@link #resolveExtension(String)} 规范化客户端 MIME 与扩展名；
     * 2. 与 {@link ImageFormat} 的解码器结果逐项比较，任一不一致时拒绝伪装或错误声明。
     *
     * @param file 客户端上传对象
     * @param imageFormat 已实际解码的图片格式
     * @throws BusinessException MIME 或扩展名与真实格式不一致时抛出
     */
    private void validateClientDeclaration(MultipartFile file, ImageFormat imageFormat) {
        String contentType = normalizeContentType(file.getContentType());
        String extension = resolveExtension(file.getOriginalFilename());
        if (!imageFormat.contentType().equals(contentType) || !imageFormat.matchesExtension(extension)) {
            throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "图片内容与声明的类型或扩展名不一致");
        }
    }

    /**
     * 基于真实图片格式生成随机最终文件路径。
     *
     * <p>方法：{@code createFinalPath}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 使用当前日期和随机 UUID 生成系统控制的相对路径，不使用用户输入的文件名或路径；
     * 2. 使用 {@link ImageFormat#extension()} 追加解码器派生扩展名，并解析到受控存储根目录。
     *
     * @param imageFormat 已实际解码的图片格式
     * @return 尚未写入的最终文件绝对路径
     */
    private Path createFinalPath(ImageFormat imageFormat) {
        String relativePath = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "/" + UUID.randomUUID().toString().replace("-", "") + "." + imageFormat.extension();
        return storageRoot.resolve(relativePath);
    }

    /**
     * 确保存储根目录存在。
     *
     * <p>方法：{@code ensureStorageRoot}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 调用 {@link Files#createDirectories(Path, java.nio.file.attribute.FileAttribute[])} 创建存储根目录及父目录；
     * 2. 目录不可创建时抛出明确的基础设施异常，不静默回退到其他路径。
     *
     * @throws IllegalStateException 存储根目录无法创建时抛出
     */
    private void ensureStorageRoot() {
        try {
            Files.createDirectories(storageRoot);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建文件存储目录: " + storageRoot, e);
        }
    }

    /**
     * 清理一次失败上传已创建的临时和最终文件。
     *
     * <p>方法：{@code cleanupFailedFiles}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 依次删除已经创建的最终文件与临时文件，二者为 null 时跳过；
     * 2. 任一删除失败时抛出基础设施异常，使“清理未完成”可见而非被忽略。
     *
     * @param temporaryFile 可能存在的临时文件
     * @param finalFile 可能存在的最终文件
     * @throws IllegalStateException 任一文件无法删除时抛出
     */
    private void cleanupFailedFiles(Path temporaryFile, Path finalFile) {
        IOException cleanupFailure = null;
        cleanupFailure = deleteIfPresent(finalFile, cleanupFailure);
        cleanupFailure = deleteIfPresent(temporaryFile, cleanupFailure);
        if (cleanupFailure != null) {
            throw new IllegalStateException("上传失败后的文件清理失败", cleanupFailure);
        }
    }

    /**
     * 删除一次失败上传可能遗留的单个文件。
     *
     * <p>方法：{@code deleteIfPresent}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 路径不存在时保留已有清理异常并直接返回；
     * 2. 调用 {@link Files#deleteIfExists(Path)} 删除文件，失败时创建或追加清理异常，供 {@link #cleanupFailedFiles(Path, Path)} 统一上报。
     *
     * @param file 可能需要删除的文件路径
     * @param cleanupFailure 此前删除操作产生的异常，可为 null
     * @return 累积后的清理异常；全部删除成功时为 null
     */
    private IOException deleteIfPresent(Path file, IOException cleanupFailure) {
        if (file == null) {
            return cleanupFailure;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            if (cleanupFailure == null) {
                return e;
            }
            cleanupFailure.addSuppressed(e);
        }
        return cleanupFailure;
    }

    /**
     * 规范化媒体类型（忽略大小写与参数部分）。
     *
     * @param contentType 原始媒体类型，可为 null
     * @return 规范化媒体类型
     */
    private String normalizeContentType(String contentType) {
        if (contentType == null) {
            return "";
        }
        int semicolon = contentType.indexOf(';');
        String normalized = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
        return normalized.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 从文件名解析扩展名。
     *
     * @param filename 原始文件名，可为 null
     * @return 小写扩展名；无扩展名时返回 null
     */
    private String resolveExtension(String filename) {
        if (filename == null) {
            return null;
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return null;
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 支持的实际图片格式及其存储事实。 */
    private enum ImageFormat {
        JPEG("image/jpeg", "jpg"),
        PNG("image/png", "png"),
        WEBP("image/webp", "webp");

        private final String contentType;
        private final String extension;

        ImageFormat(String contentType, String extension) {
            this.contentType = contentType;
            this.extension = extension;
        }

        private static ImageFormat fromReaderFormat(String readerFormat) {
            if (readerFormat == null) {
                return null;
            }
            return switch (readerFormat.toUpperCase(Locale.ROOT)) {
                case "JPEG", "JPG" -> JPEG;
                case "PNG" -> PNG;
                case "WEBP" -> WEBP;
                default -> null;
            };
        }

        private String contentType() {
            return contentType;
        }

        private String extension() {
            return extension;
        }

        private boolean matchesExtension(String candidateExtension) {
            return this == JPEG
                    ? "jpg".equals(candidateExtension) || "jpeg".equals(candidateExtension)
                    : extension.equals(candidateExtension);
        }
    }
}
