/**
 * 生产作业模块：生产需求、任务安排、负载、核验、质量处置、返工、报废、移交与数量追溯。
 *
 * <p>边界（基线 11）：
 * <ul>
 *   <li>拥有生产与实物事实，线上报废与实物去向分开记录。</li>
 *   <li>不依赖 orders、finance；通过参与接口加入调用方事务，通过来源 Feed 发布劳动、责任与材料来源。</li>
 *   <li>售后池初次处置由 orders 编排，production 仅在同一事务参与。</li>
 * </ul>
 *
 * <p>依赖方向：production → platform、product、workforce、calculation。
 */
package com.yumi.production;
