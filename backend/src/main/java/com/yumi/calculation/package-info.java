/**
 * 集中计算支撑模块：金额/比例精度基础方法（{@code DecimalPolicy}）与各业务分类的公式实现。
 * 无持久化；不依赖业务模块、Repository、实体、HTTP、当前时间或登录上下文；
 * 只接收不可变数值输入并返回结果。公式目录见 {@code docs/architecture/formula-catalog.md}。
 */
@org.springframework.modulith.ApplicationModule
package com.yumi.calculation;
