package com.yumi.customer.api;

import java.util.Objects;

/**
 * 本次收货信息覆盖值：只进入本次客户快照，不修改客户默认资料。
 *
 * <p>全部字段为空表示“未覆盖”。
 */
public record DeliveryContactOverride(String recipientName, String phone, String address) {

    public static final DeliveryContactOverride NONE = new DeliveryContactOverride(null, null, null);

    public DeliveryContactOverride {
        if (recipientName != null && recipientName.isBlank()) {
            throw new IllegalArgumentException("覆盖收件人不能为空白（未覆盖请传 null）");
        }
        if (phone != null && phone.isBlank()) {
            throw new IllegalArgumentException("覆盖电话不能为空白（未覆盖请传 null）");
        }
        if (address != null && address.isBlank()) {
            throw new IllegalArgumentException("覆盖地址不能为空白（未覆盖请传 null）");
        }
    }

    public boolean isEmpty() {
        return recipientName == null && phone == null && address == null;
    }

    /** 规范化文本，用于稳定覆盖指纹；未覆盖时返回固定空值标记。 */
    public String canonical() {
        return (recipientName == null ? "" : recipientName.strip())
                + "|" + (phone == null ? "" : phone.strip())
                + "|" + (address == null ? "" : address.strip());
    }

    public static DeliveryContactOverride of(String recipientName, String phone, String address) {
        Objects.requireNonNull(recipientName, "覆盖收件人不能为空");
        return new DeliveryContactOverride(recipientName, phone, address);
    }
}
