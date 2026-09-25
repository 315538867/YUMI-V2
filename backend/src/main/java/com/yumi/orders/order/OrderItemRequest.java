package com.yumi.orders.order;

import java.math.BigDecimal;

/**
 * 订单明细入参（创建与编辑共用）：
 * {@code quantity} 为订购数量、{@code seamQuantity} 为缝边数量；{@code seamQuantity = 0} 即不缝边剪袋，
 * 此时 {@code seamTypeId}/{@code seamFee} 被忽略并按不缝边剪袋落库。
 * {@code unitPrice} 为商品成交单价；{@code seamFee} 为缝边收费单价，未传时取商品的缝边价格默认值。
 * 商品识别信息与单件成本由服务端读取，客户端提交的派生金额一律忽略。
 */
public record OrderItemRequest(
        Long productId,
        Integer quantity,
        Integer seamQuantity,
        BigDecimal unitPrice,
        Long seamTypeId,
        BigDecimal seamFee,
        String note) {
}
