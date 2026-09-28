# YUMI V2 主数据与公式中心详细设计

## 1. 文档定位

本文是 YUMI V2 目标架构细化的第二册，冻结 `master-data` 与 `calculation` 的数据库表、公开 Java 契约、公式精度、规则版本、错误码、权限能力和验证门禁。

本文必须与以下文档共同使用：

- `making-centered-target-architecture.md`；
- `database-and-contract-foundation.md`；
- `data-facts-snapshots-projections.md`；
- `module-contracts-and-transaction-matrix.md`；
- `backend-architecture.md`；
- `http-api-architecture.md`；
- `platform-capabilities-architecture.md`。

本文不定义订单确认后的交付约定快照，不定义生产、排班、工资、成本归集或现金事实，不讨论前端实现。

## 2. 权威边界

### 2.1 `master-data` 拥有

- 商品当前资料和核心制作规格；
- 商品标准工序预算、材料预算和预计分摊；
- 商品可复用定制预算方案及其当前预算快照；
- 客户当前资料和默认收货资料；
- 员工当前资料、就业事实和工种资格；
- 系统固定静态数据类别及其用户可维护条目；
- 全局核算设置当前投影及设置变更事实；
- 材料定义和材料标准单位；
- 向其他模块提供冻结快照所需的不可变主数据视图。

### 2.2 `master-data` 不拥有

- 订单商品、交付约定、成交价格和订单预计成本快照；
- 制品物理阶段、质量、处置、生产来源和数量余额；
- 排班任务、产能占用和核验事实；
- 正式工资、经营成本和真实现金；
- 售后义务、售后交付和售后金额决定；
- 文件对象本身、登录账号、角色或会话。

### 2.3 `calculation` 拥有

- 纯数值公式；
- 金额、比例和中间值精度；
- 舍入策略；
- 代码化公式规则版本；
- 公式输入输出值类型；
- 公式目录和自动化测试标识。

### 2.4 `calculation` 不拥有

- 数据库表、Repository 或迁移；
- HTTP Controller、认证、权限或会话；
- 当前时间、随机数、外部服务或文件；
- 商品、订单、生产、工资或成本快照；
- 动态公式编辑、数据库表达式或脚本执行；
- 对业务模块的反向依赖。

## 3. 已确认的不变量

```text
商品、客户、员工、静态数据和设置的当前定义由 master-data 持有
主数据修改不追溯刷新已确认订单和其他历史快照
定制预算方案只是预算与录单模板，不是订单事实
客户自供改变成本承担方，不删除材料需求
商品标准预算和定制预算必须按工序、人工、材料和分摊拆分
calculation 是唯一公式实现，业务模块只保存输入快照、结果和规则版本
公式代码与自动化测试是规则权威来源
不建设动态公式编辑器和数据库公式引擎
不恢复固定生产路线、公共库存或跨订单制品领用
缝边剪袋是可选加工能力，不是所有商品的固定必经路线
```

## 4. 标识、精度与时间

所有独立记录使用正数 `BIGINT` 内部主键。进入公开契约或跨模块来源链的业务对象使用全局唯一、不可变的 `VARCHAR(64)` 稳定业务标识。

人工业务编号与稳定业务标识分离：

```text
商品编码：P + 5 位以上递增序号，最终格式由编号策略统一冻结
客户编号：C + 5 位以上递增序号
员工编号：E + 5 位以上递增序号
```

编号创建后不可修改，记录停用、离职或归档后不得复用。

公共数据类型：

```text
金额、成本、单价：DECIMAL(19,4)
比例、效率、损耗率：DECIMAL(12,8)
标准分钟：INT
数量：INT 或 BIGINT，按公共基线选择
重量和材料用量：DECIMAL(19,4)，单位由 unit_code 明确
系统时间：DATETIME(6)，按 UTC 保存
业务日期：DATE
版本：BIGINT，初始值 0，每次成功业务修改后递增
```

Java 金额、比例、重量和材料用量统一使用 `BigDecimal`。正式 HTTP 契约以十进制字符串传输这些值。

## 5. 表总览

| 表 | 分类 | 写入模块 | 说明 |
|---|---|---|---|
| `products` | 主数据当前记录 | master-data | 商品身份、制作规格和默认预算方案 |
| `product_file_associations` | 正式业务关联 | master-data | 商品与 platform 正式文件的关联 |
| `material_definitions` | 主数据当前记录 | master-data | 预算材料目录和标准单位 |
| `product_process_budget_templates` | 主数据当前记录 | master-data | 商品标准方案的工序预算模板 |
| `product_process_material_budgets` | 主数据当前记录 | master-data | 商品标准方案的具体材料预算 |
| `product_cost_allocation_budgets` | 主数据当前记录 | master-data | 商品级和交付级预计分摊 |
| `product_customization_profiles` | 主数据当前记录 | master-data | 可复用定制预算方案 |
| `product_customization_processes` | 主数据当前记录 | master-data | 定制方案工序参数 |
| `product_customization_material_terms` | 主数据当前记录 | master-data | 定制方案材料需求与承担方 |
| `product_standard_budget_snapshots` | 不可变快照 | master-data | 商品标准预算计算结果 |
| `product_standard_budget_snapshot_lines` | 快照明细 | master-data | 商品标准预算的工序、人工、材料和分摊结果 |
| `product_customization_budget_snapshots` | 不可变快照 | master-data | 定制方案预算计算结果 |
| `product_customization_budget_snapshot_lines` | 快照明细 | master-data | 定制方案预算的工序、人工、材料和分摊结果 |
| `customers` | 主数据当前记录 | master-data | 客户当前资料和默认收货资料 |
| `customer_change_facts` | 不可变事实 | master-data | 客户资料变更事实 |
| `customer_change_fact_details` | 事实明细 | master-data | 客户变更字段前后值 |
| `employees` | 主数据当前记录 | master-data | 员工当前资料和就业状态投影 |
| `employee_employment_facts` | 不可变事实 | master-data | 入职、离职和返聘事实 |
| `employee_work_type_assignment_facts` | 不可变事实 | master-data | 工种资格授予和撤销事实 |
| `employee_work_type_assignments` | 当前投影 | master-data | 当前有效工种资格 |
| `static_data_categories` | 系统固定主数据 | master-data | 固定静态数据类别 |
| `static_data_entries` | 主数据当前记录 | master-data | 静态数据公共身份和名称 |
| `static_time_definitions` | 主数据当前记录 | master-data | 星级、包装、缝边标准分钟 |
| `work_type_definitions` | 系统固定主数据 | master-data | 系统工种稳定 code |
| `catalog_settings` | 当前投影 | master-data | 当前全局核算参数 |
| `catalog_setting_change_facts` | 不可变事实 | master-data | 全局设置变更和生效版本 |

`calculation` 不拥有数据库表。

## 6. 商品与预算表

### 6.1 `products`

| 字段 | 类型 | 空值 | 规则与含义 |
|---|---|---|---|
| `id` | BIGINT | 否 | 内部主键，正数 |
| `business_id` | VARCHAR(64) | 否 | 稳定商品标识，全局唯一 |
| `product_no` | VARCHAR(32) | 否 | 人工商品编码，唯一且不可变 |
| `name` | VARCHAR(120) | 否 | 去除前后空格，不允许空白 |
| `description` | VARCHAR(1000) | 是 | 商品说明 |
| `standard_weight_grams` | DECIMAL(19,4) | 否 | 单件标准重量，必须大于 0 |
| `making_time_entry_id` | BIGINT | 否 | 模块内物理外键，指向有效制作标准时间条目 |
| `mould_count` | INT | 否 | 模具数量，必须大于 0 |
| `daily_batch_count` | INT | 否 | 单模每日标准批次数，必须大于 0 |
| `default_profile_id` | BIGINT | 是 | 默认定制方案；先创建商品后设置，必须属于本商品 |
| `status` | VARCHAR(32) | 否 | `ACTIVE`、`INACTIVE` |
| `version` | BIGINT | 否 | 乐观版本 |
| `created_at` | DATETIME(6) | 否 | 创建时间 |
| `created_by` | VARCHAR(64) | 否 | 操作者标识 |
| `updated_at` | DATETIME(6) | 否 | 最后修改时间 |
| `updated_by` | VARCHAR(64) | 否 | 最后修改人 |

