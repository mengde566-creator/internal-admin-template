package com.internaladmin.module.knowledge.service;

import com.internaladmin.module.file.api.DocumentFileLimitSnapshot;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeDocumentParserTest {

    private final KnowledgeDocumentParser parser = new KnowledgeDocumentParser();
    private final DocumentFileLimitSnapshot limits = new DocumentFileLimitSnapshot(10_000_000, 100_000, 20_000, 20, 7, 30);

    @Test
    void markdownIsDeterministicAndKeepsVisibleHeadingsListsAndTables() {
        byte[] source = "# 入库规则\n\n- 必须核对编码\n\n|字段|要求|\n|---|---|\n|编码|必填|\n\n<script>alert(1)</script>\n".getBytes(StandardCharsets.UTF_8);

        KnowledgeDocumentParser.ParsedDocument first = parser.parse(source, "rules.md", limits);
        KnowledgeDocumentParser.ParsedDocument second = parser.parse(source, "rules.md", limits);

        assertThat(first).isEqualTo(second);
        assertThat(first.sections()).singleElement().satisfies(section -> {
            assertThat(section.heading()).isEqualTo("入库规则");
            assertThat(section.content()).contains("必须核对编码", "字段|要求").doesNotContain("script");
        });
        assertThat(first.ignoredCount()).isPositive();
    }

    @Test
    void textRejectsMalformedUtf8AndEnforcesBoundedPreview() {
        assertThatThrownBy(() -> parser.parse(new byte[]{(byte) 0xc3, 0x28}, "rules.txt", limits))
                .hasMessageContaining("KNOWLEDGE_DRAFT_ENCODING");
        DocumentFileLimitSnapshot tiny = new DocumentFileLimitSnapshot(100, 1, 5, 1, 7, 30);
        KnowledgeDocumentParser.ParsedDocument parsed = parser.parse("abcdef\n第二行".getBytes(StandardCharsets.UTF_8), "rules.txt", tiny);
        assertThat(parsed.truncated()).isTrue();
        assertThat(parsed.characterCount()).isLessThanOrEqualTo(5);
        assertThat(parsed.sections()).hasSize(1);
    }

    @Test
    void docxReadsHeadingParagraphListAndTableWithoutExecutingBody() throws Exception {
        byte[] docx;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XWPFParagraph heading = document.createParagraph();
            heading.setStyle("Heading1");
            heading.createRun().setText("盘点规则");
            document.createParagraph().createRun().setText("不得自动补货");
            document.createParagraph().createRun().setText("- 记录差异");
            XWPFTable table = document.createTable(1, 2);
            XWPFTableRow row = table.getRow(0);
            row.getCell(0).setText("字段");
            row.getCell(1).setText("要求");
            document.write(output);
            docx = output.toByteArray();
        }
        KnowledgeDocumentParser.ParsedDocument parsed = parser.parse(docx, "rules.docx", limits);
        assertThat(parsed.sections()).isNotEmpty();
        assertThat(parsed.sections().getFirst().content()).contains("盘点规则", "不得自动补货");
        assertThat(parsed.sections().stream().map(KnowledgeDocumentParser.Section::content))
                .anyMatch(content -> content.contains("字段") && content.contains("要求"));
    }

    @Test
    void docxContainerMetadataDoesNotChangeNormalizedSections() throws Exception {
        byte[] first = docxWithCreator("first-container");
        byte[] second = docxWithCreator("second-container");

        KnowledgeDocumentParser.ParsedDocument firstParsed = parser.parse(first, "rules.docx", limits);
        KnowledgeDocumentParser.ParsedDocument secondParsed = parser.parse(second, "rules.docx", limits);

        assertThat(firstParsed.sections()).isEqualTo(secondParsed.sections());
        assertThat(firstParsed.characterCount()).isEqualTo(secondParsed.characterCount());
    }

    private static byte[] docxWithCreator(String creator) throws Exception {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.getProperties().getCoreProperties().setCreator(creator);
            XWPFParagraph heading = document.createParagraph();
            heading.setStyle("Heading1");
            heading.createRun().setText("盘点规则");
            document.createParagraph().createRun().setText("不得自动补货");
            document.write(output);
            return output.toByteArray();
        }
    }
}
