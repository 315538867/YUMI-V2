package com.yumi.customer.api;

import java.util.List;

/**
 * 客户变化数据源：报表按提供方游标读取变化。
 *
 * <p>游标由本模块定义并解释，消费方不得推测数据库主键顺序；分页必须稳定，重复读取不改变结果。
 */
public interface CustomerReportingFeed {

    CustomerChangePage changedSince(String cursor, int limit);

    /** 变化页：{@code nextCursor} 为 null 表示已追平。 */
    record CustomerChangePage(List<CustomerChangeItem> items, String nextCursor) {
        public CustomerChangePage {
            items = List.copyOf(items);
        }
    }

    /** 单条客户变化：只含稳定标识与版本，不含业务金额。 */
    record CustomerChangeItem(
            String customerBusinessId,
            String customerNo,
            CustomerStatus status,
            long contentVersion,
            java.time.Instant occurredAt,
            String operationType) {
    }
}
