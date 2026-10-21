package com.yumi.orders.api;

/**
 * 收货信息（订单自己的交易约定，不随客户资料变化）。
 *
 * @param customerName 收货人
 * @param phone 联系电话
 * @param address 详细地址
 */
public record RecipientInfo(String customerName, String phone, String address) {

    public RecipientInfo {
        if (customerName == null || customerName.isBlank()) {
            throw new IllegalArgumentException("收货人不能为空");
        }
        if (phone == null || phone.isBlank()) {
            throw new IllegalArgumentException("联系电话不能为空");
        }
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("收货地址不能为空");
        }
    }
}
