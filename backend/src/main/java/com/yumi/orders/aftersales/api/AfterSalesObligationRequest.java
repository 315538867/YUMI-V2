package com.yumi.orders.aftersales.api;

import com.yumi.platform.api.value.SettlementMoney;
import java.time.LocalDate;

/**
 * 初始售后义务。
 *
 * @param targetQuantity 数量义务的目标数量；金额义务为 null
 * @param targetAmount 金额义务的目标金额；数量义务为 null
 * @param orderItemBusinessId 数量义务对应的订单项；金额与配合义务可为空
 */
public record AfterSalesObligationRequest(
        AfterSalesObligationType obligationType,
        Integer targetQuantity,
        SettlementMoney targetAmount,
        LocalDate dueDate,
        String note,
        String orderItemBusinessId) {

    public AfterSalesObligationRequest(
            AfterSalesObligationType obligationType,
            Integer targetQuantity,
            SettlementMoney targetAmount,
            LocalDate dueDate,
            String note) {
        this(obligationType, targetQuantity, targetAmount, dueDate, note, null);
    }

    public AfterSalesObligationRequest {
        if (obligationType == null) {
            throw new IllegalArgumentException("售后义务必须指定类型");
        }
        switch (obligationType) {
            case REFUND, COMPENSATION -> {
                if (targetAmount == null || targetAmount.value().signum() < 0) {
                    throw new IllegalArgumentException("金额义务必须给出非负目标金额");
                }
                if (targetQuantity != null) {
                    throw new IllegalArgumentException("金额义务不能同时给出目标数量");
                }
            }
            case CUSTOMER_COOPERATION -> {
                if (targetAmount != null) {
                    throw new IllegalArgumentException("客户配合义务不能给出金额");
                }
            }
            case RETURN_AND_REPAIR, REPLACEMENT -> {
                if (targetQuantity == null || targetQuantity <= 0) {
                    throw new IllegalArgumentException("数量义务必须给出目标数量");
                }
                if (targetAmount != null) {
                    throw new IllegalArgumentException("数量义务不能同时给出金额");
                }
                if (orderItemBusinessId == null || orderItemBusinessId.isBlank()) {
                    throw new IllegalArgumentException("数量义务必须指向订单项");
                }
            }
        }
        if (orderItemBusinessId != null
                && (obligationType == AfterSalesObligationType.REFUND
                        || obligationType == AfterSalesObligationType.COMPENSATION)) {
            throw new IllegalArgumentException("金额义务不能指向订单项");
        }
    }
}
