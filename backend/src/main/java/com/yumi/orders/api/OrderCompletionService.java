package com.yumi.orders.api;

/**
 * 订单完成命令：只有交付履约与付款履约同时成立且无未结退款义务时才允许完成。
 */
public interface OrderCompletionService {

    OrderCompletionRef complete(CompleteOrderCommand command);
}