约束与索引：

- `UNIQUE(business_id)`；
- `UNIQUE(product_no)`；
- `CHECK(standard_weight_grams > 0)`；
- `CHECK(mould_count > 0)`；
- `CHECK(daily_batch_count > 0)`；
- `CHECK(version >= 0)`；
- `INDEX(status, name, id)`；
- `making_time_entry_id` 必须关联 `STAR_LEVEL` 类别中有效的时间定义；数据库外键保证存在性，类别与启用状态在命令锁内校验。

商品名称允许重复。商品被历史业务引用后不得物理删除；停用只阻止新订单选择和新方案创建，不影响历史快照解释。

### 6.2 `product_file_associations`

| 字段 | 类型 | 空值 | 规则与含义 |
|---|---|---|---|
| `id` | BIGINT | 否 | 内部主键 |
| `association_id` | VARCHAR(64) | 否 | 稳定关联标识 |
| `product_id` | BIGINT | 否 | 模块内商品外键 |
| `formal_file_id` | VARCHAR(64) | 否 | platform 正式文件标识，逻辑跨模块引用 |
| `purpose_code` | VARCHAR(32) | 否 | `MAIN_IMAGE`、`GALLERY_IMAGE` |
| `display_order` | INT | 否 | 主图固定为 0，画廊图从 1 开始 |
| `created_at` | DATETIME(6) | 否 | 正式关联时间 |
| `created_by` | VARCHAR(64) | 否 | 操作者标识 |

- `UNIQUE(association_id)`；
- `UNIQUE(product_id, formal_file_id, purpose_code)`；
- `UNIQUE(product_id, purpose_code, display_order)`；
- `CHECK((purpose_code = 'MAIN_IMAGE' AND display_order = 0) OR (purpose_code = 'GALLERY_IMAGE' AND display_order > 0))`，因此每个商品最多一张主图；
- 文件内容、存储键、扫描和删除由 platform 拥有；
- 正式关联与商品命令、幂等成功记录和审计在同一事务提交。

### 6.3 `material_definitions`

| 字段 | 类型 | 空值 | 规则与含义 |
|---|---|---|---|
| `id` | BIGINT | 否 | 内部主键 |
| `business_id` | VARCHAR(64) | 否 | 稳定材料标识 |
| `material_code` | VARCHAR(64) | 否 | 稳定材料代码 |
| `name` | VARCHAR(120) | 否 | 材料名称，类别内不再拆 EAV |
| `standard_unit_code` | VARCHAR(32) | 否 | `GRAM`、`PIECE`、`METER` 等受控代码 |
| `default_unit_price` | DECIMAL(19,4) | 否 | 当前预算单价，非负 |
| `status` | VARCHAR(32) | 否 | `ACTIVE`、`INACTIVE` |
| `version` | BIGINT | 否 | 乐观版本 |
| `created_at` | DATETIME(6) | 否 | 创建时间 |
| `created_by` | VARCHAR(64) | 否 | 创建人 |
| `updated_at` | DATETIME(6) | 否 | 最后修改时间 |
| `updated_by` | VARCHAR(64) | 否 | 最后修改人 |

- `UNIQUE(business_id)`；
- `UNIQUE(material_code)`；
- `CHECK(default_unit_price >= 0)`；
- 系统初始材料至少包括胶水和色浆，后续可增加袋子、填充物、缝线及其他明确材料；
- 被预算或历史快照引用的材料不得删除，只能停用；
- 材料改名或改单价不刷新既有预算快照和订单快照。

### 6.4 `product_process_budget_templates`

一行表达商品标准方案中的一道工序，不表达生产路线事实。

| 字段 | 类型 | 空值 | 规则与含义 |
|---|---|---|---|
| `id` | BIGINT | 否 | 内部主键 |
| `business_id` | VARCHAR(64) | 否 | 稳定模板标识 |
| `product_id` | BIGINT | 否 | 商品外键 |
| `process_code` | VARCHAR(32) | 否 | `MAKING`、`PACKING_BAG`、`SEAM_CUTTING`、`BOXING` |
| `inclusion_type` | VARCHAR(32) | 否 | `REQUIRED`、`OPTIONAL` |
| `time_entry_id` | BIGINT | 是 | 对应静态时间条目；制作使用商品制作时间时可空 |
| `standard_minutes` | INT | 否 | 冻结到当前模板的标准分钟，1–360 |
| `commission_amount` | DECIMAL(19,4) | 否 | 适用提成，默认 0，非负 |
| `sort_order` | INT | 否 | 非负稳定展示顺序 |
| `version` | BIGINT | 否 | 模板版本 |
| `created_at` | DATETIME(6) | 否 | 创建时间 |
| `created_by` | VARCHAR(64) | 否 | 创建人 |
| `updated_at` | DATETIME(6) | 否 | 最后修改时间 |
| `updated_by` | VARCHAR(64) | 否 | 最后修改人 |

- `UNIQUE(business_id)`；
- `UNIQUE(product_id, process_code)`；
- `CHECK(standard_minutes BETWEEN 1 AND 360)`；
- `CHECK(commission_amount >= 0)`；
- 制作必须存在且为 `REQUIRED`；
- 捏毛装袋和缝边剪袋可以是 `OPTIONAL`；
- BOXING 是交付预算项目，不进入制品物理阶段；
- 本表不允许把缝边剪袋定义为所有商品的固定必经工序。

### 6.5 `product_process_material_budgets`

| 字段 | 类型 | 空值 | 规则与含义 |
|---|---|---|---|
| `id` | BIGINT | 否 | 内部主键 |
| `business_id` | VARCHAR(64) | 否 | 稳定预算行标识 |
| `process_template_id` | BIGINT | 否 | 模块内工序模板外键 |
| `material_definition_id` | BIGINT | 否 | 模块内材料外键 |
| `usage_formula_code` | VARCHAR(64) | 否 | `FIXED_USAGE_V1`、`GLUE_USAGE_V1` 或其他明确用量公式 |
| `cost_formula_code` | VARCHAR(64) | 否 | `GLUE_COST_V1`、`PIGMENT_COST_V1`、`GENERIC_MATERIAL_COST_V1` 等 |
| `base_usage` | DECIMAL(19,4) | 是 | 固定用量或公式额外参数；由商品重量推导时允许为空 |
| `unit_code` | VARCHAR(32) | 否 | 必须与材料定义兼容 |
| `loss_rate` | DECIMAL(12,8) | 否 | 非负；不适用时为 0 |
| `budget_unit_price` | DECIMAL(19,4) | 否 | 本预算行采用的单价，非负 |
| `default_bearer_type` | VARCHAR(32) | 否 | `STUDIO`、`CUSTOMER` |
| `sort_order` | INT | 否 | 非负顺序 |
| `version` | BIGINT | 否 | 乐观版本 |
| `created_at` | DATETIME(6) | 否 | 创建时间 |
| `created_by` | VARCHAR(64) | 否 | 创建人 |
| `updated_at` | DATETIME(6) | 否 | 最后修改时间 |
| `updated_by` | VARCHAR(64) | 否 | 最后修改人 |

