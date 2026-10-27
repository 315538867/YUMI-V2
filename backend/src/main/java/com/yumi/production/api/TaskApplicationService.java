package com.yumi.production.api;

/**
 * 作业任务命令（生产/返工/其他任务）。
 *
 * <p>规则来源：`docs/一期/11-生产作业模块.md` §6、§7.1—§7.3：
 * <ul>
 *   <li>生产/返工任务绑定生产来源并按来源校验可安排数量；其他任务不关联来源；</li>
 *   <li>同一员工同日：正常/超额/其他任务各自最多一个，返工可多个但需依赖正常任务；</li>
 *   <li>工作量与产能评估由计算中心与商品供给，生产只保存结果；</li>
 *   <li>超出标准默认拒绝，具有覆盖权限时可显式覆盖并记录原因；</li>
 *   <li>正式创建直接进入 ARRANGED，不提供草稿。</li>
 * </ul>
 */
public interface TaskApplicationService {

    TaskRef arrange(ArrangeTaskCommand command);

    /** 调整任务计划；已核验部分保留在原日期、主体与标准版本下。 */
    TaskView adjust(AdjustTaskCommand command);

    TaskView getTask(String taskBusinessId);

    /** 取消未核验的任务安排，释放计划占用并保留历史事实。 */
    TaskRef cancel(CancelTaskCommand command);

    /** 按工作日期读取任务列表；排班页只读展示，不推导生产事实。 */
    java.util.List<TaskView> listTasks(java.time.LocalDate workDate);

    /** 按可选日期区间读取任务；两端为空时返回全部任务。 */
    java.util.List<TaskView> listTasks(java.time.LocalDate startDate, java.time.LocalDate endDate);
}
