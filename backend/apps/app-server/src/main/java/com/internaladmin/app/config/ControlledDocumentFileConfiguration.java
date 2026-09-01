package com.internaladmin.app.config;

import com.internaladmin.module.file.api.DocumentFileLimitSnapshot;
import com.internaladmin.module.file.api.DocumentImportLimitsProvider;
import com.internaladmin.module.iam.api.ImportLimitsApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 受控文件的应用装配边界。
 *
 * <p>module-file 不能直接依赖 IAM 表或 Mapper；这里通过公开的
 * {@link ImportLimitsApi} 在一次保存开始时取得类型化值，并转换为文件模块快照。</p>
 */
@Configuration
public class ControlledDocumentFileConfiguration {

    @Bean
    public DocumentImportLimitsProvider documentImportLimitsProvider(ImportLimitsApi importLimitsApi) {
        return () -> {
            ImportLimitsApi.ImportLimits limits = importLimitsApi.current();
            if (limits == null) {
                return null;
            }
            return new DocumentFileLimitSnapshot(
                    limits.maxFileBytes(),
                    limits.maxSpreadsheetRows(),
                    limits.maxDocumentCharacters(),
                    limits.maxDocumentChunks(),
                    limits.unconfirmedRetentionDays(),
                    limits.resultRetentionDays());
        };
    }
}