- `UNIQUE(business_id)`；
- `UNIQUE(process_template_id, material_definition_id)`；
- `CHECK(base_usage IS NULL OR base_usage > 0)`；
- `CHECK(loss_rate >= 0)`；
- `CHECK(budget_unit_price >= 0)`；
- `FIXED_USAGE_V1` 必须提供 `base_usage`，`GLUE_USAGE_V1` 使用商品标准重量时允许为空；公式与字段组合由命令校验和契约测试保证；
- 客户承担只改变成本承担方，不允许通过清空用量公式或必需参数表示客户供料。

胶水和色浆使用明确公式代码，不通过自由配置的表达式计算。胶水行以商品重量和损耗率计算用量；色浆成本引用同一预算上下文中已计算的胶水用量，不重复乘损耗率。

### 6.6 `product_cost_allocation_budgets`

| 字段 | 类型 | 空值 | 规则与含义 |
|---|---|---|---|
| `id` | BIGINT | 否 | 内部主键 |
| `business_id` | VARCHAR(64) | 否 | 稳定分摊标识 |
| `product_id` | BIGINT | 否 | 商品外键 |
| `scope_code` | VARCHAR(32) | 否 | `PRODUCT_UNIT`、`DELIVERY_ESTIMATE` |
| `allocation_code` | VARCHAR(64) | 否 | `MOULD`、`MISC`、`RENT_UTILITIES`、`OUTER_PACKAGING`、`FREIGHT_ESTIMATE` 等 |
| `name` | VARCHAR(120) | 否 | 展示名称 |
| `amount` | DECIMAL(19,4) | 否 | 非负预计金额 |
| `sort_order` | INT | 否 | 非负顺序 |
| `version` | BIGINT | 否 | 乐观版本 |
| `created_at` | DATETIME(6) | 否 | 创建时间 |
| `created_by` | VARCHAR(64) | 否 | 创建人 |
| `updated_at` | DATETIME(6) | 否 | 最后修改时间 |
| `updated_by` | VARCHAR(64) | 否 | 最后修改人 |

- `UNIQUE(business_id)`；
- `UNIQUE(product_id, scope_code, allocation_code)`；
- `CHECK(amount >= 0)`；
- 这里只保存预算，不产生经营成本事实。

### 6.7 `product_customization_profiles`

| 字段 | 类型 | 空值 | 规则与含义 |
|---|---|---|---|
| `id` | BIGINT | 否 | 内部主键 |
| `business_id` | VARCHAR(64) | 否 | 稳定方案标识 |
| `product_id` | BIGINT | 否 | 商品外键 |
| `name` | VARCHAR(120) | 否 | 商品内唯一方案名 |
| `delivery_stage_code` | VARCHAR(32) | 否 | `MAKING_COMPLETED`、`BAGGING_COMPLETED`、`SEAM_CUTTING_COMPLETED` |
| `default_unit_price` | DECIMAL(19,4) | 否 | 默认成交单价，非负 |
| `status` | VARCHAR(32) | 否 | `ACTIVE`、`INACTIVE` |
| `version` | BIGINT | 否 | 乐观版本 |
| `created_at` | DATETIME(6) | 否 | 创建时间 |
| `created_by` | VARCHAR(64) | 否 | 创建人 |
| `updated_at` | DATETIME(6) | 否 | 最后修改时间 |
| `updated_by` | VARCHAR(64) | 否 | 最后修改人 |

- `UNIQUE(business_id)`；
- `UNIQUE(product_id, name)`，名称比较使用去除前后空格后的规范值；
- `CHECK(default_unit_price >= 0)`；
- 默认方案必须为 `ACTIVE` 且属于同一商品；
- 方案停用不影响订单已冻结快照；
- 方案不保存订单数量、订单客户或履约状态。

### 6.8 `product_customization_processes`

| 字段 | 类型 | 空值 | 规则与含义 |
|---|---|---|---|
| `id` | BIGINT | 否 | 内部主键 |
| `business_id` | VARCHAR(64) | 否 | 稳定方案工序标识 |
| `profile_id` | BIGINT | 否 | 定制方案外键 |
| `process_code` | VARCHAR(32) | 否 | 受控工序代码 |
| `standard_minutes` | INT | 否 | 1–360 |
| `commission_amount` | DECIMAL(19,4) | 否 | 非负 |
| `sort_order` | INT | 否 | 工序顺序 |
| `version` | BIGINT | 否 | 乐观版本 |
| `created_at` | DATETIME(6) | 否 | 创建时间 |
| `created_by` | VARCHAR(64) | 否 | 创建人 |
| `updated_at` | DATETIME(6) | 否 | 最后修改时间 |
| `updated_by` | VARCHAR(64) | 否 | 最后修改人 |

- `UNIQUE(business_id)`；
- `UNIQUE(profile_id, process_code)`；
- 工序组合必须符合 `制作 → 捏毛装袋 → 缝边剪袋` 顺序；
- 交付终点为缝边剪袋时必须同时包含捏毛装袋；
- BOXING 可以作为交付预算项目，但不成为生产物理工序。

### 6.9 `product_customization_material_terms`

| 字段 | 类型 | 空值 | 规则与含义 |
|---|---|---|---|
| `id` | BIGINT | 否 | 内部主键 |
| `business_id` | VARCHAR(64) | 否 | 稳定条款标识 |
| `profile_process_id` | BIGINT | 否 | 方案工序外键 |
| `material_definition_id` | BIGINT | 否 | 材料外键 |
| `usage_formula_code` | VARCHAR(64) | 否 | 本条款采用的材料用量公式 |
| `cost_formula_code` | VARCHAR(64) | 否 | 本条款采用的材料成本公式 |
| `base_usage` | DECIMAL(19,4) | 是 | 固定用量或公式额外参数；由商品重量推导时允许为空 |
| `unit_code` | VARCHAR(32) | 否 | 明确单位 |
| `loss_rate` | DECIMAL(12,8) | 否 | 非负 |
| `budget_unit_price` | DECIMAL(19,4) | 否 | 非负 |
| `bearer_type` | VARCHAR(32) | 否 | `STUDIO`、`CUSTOMER` |
| `version` | BIGINT | 否 | 乐观版本 |
| `created_at` | DATETIME(6) | 否 | 创建时间 |
| `created_by` | VARCHAR(64) | 否 | 创建人 |
| `updated_at` | DATETIME(6) | 否 | 最后修改时间 |
| `updated_by` | VARCHAR(64) | 否 | 最后修改人 |

- `UNIQUE(business_id)`；
- `UNIQUE(profile_process_id, material_definition_id)`；
- `CHECK(base_usage IS NULL OR base_usage > 0)`；
- 用量公式、成本公式与必需参数的组合规则与商品标准材料预算一致；
- 客户供料仍保留用量公式、计算参数、单价和标准价值；
- 不据此创建客户材料库存、领用或余额。

### 6.10 商品预算快照

`product_standard_budget_snapshots` 与 `product_customization_budget_snapshots` 使用相同结果语义，但分别物理关联商品标准方案和定制方案，避免多态外键。

公共字段：

| 字段 | 类型 | 空值 | 规则与含义 |
|---|---|---|---|
| `id` | BIGINT | 否 | 内部主键 |
| `snapshot_id` | VARCHAR(64) | 否 | 稳定快照标识 |
| `product_id` | BIGINT | 否 | 商品外键 |
| `profile_id` | BIGINT | 仅定制快照非空 | 定制方案外键 |
| `snapshot_version` | BIGINT | 否 | 所属商品或方案内递增版本 |
| `source_version` | BIGINT | 否 | 计算时的商品或方案版本 |
| `formula_rule_version` | VARCHAR(64) | 否 | 公式规则版本 |
| `rounding_version` | VARCHAR(64) | 否 | 舍入版本 |
| `full_resource_unit_cost` | DECIMAL(19,4) | 否 | 全部人工、材料和分摊标准价值 |
| `studio_borne_unit_cost` | DECIMAL(19,4) | 否 | 工作室承担预计成本 |
| `customer_supplied_unit_value` | DECIMAL(19,4) | 否 | 客户供料标准价值 |
| `expected_unit_revenue` | DECIMAL(19,4) | 否 | 预计单件收入 |
| `expected_unit_profit` | DECIMAL(19,4) | 否 | 收入减工作室承担成本 |
| `expected_profit_rate` | DECIMAL(12,8) | 是 | 收入为 0 时为空 |
| `calculation_input_fingerprint` | VARCHAR(128) | 否 | 规范化输入摘要 |
| `created_by_command_execution_id` | VARCHAR(64) | 否 | 创建命令执行标识 |
| `created_at` | DATETIME(6) | 否 | 创建时间 |

