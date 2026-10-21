package com.yumi.orders.aftersales.api;

/** 售后案例引用。 */
public record AfterSalesCaseRef(
        String caseBusinessId, String caseNo, AfterSalesCaseStatus status, long contentVersion) {
}
