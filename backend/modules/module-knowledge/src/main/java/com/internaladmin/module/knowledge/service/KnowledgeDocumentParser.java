package com.internaladmin.module.knowledge.service;

import com.internaladmin.module.file.api.DocumentFileLimitSnapshot;
import com.internaladmin.platform.kernel.error.BusinessException;
import com.internaladmin.platform.kernel.error.ErrorCode;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识草稿的确定性文本提取器。
 *
 * <p>只读取用户可见的段落、标题、列表和表格文字，不渲染 Markdown、不执行文档字段，
 * 也不把正文当作指令。解析结果受创建文件时的限制快照约束。</p>
 */
public final class KnowledgeDocumentParser {

    public static final String PARSER_VERSION = "knowledge-document-parser-v1";
    private static final int HARD_MAX_CHARS = 1_000_000;
    private static final int HARD_MAX_SECTIONS = 2_000;
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^#{1,6}\\s+(.+?)\\s*$");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]*>");

    /** 解析受控文件，返回有界章节、摘要计数和被忽略内容计数。 */
    public ParsedDocument parse(byte[] bytes, String filename, DocumentFileLimitSnapshot limits) {
        if (bytes == null || bytes.length == 0) {
            throw reject("KNOWLEDGE_DRAFT_EMPTY: 文档正文为空");
        }
        String extension = extension(filename);
        return switch (extension) {
            case "docx" -> parseDocx(bytes, limits);
            case "md" -> parseMarkdown(decodeUtf8(bytes), limits);
            case "txt" -> parseText(decodeUtf8(bytes), limits);
            default -> throw reject("KNOWLEDGE_DRAFT_FORMAT: 仅支持 docx、md、txt 文件");
        };
    }

