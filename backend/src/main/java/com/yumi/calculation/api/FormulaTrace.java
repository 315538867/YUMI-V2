package com.yumi.calculation.api;

/**
 * 纯计算结果的可追溯标识。
 *
 * <p>计算中心不保存任何状态；每次计算返回公式代码、规则版本、舍入版本与规范化输入指纹，
 * 由调用方在自己的事实或快照中保存。同一输入与同一规则版本必须得到相同结果。
 *
 * @param formulaCode 稳定公式代码
 * @param ruleVersion 代码规则版本
 * @param roundingVersion 舍入规则版本
 * @param inputFingerprint 规范化输入指纹（SHA-256 小写十六进制）
 */
public record FormulaTrace(
        String formulaCode, String ruleVersion, String roundingVersion, String inputFingerprint) {
}
