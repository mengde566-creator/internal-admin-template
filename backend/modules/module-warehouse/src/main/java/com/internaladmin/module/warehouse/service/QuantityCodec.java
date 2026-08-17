package com.internaladmin.module.warehouse.service;

import com.internaladmin.platform.kernel.error.BusinessException;
import com.internaladmin.platform.kernel.error.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** 仓储数量的唯一精确缩放边界。 */
public final class QuantityCodec {
    public static final int SCALE = 4;
    public static final long MAX_SCALED = Long.MAX_VALUE;
    private static final BigDecimal SCALE_FACTOR = BigDecimal.TEN.pow(SCALE);
    private QuantityCodec() {}

    public static long parse(String text, boolean positive, boolean nonNegative) {
        if (text == null || text.isBlank()) throw invalid("数量不能为空");
        final BigDecimal value;
        try { value = new BigDecimal(text.trim()); }
        catch (NumberFormatException ex) { throw invalid("数量必须是精确十进制字符串"); }
        if (value.scale() > SCALE) throw invalid("数量最多保留4位小数");
        if (positive && value.signum() <= 0) throw invalid("数量必须大于0");
        if (nonNegative && value.signum() < 0) throw invalid("数量不能小于0");
        try {
            long scaled = value.setScale(SCALE, RoundingMode.UNNECESSARY).multiply(SCALE_FACTOR).longValueExact();
            if (scaled == Long.MIN_VALUE) throw invalid("数量超出可表示范围");
            return scaled;
        } catch (ArithmeticException ex) { throw invalid("数量超出可表示范围"); }
    }

    public static long add(long left, long delta) {
        try {
            long result = Math.addExact(left, delta);
            if (result < 0) throw invalid("库存不能为负数");
            return result;
        } catch (ArithmeticException ex) { throw invalid("库存数量溢出"); }
    }

    /** 合并调拨净变化时保留有符号语义，仅拒绝 long 溢出。 */
    public static long addSigned(long left, long delta) {
        try {
            return Math.addExact(left, delta);
        } catch (ArithmeticException ex) {
            throw invalid("库存数量溢出");
        }
    }

    public static String format(long scaled) {
        return BigDecimal.valueOf(scaled, SCALE).stripTrailingZeros().toPlainString();
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.PARAM_ERROR, message);
    }
}