    private ParsedDocument parseDocx(byte[] bytes, DocumentFileLimitSnapshot limits) {
        List<Block> blocks = new ArrayList<>();
        int ignored = 0;
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String text = normalise(paragraph.getText());
                if (text.isEmpty()) {
                    ignored++;
                    continue;
                }
                String style = paragraph.getStyle();
                String heading = headingFromStyle(style, text);
                blocks.add(new Block(heading, text));
            }
            for (XWPFTable table : document.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    List<String> cells = new ArrayList<>();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        String text = normalise(cell.getText());
                        if (!text.isEmpty()) cells.add(text);
                    }
                    if (!cells.isEmpty()) blocks.add(new Block(null, String.join(" | ", cells)));
                    else ignored++;
                }
            }
        } catch (IOException | RuntimeException exception) {
            throw reject("KNOWLEDGE_DRAFT_CORRUPT: DOCX 无法解析");
        }
        return finish(blocks, ignored, limits);
    }

    private ParsedDocument parseMarkdown(String markdown, DocumentFileLimitSnapshot limits) {
        List<Block> blocks = new ArrayList<>();
        int ignored = 0;
        boolean fenced = false;
        for (String raw : markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String line = normalise(raw);
            if (line.startsWith("```") || line.startsWith("~~~")) {
                fenced = !fenced;
                ignored++;
                continue;
            }
            if (fenced || line.isEmpty()) {
                if (!line.isEmpty()) ignored++;
                continue;
            }
            if (HTML_TAG.matcher(line).find() || line.startsWith("<") || line.startsWith("</")) {
                ignored++;
                continue;
            }
            Matcher heading = MARKDOWN_HEADING.matcher(line);
            if (heading.matches()) {
                blocks.add(new Block(normalise(heading.group(1)), normalise(heading.group(1))));
            } else {
                blocks.add(new Block(null, line));
            }
        }
        return finish(blocks, ignored, limits);
    }

    private ParsedDocument parseText(String text, DocumentFileLimitSnapshot limits) {
        List<Block> blocks = new ArrayList<>();
        int ignored = 0;
        StringBuilder body = new StringBuilder();
        for (String raw : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String line = normalise(raw);
            if (line.isEmpty()) {
                ignored++;
                continue;
            }
            if (body.length() > 0) body.append('\n');
            body.append(line);
        }
        if (body.length() > 0) blocks.add(new Block(null, body.toString()));
        return finish(blocks, ignored, limits);
    }

    private ParsedDocument finish(List<Block> blocks, int ignored, DocumentFileLimitSnapshot suppliedLimits) {
        DocumentFileLimitSnapshot limits = suppliedLimits == null
                ? new DocumentFileLimitSnapshot(10L * 1024 * 1024, 100_000, HARD_MAX_CHARS, HARD_MAX_SECTIONS, 7, 30)
                : suppliedLimits;
        int maxChars = bounded(limits.maxDocumentCharacters(), HARD_MAX_CHARS, "KNOWLEDGE_DRAFT_LIMIT: 文档字符上限无效");
        int maxSections = bounded(limits.maxDocumentChunks(), HARD_MAX_SECTIONS, "KNOWLEDGE_DRAFT_LIMIT: 文档分片上限无效");
        List<Section> sections = new ArrayList<>();
        String currentHeading = null;
        StringBuilder current = new StringBuilder();
        for (Block block : blocks) {
            if (block.heading() != null && current.length() > 0) {
                sections.add(section(sections.size() + 1, currentHeading, current.toString()));
                current = new StringBuilder();
            }
            if (block.heading() != null) currentHeading = block.heading();
            if (current.length() > 0) current.append('\n');
            current.append(block.text());
        }
        if (current.length() > 0) sections.add(section(sections.size() + 1, currentHeading, current.toString()));
        if (sections.isEmpty()) throw reject("KNOWLEDGE_DRAFT_EMPTY: 文档正文为空");

        boolean truncated = sections.size() > maxSections;
        if (sections.size() > maxSections) sections = new ArrayList<>(sections.subList(0, maxSections));
        int used = 0;
        List<Section> bounded = new ArrayList<>();
        for (Section section : sections) {
            if (used >= maxChars) {
                truncated = true;
                break;
            }
            int remaining = maxChars - used;
            String content = section.content();
            if (content.length() > remaining) {
                content = content.substring(0, remaining).stripTrailing();
                truncated = true;
            }
            if (!content.isBlank()) {
                bounded.add(new Section(bounded.size() + 1, section.sectionKey(), section.heading(), content));
                used += content.length();
            }
        }
        if (bounded.isEmpty()) throw reject("KNOWLEDGE_DRAFT_EMPTY: 文档正文为空");
        return new ParsedDocument(List.copyOf(bounded), used, ignored, truncated);
    }

    private static Section section(int number, String heading, String content) {
        String key = heading == null || heading.isBlank() ? "section-" + number : slug(heading);
        return new Section(number, key, heading, content);
    }

    private static String slug(String heading) {
        String value = heading.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{Nd}]+", "-").replaceAll("^-|-$", "");
        return value.isBlank() ? "section" : value;
    }

    private static int bounded(int value, int hardMaximum, String message) {
        if (value < 1 || value > hardMaximum) throw reject(message);
        return value;
    }

    private static String headingFromStyle(String style, String text) {
        if (style != null && style.matches("(?i)heading\\s*[1-6]")) return text;
        return null;
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw reject("KNOWLEDGE_DRAFT_ENCODING: 文本必须使用有效 UTF-8 编码");
        }
    }

    private static String normalise(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("[ \\t\\f\\v]+", " ").trim();
    }

    private static String extension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static BusinessException reject(String message) {
        return new BusinessException(ErrorCode.BUSINESS_REJECTED, message);
    }

    public record ParsedDocument(List<Section> sections, int characterCount, int ignoredCount, boolean truncated) {
        public ParsedDocument {
            sections = sections == null ? List.of() : List.copyOf(sections);
        }
    }

    public record Section(int sectionNo, String sectionKey, String heading, String content) {
    }

    private record Block(String heading, String text) {
    }
}
