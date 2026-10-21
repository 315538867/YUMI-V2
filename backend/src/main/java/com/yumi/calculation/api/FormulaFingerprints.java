package com.yumi.calculation.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 规范化输入指纹。
 *
 * <p>指纹基于调用方传入字段的稳定规范化文本（字段名 + 值，固定顺序），不含随机数或系统时间；
 * 相同输入必须得到相同指纹，用于保存历史依据与判断是否需要重算。
 */
public final class FormulaFingerprints {

    private FormulaFingerprints() {
    }

    public static String of(Object... fieldValuePairs) {
        if (fieldValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("指纹参数必须是成对的字段名与值");
        }
        StringBuilder canonical = new StringBuilder();
        for (int index = 0; index < fieldValuePairs.length; index += 2) {
            Object name = fieldValuePairs[index];
            Object value = fieldValuePairs[index + 1];
            if (name == null) {
                throw new IllegalArgumentException("指纹字段名不能为空");
            }
            canonical.append(name).append('=').append(normalize(value)).append(';');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("缺少 SHA-256 算法", exception);
        }
    }

    private static String normalize(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof java.math.BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof java.util.Collection<?> collection) {
            StringBuilder joined = new StringBuilder("[");
            for (Object element : collection) {
                joined.append(normalize(element)).append(',');
            }
            return joined.append(']').toString();
        }
        return value.toString();
    }
}
