package com.yumi.platform.api.http.json;

import com.yumi.platform.api.value.CurrencyCode;

/**
 * 金额的线上表示：金额值为十进制字符串，并与币种一起传输。
 *
 * @param amount 十进制字符串，精度由具体金额类型决定
 * @param currency 币种代码
 */
public record MoneyWire(String amount, CurrencyCode currency) {

    public MoneyWire {
        DecimalText.requireDecimalText(amount, "金额");
        if (currency == null) {
            throw new IllegalArgumentException("金额必须携带币种");
        }
    }
}