预算快照必须分别配套 `product_standard_budget_snapshot_lines` 和 `product_customization_budget_snapshot_lines`。两张明细表不使用多态父键，公共字段为：

```text
id
line_id
standard_snapshot_id 或 customization_snapshot_id
line_no
process_code
cost_component_type
material_business_id（材料项时非空）
bearer_type
standard_usage
unit_code
unit_price
full_resource_cost
studio_borne_cost
customer_supplied_value
formula_code
formula_rule_version
rounding_version
created_at
```

约束：

- `UNIQUE(line_id)`；
- `UNIQUE(所属快照, line_no)`；
- 明细通过模块内物理外键关联对应快照，快照和明细都不允许更新或删除；
- `cost_component_type` 只能取 `LABOR`、`MATERIAL`、`ALLOCATION`；
- 材料项必须存在 `material_business_id`、用量、单位和单价，非材料项不得伪造材料标识；
- 承担方、完整资源成本、工作室承担成本和客户供料价值必须满足单行可验证的互斥与非负约束；
- 快照汇总必须等于对应明细按未过早舍入口径计算后的总和。

快照头约束：

- `UNIQUE(snapshot_id)`；
- `UNIQUE(product_id, snapshot_version)` 或 `UNIQUE(profile_id, snapshot_version)`；
- 金额除利润外均不得为负；
- 快照创建后不可更新或删除；
- 输入摘要相同且来源版本、公式版本相同的重复重算可以复用已有结果，但不能覆盖；
- 订单确认时复制必要预算内容形成 orders 自己拥有的冻结快照，不长期依赖本表当前值。

## 7. 客户表

### 7.1 `customers`

| 字段 | 类型 | 空值 | 规则与含义 |
|---|---|---|---|
| `id` | BIGINT | 否 | 内部主键 |
| `business_id` | VARCHAR(64) | 否 | 稳定客户标识 |
| `customer_no` | VARCHAR(32) | 否 | 人工客户编号，唯一不可变 |
| `name` | VARCHAR(120) | 否 | 必填、去除前后空格、允许重复 |
| `contact_name` | VARCHAR(120) | 否 | 主要联系人 |
| `contact_phone` | VARCHAR(80) | 否 | 文本保存，不限制为纯数字，不唯一 |
| `note` | VARCHAR(1000) | 是 | 客户内部备注 |
| `default_recipient_name` | VARCHAR(120) | 是 | 默认收货人 |
| `default_recipient_phone` | VARCHAR(80) | 是 | 默认联系电话 |
| `default_shipping_address` | VARCHAR(500) | 是 | 默认收货地址 |
| `default_shipping_note` | VARCHAR(500) | 是 | 默认收货备注 |
| `version` | BIGINT | 否 | 乐观版本 |
| `created_at` | DATETIME(6) | 否 | 创建时间 |
| `created_by` | VARCHAR(64) | 否 | 创建人 |
| `updated_at` | DATETIME(6) | 否 | 最后修改时间 |
| `updated_by` | VARCHAR(64) | 否 | 最后修改人 |

规则：

- `UNIQUE(business_id)`；
- `UNIQUE(customer_no)`；
- `INDEX(name, id)`；
- `INDEX(contact_phone, id)`；
- 客户不设置启用、停用状态，不提供物理删除；
- 相同名称或相同电话只产生重复候选提示，不构成唯一性拒绝；
- 不自动合并客户；
- 首期每个客户只保存一组默认收货资料；
- 不保存客户等级、类型、标签、默认折扣、默认价格、付款周期、银行资料、证照或附件；
- 订单确认时由 orders 冻结客户与本次收货资料，后续客户修改不回溯。

### 7.2 客户变更事实

`customer_change_facts`：

```text
id
fact_id
customer_id
fact_type = CUSTOMER_PROFILE_CHANGED
customer_version_before
customer_version_after
reason_text（可空）
operator_id
command_execution_id
occurred_at
recorded_at
created_at
```

`customer_change_fact_details`：

```text
id
change_fact_id
field_code
before_value
after_value
```

`field_code` 只能取客户公开可编辑字段的受控代码。明细表只用于解释变更事实，不作为客户当前状态来源，不允许任意业务字段 EAV 化。

- `UNIQUE(fact_id)`；
- `UNIQUE(change_fact_id, field_code)`；
- 客户更新、变更事实、当前版本递增、幂等和审计在同一事务提交；
- 原事实和事实明细不可编辑或删除；
- 录错通过新的客户变更命令恢复正确值。

## 8. 员工表

### 8.1 `employees`

| 字段 | 类型 | 空值 | 规则与含义 |
|---|---|---|---|
| `id` | BIGINT | 否 | 内部主键 |
| `business_id` | VARCHAR(64) | 否 | 稳定员工标识 |
| `employee_no` | VARCHAR(32) | 否 | 人工员工编号，唯一不可变 |
| `name` | VARCHAR(120) | 否 | 必填，允许重名 |
| `phone` | VARCHAR(80) | 是 | 文本保存，不唯一 |
| `employment_status` | VARCHAR(32) | 否 | `EMPLOYED`、`DEPARTED` |
| `current_employment_started_on` | DATE | 是 | 在职时必填 |
| `current_employment_ended_on` | DATE | 是 | 离职时可保存最近离职日 |
| `note` | VARCHAR(1000) | 是 | 员工备注 |
| `version` | BIGINT | 否 | 乐观版本 |
| `last_fact_id` | VARCHAR(64) | 否 | 最近就业事实标识 |
| `created_at` | DATETIME(6) | 否 | 创建时间 |
| `created_by` | VARCHAR(64) | 否 | 创建人 |
| `updated_at` | DATETIME(6) | 否 | 最后修改时间 |
| `updated_by` | VARCHAR(64) | 否 | 最后修改人 |

- `UNIQUE(business_id)`；
- `UNIQUE(employee_no)`；
- `INDEX(employment_status, name, id)`；
- 在职状态必须存在当前入职日期；
- 员工不物理删除；
- 返聘沿用原员工稳定标识和编号，不创建同一人的第二身份；
- 管理员账号与员工记录分离；管理员参与生产时必须通过 platform 关联到在职员工，并满足工种资格；
- 首期不保存身份证、住址、银行卡、合同、照片和附件。

### 8.2 `employee_employment_facts`

| 字段 | 类型 | 空值 | 规则与含义 |
|---|---|---|---|
| `id` | BIGINT | 否 | 内部主键 |
| `fact_id` | VARCHAR(64) | 否 | 稳定事实标识 |
| `employee_id` | BIGINT | 否 | 员工外键 |
| `fact_type` | VARCHAR(32) | 否 | `HIRED`、`DEPARTED`、`REHIRED` |
| `effective_date` | DATE | 否 | 生效业务日期 |
| `reason_text` | VARCHAR(500) | 是 | 离职或返聘说明 |
| `operator_id` | VARCHAR(64) | 否 | 操作者 |
| `command_execution_id` | VARCHAR(64) | 否 | 命令执行标识 |
| `occurred_at` | DATETIME(6) | 否 | 业务发生时间 |
| `recorded_at` | DATETIME(6) | 否 | 服务端记录时间 |
| `created_at` | DATETIME(6) | 否 | 创建时间 |

