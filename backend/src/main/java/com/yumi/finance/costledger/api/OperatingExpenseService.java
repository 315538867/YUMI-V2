package com.yumi.finance.costledger.api;

/** 经营费用与成本同事务写入，可成对反向。 */
public interface OperatingExpenseService {

    OperatingExpenseRef record(OperatingExpenseCommand command);

    String reverse(String expenseBusinessId, String reasonText);

    record OperatingExpenseRef(String expenseBusinessId, String costEntryBusinessId) {
    }
}
