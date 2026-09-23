# YUMI V2 总体架构方案

## 1. 状态与范围

本文描述 YUMI V2 的目标架构，当前项目尚未实现这些组件。业务依据见 `../requirements/order-fulfillment-requirements.md`。

目标是以单体部署和单库事务支撑首期订单履约闭环，同时为工资、报表、多管理员权限和未来财务模块保留清晰边界。

## 2. 关键决策

| 决策 | 选择 | 原因 |
| --- | --- | --- |
| 系统形态 | 模块化单体 | 业务事务高度关联，首期不需要微服务复杂度 |
| 后端 | Java 21 LTS + Spring Boot 3.5.x | 强事务、成熟数据访问和长期复杂业务维护能力 |
| 模块治理 | Spring Modulith | 在单体内验证模块依赖并限制内部实现泄漏 |
| 构建 | Maven | 与 Spring Boot、Flyway 和测试工具链成熟集成 |
| 数据访问 | Spring Data JPA + Hibernate | 聚合写入和常规查询使用 JPA，复杂只读查询允许专用 SQL |
| 数据库迁移 | Flyway | SQL 迁移是数据库结构唯一来源 |
| 数据库 | MySQL 8.x | 支持事务、约束、行级锁和单机云部署 |
| 前端 | React + TypeScript | 浏览器和 Electron 共用业务前端 |
| 桌面端 | Electron 薄壳 | 只提供桌面增强，不承载业务规则或直连数据库 |
| 一致性 | 单库事务 + 不可变业务事实 | 首期无需消息队列和分布式事务 |
| 金额 | Java `BigDecimal` + MySQL `DECIMAL(19,4)` | 避免二进制浮点误差 |
| 部署 | 单云服务器应用与 MySQL 同机 | 控制首期成本，数据库不暴露公网 |

## 3. 系统上下文

管理员通过浏览器或 Electron 访问同一套 React 前端。客户端通过 HTTPS 调用 Spring Boot API，API 访问 MySQL 和文件存储。客户端禁止直连数据库。

```text
管理员
  ├─ 浏览器 ─────┐
  └─ Electron ───┼─ HTTPS ─> Spring Boot 模块化单体 ─> MySQL
                 │                              └──────> 文件存储
                 └─ 共用 React 业务前端
```

架构源文件见 `system-architecture.dsl`，部署细节见 `deployment-architecture.md`。

## 4. 顶级业务模块

### 4.1 身份与审计 `identity`

负责管理员登录、密码验证、会话、当前操作人和审计上下文。账号表示操作人，员工档案表示业务执行人。

### 4.2 基础资料 `catalog`

负责商品、成本参数、星级配置、包装档位、客户和员工。已确认订单只能读取冻结快照，不能用当前资料覆盖历史。

### 4.3 订单 `orders`

订单是围绕订单生命周期的核心业务模块，内部组件包括：

```text
orders
├── order            订单、明细、确认和快照
├── change           订单变更、取消剩余需求和金额重算
├── fulfillment      履约事实与当前数量投影
├── shipping         发货、物流变更和发货更正
├── settlement       收款、退款、实收净额和待退款
├── aftersales       退回、售后返工、报废、补发和售后退款
└── closing          履约与款项条件检查、订单关闭
```

订单模块拥有订单主状态、当前有效需求、履约台账、可发货数量、累计有效发货、收退款关联、售后台账和关闭判定。独立数据库表不代表独立顶级模块。

### 4.4 库存 `inventory`

负责库存批次、库存流水、期初库存、盘点调整、订单领用、取消领用、工序合格库存和订单余量库存。库存批次不绑定目标订单，订单使用库存必须通过领用命令和不可变流水。

### 4.5 生产 `production`

负责生产计划、其他排班、一次性核验、返工、报废重做、超额任务、售后生产和工作台提醒。生产计划关联订单明细或售后来源，但生产模块不拥有订单需求或主状态。

### 4.6 文件与导出 `files`

负责商品图片、受控下载、PDF/打印数据准备和表格导出。历史输出读取业务快照，不回读当前资料替换历史值。

## 5. Java 包与模块边界

