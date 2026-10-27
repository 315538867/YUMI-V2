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

## 2. 展示组件

统一从 `@/shared/components` 使用：

| 组件 | 用途 |
|---|---|
| `CodeTag` | 状态、类型、工序等编码的中文标签 + 颜色 |
| `CodeLabel` | 不需要标签样式的编码中文名 |
| `MoneyText` | 金额对象/字符串统一展示 |
| `DateTimeText` | 时间统一格式化 |
| `BusinessFileUpload` | 附件上传与授权读取 |

页面不再自写 `status === 'ACTIVE' ? '启用' : '停用'` 这类零散映射；新增编码先加码表，再在页面使用组件。

## 3. 请求规范

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

## 4. 新增/修改编码的流程

1. 后端枚举或稳定常量先落地，并补契约测试。
2. 在 `domain-codes.json` 增加分类和中文标签、颜色。
3. 页面使用 `CodeTag`/`CodeLabel`，不直接输出字段。
4. 运行前端 `codeCatalog.test.ts`、`display.contract.test.ts` 和后端 `DomainCodeCatalogTest`。
5. 涉及页面流程时，E2E 按中文标签断言，不按原始编码断言。

## 5. 门禁命令

```bash
cd frontend && pnpm run typecheck && pnpm run test:unit && pnpm run build
cd backend && ./mvnw -Dtest=DomainCodeCatalogTest test
```
