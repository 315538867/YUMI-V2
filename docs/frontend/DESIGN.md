# YUMI 前端设计契约（DESIGN.md）

本文件是页面视觉与交互的可执行契约。代码里的令牌在 `frontend/src/platform/theme/tokens.css`，
码表在 `frontend/src/platform/display/domain-codes.json`；两者与本文件冲突时，以本文件为准并同步修改代码。

## 1. 产品定位

- 面向工作室内部的全白常规后台，重点是可读、可核对、可连续操作。
- 页面由“浅灰底 + 白色工作区”构成，不使用大面积品牌色块、渐变背景或装饰性插画。
- 信息密度优先：一屏内优先呈现业务字段、金额、数量与状态，不用卡片堆叠代替表格。

## 2. 令牌分层

样式分三层，页面不得绕过：

| 层 | 文件 | 内容 |
|---|---|---|
| 基础令牌 | `frontend/src/platform/theme/tokens.css` | 原型色板、字号、间距、圆角、阴影、Element Plus 变量映射 |
| 组件样式 | `frontend/src/platform/theme/components.css` | `yumi-*` 页头/工具条/面板/统计卡/信息网格，以及 Element Plus 统一外观 |
| 业务页面 | `src/modules/**/*.vue` | 只组合共享组件与 `yumi-*` 类，页面 scoped CSS 只保留该页独有布局 |

## 3. 色彩

| 令牌 | 值 | 用途 |
|---|---|---|
| `--yumi-color-brand` | `#2f6f57` | 主按钮、链接、选中态、品牌强调 |
| `--yumi-color-brand-strong` | `#245541` | 主色按下态、深色强调 |
| `--yumi-color-brand-soft` | `#e7f1eb` | 选中行、轻量高亮底色 |
| `--yumi-color-ink` | `#20352d` | 正文与标题 |
| `--yumi-color-text` | `#375441` | 表单标签、常规文字 |
| `--yumi-color-muted` | `#65746c` | 说明、辅助字段、占位 |
| `--yumi-color-line` | `#e5ebe7` | 卡片、表格、分隔线 |
| `--yumi-color-canvas` | `#f5f7f5` | 内容区底色 |
| `--yumi-color-surface` | `#ffffff` | 工作区、顶栏 |
| `--yumi-color-gold` | `#8b5e2b` | 待处理、部分完成、风险提醒 |
| `--yumi-color-danger` | `#a85b50` | 失败、报废、错误 |
| `--yumi-color-rail` | `#1d3028` | 侧栏底色 |

- 状态色只用于标签、图标和小面积强调，不整行、整卡铺色。
- 所有文字与背景对比度至少 4.5:1。
- 页面禁止写死色值；`style.contract.test.ts` 扫描所有 `<style>` 并拦截十六进制、`rgb()`、`hsl()`。

## 4. 字体与层级

| 令牌 | 值 | 用途 |
|---|---|---|
| `--yumi-font-size-page` | `23px` | 工作区页标题 `h1` |
| `--yumi-font-size-2xl` | `20px` | 统计卡主数值、首页大标题 |
| `--yumi-font-size-xl` | `18px` | 强调数值 |
| `--yumi-font-size-lg` | `16px` | 大正文、顶部当前页名 |
| `--yumi-font-size-subtitle` | `15px` | 区块标题 `h2`、卡片头 |
| `--yumi-font-size-md` | `14px` | 较大正文 |
| `--yumi-font-size-base` | `13px` | 正文、表格、表单 |
| `--yumi-font-size-sm` | `12px` | 标签、说明、辅助字段 |
| `--yumi-font-size-xs` | `11px` | 表头、时间、单位、卡片注释 |

- 字重：`400 / 500 / 600 / 650` 分别使用 `--yumi-font-weight-normal/medium/semibold/bold`。
- 中文使用系统无衬线字体，不引入第三方字体。
- 金额、数量、日期使用等宽感呈现由 `MoneyText` / `DateTimeText` 负责，页面不自造格式。

## 5. 间距、圆角与布局

| 令牌 | 值 |
|---|---|
| `--yumi-space-1..12` | `4 / 8 / 12 / 16 / 20 / 24 / 32 / 40 / 48px` |
| `--yumi-radius-xs/sm/md/lg/xl` | `4 / 5 / 6 / 8 / 10px` |
| `--yumi-radius-pill` | `999px` |
| `--yumi-content-max-width` | `1600px` |
| `--yumi-rail-width` | `76px`（窄屏 `58px`） |
| `--yumi-content-padding` | `24px 32px 48px` |
| `--yumi-card-padding` | `16px` |