```text
com.yumi
├── identity
├── catalog
├── orders
│   ├── order
│   ├── change
│   ├── fulfillment
│   ├── shipping
│   ├── settlement
│   ├── aftersales
│   └── closing
├── inventory
├── production
├── files
└── shared
```

每个顶级模块公开应用服务和必要的公开 DTO；实体、Repository 和内部领域服务留在模块内部。Spring Modulith 边界测试必须阻止跨模块访问内部包。`shared` 只保存稳定的技术型值对象和基础能力，不承载业务流程，也不得成为通用杂物包。

## 6. 依赖与协作

```text
identity ──> 为所有写命令提供操作人
catalog ───> orders 确认时创建快照
inventory ─┐
production ─┼──> orders（履约、发货、收退款、售后、关闭）
            │
orders ─────┼──> inventory：库存领用、取消和售后库存补发
            └──> production：正常生产、返工、重做和售后生产
orders ─────────> files：打印、PDF、导出和附件
```

模块只能通过公开应用服务或领域接口协作，禁止直接访问其他模块的 JPA Repository、实体或表。跨模块强一致写操作由发起业务命令的应用服务统一开启事务。

## 7. 数据访问与迁移

Flyway SQL 是数据库结构唯一来源，迁移文件位于 `src/main/resources/db/migration`。Hibernate 配置为：

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
```

禁止在任何环境使用 Hibernate `update` 维护正式结构。普通聚合写入使用 Spring Data JPA；复杂统计、列表和事实重建可使用专用只读 Repository、JPQL 或原生 SQL，但不得绕过领域命令写入业务事实。

## 8. 关键事务与并发

以下操作必须在单个数据库事务中完成：订单确认、订单变更确认、库存领用或取消、生产核验、发货确认或更正、收退款登记、售后核验与补发确认、订单关闭和超额任务预占或释放。

实现使用：

- Spring `@Transactional` 定义事务边界；
- JPA `@Version` 处理常规并发修改；
- `PESSIMISTIC_WRITE` 或显式 `SELECT ... FOR UPDATE` 锁定关键数量余额；
- 数据库唯一约束防止来源重复消费和重复核验；
- 幂等请求标识防止客户端重试重复写入；
- 事务内重新读取权威数量，不信任客户端汇总。

## 9. 数据与金额原则

- Java 金额、成本、单价和比例使用 `BigDecimal`，禁止使用 `double` 或 `float` 产生权威结果；
- API 金额使用十进制字符串；
- 订单确认快照、生产核验、库存流水、发货确认、收款、退款和售后核验均为不可变事实；
- 错误通过冲销、更正或反向事实处理；
- 汇总投影必须能从事实重建；
- 所有业务时间使用服务端时间，业务日期和创建时间分开保存。

## 10. API 与客户端边界

API 采用资源查询和业务命令，不提供通用状态更新接口。例如：

```text
POST /orders
POST /orders/{id}/confirm
POST /orders/{id}/change-orders
POST /order-changes/{id}/confirm
POST /production-plans/{id}/verify
POST /inventory-allocations
POST /orders/{orderId}/shipments/{shipmentId}/confirm
POST /orders/{id}/close
POST /orders/{orderId}/after-sales/{caseId}/verify-return
```

React 前端按基础资料、订单、生产工作台和库存组织业务工作区；发货、收退款和售后入口位于订单工作区。Electron 主进程只负责窗口、打印、文件选择和系统集成。

## 11. 测试与架构门禁

- JUnit 5：领域规则和金额计算；
- Spring Boot 集成测试：事务和业务命令；
- Testcontainers MySQL：约束、锁、Flyway 和并发测试；
- Spring Modulith：模块依赖验证；
- Flyway：空库迁移、重复校验和迁移历史验证；
- API 契约测试：金额字符串、状态和错误响应；
- 端到端测试：订单确认、部分发货后并行售后与剩余履约、结清关闭，以及已关闭订单继续售后的路径。

进入实现前，需求、领域、数据库、部署和路线图必须对模块边界、数量口径、库存扣减、订单关闭和售后独立台账保持一致。
