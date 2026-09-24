# 阶段通用 TDD / 证据模板

> 适用于 build-yumi-v2-order-fulfillment 全部 9 个阶段的 `tasks.md` 任务条目。
> 每个任务在勾选 `[x]` 前必须按下述结构回填“证据”子项；阶段九双向追踪表只做汇总，不能替代逐项留证。

## 1. 证据条目结构

```markdown
- [ ] <编号> <任务原文>
  - 证据：Requirement/Scenario：<specs/<capability>/spec.md 的 Requirement 与 Scenario>；
    正式文档：<docs/... 或 design.md 的章节>；
    文件：<实际新增/修改文件，含路径>；
    Flyway 版本：<V<n>__<说明>.sql，或“不适用”及理由>；
    API：<HTTP 方法/路径>；错误码：<涉及的稳定错误码>；
    表：<涉及的表>；事务拥有者/<锁定对象>/<幂等键>：<说明或“不适用”及理由>。
  - RED：<命令>，退出码 <N>，关键失败原文：<断言输出原文片段>。
  - GREEN：<命令>，退出码 <N>，<测试数> 个测试通过；关键断言：<断言点列表>。
  - 回归：<阶段边界全量命令>，退出码 <N>，<总测试数> 个测试 <失败数> 失败。
  - 人工证据：不适用；或按第 3 节回填 humanVisualConclusion。
```

### 字段规则

1. **RED 必须是行为断言失败**：失败原因必须来自“要实现的行为尚不存在”（状态码不符、JSON 路径为空、数组缺项、掩码未生效等）。
   编译错误、缺依赖、导入失败只算环境搭建，修补后重新取证，不得充当 RED。
2. **GREEN 必须给出数量与断言**：命令、退出码、测试数、最少 3 条关键断言原文；只有退出码不算完整证据。
3. **不适用必须写理由**：HTTP 方法、错误码、事务拥有者、锁定对象、幂等键、Flyway 版本等字段，
   无涉时写“不适用（<理由>）”，禁止留空或删除字段。
4. **命令必须可在 `backend/`（或 `frontend/`）原样复跑**：数据库口令一律
   `YUMI_DB_PASSWORD=<钥匙串读取值>` 注入，禁止把口令写进仓库或证据文本。
5. **前端任务**额外记录：正式可达路由、操作入口、`npm run typecheck`/`npm test`/`npm run build`
   退出码、浏览器验证快照要点、Electron 复用结果（未建立壳时注明由 1.9/1.12 回填）。
6. **预览路由 / 临时页面不得作为验收证据**；只认正式路由与 HTTP 黑盒结果。

## 2. 命令速查

| 范围 | 命令 | 工作目录 |
| --- | --- | --- |
| 后端单测/RED/GREEN | `YUMI_DB_PASSWORD=<钥匙串读取值> ./mvnw -q -Dtest=<TestClass> test` | `backend/` |
| 后端全量回归 | `YUMI_DB_PASSWORD=<钥匙串读取值> ./mvnw -q test` | `backend/` |
| 前端类型检查 | `npm run typecheck` | `frontend/` |
| 前端单测 | `npm test` | `frontend/` |
| 前端构建 | `npm run build` | `frontend/` |
| Electron 壳守卫 | `npm test`（含 `electron/shell-guard.test.ts`） | `frontend/` |
| 规格校验 | `openspec validate build-yumi-v2-order-fulfillment --strict` | 仓库根 |

退出码取证规范：命令与 `echo "EXIT=$?"` 同一次调用记录；经管道取日志时先重定向到文件再回读，
禁止用管道末端（`tail`/`grep`）的退出码冒充测试退出码。

## 3. 人工视觉结论（humanVisualConclusion）

需要人眼判断的条目（页面布局、打印/PDF、阶段人工验收、9.12/9.13）必须附：

```markdown
  - 人工证据：
    humanVisualConclusion:
      status: pending-user-signoff
      checklist:
        - "<可独立判定的观察点，逐条列出，含预期结论>"
        - "<观察点2>"
      confirmedBy: null
      confirmedOn: null
      conclusion: null
```

规则：

1. 观察点必须是**可判定的单条结论**（例如“正式 `/orders/:id` 只有一组订单页签”），
   不写“看起来更好”这类不可判定描述；数量多时按页面分组为子清单。
2. `status` 在用户明确确认前一律保持 `pending-user-signoff`；
   截图、无重叠、自动化通过都**不能**替代用户确认。
3. 用户确认后补全三个字段再勾选任务：

```markdown
      confirmedBy: chen
      confirmedOn: 2026-09-23
      conclusion: "<确认结论，含已知偏差与取代关系>"
```

4. 多个任务共享同一次人工验收时，结论写在主任务（如 3.15、9.12），
   关联任务引用其 `humanVisualConclusion` 结论并注明取代关系。

## 4. 已完成条目示例（可直接对照）

- 行为 RED + 断言 GREEN：见 1.6（`Status expected:<409> but was:<201>` → 19 用例退出码 0）。
- 变异注入 RED（证明守卫有效）：见 1.9（注入 `require("mysql")` 令守卫用例失败后移除）。
- 不适用字段写法：见 1.6 “事务拥有者/锁定对象：不适用（本任务无业务写事务……）”。
- 前端浏览器验证写法：见 1.8（快照 URL、网络日志、控制台要点，交互登录归 1.12 回填）。
