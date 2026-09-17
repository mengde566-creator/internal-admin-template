package com.internaladmin.module.knowledge.service;

import com.internaladmin.module.knowledge.api.KnowledgeContentPack;
import org.springframework.core.io.ByteArrayResource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Test-only catalog fixture retained for legacy provider-gate tests. Production
 * content is supplied by an adapter {@link com.internaladmin.module.knowledge.api.KnowledgeContentPack}.
 */
final class SyntheticKnowledgeCatalog {
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.+?)\\s*$");

    private SyntheticKnowledgeCatalog() {
    }

    static List<Chunk> load() {
        List<Chunk> chunks = new ArrayList<>();
        add(chunks, "warehouse-rules", "v0", "INACTIVE");
        add(chunks, "warehouse-rules", "v1", "INACTIVE");
        add(chunks, "warehouse-rules", "v2", "ACTIVE");
        add(chunks, "item-codes", "v1", "INACTIVE");
        add(chunks, "item-codes", "v2", "ACTIVE");
        add(chunks, "warehouse-codes", "v1", "INACTIVE");
        add(chunks, "warehouse-codes", "v2", "ACTIVE");
        add(chunks, "low-stock-policy", "v1", "ACTIVE");
        return List.copyOf(chunks);
    }

    static KnowledgeContentPack pack() {
        List<KnowledgeContentPack.Document> documents = new ArrayList<>();
        List<String[]> definitions = List.of(
                new String[]{"warehouse-rules", "v0", "INACTIVE", "1"},
                new String[]{"warehouse-rules", "v1", "INACTIVE", "1"},
                new String[]{"warehouse-rules", "v2", "ACTIVE", "1"},
                new String[]{"item-codes", "v1", "INACTIVE", "2"},
                new String[]{"item-codes", "v2", "ACTIVE", "2"},
                new String[]{"warehouse-codes", "v1", "INACTIVE", "3"},
                new String[]{"warehouse-codes", "v2", "ACTIVE", "3"},
                new String[]{"low-stock-policy", "v1", "ACTIVE", "4"});
        for (String[] definition : definitions) {
            String document = definition[0];
            String version = definition[1];
            String markdown = load().stream()
                    .filter(chunk -> document.equals(chunk.documentCode()) && version.equals(chunk.versionCode()))
                    .map(Chunk::content)
                    .reduce((left, right) -> left + "\n\n" + right)
                    .orElseThrow();
            byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
            documents.add(new KnowledgeContentPack.Document(document, version, document + " " + version,
                    definition[2], Integer.parseInt(definition[3]), new ByteArrayResource(bytes), sha256(bytes)));
        }
        return new KnowledgeContentPack() {
            @Override public String packId() { return "test-knowledge-pack"; }
            @Override public String packVersion() { return "test-knowledge-pack-v1"; }
            @Override public String compatibilityVersion() { return KnowledgeContentPackRegistry.COMPATIBILITY_VERSION; }
            @Override public List<Document> documents() { return List.copyOf(documents); }
        };
    }

    private static void add(List<Chunk> chunks, String document, String version, String status) {
        for (int chunkNo = 1; chunkNo <= 3; chunkNo++) {
            chunks.add(new Chunk(document, version, document + " " + version, status, chunkNo,
                    "# " + document + " " + version + " section " + chunkNo + "\n\n内容 " + document + " " + version + " " + chunkNo));
        }
    }

    static List<Chunk> parse(String indexJson, Function<String, String> resourceLoader) {
        if (indexJson == null || indexJson.isBlank()) throw invalid("索引无法解析");
        List<Chunk> chunks = new ArrayList<>();
        Set<String> versions = new HashSet<>();
        Set<String> documents = new HashSet<>();
        Set<String> activeDocuments = new HashSet<>();
        String body = indexJson.replace("[", "").replace("]", "");
        if (body.isBlank()) throw invalid("索引不能为空");
        for (String raw : body.split("\\},\\s*\\{")) {
            String entry = raw.replace("{", "").replace("}", "");
            String document = value(entry, "documentCode");
            String version = value(entry, "versionCode");
            String title = value(entry, "title");
            String status = value(entry, "status");
            String resource = value(entry, "resource");
            require(document, "documentCode"); require(version, "versionCode");
            require(title, "title"); require(status, "status"); require(resource, "resource");
            if (!("ACTIVE".equals(status) || "INACTIVE".equals(status))) throw invalid("状态无效");
            if (!versions.add(document + "\u0000" + version)) throw invalid("版本重复");
            documents.add(document);
            if ("ACTIVE".equals(status) && !activeDocuments.add(document)) throw invalid("ACTIVE版本重复");
            String markdown = resourceLoader.apply(resource);
            if (markdown == null) throw invalid("资源缺失");
            List<String> sections = sections(markdown);
            for (int i = 0; i < sections.size(); i++) {
                chunks.add(new Chunk(document, version, title, status, i + 1, sections.get(i)));
            }
        }
        if (!activeDocuments.containsAll(documents)) throw invalid("每个文档必须有一个ACTIVE版本");
        return List.copyOf(chunks);
    }

    private static List<String> sections(String markdown) {
        if (markdown == null || markdown.isBlank()) throw invalid("资源为空");
        List<String> result = new ArrayList<>();
        String heading = null;
        StringBuilder text = new StringBuilder();
        for (String line : markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            var matcher = HEADING.matcher(line);
            if (matcher.matches()) {
                if (heading != null) addSection(result, heading, text);
                heading = matcher.group(1).trim();
                if (heading.isEmpty()) throw invalid("标题为空");
                text = new StringBuilder();
            } else if (heading != null) {
                text.append(line).append('\n');
            } else if (!line.isBlank()) {
                throw invalid("缺少Markdown标题");
            }
        }
        if (heading == null) throw invalid("缺少Markdown标题");
        addSection(result, heading, text);
        return result;
    }

    private static void addSection(List<String> result, String heading, StringBuilder text) {
        String content = text.toString().strip();
        if (content.isEmpty()) throw invalid("标题段为空");
        result.add("# " + heading + "\n\n" + content);
    }

    private static String value(String entry, String key) {
        String marker = "\"" + key + "\":\"";
        int start = entry.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        int end = entry.indexOf('"', start);
        return end < 0 ? null : entry.substring(start, end);
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw invalid("字段为空: " + field);
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException("AI_KNOWLEDGE_CATALOG_INVALID: " + message);
    }

    record Chunk(String documentCode, String versionCode, String title, String desiredStatus,
                 int chunkNo, String content) {
    }
}
