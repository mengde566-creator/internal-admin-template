package com.internaladmin.module.iam.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.internaladmin.module.iam.api.ImportLimitConfig;
import com.internaladmin.module.iam.api.ImportLimitsApi;
import com.internaladmin.module.iam.model.dto.ImportLimitsDTO;
import com.internaladmin.module.iam.model.dto.UpdateImportLimitsDTO;
import com.internaladmin.module.iam.mapper.SystemConfigMapper;
import com.internaladmin.module.iam.model.dto.SystemConfigDTO;
import com.internaladmin.module.iam.model.entity.SystemConfigDO;
import com.internaladmin.platform.kernel.error.BusinessException;
import com.internaladmin.platform.kernel.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Arrays;

/**
 * 系统参数服务：读取与更新全局配置。
 */
@Service
public class SystemConfigService implements ImportLimitsApi {

    /** 是否强制首次登录修改密码的参数键 */
    public static final String KEY_FORCE_PASSWORD_CHANGE = "force_password_change";

    private final SystemConfigMapper systemConfigMapper;

    public SystemConfigService(SystemConfigMapper systemConfigMapper) {
        this.systemConfigMapper = systemConfigMapper;
    }

    /**
     * 查询全部系统参数。
     *
     * <p>方法：{@code list}</p>
     *
     * <p>执行链路（共 3 步）：</p>
     * 1. 查询全部 {@link SystemConfigDO}，按 ID 升序；
     * 2. 转换为 DTO 列表；
     * 3. 返回。
     *
     * @return 参数列表
     */
    public List<SystemConfigDTO> list() {
        return systemConfigMapper.selectList(
                        new LambdaQueryWrapper<SystemConfigDO>().orderByAsc(SystemConfigDO::getId))
                .stream()
                .map(config -> {
                    SystemConfigDTO dto = new SystemConfigDTO();
                    dto.setId(config.getId());
                    dto.setName(config.getName());
                    dto.setParamKey(config.getParamKey());
                    dto.setParamValue(config.getParamValue());
                    return dto;
                })
                .toList();
    }

