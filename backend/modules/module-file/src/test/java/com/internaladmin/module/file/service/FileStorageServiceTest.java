package com.internaladmin.module.file.service;

import com.internaladmin.module.file.mapper.FileAssetMapper;
import com.internaladmin.module.file.model.entity.FileAssetDO;
import com.internaladmin.platform.kernel.error.BusinessException;
import com.internaladmin.platform.kernel.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.zip.CRC32;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class FileStorageServiceTest {

    /*
     * Fixture source: TwelveMonkeys tag twelvemonkeys-3.14.0,
     * imageio/imageio-webp/src/test/resources/webp/small_1x1.webp.
     * SHA-256: 2f34799482dd5349b549d113fdaa188714d9737fe414e71541b752627bedbde3.
     * Purpose: only verify the ImageIO WebP normal decoding path; not a product asset.
     *
     * BSD 3-Clause License
     * Copyright (c) 2008-2020, Harald Kuhr
     * All rights reserved.
     *
     * Redistribution and use in source and binary forms, with or without
     * modification, are permitted provided that the following conditions are met:
     * 1. Redistributions of source code must retain the above copyright notice,
     *    this list of conditions and the following disclaimer.
     * 2. Redistributions in binary form must reproduce the above copyright notice,
     *    this list of conditions and the following disclaimer in the documentation
     *    and/or other materials provided with the distribution.
     * 3. Neither the name of the copyright holder nor the names of its
     *    contributors may be used to endorse or promote products derived from
     *    this software without specific prior written permission.
     *
     * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
     * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
     * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
     * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
     * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
     * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
     * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
     * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
     * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
     * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
     */
    private static final byte[] VALID_WEBP = Base64.getDecoder().decode(
            "UklGRlYAAABXRUJQVlA4IDoAAADwAgCdASoBAAEAAEcIhYWIhYSIAgICdaoD+AP6Ag1NGAD+/vNYf/5gZt2KO//mBv/80F4SW6//zLwASUNNVAgAAAB0ZXN0MXgxAA==");

    private static final String VALID_WEBP_SHA_256 =
            "2f34799482dd5349b549d113fdaa188714d9737fe414e71541b752627bedbde3";

    @TempDir
    Path storageRoot;

    private FileAssetMapper fileAssetMapper;
    private FileStorageService fileStorageService;

    @BeforeEach
    void setUp() {
        fileAssetMapper = mock(FileAssetMapper.class);
        fileStorageService = new FileStorageService(fileAssetMapper, storageRoot.toString());
        doAnswer(invocation -> {
            FileAssetDO asset = invocation.getArgument(0);
            asset.setId(1001L);
            return 1;
        }).when(fileAssetMapper).insert(any(FileAssetDO.class));
    }

    @Test
    void storesJpegUsingDecoderDerivedExtensionAndContentType() throws Exception {
        Long fileId = fileStorageService.store(upload("original.jpeg", "image/jpeg", imageBytes("jpeg")));

        assertEquals(1001L, fileId);
        assertStoredAsset("image/jpeg", ".jpg");
    }

    @Test
    void storesPngUsingDecoderDerivedExtensionAndContentType() throws Exception {
        Long fileId = fileStorageService.store(upload("original.png", "image/png", imageBytes("png")));

        assertEquals(1001L, fileId);
        assertStoredAsset("image/png", ".png");
    }

    @Test
    void storesWebpUsingTwelveMonkeysReader() throws Exception {
        assertEquals(VALID_WEBP_SHA_256, sha256Hex(VALID_WEBP));

        Long fileId = fileStorageService.store(upload("original.webp", "image/webp", VALID_WEBP));

        assertEquals(1001L, fileId);
        assertStoredAsset("image/webp", ".webp");
    }

    @Test
    void rejectsMimeMasqueradeWithoutMapperOrFiles() throws Exception {
        assertRejected(upload("claimed.jpg", "image/jpeg", imageBytes("png")),
                "图片内容与声明的类型或扩展名不一致");
    }

    @Test
    void rejectsExtensionMasqueradeWithoutMapperOrFiles() throws Exception {
        assertRejected(upload("claimed.jpg", "image/png", imageBytes("png")),
                "图片内容与声明的类型或扩展名不一致");
    }

    @Test
    void rejectsTruncatedPngWithCorrectSignatureWithoutMapperOrFiles() throws Exception {
        assertRejected(upload("truncated.png", "image/png", pngHeader(1, 1)), "图片内容无法完整解码");
    }

    @Test
    void rejectsUnsupportedContentWithoutMapperOrFiles() throws Exception {
        assertRejected(upload("claimed.png", "image/png", "not an image".getBytes(StandardCharsets.UTF_8)),
                "仅支持 jpg/jpeg/png/webp 图片");
    }

    @Test
    void rejectsImageOverSingleSideLimitWithoutMapperOrFiles() throws Exception {
        assertRejected(upload("wide.png", "image/png", pngHeader(8193, 1)), "图片尺寸或总像素超过限制");
    }

    @Test
    void rejectsImageOverTotalPixelLimitWithoutMapperOrFiles() throws Exception {
        assertRejected(upload("pixels.png", "image/png", pngHeader(8000, 5001)), "图片尺寸或总像素超过限制");
    }

    @Test
    void rejectsAnimatedWebpWithoutMapperOrFiles() throws Exception {
        byte[] animatedWebp = animatedWebp();
        assertImageFrameCount(animatedWebp, 2);

        assertRejected(upload("animated.webp", "image/webp", animatedWebp), "仅支持单帧图片");
    }

    @Test
    void rejectsActualBytesOverTenMegabytesWithoutMapperOrFiles() throws Exception {
        assertRejected(upload("large.png", "image/png", new byte[10 * 1024 * 1024 + 1]), "文件大小不能超过 10MB");
    }

    @Test
    void removesFinalFileWhenMetadataRegistrationFails() throws Exception {
        doThrow(new IllegalStateException("mapper unavailable")).when(fileAssetMapper).insert(any(FileAssetDO.class));

        assertThrows(IllegalStateException.class,
                () -> fileStorageService.store(upload("original.png", "image/png", imageBytes("png"))));

        assertNoRegularFiles();
        verify(fileAssetMapper).insert(any(FileAssetDO.class));
    }

    private void assertRejected(MockMultipartFile file, String expectedMessage) throws Exception {
        BusinessException exception = assertThrows(BusinessException.class, () -> fileStorageService.store(file));

        assertEquals(ErrorCode.BUSINESS_REJECTED, exception.getErrorCode());
        assertEquals(expectedMessage, exception.getMessage());
        verifyNoInteractions(fileAssetMapper);
        assertNoRegularFiles();
    }

    private void assertImageFrameCount(byte[] image, int expectedFrameCount) throws IOException {
        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(image))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            assertTrue(readers.hasNext());
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, false);
                assertEquals(expectedFrameCount, reader.getNumImages(true));
            } finally {
                reader.dispose();
            }
        }
    }

    private String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private void assertStoredAsset(String expectedContentType, String expectedExtension) throws Exception {
        ArgumentCaptor<FileAssetDO> assetCaptor = ArgumentCaptor.forClass(FileAssetDO.class);
        verify(fileAssetMapper).insert(assetCaptor.capture());
        FileAssetDO asset = assetCaptor.getValue();
        assertEquals(expectedContentType, asset.getContentType());
        assertTrue(asset.getRelativePath().endsWith(expectedExtension));
        assertFalse(asset.getRelativePath().contains("original"));
        assertTrue(Files.isRegularFile(storageRoot.resolve(asset.getRelativePath())));
    }

    private void assertNoRegularFiles() throws IOException {
        try (Stream<Path> paths = Files.walk(storageRoot)) {
            assertEquals(0, paths.filter(Files::isRegularFile).count());
        }
    }

    private MockMultipartFile upload(String filename, String contentType, byte[] bytes) {
        return new MockMultipartFile("file", filename, contentType, bytes);
    }

    private byte[] imageBytes(String format) throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x336699);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            assertTrue(ImageIO.write(image, format, output));
            return output.toByteArray();
        }
    }

    private byte[] animatedWebp() throws IOException {
        byte[] staticVp8Chunk = Arrays.copyOfRange(VALID_WEBP, 12,
                20 + readLittleEndianInt(VALID_WEBP, 16));
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        writeLittleEndian24(frame, 0);
        writeLittleEndian24(frame, 0);
        writeLittleEndian24(frame, 0);
        writeLittleEndian24(frame, 0);
        writeLittleEndian24(frame, 100);
        frame.write(0);
        frame.write(staticVp8Chunk);

        ByteArrayOutputStream chunks = new ByteArrayOutputStream();
        writeWebpChunk(chunks, "VP8X", new byte[]{0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        writeWebpChunk(chunks, "ANIM", new byte[6]);
        writeWebpChunk(chunks, "ANMF", frame.toByteArray());
        writeWebpChunk(chunks, "ANMF", frame.toByteArray());

        ByteArrayOutputStream webp = new ByteArrayOutputStream();
        webp.write("RIFF".getBytes(StandardCharsets.US_ASCII));
        writeLittleEndian32(webp, 4 + chunks.size());
        webp.write("WEBP".getBytes(StandardCharsets.US_ASCII));
        webp.write(chunks.toByteArray());
        return webp.toByteArray();
    }

    private void writeWebpChunk(ByteArrayOutputStream output, String type, byte[] payload) throws IOException {
        output.write(type.getBytes(StandardCharsets.US_ASCII));
        writeLittleEndian32(output, payload.length);
        output.write(payload);
        if (payload.length % 2 != 0) {
            output.write(0);
        }
    }

    private void writeLittleEndian24(ByteArrayOutputStream output, int value) {
        output.write(value & 0xFF);
        output.write((value >>> 8) & 0xFF);
        output.write((value >>> 16) & 0xFF);
    }

    private void writeLittleEndian32(ByteArrayOutputStream output, int value) {
        output.write(value & 0xFF);
        output.write((value >>> 8) & 0xFF);
        output.write((value >>> 16) & 0xFF);
        output.write((value >>> 24) & 0xFF);
    }

    private int readLittleEndianInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private byte[] pngHeader(int width, int height) throws IOException {
        byte[] ihdr = ByteBuffer.allocate(13)
                .putInt(width)
                .putInt(height)
                .put((byte) 8)
                .put((byte) 2)
                .put((byte) 0)
                .put((byte) 0)
                .put((byte) 0)
                .array();
        CRC32 checksum = new CRC32();
        checksum.update("IHDR".getBytes(StandardCharsets.US_ASCII));
        checksum.update(ihdr);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            output.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
            output.write(ByteBuffer.allocate(4).putInt(ihdr.length).array());
            output.write("IHDR".getBytes(StandardCharsets.US_ASCII));
            output.write(ihdr);
            output.write(ByteBuffer.allocate(4).putInt((int) checksum.getValue()).array());
            return output.toByteArray();
        }
    }
}