- `UNIQUE(fact_id)`；
- `UNIQUE(command_execution_id, fact_type, employee_id)`；
- 状态序列必须合法：新员工从 `HIRED` 开始，离职后才能 `REHIRED`；
- 离职不改写历史任务、核验或工资中的员工快照；
- 就业事实与 employees 当前投影在同一事务更新。

### 8.3 工种资格事实与投影

`employee_work_type_assignment_facts`：

```text
id
fact_id
employee_id
work_type_definition_id
fact_type = GRANTED | REVOKED
effective_date
reason_text（可空）
operator_id
command_execution_id
occurred_at
recorded_at
created_at
```

`employee_work_type_assignments`：

```text
id
assignment_id
employee_id
work_type_definition_id
status = ACTIVE | REVOKED
granted_by_fact_id
revoked_by_fact_id（可空）
version
created_at
updated_at
```

约束：

- 同一员工和工种最多一个当前投影；
- 同一命令不得重复授予或撤销同一资格；
- 至少保留一个工种不是数据库单行约束，是否要求由员工业务命令决定；
- 离职不删除工种资格历史，排班资格校验同时要求员工在职和资格当前有效；
- 工种变更不回溯修改历史任务和工资事实。

## 9. 静态数据表

### 9.1 类别

`static_data_categories` 由 Flyway 预置以下固定类别：

```text
STAR_LEVEL       制作标准时间
PACKAGING_TIER   捏毛装袋标准时间
SEAM_TYPE        缝边剪袋标准时间
WORK_TYPE        员工工种
```

字段：

```text
id
business_id
category_code
name
value_kind = STANDARD_MINUTES | WORK_TYPE
created_at
```

- `UNIQUE(business_id)`；
- `UNIQUE(category_code)`；
- 类别不允许通过业务接口新增、删除、改 code 或改 `value_kind`；
- 类别显示名如需调整必须通过受控系统变更，不由普通静态数据维护接口修改。

### 9.2 公共条目

`static_data_entries`：

```text
id
business_id
category_id
entry_code（用户条目可空，系统固定条目非空）
name
status = ACTIVE | INACTIVE
version
created_at
created_by
updated_at
updated_by
```

- `UNIQUE(business_id)`；
- `UNIQUE(category_id, name)`；
- 系统固定 `entry_code` 在类别内唯一；
- 名称去除前后空格且不允许空白；
- 已被商品、员工、预算或历史快照引用的条目不得物理删除；
- 停用只阻止新引用，不影响历史解释；
- 改名和改标准分钟不刷新历史快照。

### 9.3 标准时间定义

`static_time_definitions`：

```text
entry_id BIGINT PRIMARY KEY
standard_minutes INT NOT NULL
```

- 仅允许关联 `STAR_LEVEL`、`PACKAGING_TIER`、`SEAM_TYPE`；
- `CHECK(standard_minutes BETWEEN 1 AND 360)`；
- 制作有效工时率只作用于 `STAR_LEVEL` 所代表的制作计算；
- 包装和缝边剪袋人工不乘制作有效工时率；
- 包装提成不属于包装档位字段。

### 9.4 工种定义

`work_type_definitions`：

```text
entry_id BIGINT PRIMARY KEY
work_type_code VARCHAR(32) NOT NULL UNIQUE
```

Flyway 固定预置：

```text
MAKING
PACKING_BAG
SEAM_CUTTING
OTHER
```

- 工种 code 不允许新增、删除或修改；
- 允许修改公共条目的显示名称；
- 工种启用状态不作为排班业务开关；系统固定工种始终可被资格事实引用；
- 工种是排班硬资格，不是员工星级。

## 10. 全局设置

### 10.1 `catalog_settings`

全系统只有一条当前设置投影，不使用自由键值 EAV。

| 字段 | 类型 | 空值 | 规则与含义 |
|---|---|---|---|
| `id` | BIGINT | 否 | 固定单行内部主键 |
| `business_id` | VARCHAR(64) | 否 | 稳定设置标识 |
| `hourly_wage` | DECIMAL(19,4) | 否 | 时薪，非负 |
| `workday_hours` | DECIMAL(12,8) | 否 | 标准工作日小时数，大于 0 且不大于 24 |
| `making_effective_hour_rate` | DECIMAL(12,8) | 否 | 制作有效工时率，大于 0 且不大于 1 |
| `target_margin_rate` | DECIMAL(12,8) | 否 | 目标利润率，大于等于 0 且小于 1 |
| `packaging_commission_default` | DECIMAL(19,4) | 否 | 默认包装提成，非负 |
| `version` | BIGINT | 否 | 当前设置版本 |
| `last_fact_id` | VARCHAR(64) | 否 | 最近设置变更事实 |
| `created_at` | DATETIME(6) | 否 | 创建时间 |
| `created_by` | VARCHAR(64) | 否 | 创建人 |
| `updated_at` | DATETIME(6) | 否 | 最后修改时间 |
| `updated_by` | VARCHAR(64) | 否 | 最后修改人 |

数据库约束覆盖所有单行范围。业务接口和 HTTP 使用小数比例，例如 30% 传输为 `0.30000000`；显示层百分数转换不进入公式中心。

### 10.2 `catalog_setting_change_facts`

每次变更保存生效后的完整参数集，而不是任意键值列表：

```text
id
fact_id
settings_business_id
settings_version_before
settings_version_after
hourly_wage
workday_hours
making_effective_hour_rate
target_margin_rate
packaging_commission_default
reason_text
operator_id
command_execution_id
occurred_at
recorded_at
created_at
```

- `UNIQUE(fact_id)`；
- `UNIQUE(settings_business_id, settings_version_after)`；
- 设置事实只追加；
- 当前投影和设置事实在同一事务提交；
- 新设置只作用于之后的新预算和新冻结快照，不刷新既有订单、任务、工资和成本事实；
- 商品当前预算可以由管理员显式重算，不因设置变化在后台静默覆盖。

## 11. `calculation` 公式契约

### 11.1 纯函数要求

每个公式必须满足：

- 相同输入和相同规则版本始终得到相同输出；
- 不读取数据库、系统时间、当前用户或环境变量；
- 不写日志作为业务结果，不产生审计或业务事实；
- 输入输出为不可变类型；
- 不接受缺少单位和业务语义的裸 `Map`；
- 不在公式内部读取“当前设置”；设置值必须作为输入显式传入；
- 不以异常堆栈表达预期业务拒绝，使用结构化公式错误。

### 11.2 规则与舍入版本

每个可冻结结果至少携带：

```text
formulaCode
formulaRuleVersion
roundingVersion
```

初始版本命名建议：

```text
formulaRuleVersion = COSTING_V1
roundingVersion    = MONEY_HALF_UP_SCALE_4_V1
```

版本是代码化解释标识，不是数据库公式记录。公式语义变化时新增版本实现和回归用例；历史规则在仍需重算或核对时必须可调用，不能在同一版本下静默改变结果。

### 11.3 中间精度与最终舍入

- 输入按数据库公共精度校验；
- 中间乘除计算使用不低于 16 位小数的明确计算上下文；
- 不能在每一步无条件先舍入到 4 位后再继续计算；
- 对外冻结的金额统一四位小数；
- 比例冻结为八位小数；
- 数量产能使用 `floor` 时必须在公式定义中明确；
- 除零返回结构化错误或“不可计算”结果，不返回 0、无穷或 NaN；
- 汇总结果必须由未过早舍入的明细计算，并同时返回已舍入明细供核对。

## 12. 公式目录

### 12.1 材料公式

#### `GLUE_USAGE_V1`

```text
胶水用量 = 商品标准重量 × (1 + 损耗率)
```

输入：商品标准重量、损耗率。输出单位：克。损耗率必须非负。

#### `GLUE_COST_V1`

```text
胶水成本 = 胶水用量 × 胶水单价
```

