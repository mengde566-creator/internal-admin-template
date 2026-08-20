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
        assertThat(first).hasSize(4);
        assertThat(first).extracting(SyntheticKnowledgeCatalog.Chunk::documentCode)
                .containsExactlyInAnyOrder("warehouse-rules", "warehouse-rules", "item-codes", "warehouse-codes");
        assertThat(first).filteredOn(chunk -> chunk.documentCode().equals("warehouse-rules"))
                .extracting(SyntheticKnowledgeCatalog.Chunk::versionCode)
                .containsExactlyInAnyOrder("v0", "v1");
        assertThat(first).allSatisfy(chunk -> assertThat(chunk.chunkNo()).isEqualTo(1));
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
    }
}
