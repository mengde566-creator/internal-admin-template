package com.internaladmin.module.iam.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.internaladmin.module.iam.mapper.SystemConfigMapper;
import com.internaladmin.module.iam.model.dto.SystemConfigDTO;
import com.internaladmin.module.iam.model.entity.SystemConfigDO;
import com.internaladmin.platform.kernel.error.BusinessException;
import com.internaladmin.platform.kernel.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 系统参数服务：读取与更新全局配置。
 */
@Service
public class SystemConfigService {

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
}