#### `PIGMENT_COST_V1`

```text
色浆成本 = 胶水用量 × 色浆单价
```

胶水用量已经包含损耗，色浆成本不得再次乘损耗率。

#### `GENERIC_MATERIAL_COST_V1`

```text
材料成本 = 标准用量 × (1 + 适用损耗率) × 预算单价
```

只有明确采用该通用口径的材料使用本公式。胶水和色浆继续使用专用公式，避免重复损耗。

### 12.2 制作人工

#### `MAKING_STANDARD_DAILY_QUANTITY_V1`

```text
工作日标准数量
= floor(工作日小时数 × 60 ÷ 制作标准分钟)
```

#### `MAKING_EFFECTIVE_DAILY_QUANTITY_V1`

```text
制作有效产量
= floor(工作日小时数 × 60 × 制作有效工时率 ÷ 制作标准分钟)
```

制作有效产量必须大于 0，否则拒绝计算单件制作人工。

#### `MAKING_UNIT_LABOR_COST_V1`

```text
制作单件人工成本
= (时薪 × 工作日小时数) ÷ 制作有效产量
```

制作有效工时率只作用于制作。

### 12.3 包装和缝边剪袋人工

#### `PACKAGING_UNIT_LABOR_COST_V1`

```text
包装单件人工成本
= 包装标准分钟 × (时薪 ÷ 60)
+ 适用包装提成
```

适用提成由商品或方案明确值决定；未覆盖时使用全局默认值。公式本身不读取默认值。

#### `SEAM_CUTTING_UNIT_LABOR_COST_V1`

```text
缝边剪袋单件人工成本
= 缝边标准分钟 × (时薪 ÷ 60)
```

不乘制作有效工时率。

### 12.4 汇总、售价和利润

#### `UNIT_TOTAL_COST_V1`

```text
单件总成本
= 材料成本合计
+ 人工成本合计
+ 其他预计分摊合计
```

人工成本合计已经包含制作、包装、缝边剪袋和适用交付人工，不得再次把人工分项与人工总额重复相加。

#### `REFERENCE_UNIT_PRICE_V1`

```text
参考售价 = 单件总成本 ÷ (1 - 目标利润率)
```

目标利润率必须大于等于 0 且小于 1。

#### `EXPECTED_PROFIT_V1`

```text
预计利润 = 预计收入 - 工作室承担预计成本
```

#### `EXPECTED_PROFIT_RATE_V1`

```text
预计利润率 = 预计利润 ÷ 预计收入
```

预计收入为 0 时返回 `NOT_CALCULABLE`，不得执行除零。客户自供材料标准价值不计入工作室承担预计成本，但计入标准完整资源成本。

## 13. 公式公开接口

### 13.1 基础值类型

```java
record Money(BigDecimal amount, Currency currency) {}
record Rate(BigDecimal value) {}
record Minutes(int value) {}
record WeightInGrams(BigDecimal value) {}
record MaterialUsage(BigDecimal value, UnitCode unit) {}
record FormulaRuleVersion(String value) {}
record RoundingVersion(String value) {}
```

### 13.2 单项公式接口

```java
interface MakingLaborCalculator {
    MakingLaborResult calculate(MakingLaborInput input);
}

interface PackagingLaborCalculator {
    PackagingLaborResult calculate(PackagingLaborInput input);
}

interface SeamCuttingLaborCalculator {
    SeamCuttingLaborResult calculate(SeamCuttingLaborInput input);
}

interface MaterialCostCalculator {
    MaterialCostResult calculate(MaterialCostInput input);
}

interface ReferencePriceCalculator {
    ReferencePriceResult calculate(ReferencePriceInput input);
}

interface ProfitCalculator {
    ProfitResult calculate(ProfitInput input);
}
```

### 13.3 聚合预算接口

```java
interface ProductBudgetCalculator {
    ProductBudgetResult calculate(ProductBudgetInput input);
}

interface CustomizationBudgetCalculator {
    CustomizationBudgetResult calculate(CustomizationBudgetInput input);
}
```

聚合结果至少返回：

```text
工序人工明细
材料需求与成本明细
其他分摊明细
标准完整资源成本
工作室承担预计成本
客户自供材料标准价值
预计收入
预计利润
预计利润率或不可计算原因
formulaRuleVersion
roundingVersion
关键中间值
```

### 13.4 公式目录

```java
interface FormulaCatalogue {
    FormulaDefinition get(FormulaCode code, FormulaRuleVersion version);
    List<FormulaDefinition> listCurrent();
}
```

`FormulaDefinition` 只描述代码内公式：

```text
formulaCode
ruleVersion
inputDefinitions
outputDefinitions
unitRules
roundingRule
domainScope
testCaseIds
```

不提供新增、修改或删除公式的业务接口。

## 14. `master-data` 应用命令接口

应用命令由 `master-data` 开启 `REQUIRED` 事务，接收 `RequestContext` 和外部幂等输入。命令所有者生成 `CommandExecutionContext`。

### 14.1 商品

```java
interface ProductApplication {
    CreateProductResult create(
        CreateProductCommand command,
        RequestContext context,
        IdempotencyInput idempotency
    );

    UpdateProductResult update(
        UpdateProductCommand command,
        RequestContext context,
        IdempotencyInput idempotency
    );

    ChangeProductStatusResult changeStatus(
        ChangeProductStatusCommand command,
        RequestContext context,
        IdempotencyInput idempotency
    );
}
```

```java
interface ProductBudgetApplication {
    SaveStandardBudgetResult saveStandardBudget(
        SaveStandardBudgetCommand command,
        RequestContext context,
        IdempotencyInput idempotency
    );

    CreateCustomizationProfileResult createProfile(
        CreateCustomizationProfileCommand command,
        RequestContext context,
        IdempotencyInput idempotency
    );

    UpdateCustomizationProfileResult updateProfile(
        UpdateCustomizationProfileCommand command,
        RequestContext context,
        IdempotencyInput idempotency
    );

    ChangeCustomizationProfileStatusResult changeProfileStatus(
        ChangeCustomizationProfileStatusCommand command,
        RequestContext context,
        IdempotencyInput idempotency
    );

    SetDefaultCustomizationProfileResult setDefaultProfile(
        SetDefaultCustomizationProfileCommand command,
        RequestContext context,
        IdempotencyInput idempotency
    );

    RecalculateProductBudgetResult recalculate(
        RecalculateProductBudgetCommand command,
        RequestContext context,
        IdempotencyInput idempotency
    );
}
```

### 14.2 客户

```java
interface CustomerApplication {
    CreateCustomerResult create(
        CreateCustomerCommand command,
        RequestContext context,
        IdempotencyInput idempotency
    );

    UpdateCustomerResult update(
        UpdateCustomerCommand command,
        RequestContext context,
        IdempotencyInput idempotency
    );
}
```

客户不公开删除、停用或自动合并命令。

### 14.3 员工

```java
interface EmployeeApplication {
    HireEmployeeResult hire(
        HireEmployeeCommand command,
        RequestContext context,
        IdempotencyInput idempotency
    );

    UpdateEmployeeProfileResult updateProfile(
        UpdateEmployeeProfileCommand command,
        RequestContext context,
        IdempotencyInput idempotency
    );

    DepartEmployeeResult depart(
        DepartEmployeeCommand command,
        RequestContext context,
        IdempotencyInput idempotency
    );

    RehireEmployeeResult rehire(
        RehireEmployeeCommand command,
        RequestContext context,
        IdempotencyInput idempotency
    );

    GrantEmployeeWorkTypeResult grantWorkType(
        GrantEmployeeWorkTypeCommand command,
        RequestContext context,
        IdempotencyInput idempotency
    );

    RevokeEmployeeWorkTypeResult revokeWorkType(
        RevokeEmployeeWorkTypeCommand command,
        RequestContext context,
        IdempotencyInput idempotency
    );
}
```

