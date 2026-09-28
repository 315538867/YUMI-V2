# platform-foundation Specification

## Purpose
为 YUMI V2 提供受保护、可审计、可迁移且能被浏览器与 Electron 共同使用的 Java 模块化单体基础能力，并让业务命令获得稳定错误和幂等语义。
## Requirements
### Requirement: 系统必须提供受保护的管理员访问

系统 SHALL 只允许认证管理员调用业务写入 API；每次写入 SHALL 记录管理员、请求 ID、幂等键、业务编号、服务端时间和结果。

#### Scenario: 未认证写入被拒绝
- **WHEN** 未认证客户端调用任一业务写入 API
- **THEN** 系统返回 `AUTH_REQUIRED` 且不产生业务事实

#### Scenario: 重复幂等请求不重复写入
- **WHEN** 已成功的写命令使用相同幂等键再次提交相同请求
- **THEN** 系统返回原业务结果且数据库只存在一份事实

### Requirement: 系统必须提供统一响应契约

系统 SHALL 对所有 API 响应（成功与失败）使用同一信封 `{code, message, fieldErrors, requestId, data}`：成功时 `code="OK"`、`message=""`、`fieldErrors=[]`、`data` 为业务负载；失败时 `code` 为稳定错误码、`message` 为可读描述、`fieldErrors` 携带字段定位、`data=null`。HTTP 状态码 SHALL 保持语义（200/201/204、400/401/404/409、500/503），`204` SHALL 无响应体。客户端 SHALL 只在 HTTP 封装层解析信封，依据稳定错误码而非文案判断下一步；每个响应 SHALL 携带 `X-Request-Id` 作为日志与审计的追踪入口。

#### Scenario: 成功与失败响应形状一致
- **WHEN** 客户端调用任一业务命令或查询，无论成功或失败
- **THEN** 响应都包含 `code`、`message`、`fieldErrors`、`requestId`、`data` 五个字段且携带 `X-Request-Id`，`204` 响应无响应体

#### Scenario: 数量边界错误
- **WHEN** 管理员提交负数、超过需求或超过余额的数量
- **THEN** 系统返回对应 `QUANTITY_*` 错误码、字段定位和请求 ID

#### Scenario: 并发版本冲突
- **WHEN** 客户端使用过期版本提交可并发修改的命令
- **THEN** 系统返回 `CONFLICT_VERSION`，不覆盖其他成功提交

### Requirement: 系统必须通过版本化迁移管理数据库结构

系统 SHALL 使用 Flyway 版本化迁移创建和升级 MySQL 结构，启动前 SHALL 校验迁移历史；ORM 不得自动修改正式结构。

#### Scenario: 空库启动
- **WHEN** 应用连接没有业务表的新 MySQL 数据库
- **THEN** Flyway 按顺序完成迁移并建立历史，Hibernate validate 通过后应用就绪

#### Scenario: 已执行迁移被修改
- **WHEN** Flyway 校验发现已执行版本脚本内容发生变化
- **THEN** 应用返回 `MIGRATION_INVALID`、就绪检查失败且不接受业务写入

### Requirement: 浏览器和 Electron 必须使用同一业务 API

浏览器和 Electron SHALL 共享 React/TypeScript 业务前端并通过 HTTPS API 访问；客户端不得直连数据库或覆盖服务端金额、数量和状态。

#### Scenario: 两种客户端读取同一订单
- **WHEN** 浏览器和 Electron 使用同一管理员请求同一订单
- **THEN** 两者获得相同服务端状态、数量和金额

### Requirement: 系统必须提供运行和恢复门禁

系统 SHALL 提供受保护的存活、就绪、数据库迁移状态、结构化请求日志和数据库/文件一致备份恢复验证。

#### Scenario: 数据库不可用时未就绪
- **WHEN** 应用无法连接业务数据库
- **THEN** 就绪检查失败且系统拒绝新的业务写命令

#### Scenario: 恢复点校验
- **WHEN** 运维人员使用同一恢复点恢复 MySQL 和业务文件
- **THEN** 系统校验迁移历史、履约投影、库存流水、发货、收退款和售后台账后才恢复访问
