package com.yumi.platform.api.value;

import java.math.BigDecimal;

/** 带币种的金额通用读取契约；不提供跨精度算术。 */
public interface MoneyAmount {

    BigDecimal value();

    CurrencyCode currency();
}