离职、返聘和工种资格变化必须产生专用事实，不通过一个通用员工更新命令修改状态。

### 14.4 静态数据和设置

```java
interface StaticDataApplication {
    CreateStaticDataEntryResult createEntry(
        CreateStaticDataEntryCommand command,
        RequestContext context,
        IdempotencyInput idempotency
    );

    UpdateStaticDataEntryResult updateEntry(
        UpdateStaticDataEntryCommand command,
        RequestContext context,
        IdempotencyInput idempotency
    );

    ChangeStaticDataEntryStatusResult changeEntryStatus(
        ChangeStaticDataEntryStatusCommand command,
        RequestContext context,
        IdempotencyInput idempotency
    );

    RenameWorkTypeResult renameWorkType(
        RenameWorkTypeCommand command,
        RequestContext context,
        IdempotencyInput idempotency
    );
}

interface CatalogSettingsApplication {
    UpdateCatalogSettingsResult update(
        UpdateCatalogSettingsCommand command,
        RequestContext context,
        IdempotencyInput idempotency
    );
}
```

- 静态数据接口不得新增、删除或改名类别；
- 不得新增、删除或改 code 固定工种；
- 被引用条目不提供物理删除；
- 设置更新是一次完整参数集变更，不提供任意 key/value 更新。

## 15. 查询和跨模块快照端口

### 15.1 管理查询

```java
interface ProductQuery {
    ProductDetailView get(ProductId productId, QueryContext context);
    CursorPage<ProductSummaryView> search(ProductSearchQuery query, QueryContext context);
}

interface CustomerQuery {
    CustomerDetailView get(CustomerId customerId, QueryContext context);
    CursorPage<CustomerSummaryView> search(CustomerSearchQuery query, QueryContext context);
    List<CustomerDuplicateCandidateView> findDuplicateCandidates(
        CustomerDuplicateCandidateQuery query,
        QueryContext context
    );
}

interface EmployeeQuery {
    EmployeeDetailView get(EmployeeId employeeId, QueryContext context);
    CursorPage<EmployeeSummaryView> search(EmployeeSearchQuery query, QueryContext context);
}

interface StaticDataQuery {
    List<StaticDataCategoryView> listCategories(QueryContext context);
    List<StaticDataEntryView> listEntries(StaticDataCategoryCode category, QueryContext context);
}

interface CatalogSettingsQuery {
    CatalogSettingsView get(QueryContext context);
}
```

### 15.2 预算试算

```java
interface ProductBudgetPreviewQuery {
    ProductBudgetPreview preview(
        ProductBudgetPreviewInput input,
        QueryContext context
    );
}
```

试算可以使用只读 `POST` 暴露于 HTTP，但不得创建预算快照、幂等成功记录、正式审计或业务事实。正式保存时必须在锁内重新读取主数据和设置并重新计算。

### 15.3 跨模块快照读取端口

```java
interface ProductSnapshotQuery {
    ProductOrderSnapshot getForOrder(ProductId productId, Optional<CustomizationProfileId> profileId);
}

interface CustomerSnapshotQuery {
    CustomerOrderSnapshot getForOrder(CustomerId customerId);
}

interface EmployeeQualificationQuery {
    EmployeeQualificationView get(EmployeeId employeeId);
}

interface CatalogParameterQuery {
    CatalogParameterSnapshot getCurrent();
}
```

规则：

- 端口返回不可变业务视图，不返回主数据行模型；
- orders 在确认事务中复制必要内容到自己拥有的快照；
- scheduling 在任务创建和核验时重新校验员工在职状态与工种资格；
- payroll 使用历史生产事实中的员工标识和快照，不以员工当前姓名覆盖历史；
- 参与正式命令时，跨模块读取必须遵守命令所有者制定的锁计划，不能把先前查询结果当作提交授权。

## 16. 命令锁定与事务

### 16.1 模块内锁序

```text
商品 / 客户 / 员工 / 设置业务根
→ 从属预算、方案或资格记录
→ 事实来源
→ 当前投影
```

同类型记录按内部主键升序锁定。

### 16.2 典型命令

商品预算保存：

```text
锁定商品
→ 锁定受影响的标准预算模板和材料行
→ 锁定当前设置投影
→ 重验 expectedVersion、静态条目、材料状态和字段范围
→ 调用 calculation
→ 更新当前模板
→ 追加不可变预算快照及明细
→ 更新商品版本
→ 提交幂等、审计和文件关联
```

客户更新：

```text
锁定客户
→ 重验 expectedVersion
→ 查询重复候选但不以重复为硬拒绝
→ 追加客户变更事实和明细
→ 更新客户当前记录与版本
→ 同事务提交幂等和审计
```

员工离职：

```text
锁定员工
→ 重验在职状态与 expectedVersion
→ 追加离职事实
→ 更新就业状态投影
→ 不修改历史任务、核验和工资事实
```

设置更新：

```text
锁定单行设置投影
→ 重验 expectedVersion 和全部参数范围
→ 追加完整设置变更事实
→ 更新当前投影与版本
→ 不自动重算或覆盖既有商品预算快照
```

## 17. 幂等和领域唯一性

外部正式命令按公共基线使用：

```text
command_owner
operation_type
operator_id
idempotency_key_hash
```

参与写入的领域唯一约束至少覆盖：

- 同一命令只创建一个商品、客户或员工；
- 同一命令只追加一次就业或资格事实；
- 同一设置版本只追加一条变更事实；
- 同一商品或方案快照版本只创建一次；
- 同一文件不重复关联到同一商品用途。

试算查询不写成功幂等记录。输入摘要仅用于预算快照复用和核对，不替代正式命令幂等。

## 18. 错误码

| 错误码 | 分类 | 所有者 | 含义 |
|---|---|---|---|
| `MASTER_DATA_NOT_FOUND` | NOT_FOUND | master-data | 主数据不存在 |
| `MASTER_DATA_VERSION_CONFLICT` | CONCURRENT_CONFLICT | master-data | 当前版本与 expectedVersion 不符 |
| `MASTER_DATA_REFERENCED` | BUSINESS_REJECTION | master-data | 记录已被正式引用，不能删除或执行不兼容变更 |
| `PRODUCT_INACTIVE` | BUSINESS_REJECTION | master-data | 商品已停用，不能用于新业务 |
| `PRODUCT_BUDGET_INVALID` | VALIDATION | master-data | 商品预算结构或参数不完整 |
| `PRODUCT_PROFILE_INVALID` | VALIDATION | master-data | 定制方案工序、终点或材料条款不合法 |
| `CUSTOMER_DUPLICATE_WARNING` | 业务提示 | master-data | 存在相似名称或电话，仅供确认，不是失败 |
| `EMPLOYEE_NOT_EMPLOYED` | BUSINESS_REJECTION | master-data | 员工当前不在职 |
| `EMPLOYEE_WORK_TYPE_REQUIRED` | BUSINESS_REJECTION | master-data | 员工缺少所需工种资格 |
| `EMPLOYMENT_STATE_CONFLICT` | BUSINESS_REJECTION | master-data | 入职、离职或返聘状态序列不合法 |
| `STATIC_CATEGORY_FIXED` | BUSINESS_REJECTION | master-data | 固定类别不可变更 |
| `STATIC_ENTRY_CODE_FIXED` | BUSINESS_REJECTION | master-data | 系统条目 code 不可变更 |
| `STATIC_ENTRY_NAME_DUPLICATED` | BUSINESS_REJECTION | master-data | 类别内名称重复 |
| `STATIC_ENTRY_INACTIVE` | BUSINESS_REJECTION | master-data | 停用条目不能建立新引用 |
| `SETTING_VALUE_INVALID` | VALIDATION | master-data | 设置值超出范围 |
| `FORMULA_INPUT_INVALID` | VALIDATION | calculation | 公式输入、单位或范围不合法 |
| `FORMULA_DENOMINATOR_INVALID` | BUSINESS_REJECTION | calculation | 除数为零或公式不可计算 |
| `FORMULA_RULE_VERSION_UNSUPPORTED` | BUSINESS_REJECTION | calculation | 请求的历史公式版本不可用 |
| `FORMULA_RESULT_OUT_OF_RANGE` | PROGRAMMING_DEFECT | calculation | 结果超过约定精度或范围 |

