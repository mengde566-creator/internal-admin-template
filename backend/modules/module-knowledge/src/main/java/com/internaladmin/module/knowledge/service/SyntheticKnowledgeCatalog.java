package com.internaladmin.module.knowledge.service;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Fixed, repository-owned markdown catalog used by the Gate A import. */
public final class SyntheticKnowledgeCatalog {

    static final String INDEX_RESOURCE = "knowledge/synthetic/index.json";
    static final String CHUNKER_VERSION = "markdown-section-v1";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.+?)\\s*$");

    private SyntheticKnowledgeCatalog() {
    }

    /** Load and validate the fixed index and its classpath markdown resources. */
    public static List<Chunk> load() {
        try (InputStream input = SyntheticKnowledgeCatalog.class.getClassLoader()
                .getResourceAsStream(INDEX_RESOURCE)) {
            if (input == null) {
                throw invalid("固定知识索引资源缺失: " + INDEX_RESOURCE);
            }
            IndexEntry[] entries = JSON.readValue(input, IndexEntry[].class);
            return parse(entries, resource -> readResource(resource));
        } catch (IOException exception) {
            throw invalid("固定知识索引无法读取", exception);
        }
    }

    static List<Chunk> parse(String indexJson, Function<String, String> resourceLoader) {
        try {
            return parse(JSON.readValue(indexJson, IndexEntry[].class), resourceLoader);
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException illegalStateException) {
                throw illegalStateException;
            }
            throw invalid("固定知识索引无法解析", exception);
        }
    }

    private static List<Chunk> parse(IndexEntry[] entries, Function<String, String> resourceLoader) {
        if (entries == null || entries.length == 0) {
            throw invalid("固定知识索引不能为空");
        }
        List<Chunk> chunks = new ArrayList<>();
        Set<String> versionKeys = new HashSet<>();
        for (IndexEntry entry : entries) {
            requireText(entry.documentCode(), "documentCode");
            requireText(entry.versionCode(), "versionCode");
            requireText(entry.title(), "title");
            requireText(entry.status(), "status");
            requireText(entry.resource(), "resource");
            if (!entry.status().equals("ACTIVE") && !entry.status().equals("INACTIVE")) {
                throw invalid("固定知识索引状态无效: " + entry.status());
            }
            String key = entry.documentCode() + "\u0000" + entry.versionCode();
            if (!versionKeys.add(key)) {
                throw invalid("固定知识索引版本重复: " + key);
            }
            String markdown = resourceLoader.apply(entry.resource());
            if (markdown == null) {
                throw invalid("固定知识资源缺失: " + entry.resource());
            }
            List<Section> sections = markdownSections(markdown, entry.resource());
            for (int i = 0; i < sections.size(); i++) {
                Section section = sections.get(i);
                chunks.add(new Chunk(entry.documentCode(), entry.versionCode(), entry.title(), entry.status(),
                        i + 1, section.content()));
            }
        }
        return List.copyOf(chunks);
    }

    private static List<Section> markdownSections(String markdown, String resource) {
        if (markdown == null || markdown.isBlank()) {
            throw invalid("固定知识资源为空: " + resource);
        }
        List<Section> sections = new ArrayList<>();
        String heading = null;
        StringBuilder body = new StringBuilder();
        for (String line : markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            var matcher = HEADING.matcher(line);
            if (matcher.matches()) {
                if (heading != null) {
                    sections.add(section(heading, body, resource));
                }
                heading = matcher.group(1).trim();
                if (heading.isEmpty()) {
                    throw invalid("固定知识标题为空: " + resource);
                }
                body = new StringBuilder();
            } else if (heading != null) {
                body.append(line).append('\n');
            } else if (!line.isBlank()) {
                throw invalid("固定知识资源缺少Markdown标题: " + resource);
            }
        }
        if (heading == null) {
            throw invalid("固定知识资源缺少Markdown标题: " + resource);
        }
        sections.add(section(heading, body, resource));
        return sections;
    }

    private static Section section(String heading, StringBuilder body, String resource) {
        String normalizedBody = body.toString().strip();
        if (normalizedBody.isEmpty()) {
            throw invalid("固定知识标题段为空: " + resource + "#" + heading);
        }
        return new Section(heading, ("# " + heading + "\n\n" + normalizedBody).strip());
    }

    private static String readResource(String resource) {
        try (InputStream input = SyntheticKnowledgeCatalog.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                return null;
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw invalid("固定知识资源无法读取: " + resource, exception);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid("固定知识索引字段为空: " + field);
        }
    }

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException("AI_KNOWLEDGE_CATALOG_INVALID: " + message);
    }

    private static IllegalStateException invalid(String message, Throwable cause) {
        return new IllegalStateException("AI_KNOWLEDGE_CATALOG_INVALID: " + message, cause);
    }

    private record IndexEntry(String documentCode, String versionCode, String title, String status, String resource) {
    }

    private record Section(String heading, String content) {
    }

    public record Chunk(String documentCode, String versionCode, String title, String desiredStatus,
                        int chunkNo, String content) {
    }
}