    /**
     * 更新参数值。
     *
     * <p>方法：{@code updateValue}</p>
     *
     * <p>执行链路（共 3 步）：</p>
     * 1. 按参数键查询参数，不存在时抛出业务异常；
     * 2. 更新参数值；
     * 3. 持久化。
     *
     * @param paramKey   参数键
     * @param paramValue 新参数值
     * @throws BusinessException 参数不存在时抛出
     */
    public void updateValue(String paramKey, String paramValue) {
        if (ImportLimitConfig.isImportLimitKey(paramKey)) {
            throw new BusinessException(ErrorCode.BUSINESS_REJECTED, "导入限制必须通过专用类型化入口修改");
        }
        SystemConfigDO config = systemConfigMapper.selectOne(
                new LambdaQueryWrapper<SystemConfigDO>().eq(SystemConfigDO::getParamKey, paramKey));
        if (config == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "系统参数不存在: " + paramKey);
        }
        config.setParamValue(paramValue);
        systemConfigMapper.updateById(config);
    }

    /**
     * 读取布尔型参数值。
     *
     * <p>方法：{@code getBoolean}</p>
     *
     * <p>执行链路（共 3 步）：</p>
     * 1. 按参数键查询；
     * 2. 参数不存在或值非 "true" 时返回 false（布尔参数默认关闭，不静默猜测）；
     * 3. 返回解析结果。
     *
     * @param paramKey 参数键
     * @return 布尔值
     */
    public boolean getBoolean(String paramKey) {
        SystemConfigDO config = systemConfigMapper.selectOne(
                new LambdaQueryWrapper<SystemConfigDO>().eq(SystemConfigDO::getParamKey, paramKey));
        return config != null && "true".equals(config.getParamValue());
    }

    /**
     * 读取受控导入限制。
     *
     * <p>方法：{@code current}</p>
     *
     * <p>执行链路（共 3 步）：</p>
     * 1. 一次读取系统参数表并收集六个固定导入键；缺失或非整数时明确失败；
     * 2. 调用 {@link ImportLimitConfig#validate(ImportLimitsApi.ImportLimitsUpdate)} 校验范围与硬上限；
     * 3. 返回可供文件模块保存的不可变快照。</p>
     *
     * @return 当前导入限制快照
     * @throws BusinessException 配置缺失、类型错误或越界时抛出
     */
    @Override
    public ImportLimitsApi.ImportLimits current() {
        return readImportLimits();
    }

    /**
     * 保存受控导入限制。
     *
     * <p>方法：{@code update}</p>
     *
     * <p>执行链路（共 4 步）：</p>
     * 1. 调用 {@link ImportLimitConfig#validate(ImportLimitsApi.ImportLimitsUpdate)} 拒绝缺失、类型和越界值；
     * 2. 一次读取六个固定参数并确认数据库配置完整；
     * 3. 在同一事务内只更新白名单键，未知系统参数不受影响；
     * 4. 返回新快照，后续文件创建者自行保存该值。</p>
     *
     * @param update 管理员提交的六项限制
     * @return 保存后的限制快照
     * @throws BusinessException 配置缺失、类型错误、越界或更新失败时抛出
     */
    @Override
    @Transactional
    public ImportLimitsApi.ImportLimits update(ImportLimitsApi.ImportLimitsUpdate update) {
        ImportLimitsApi.ImportLimits validated = ImportLimitConfig.validate(update);
        List<SystemConfigDO> configs = importConfigRows();
        for (SystemConfigDO config : configs) {
            config.setParamValue(valueFor(config.getParamKey(), validated));
            if (systemConfigMapper.updateById(config) != 1) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "导入限制配置保存失败，请稍后重试");
            }
        }
        return validated;
    }

    /**
     * 返回管理员可理解的导入限制定义。
     *
     * <p>方法：{@code importLimitsView}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 调用 {@link #current()} 读取当前类型化值；
     * 2. 以固定顺序组装单位、编辑范围、硬上限和影响说明。</p>
     *
     * @return 导入限制及固定定义
     */
    public ImportLimitsDTO importLimitsView() {
        ImportLimitsApi.ImportLimits values = current();
        List<ImportLimitsApi.Definition> definitions = List.of(
                new ImportLimitsApi.Definition(ImportLimitConfig.MAX_FILE_BYTES, values.maxFileBytes(),
                        ImportLimitConfig.MIN_MAX_FILE_BYTES, ImportLimitConfig.HARD_MAX_FILE_BYTES,
                        ImportLimitConfig.HARD_MAX_FILE_BYTES, "字节", "单个原始文件的最大大小；超过后不会保存文件"),
                new ImportLimitsApi.Definition(ImportLimitConfig.MAX_SPREADSHEET_ROWS, values.maxSpreadsheetRows(),
                        ImportLimitConfig.MIN_MAX_SPREADSHEET_ROWS, ImportLimitConfig.HARD_MAX_SPREADSHEET_ROWS,
                        ImportLimitConfig.HARD_MAX_SPREADSHEET_ROWS, "行", "CSV/XLSX 可接受的数据行预算，不解析业务字段"),
                new ImportLimitsApi.Definition(ImportLimitConfig.MAX_DOCUMENT_CHARACTERS, values.maxDocumentCharacters(),
                        ImportLimitConfig.MIN_MAX_DOCUMENT_CHARACTERS, ImportLimitConfig.HARD_MAX_DOCUMENT_CHARACTERS,
                        ImportLimitConfig.HARD_MAX_DOCUMENT_CHARACTERS, "字符", "MD/TXT/DOCX 文本预算，避免无界解析"),
                new ImportLimitsApi.Definition(ImportLimitConfig.MAX_DOCUMENT_CHUNKS, values.maxDocumentChunks(),
                        ImportLimitConfig.MIN_MAX_DOCUMENT_CHUNKS, ImportLimitConfig.HARD_MAX_DOCUMENT_CHUNKS,
                        ImportLimitConfig.HARD_MAX_DOCUMENT_CHUNKS, "片段", "后续解析可使用的最大片段预算"),
                new ImportLimitsApi.Definition(ImportLimitConfig.UNCONFIRMED_RETENTION_DAYS, values.unconfirmedRetentionDays(),
                        ImportLimitConfig.MIN_RETENTION_DAYS, ImportLimitConfig.HARD_MAX_RETENTION_DAYS,
                        ImportLimitConfig.HARD_MAX_RETENTION_DAYS, "天", "未确认资产的最长保留时间"),
                new ImportLimitsApi.Definition(ImportLimitConfig.RESULT_RETENTION_DAYS, values.resultRetentionDays(),
                        ImportLimitConfig.MIN_RETENTION_DAYS, ImportLimitConfig.HARD_MAX_RETENTION_DAYS,
                        ImportLimitConfig.HARD_MAX_RETENTION_DAYS, "天", "导入/导出结果资产的最长保留时间"));
        return new ImportLimitsDTO(values.maxFileBytes(), values.maxSpreadsheetRows(), values.maxDocumentCharacters(),
                values.maxDocumentChunks(), values.unconfirmedRetentionDays(), values.resultRetentionDays(), definitions);
    }

    private ImportLimitsApi.ImportLimits readImportLimits() {
        List<SystemConfigDO> rows = importConfigRows();
        return ImportLimitConfig.validate(new ImportLimitsApi.ImportLimitsUpdate(
                parse(rows, ImportLimitConfig.MAX_FILE_BYTES),
                (int) parse(rows, ImportLimitConfig.MAX_SPREADSHEET_ROWS),
                (int) parse(rows, ImportLimitConfig.MAX_DOCUMENT_CHARACTERS),
                (int) parse(rows, ImportLimitConfig.MAX_DOCUMENT_CHUNKS),
                (int) parse(rows, ImportLimitConfig.UNCONFIRMED_RETENTION_DAYS),
                (int) parse(rows, ImportLimitConfig.RESULT_RETENTION_DAYS)));
    }

    private List<SystemConfigDO> importConfigRows() {
        String[] keys = importKeys();
        List<SystemConfigDO> rows = systemConfigMapper.selectList(
                new LambdaQueryWrapper<SystemConfigDO>().in(SystemConfigDO::getParamKey, Arrays.asList(keys)));
        if (rows.size() != keys.length) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "导入限制配置不完整，请联系系统管理员");
        }
        return rows;
    }

    private long parse(List<SystemConfigDO> rows, String key) {
        String value = rows.stream().filter(row -> key.equals(row.getParamKey())).findFirst()
                .map(SystemConfigDO::getParamValue).orElseThrow(
                        () -> new BusinessException(ErrorCode.INTERNAL_ERROR, "导入限制配置缺失，请联系系统管理员"));
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "导入限制配置类型错误，请联系系统管理员");
        }
    }

    private String valueFor(String key, ImportLimitsApi.ImportLimits values) {
        return switch (key) {
            case ImportLimitConfig.MAX_FILE_BYTES -> Long.toString(values.maxFileBytes());
            case ImportLimitConfig.MAX_SPREADSHEET_ROWS -> Integer.toString(values.maxSpreadsheetRows());
            case ImportLimitConfig.MAX_DOCUMENT_CHARACTERS -> Integer.toString(values.maxDocumentCharacters());
            case ImportLimitConfig.MAX_DOCUMENT_CHUNKS -> Integer.toString(values.maxDocumentChunks());
            case ImportLimitConfig.UNCONFIRMED_RETENTION_DAYS -> Integer.toString(values.unconfirmedRetentionDays());
            case ImportLimitConfig.RESULT_RETENTION_DAYS -> Integer.toString(values.resultRetentionDays());
            default -> throw new BusinessException(ErrorCode.INTERNAL_ERROR, "导入限制配置键无效");
        };
    }

    private String[] importKeys() {
        return new String[]{ImportLimitConfig.MAX_FILE_BYTES, ImportLimitConfig.MAX_SPREADSHEET_ROWS,
                ImportLimitConfig.MAX_DOCUMENT_CHARACTERS, ImportLimitConfig.MAX_DOCUMENT_CHUNKS,
                ImportLimitConfig.UNCONFIRMED_RETENTION_DAYS, ImportLimitConfig.RESULT_RETENTION_DAYS};
    }
}