重复客户候选不能映射成失败 HTTP 状态。创建或更新命令可以返回候选摘要并要求调用方通过明确确认字段再次提交；确认仅绑定本次请求指纹，不能关闭未来重复检查。

## 19. 权限能力

权限标识按能力拆分，不提供一个泛化的“主数据全部管理”权限：

```text
PRODUCT_VIEW
PRODUCT_CREATE
PRODUCT_UPDATE
PRODUCT_STATUS_CHANGE
PRODUCT_BUDGET_VIEW
PRODUCT_BUDGET_MANAGE
PRODUCT_BUDGET_RECALCULATE

CUSTOMER_VIEW
CUSTOMER_CREATE
CUSTOMER_UPDATE

EMPLOYEE_VIEW
EMPLOYEE_CREATE
EMPLOYEE_UPDATE
EMPLOYEE_EMPLOYMENT_CHANGE
EMPLOYEE_WORK_TYPE_MANAGE

STATIC_DATA_VIEW
STATIC_DATA_MANAGE
WORK_TYPE_RENAME

CATALOG_SETTINGS_VIEW
CATALOG_SETTINGS_UPDATE

FORMULA_CATALOGUE_VIEW
BUDGET_PREVIEW
```

platform 解析账号和基础权限，`master-data` 在正式事务内完成最终业务授权。`calculation` 纯函数不自行读取权限；权限由调用它的应用命令或查询入口负责。

## 20. 查询、分页与重复提示

- 商品、客户和员工长列表使用稳定游标分页；
- 静态数据类别及单类别条目属于有界管理列表，可以不分页或使用页码分页；
- 排序字段使用服务端白名单，不暴露数据库列名；
- 客户重复候选按规范化名称和规范化电话分别检索，展示客户编号、名称、联系人、电话和默认地址摘要；
- 模糊重复检索只用于提示，不参与唯一约束；
- 核心预算数据缺失必须显式失败，不能以 0 补齐；
- 商品详情可显示最新预算快照，但必须同时显示快照版本、来源版本和规则版本。

## 21. Flyway 和种子

`master-data` 迁移必须：

- 建立本文列出的表、外键、唯一约束、检查约束和锁定索引；
- 预置四个固定静态数据类别；
- 预置四个固定工种 code；
- 预置全局设置单行初始值；
- 预置胶水和色浆等系统必需材料时使用稳定 code；
- 不预置用户尚未确认的星级、包装档位或缝边种类；
- 不建立 JPA 序列表、公共库存表、固定生产路线表、数据库公式表或通用事件表；
- 不使用触发器执行预算计算、客户重复判断或跨模块编排。

固定种子必须在启动验证中与代码常量逐项核对。种子显示名允许后续受控修改时，稳定 code 不变。

## 22. 验证门禁

### 22.1 结构与约束

- 所有稳定业务标识和人工编号唯一；
- 负金额、非法比例、非法标准分钟和空白名称被数据库或边界拒绝；
- 商品默认方案只能指向本商品有效方案；
- 静态时间定义不能用于 WORK_TYPE；
- 工种定义只能使用四个系统 code；
- 客户名称和电话允许重复；
- 员工编号、客户编号和商品编号不可复用；
- 预算快照及明细不可更新和删除；
- calculation 模块没有数据库迁移和 Repository。

### 22.2 真实 MySQL 事务与并发

- 并发更新同一商品、客户、员工或设置时只有一个 expectedVersion 成功；
- 商品预算模板、预算快照、版本、幂等和审计原子提交；
- 预算计算失败时不留下部分模板或快照；
- 客户当前记录与变更事实原子提交；
- 员工就业事实与当前就业投影原子提交；
- 工种授予和撤销不会产生两个有效投影；
- 设置事实与当前设置投影原子提交；
- 相同幂等键不产生第二套主数据或事实；
- 锁定查询命中明确索引并按内部主键稳定排序。

### 22.3 公式测试

每个公式目录项必须登记自动化测试标识，并至少覆盖：

- 正常值；
- 精度边界；
- 0 值允许与禁止场景；
- 除零；
- 非法负数；
- 极大值溢出；
- 中间精度和最终舍入；
- 客户供料只改变承担成本、不删除标准价值；
- 胶水损耗只计算一次；
- 色浆不重复乘损耗率；
- 制作有效工时率只作用于制作；
- 包装与缝边剪袋不乘制作有效工时率；
- 收入为 0 时利润率不可计算；
- 历史规则版本在相同输入下保持原结果。

### 22.4 契约测试

- 命令输入不包含内部主键、调用方计算的总成本或目标预算投影；
- 快照查询不返回 Repository、行模型或文件存储键；
- 预算试算不产生业务写入；
- 正式保存不信任先前试算，必须重新计算；
- orders 复制快照后不因主数据修改而变化；
- scheduling 同时校验员工在职和工种资格；
- 固定类别和工种 code 没有通用增删接口；
- 公式接口不读取当前设置或系统时间。

## 23. 明确不采用

- 用商品当前值替代订单确认快照；
- 把所有成本压成一个可手改总成本；
- 用客户供料布尔值覆盖整道工序；
- 客户供料时删除材料需求或把用量改为 0；
- 把包装或缝边剪袋恢复为所有商品固定必经路线；
- 在商品中保存生产数量、公共库存或可发货数量；
- 为客户建立首期多地址簿、等级、标签和默认折扣；
- 用员工当前姓名覆盖历史任务和工资快照；
- 把管理员账号和员工身份合并；
- 让用户新增、删除静态数据类别或系统工种；
- 把包装提成放入包装档位定义；
- 用自由 key/value 全局设置替代明确字段；
- 在数据库保存公式表达式、脚本或动态规则；
- 在前端、订单、工资或成本模块复制同一套公式；
- 使用 FLOAT、DOUBLE、MySQL ENUM、EAV 或通用 JSON 保存核心预算；
- 用预算预计值代替正式工资、已归集经营成本或真实现金。

## 24. 与后续分册的衔接

`orders` 分册必须从本册定义的商品、客户和预算快照端口读取当前定义，并在订单确认时复制为 orders 自己拥有的冻结快照。

`production` 与 `scheduling` 分册必须使用稳定商品标识、制作规格快照、员工标识和工种资格，不直接读取本册表，也不得把商品预算模板当作生产事实。

`payroll` 分册可以调用 calculation 的工资相关纯公式，但正式工资规则、封账和补差由 payroll 拥有。

`cost-ledger` 只能把正式发生事实归集为经营成本，不能把本册预计预算直接认定为已发生成本。

`reporting` 可以组合本册当前主数据与其他模块公开查询，但其报表投影不得参与主数据命令校验。

## 25. 最终确认结论

```text
master-data 维护当前定义、标准和可复用预算模板
calculation 维护唯一的纯公式、精度和规则版本
商品拥有核心制作规格，但不拥有履约状态
预算按工序、人工、材料、承担方和分摊拆分
客户自供不删除材料需求，只改变工作室承担成本
定制方案是录单模板，订单确认后复制冻结
客户不启停、不删除，重复只提示
员工保留就业事实和工种资格历史
静态数据类别和工种 code 固定，时间条目可维护
全局设置使用明确字段和追加变更事实
公式不持久化、不动态编辑、不在多个模块重复实现
所有历史快照携带公式规则与舍入版本
```