- 应用外壳：侧栏 `76px` 深绿图标 rail，顶栏 `70px`；内容区最大宽度 `1600px`。每个一级模块一个图标入口，名称通过右侧悬浮提示和 `aria-label` 给出；窄屏 rail 收至 `58px`。
- 页头在卡片外：标题左、说明紧随，主操作右；列表筛选与表格在卡片内。
- 模块页统一使用 `ModuleTabsPage`：模块标题 `h1` 固定在页签上方，子页不再重复页头；子页标题与主操作进入卡片头 `h2`。
- 工作区卡片统一使用 `WorkspacePage`（白底、`1px` 浅边框、圆角 `8px`、默认内边距 `16px`）；带卡片头时标题与动作位于卡头，正文保持同一内边距。
- 列表页使用 `ListPage`：页头在卡片外，卡片头可选，筛选条在卡片内；模块子页设置 `heading=false`，动作放 `panel-actions` 插槽。
- 编辑页使用 `EditorPage`：主表单 `minmax(0, 1fr)`、右侧 `300px` 预览、间距 `16px`。
- 详情页使用 `DetailPage`：页头在卡片外，只读内容在卡片内。
- 表格：撑满工作区宽度，`border` 样式；表头 `11px`，行内边距 `16px`，金额列右对齐。
- 弹窗：只用于短命令；长表单必须使用整页。

## 5.1 组件样式库

页面优先组合以下共享组件与类，不重复写样式：

| 组件/类 | 用途 |
|---|---|
| `PageShell` | 裸页面页头：标题、说明、右侧动作，卡片内容由页面自理 |
| `ModuleTabsPage` | 模块页：模块标题在页签之上，页签切换子路由，`overview` 插槽放模块概览 |
| `WorkspacePage` | 白底工作区卡片外壳；可带卡片头（标题、说明、动作），无标题时整卡内边距 |
| `ListPage` | 列表页：页头、卡头、筛选条、PageState、分页；模块子页用 `heading=false` 隐藏页头 |
| `EditorPage` | 编辑页：页头、表单与预览两栏 |
| `DetailPage` | 详情页：页头、动作区、只读内容 |
| `PageState` | 加载、失败重试、空态 |
| `ListPager` | 上一页/下一页与每页条数 |
| `.yumi-page-heading` | 页头布局与标题排版 |
| `.yumi-module-tabs` | 模块页签条：白底、浅边框、与子页标题分层 |
| `.yumi-toolbar` / `.yumi-actions` | 筛选条与动作组 |
| `.yumi-panel` / `.yumi-panel__header/body` | 卡片及卡头 |
| `.yumi-stat-grid` / `.yumi-stat-card` | 统计卡片组 |
| `.yumi-card-grid` | 自适应卡片网格（可按 2/3 列） |
| `.yumi-info-grid` | `dl/dt/dd` 信息网格 |
| `.yumi-form-grid` | 两列/三列表单网格 |
| `.yumi-hint` / `.yumi-meta` / `.yumi-cell-title` | 辅助文字与表格双层单元格 |
| `.yumi-text-positive` / `.yumi-text-pending` | 已达成的绿色文字与待处理的金色文字 |

### 页面样式规则

- 页面 scoped CSS 只允许写该页独有的布局（如看板网格、时间轴），通用页头、工具条、卡片、统计卡、提示必须使用组件或 `yumi-*` 类。
- 页面不得定义 `h1`、`.head`、`.toolbar`、`.alert`、`.hint`、`.meta`、`.actions`。
- 字号、字重、圆角、颜色、`margin/padding/gap` 必须引用令牌；`style.contract.test.ts` 为结构门禁。
- Element Plus 变量只在 `tokens.css` 和 `components.css` 中出现，页面只消费 `--yumi-*`。

## 6. 交互与状态

- 详情页默认只读；新建、编辑、收款、发货、完成等操作由显式按钮进入。
- 列表筛选条件与当前页保持同步；刷新后不丢筛选（P1 待补齐 URL 查询参数）。
- 加载态覆盖数据区域，不整页白屏；失败时保留上次数据并给出重试入口。
- 破坏性操作必须二次确认，确认文案写明对象、数量和影响。
- 成功提示只出现一次；失败不得伪装成功。

## 7. 无障碍

- 所有输入、下拉、日期控件必须有可见标签或 `aria-label`；placeholder 不能替代名称。
- 键盘可完成主要流程；焦点可见；弹窗打开后焦点进入弹窗，关闭后回到触发按钮。
- 图标按钮必须有中文 `aria-label`；纯装饰图标对辅助技术隐藏。
- 表格表头与数据列语义正确；不使用颜色作为唯一状态表达。
- 门禁：`frontend/e2e/accessibility.spec.ts` 对登录页与 11 个主要业务页执行 axe（WCAG 2.1 A/AA），严重与致命问题必须为 0。

## 8. 视觉回归

- 基线目录：`frontend/e2e/visual.spec.ts-snapshots/`。
- 覆盖稳定页面：登录、经营总览、订单空态、商品空态；业务数据页不进入基线。
- 截图前冻结动画、固定视口 `1280×800`，容差 `maxDiffPixelRatio: 0.01`。
- 基线按操作系统命名（如 `*-chromium-darwin.png`）；换平台需重新生成并在评审中说明。
- 有意修改视觉时：先改令牌/页面，再运行 `pnpm exec playwright test e2e/visual.spec.ts --update-snapshots`，并在提交中附截图差异说明。

## 9. 变更流程

1. 先改 `tokens.css` 或码表，再改页面。
2. 同步更新本文件对应表格。
3. 运行 lint、format、typecheck、unit、build、E2E；无障碍与视觉回归必须通过。
4. 视觉变更需在提交信息里写明影响页面与基线更新原因。
