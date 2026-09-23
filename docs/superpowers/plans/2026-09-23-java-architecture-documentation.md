# YUMI V2 Java Architecture Documentation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 将 YUMI V2 整体方案统一修订为 Java 21 + Spring Boot 模块化单体，并补齐部署架构与实施路线图。

**Architecture:** 保持已经确认的订单、库存、生产和共同数量模型不变，只替换后端技术实现与工程治理方式。订单模块内部拥有履约台账、发货、收退款、售后和关闭；库存、生产保持独立模块，通过应用服务和单库事务协作。

**Tech Stack:** Java 21 LTS、Spring Boot 3.5.x、Spring Modulith、Spring Data JPA、Hibernate、Flyway、Maven、MySQL 8.x、JUnit 5、Testcontainers、React、TypeScript、Electron。

**Spec:** `docs/requirements/order-fulfillment-requirements.md`、`docs/architecture/system-architecture.md`

## Global Constraints

- 当前工作只修改方案文档，不创建应用代码或工程脚手架。
- 后端统一采用 Java 21 LTS、Spring Boot 3.5.x、Spring Modulith、Spring Data JPA、Flyway 和 Maven。
- Flyway 是数据库结构唯一来源；Hibernate 使用 `ddl-auto: validate`。
- 金额使用 Java `BigDecimal` 和 MySQL `DECIMAL(19,4)`。
- React、TypeScript 和 Electron 客户端边界保持不变。
- 发货、收退款、售后和履约台账属于订单模块，不作为顶级业务模块。
- 库存与生产保持独立模块，禁止跨模块直接操作 Repository 或数据表。
- 不修改已经确认的共同数量、库存扣减、生产核验、订单关闭和售后规则。
- 不创建 git 提交。

---

### Task 1: 修订需求和业务边界

**Files:**
- Modify: `docs/requirements/order-fulfillment-requirements.md`

**Interfaces:**
- Consumes: 已确认的订单生命周期和模块边界。
- Produces: 与实现语言无关、明确订单子域归属的正式需求基线。

- [x] **Step 1:** 将金额约束从“禁止 JavaScript 原生浮点数”改为“服务端使用 BigDecimal、客户端金额字符串传输且不得用二进制浮点作为权威结果”。
- [x] **Step 2:** 在核心业务对象和订单章节明确发货、收退款、售后、履约台账均属于订单模块。
- [x] **Step 3:** 检查业务规则和首期范围没有因技术栈调整发生变化。

### Task 2: 重写总体架构和 C4 容器技术标注

**Files:**
- Modify: `docs/architecture/system-architecture.md`
- Modify: `docs/architecture/system-architecture.dsl`

**Interfaces:**
- Consumes: Task 1 的业务边界。
- Produces: Java 目标架构、模块依赖、事务边界和客户端边界。

- [x] **Step 1:** 将 Node.js、NestJS 替换为 Java 21、Spring Boot 3.5.x、Spring Modulith、Spring Data JPA、Flyway 和 Maven。
- [x] **Step 2:** 将 fulfillment、shipping、payments、after-sales 合并为 orders 内部组件。
- [x] **Step 3:** 增加 Java 包结构、Spring Modulith 边界、JPA 使用限制和 Flyway 唯一结构来源规则。
- [x] **Step 4:** 修订依赖图、API 容器说明和前端工作区表述。
- [x] **Step 5:** 更新 Structurizr DSL 中 API 容器的技术标注。

### Task 3: 修订领域和数据库设计

**Files:**
- Modify: `docs/architecture/domain-and-quantity-model.md`
- Modify: `docs/architecture/database-design.md`
- Review: `docs/architecture/quantity-flow.dot`

**Interfaces:**
- Consumes: Task 2 的模块边界。
- Produces: 聚合归属、表归属、Java/MySQL 类型映射和迁移治理规则。

- [x] **Step 1:** 明确 Shipment、Payment、Refund、AfterSalesCase 是订单模块内部聚合或事实。
- [x] **Step 2:** 保持库存、生产和共同数量公式不变。
- [x] **Step 3:** 按 catalog、identity、orders、inventory、production、files 标注数据库表归属。
- [x] **Step 4:** 增加 BigDecimal、JPA `@Version`、悲观锁、Flyway 和 `ddl-auto: validate` 规则。
- [x] **Step 5:** 检查数量流转图没有把订单子流程误画成独立顶级系统；如无矛盾则不改图。

### Task 4: 编写部署架构

**Files:**
- Create: `docs/architecture/deployment-architecture.md`

**Interfaces:**
- Consumes: Task 2 的系统容器和 Task 3 的数据库治理。
- Produces: 单服务器部署、网络、启动、备份、恢复和升级边界。

- [x] **Step 1:** 描述浏览器、Electron、HTTPS 反向代理、Spring Boot、MySQL 和文件存储拓扑。
- [x] **Step 2:** 明确数据库不暴露公网，Spring Boot 使用非特权运行账户，秘密通过环境或受控配置注入。
- [x] **Step 3:** 定义构建、Flyway 校验/迁移、应用启动和健康检查顺序。
- [x] **Step 4:** 定义 MySQL 与文件的一致备份、恢复演练、日志轮转和监控要求。
- [x] **Step 5:** 明确首期不引入 Kubernetes、消息队列、服务网格或多节点高可用。

### Task 5: 编写实施路线图

**Files:**
- Create: `docs/delivery/implementation-roadmap.md`

**Interfaces:**
- Consumes: Tasks 1-4 的完整方案。
- Produces: 可分阶段执行且每阶段可验收的交付顺序。

- [x] **Step 1:** 定义工程基础、基础资料、订单核心、库存、生产、发货、结算关闭、售后、报表上线九个阶段。
- [x] **Step 2:** 为每阶段列出交付内容、前置依赖、业务验收和自动化门禁。
- [x] **Step 3:** 明确 JUnit 5、Spring Boot 集成测试、Testcontainers MySQL、Spring Modulith 边界测试和 Flyway 空库迁移测试。
- [x] **Step 4:** 明确工资、复杂权限、离线同步和完整财务模块仍在首期范围外。

### Task 6: 全局一致性复核

**Files:**
- Review: `docs/**/*.md`
- Review: `docs/**/*.dsl`
- Review: `docs/**/*.dot`

**Interfaces:**
- Consumes: Tasks 1-5 的全部文档。
- Produces: 无旧技术栈残留、无模块边界冲突的评审版整体方案。

- [x] **Step 1:** 搜索并清除作为目标后端出现的 Node.js、NestJS 和 TypeScript 后端表述。
- [x] **Step 2:** 搜索 fulfillment、shipping、payments、after-sales，确认它们只作为订单内部组件或物理表分组出现。
- [x] **Step 3:** 核对 Spring Boot、Spring Modulith、JPA、Flyway、Maven、BigDecimal 和 `ddl-auto: validate` 表述一致。
- [x] **Step 4:** 核对需求、领域、数据库、部署和路线图中的订单关闭、库存扣减、共同数量和售后规则一致。
- [x] **Step 5:** 列出最终文件清单和未实施范围，不声称应用代码已经完成。
