# 前端编码、组件与请求规范

## 1. 编码中文化

- 后端只返回稳定业务编码，不在 API 载荷里返回中文展示名。
- 前端共享码表：`frontend/src/platform/display/domain-codes.json`。
  - 每个分类按 `code → { label, tag }` 维护中文名和标签样式。
  - 后端枚举由 `backend/src/test/java/com/yumi/platform/display/DomainCodeCatalogTest.java` 校验覆盖；
  - 前端码表由 `frontend/src/platform/display/codeCatalog.test.ts` 校验标签、样式和未知编码回退。
- 页面禁止直接渲染编码字段：
  - 禁止 `<el-table-column prop="status|type|processCode|...">` 直接输出编码；
  - 禁止 `{{ row.status }}`、`{{ task.taskType }}` 这类模板表达式；
  - `frontend/src/platform/display/display.contract.test.ts` 扫描模板并失败。
- 动态编码（审计动作、提交后任务类型等）走同一组件，未收录时显示中文兜底（如“其他操作”“其他任务”），不得回退成原始编码。
- 业务编号（订单号、客户编号、员工编号等）是标识，不属于展示编码，原样显示。
- 后端组装的展示文案若需要拼接编码，改为返回独立 `code` 字段，由前端用 `CodeTag` 翻译；不得把编码拼进中文标题。

## 2. 契约类型

- 接口契约中的编码字段一律使用码表推导的联合类型：

  ```ts
  import type { CodeOf } from '@/platform/display/codeCatalog'

  export type OrderStatus = CodeOf<'order.status'>
  ```

- `CodeOf` 直接从 `domain-codes.json` 推导，新增/删除编码时类型检查会同时校验契约和码表，禁止手写编码字面量联合类型。
- 自由文本字段（例如线下转出去向中的 `detail`）保持 `string`，前端按值判断是否命中码表，命中才用 `CodeTag`。

## 3. 展示组件

统一从 `@/shared/components` 使用：

| 组件 | 用途 |
|---|---|
| `CodeTag` | 状态、类型、工序等编码的中文标签 + 颜色 |
| `CodeLabel` | 不需要标签样式的编码中文名 |
| `MoneyText` | 金额对象/字符串统一展示 |
| `DateTimeText` | 时间统一格式化 |
| `PermissionButton` | 按权限显隐的按钮 |
| `Can` | 按权限显隐的任意片段 |
| `BusinessFileUpload` | 附件上传与授权读取 |

- 金额、时间不得在模板里直接输出或格式化：
  - 禁止 `{{ row.amount }}`、`{{ order.createdAt }}`；
  - 禁止模板内 `toLocaleString`、`toISOString`、`new Date(...)`；
  - 契约测试第二组规则扫描。
- 页面不再自写 `status === 'ACTIVE' ? '启用' : '停用'` 这类零散映射；新增编码先加码表，再在页面使用组件。

## 4. 权限与操作可见性

- 页面不得用内联 `v-if="auth.hasPermission(...)"` 包裹按钮；使用 `PermissionButton`：

  ```vue
  <PermissionButton permission="order.ship" @click="openShipment">登记发货</PermissionButton>
  ```

- 非按钮片段（页签、卡片、表格列）使用 `Can`。
- 权限编码来自码表分类 `platform.permission`，与后端 `PermissionCodes` 同名。
- 只隐藏入口不等于授权：后端仍必须校验权限；前端只在“无权限时不展示”这一层负责。
- 权限判定集中在 `auth` store，页面不自行拼接角色或权限前缀。

## 5. 错误处理

- 新写的写操作统一使用 `useAsyncAction`，页面不重复写 `try/catch`；存量页面沿用 `userMessageOf` 归一化，迁移时保持同一策略：

  ```ts
  const { loading, fieldErrors, run } = useAsyncAction()
  await run(() => orderService.complete(id, version), {
    success: '订单已完成',
    fallback: '订单完成失败，请稍后重试',
  })
  ```

- 错误表现按类型区分：
  - 字段校验错误（`ApiClientError.fieldIssues`）写入 `fieldErrors`，由表单内联展示；
  - 其他接口错误统一 `ElMessage.error`，文案取后端中文消息，网络失败用兜底文案；
  - 代码不得直接展示英文异常原文或 HTTP 状态码。
- 页面按稳定错误码判断空态，使用 `isErrorCode`，不直接比较 HTTP 状态。
- 同一操作只提示一次；`run` 内部已弹 toast 时，`onError` 只做状态恢复。

## 6. 请求规范

