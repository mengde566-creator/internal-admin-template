package com.internaladmin.app.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 应用级数据源配置。
 *
 * <p>未配置外部数据库时使用项目本地 SQLite 文件库（零配置启动）：
 * 启动前确保数据目录存在，使 SQLite 数据文件可被创建（REQ-V01-001）。</p>
 */
@Configuration
public class AppDataSourceConfig {

    /**
     * 创建数据源；SQLite 模式下先创建数据目录。
     *
     * <p>方法：{@code dataSource}</p>
     *
     * <p>执行链路（共 4 步）：</p>
     * 1. 读取 {@code spring.datasource.url}；
     * 2. 若为 SQLite 连接，解析文件路径并调用 {@link #ensureDataDirectory(String)} 创建父目录；
     * 3. 通过 {@link DataSourceBuilder} 构建数据源；
     * 4. 返回数据源。
     *
     * @param url 数据源 JDBC URL
     * @return 数据源
     */
    @Bean
    public DataSource dataSource(@Value("${spring.datasource.url}") String url) {
        if (url.startsWith("jdbc:sqlite:")) {
            ensureDataDirectory(url);
        }
        return DataSourceBuilder.create().url(url).build();
    }

    /**
     * 确保 SQLite 数据文件的父目录存在。
     *
     * <p>方法：{@code ensureDataDirectory}</p>
     *
     * <p>执行链路（共 3 步）：</p>
     * 1. 去掉连接串中的查询参数（如 {@code ?foreign_keys=on}），取文件路径；
     * 2. 转绝对路径并取得父目录；
     * 3. 目录不存在时调用 {@link Files#createDirectories(Path)} 创建；失败时抛出启动异常（快速失败）。
     *
     * @param url SQLite JDBC URL
     * @throws IllegalStateException 数据目录创建失败时抛出（启动失败，不静默降级）
     */
    private void ensureDataDirectory(String url) {
        String filePart = url.substring("jdbc:sqlite:".length());
        int queryIndex = filePart.indexOf('?');
        if (queryIndex >= 0) {
            filePart = filePart.substring(0, queryIndex);
        }
        Path parent = Path.of(filePart).toAbsolutePath().getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建 SQLite 数据目录: " + parent, e);
        }
    }
}
