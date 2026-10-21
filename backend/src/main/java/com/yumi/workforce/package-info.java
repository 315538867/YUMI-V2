/**
 * 人员与小组模块：员工就业、资格、小组、效率与有效时间标准、执行主体快照与活动引用守卫。
 *
 * <p>边界（基线 10）：
 * <ul>
 *   <li>合并员工与小组，因为资格、成员、标准、快照与活动占用必须统一。</li>
 *   <li>不建立员工与平台账号的绑定。</li>
 *   <li>不依赖 production；由 production 通过预留参与接口调用。</li>
 * </ul>
 *
 * <p>依赖方向：workforce → platform。
 */
package com.yumi.workforce;
