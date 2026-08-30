package com.internaladmin.module.knowledge.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyntheticKnowledgeCatalogTest {

    @Test
    void fixedIndexLoadsStableMarkdownChunksForAllGateCategories() {
        var first = SyntheticKnowledgeCatalog.load();
        var second = SyntheticKnowledgeCatalog.load();

        assertThat(first).isEqualTo(second);
        assertThat(first).hasSizeGreaterThan(20);
        assertThat(first).extracting(SyntheticKnowledgeCatalog.Chunk::documentCode)
                .contains("warehouse-rules", "item-codes", "warehouse-codes", "low-stock-policy");
        assertThat(first).filteredOn(chunk -> chunk.documentCode().equals("warehouse-rules"))
                .extracting(SyntheticKnowledgeCatalog.Chunk::versionCode)
                .contains("v0", "v1", "v2");
        assertThat(first).allSatisfy(chunk -> assertThat(chunk.chunkNo()).isPositive());
        assertThat(first).allSatisfy(chunk -> assertThat(chunk.content()).contains("# ").isNotEmpty());

        String multiSection = "[{\"documentCode\":\"multi\",\"versionCode\":\"v1\","
                + "\"title\":\"多段\",\"status\":\"ACTIVE\",\"resource\":\"multi.md\"}]";
        assertThat(SyntheticKnowledgeCatalog.parse(multiSection,
                        ignored -> "# 一段\n\n内容一\n\n## 二段\n\n内容二"))
                .extracting(SyntheticKnowledgeCatalog.Chunk::chunkNo)
                .containsExactly(1, 2);
    }

    @Test
    void missingResourceDuplicateVersionAndEmptySectionFailClearly() {
        String missing = "[{\"documentCode\":\"a\",\"versionCode\":\"v1\","
                + "\"title\":\"A\",\"status\":\"ACTIVE\",\"resource\":\"missing.md\"}]";
        assertThatThrownBy(() -> SyntheticKnowledgeCatalog.parse(missing, ignored -> null))
                .hasMessageContaining("资源缺失");

        String duplicate = "[{\"documentCode\":\"a\",\"versionCode\":\"v1\","
                + "\"title\":\"A\",\"status\":\"ACTIVE\",\"resource\":\"a.md\"},"
                + "{\"documentCode\":\"a\",\"versionCode\":\"v1\","
                + "\"title\":\"A\",\"status\":\"ACTIVE\",\"resource\":\"a.md\"}]";
        assertThatThrownBy(() -> SyntheticKnowledgeCatalog.parse(duplicate,
                        ignored -> "# A\n\nbody"))
                .hasMessageContaining("版本重复");

        String emptySection = "[{\"documentCode\":\"a\",\"versionCode\":\"v1\","
                + "\"title\":\"A\",\"status\":\"ACTIVE\",\"resource\":\"a.md\"}]";
        assertThatThrownBy(() -> SyntheticKnowledgeCatalog.parse(emptySection,
                        ignored -> "# A"))
                .hasMessageContaining("标题段为空");

        String blankTitle = "[{\"documentCode\":\"a\",\"versionCode\":\"v1\","
                + "\"title\":\" \",\"status\":\"ACTIVE\",\"resource\":\"a.md\"}]";
        assertThatThrownBy(() -> SyntheticKnowledgeCatalog.parse(blankTitle,
                        ignored -> "# A\n\nbody"))
                .hasMessageContaining("字段为空: title");

        assertThatThrownBy(() -> SyntheticKnowledgeCatalog.parse(emptySection,
                        ignored -> "没有标题的正文"))
                .hasMessageContaining("缺少Markdown标题");

        String noActiveVersion = "[{\"documentCode\":\"a\",\"versionCode\":\"v1\","
                + "\"title\":\"A\",\"status\":\"INACTIVE\",\"resource\":\"a.md\"}]";
        assertThatThrownBy(() -> SyntheticKnowledgeCatalog.parse(noActiveVersion,
                        ignored -> "# A\n\nbody"))
                .hasMessageContaining("每个文档必须有一个ACTIVE版本");
    }
}
