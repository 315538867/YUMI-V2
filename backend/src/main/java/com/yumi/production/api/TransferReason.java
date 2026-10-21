package com.yumi.production.api;

/** 移交线下的业务原因：不得用报废原因伪造非报废移交。 */
public enum TransferReason {
    ORDER_REDUCTION,
    ORDER_CANCELLED,
    AFTER_SALES_OBLIGATION_RELEASED,
    QUALITY_SCRAP
}