- 页面（`src/modules/**/*.vue`）只能调用模块 `services`，不得直接导入：
  - `@/platform/services/apiClient`
  - `@/platform/services/api`
  - 原生 `fetch`
- `apiClient` 统一处理：
  - 响应信封解包与错误归一化；
  - `X-Request-Id`、`Idempotency-Key`；
  - 十进制金额字符串契约。
- 所有命令通过模块 service 生成幂等键；查询参数在 service 内构造，页面不拼 URL。
- `frontend/src/platform/services/apiClient.contract.test.ts` 作为结构门禁。

## 7. 新增/修改编码的流程

1. 后端枚举或稳定常量先落地，并补契约测试。
2. 在 `domain-codes.json` 增加分类和中文标签、颜色。
3. 契约字段改用 `CodeOf<'分类'>`，修正类型检查暴露的旧字面量。
4. 页面使用 `CodeTag`/`CodeLabel`，不直接输出字段。
5. 运行前端 `codeCatalog.test.ts`、`display.contract.test.ts` 和后端 `DomainCodeCatalogTest`。
6. 涉及页面流程时，E2E 按中文标签断言，不按原始编码断言。

## 8. 目录与命名

- 业务模块：`src/modules/<domain>/`，至少包含：
  - `pages/`：路由页面，文件名 `XxxPage.vue`；布局用 `XxxLayout.vue`。
  - `services/`：`xxxService.ts`，导出一个 `xxxService` 对象，页面只通过它访问接口。
  - `contracts.ts`：接口入参与返回类型，编码字段使用 `CodeOf<'分类'>`。
- 跨模块能力：`src/platform/`（认证、请求、展示码表、主题、通用 composable）。
- 纯展示组件：`src/shared/components/`，文件名 PascalCase，组件名与文件名一致。
- Composable：`useXxx.ts`，返回 `ref`/`computed` 与动作函数；不直接操作 DOM。
- 类型命名：接口返回 `XxxView`，命令入参 `XxxInput`，领域记录 `XxxRecord`。
- 布尔值用 `is/has/can/should` 前缀；事件处理函数用 `openXxx`、`submit`、`load`、`saveXxx`。
- 测试与源码同目录：`*.test.ts`；E2E 放 `e2e/<domain>.spec.ts`，按中文标签断言。
- CSS class 用 kebab-case，样式默认 `scoped`；颜色只用 `tokens.css` 变量（白底 `--yumi-surface`、内容区底色 `--yumi-canvas`），禁止行内样式（动态尺寸/颜色绑定除外）；`<style>` 中的十六进制色值由展示契约测试拦截。
- 依赖方向：`pages → services → platform`；`shared` 和 `platform` 不得导入 `@/modules/**`（ESLint 强制）。
- 类型导入一律 `import type`；页面不得直接导入 `apiClient`、`api` 或使用 `fetch`。

## 9. 工程门禁

- ESLint 9 扁平配置：`frontend/eslint.config.js`，规则包含 Vue 推荐集、TypeScript 推荐集、禁止 `any`、禁止共享层依赖业务模块。
- Prettier 配置：`frontend/.prettierrc.json`；`.prettierignore` 排除构建产物与 Markdown。
- 提交前必须全部通过：

```bash
cd frontend
pnpm run lint
pnpm run format:check
pnpm run typecheck
pnpm run test:unit
pnpm run build
pnpm run test:e2e        # 含无障碍 axe 与视觉回归
```

```bash
cd backend
./mvnw test
./mvnw -Dtest=DomainCodeCatalogTest test
```

- 视觉与无障碍契约见 [DESIGN.md](DESIGN.md)；颜色或布局变更必须同步更新该文件并重生成截图基线。

## 10. 页面交互（P1）

- 列表统一使用 `useListQuery`：筛选、页码、每页条数写入 URL，刷新/分享/前进后退可恢复；后端分页统一从 1 开始。
- 分页使用 `ListPager`：默认每页 20，可选 20/50/100；接口没有总数时不伪造总数，按本页是否满页判断下一页。
- 加载、失败重试、空态统一使用 `PageState`；表格空态用 `empty-text`，查询无结果与初始空态文案分开。
- 详情页只读；编辑进入独立整页路由（如 `/customers/:id/edit`），保存后返回详情。
- 员工、小组已改为详情页 + 独立编辑页，并支持关键词/状态/工种筛选与分页；新增/编辑页有未保存离开确认。
- 施工项、已完成范围和明确不做项见 [page-interaction-standards.md](page-interaction-standards.md)。
