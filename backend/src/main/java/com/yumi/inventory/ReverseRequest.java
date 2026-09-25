package com.yumi.inventory;

/** 流水冲销入参（任务 4.10）：原因必填，冲销流水通过 reverses_movement_id 关联原流水。 */
public record ReverseRequest(String reason) {
}
