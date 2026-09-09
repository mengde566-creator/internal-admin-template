# 管理端弹窗与高影响操作确认专项

> 状态：7 项缺陷与加固闭环完成（前端研发工程师闭环，自动化测试与静态门禁全部通过）  
> 范围：仅管理端前端样式、交互、提示与系统友好度  
> 实施日期：2026-09-09  
> 不在范围：后端、API 契约、权限模型、数据库、Liquibase、业务规则变更

## 1. 核心结论

本次对 `frontend/src` 和全部已注册管理端路由做了深度复核与缺陷加固，完成 7 项关键问题闭环治理：

- **0 处浏览器原生弹窗**：知识资料发布的 `window.confirm` 已彻底替换为 `ElMessageBox.confirm`，静态门禁 `no-native-dialog.test.ts` 保证后续开发中出现原生弹窗即构建阻断。
- **主页发布目标与草稿一致**：主页发布二次确认严格使用服务端已保存草稿名称；当本地表单存在未保存修改时，确认弹窗显示服务端草稿名称并明确警示“当前表单存在未保存的修改，发布仅生效服务端已保存的草稿”，杜绝误导。
- **高影响操作防重复互斥锁覆盖完整生命周期**：主页发布与撤回、部门停用与删除、物品停用/启用与取消导入、仓库与库位停用/启用、用户删除、角色删除、知识资料发布均已补齐防重复互斥锁；互斥锁严格覆盖“确认弹窗 + 网络请求 pending + 数据刷新”完整生命周期，并在请求期间保持对应按钮为 disabled 或 loading，用户在任何阶段重复点击均不会打开多余确认框或发送重复网络请求。
- **抽屉内错误提示可见性与输入保留**：物品档案、仓库与库位抽屉保存失败时，错误信息不再只写在遮罩底层的页面区域，而是在抽屉内部表单上方以 `drawer-error-alert`（结合 `formatTaskError` 任务化指引）即时呈现，抽屉保持打开，用户已输入内容完整保留不丢失。
- **离开守卫异常捕获精准化**：`confirmDiscardChanges` 仅在用户明确取消或关闭（`cancel` / `close`）时返回 `false`，非取消的程序异常向外抛出，不吞掉未知异常。
- **抽屉与弹窗样式基线完整统一**：`.ui-managed-dialog` 在 `element-plus-overrides.css` 中完整适配 `.el-drawer`（包括 `header`、`body`、`footer`、`close-btn` 和移动端 100% 宽度），抽屉与弹窗样式基线完全统一。
- **自动化测试场景全面加固**：单测新增覆盖确认弹窗防重复、deferred Promise 模拟网络请求 pending 期间重复点击拦截、请求失败状态恢复与错误提示、无确认框的启用路径防重复点击、保存失败抽屉内错误与输入保留、未修改表单直接关闭（不打扰）、`before-close`（Esc、遮罩、关闭按钮）行为。18 个测试套件、179 项用例全部 PASS，`vue-tsc --noEmit && vite build` 零错误通过。
- **验收事实与证据边界纠偏**：明确说明历史 64 张视口截图已移除，不再作为当前验收证据；交互弹窗与高影响确认严格以 18 个测试套件（179 项自动化组件用例）为事实证据，且不将组件单测混淆为真实浏览器端到端验收。

## 2. 盘点口径

### 2.1 已纳入

1. 浏览器原生 `alert / confirm / prompt`。
2. Element Plus 的 Dialog、Drawer、MessageBox、Popconfirm。
3. 自定义且声明为 `role="dialog"` 的页面浮层。
4. 删除、发布、撤回、停用、取消进行中作业、写入业务主数据等高影响操作是否具有适当确认。
5. 弹层的对象说明、影响说明、按钮语义、提交态、错误反馈、关闭行为、窄屏布局、键盘与焦点行为。

### 2.2 未误计为弹窗问题

文件选择器、下拉选项、日期选择器、工具提示、成功/失败 Toast 和普通页面内提示不是本专项所称的“确认弹窗”。它们只有在遮挡、文案或操作反馈本身存在问题时，才另行列入页面友好度专项。

## 3. 现有弹层清单与治理结果

| 模块 / 场景 | 原实现 | 治理后实现 | 治理方案与效果 | 状态 |
| --- | --- | --- | --- | --- |
| 知识资料：发布并替换当前版 | `window.confirm` | `ElMessageBox.confirm` | 显示文档编码、标题、版本号与影响，按钮为“发布并设为当前版”与“返回预览” | 已完成 |
| 物品导入：确认写入 | `ElMessageBox.confirm` | `ElMessageBox.confirm` | 保留基线，展示新增/更新/停用数量 | 已保持 |
| 部门：删除 | `ElMessageBox.confirm` | `ElMessageBox.confirm` | 增加部门名称和编码“名称（编码）”，按钮为“确认删除” | 已完成 |
| 用户：删除 | `el-popconfirm` | `ElMessageBox.confirm` | 替换为模态确认，显示姓名与账号“姓名（账号）”，按钮为“确认删除” | 已完成 |
| 角色：删除 | `el-popconfirm` | `ElMessageBox.confirm` | 替换为模态确认，显示角色名称与编码“名称（编码）”，SYSTEM_ADMIN 保持禁用 | 已完成 |
| 用户：新建/编辑 | `el-dialog` | `.ui-managed-dialog` | 统一管理弹窗样式，挂载 `formLeaveGuard` 脏表单拦截 | 已完成 |
| 角色：新建/编辑 | `el-dialog` | `.ui-managed-dialog` | 统一管理弹窗样式，挂载 `formLeaveGuard` 脏表单拦截 | 已完成 |
| 部门：新建/编辑 | `el-dialog` | `.ui-managed-dialog` | 统一管理弹窗样式，挂载 `formLeaveGuard` 脏表单拦截 | 已完成 |
| 主页内容：草稿预览 | `el-dialog` | `.ui-managed-dialog` | 挂载管理弹窗样式，保证窄屏下自适应并居中展示 | 已完成 |
| 物品：新建/编辑 | `el-drawer` | `.ui-managed-dialog` | 挂载 `formLeaveGuard` 脏表单拦截，保存按钮增加 `saveLoading` 防重复提交 | 已完成 |
| 仓库/库位：新建/编辑 | 共用 `el-drawer` | `.ui-managed-dialog` | 挂载 `formLeaveGuard` 脏表单拦截，保存按钮增加 `saveLoading` 防重复提交 | 已完成 |
| 库存记录：详情 | `el-drawer` | `el-drawer` | 只读详情，响应式尺寸明确，无破坏性操作 | 保持 |
| 库存查询：移动端筛选 | 底部 `el-drawer` | `el-drawer` | 临时筛选且可恢复，底部抽屉符合移动端任务 | 保持 |
| 仓储助手：历史对话选择 | 自定义 `role="dialog"` | 自定义 `role="dialog"` | 走查验证通过，焦点、Esc、点击外部关闭和窄屏遮挡均正常 | 保持 |

## 4. 高影响动作确认与防重复清单

| 场景 | 确认目标与警示 | 互斥锁控制 | 状态 |
| --- | --- | --- | --- |
| 主页内容发布 | 确认文案严格展示服务端草稿名称；本地有改动时警示未保存修改，引导先保存 | `isPublishConfirming` + `publishMutation.isPending` | 已完成 |
| 主页内容撤回 | 弹出确认，显示站点名称，明确下线影响 | `isWithdrawConfirming` + `withdrawMutation.isPending` | 已完成 |
| 部门停用 | 显示“名称（编码）”及停用影响 | `confirmingDepartmentAction` + `statusMutation.isPending` | 已完成 |
| 物品停用/启用 | 显示“名称（编码）”及禁止新入库影响；启用无弹窗但防重复提交 | `isToggleConfirming`（覆盖确认框 + updateItem + load，按钮绑定 `:disabled`） | 已完成 |
| 物品导入作业取消 | 明确未写入数据不保存，按钮区分明确 | `isCancelImportConfirming`（覆盖确认框 + cancelItemImport，按钮绑定 `:disabled`） | 已完成 |
| 仓库停用/启用 | 显示“名称（编码）”及新业务操作影响；启用无弹窗但防重复提交 | `confirmingWarehouseToggle`（覆盖确认框 + updateWarehouse + load，按钮绑定 `:disabled`） | 已完成 |
| 库位停用/启用 | 显示“名称（编码）”及存放新入库物品影响；启用无弹窗但防重复提交 | `confirmingLocationToggle`（覆盖确认框 + updateLocation + loadWarehouseLocations，按钮绑定 `:disabled`） | 已完成 |
| 用户删除 | 显示“姓名（账号）”及不可恢复说明 | `isDeleteConfirming` + `deleteMutation.isPending` | 已完成 |
| 角色删除 | 显示“名称（编码）”及不可恢复说明 | `isDeleteConfirming` + `deleteMutation.isPending` | 已完成 |
| 知识资料发布 | 显示文档编码、标题、版本名称及生效说明 | `confirmingPublish` + `submitting` | 已完成 |

## 5. 7 项关键问题治理前后对照

| 序号 | 问题描述 | 治理前问题 | 治理后解决方案 | 验证用例 |
| --- | --- | --- | --- | --- |
| 1 | 主页发布确认对象偏差 | 展示本地表单名称，发布未保存修改时用户产生误解 | 确认对象严格展示服务端草稿名称；dirty 时明确警告未保存修改并引导先保存草稿 | `SiteManagePage.test.ts` |
| 2 | 高影响操作锁未覆盖请求阶段 | confirming 状态在确认后提前释放，API pending 期间可再次点击 | 互斥锁严格覆盖确认弹窗、网络请求 pending 及数据刷新完整生命周期，对应按钮全流程 disabled | 7 处测试套件快速双击与 deferred API pending 回归用例 |
| 3 | 抽屉保存失败错误不可见 | 错误写在抽屉遮罩底层的页面区域，抽屉保持打开时用户看不见原因 | 抽屉内渲染 `drawer-error-alert`（结合 `formatTaskError`），抽屉不关闭且保留输入 | `WarehouseItemsPage.test.ts`, `WarehouseLocationsPage.test.ts` |
| 4 | `confirmDiscardChanges` 吞异常 | `catch` 吞掉所有异常，将运行时/程序错误误判为取消 | 仅在 `cancel`/`close` 时返回 `false`，非取消异常向外抛出 | `formLeaveGuard.test.ts` |
| 5 | Drawer 样式类选择器未生效 | `.ui-managed-dialog` 仅匹配 `el-dialog`，抽屉未生效 | 补全 `.ui-managed-dialog.el-drawer` 完整类选择器与移动端适配 | `element-plus-overrides.css` |
| 6 | 自动化测试未覆盖请求阶段 | 仅单次取消/确认，缺少 API pending 拦截、启用路径防重、抽屉内错误、无修改直接关闭与 beforeClose | 补齐双击互斥、deferred Promise 模拟 pending 拦截、抽屉错误与输入保留、无修改不打扰、beforeClose 用例 | 18 个测试套件，179 项用例全部通过 |
| 7 | 截图验收声称不实 | 将静态路由全屏截图误称作动态弹窗验收证据 | 确认旧截图已移除，不作为当前验收证据；交互弹窗与确认流程以 18 个测试套件（179 项自动化组件用例）为权威证据 | 本文档口径纠偏 |

## 6. 交互规范落实

- **禁止项**：生产代码 0 原生弹窗；静态测试自动门禁看护。
- **确认层信息规范**：全部高影响确认均带操作对象标识（名称+编码/账号）、明确操作影响和动作型按钮（“确认停用”、“确认删除”、“确认发布并公开”、“确认撤回并下线”、“确认取消”）。
- **弹层行为规范**：
  - 统一应用 `.ui-managed-dialog` 基线（支持 Dialog 与 Drawer）。
  - 编辑表单离开保护统一走 `formLeaveGuard.ts`（无改动直接关闭，有改动提示丢弃）。
  - 抽屉与高影响操作增加 `confirming` 锁与 `isPending` / `saveLoading` 防重复提交。
  - 抽屉保存失败在抽屉内部显示任务化错误，保持抽屉打开并保留表单输入。

## 7. 验收标准达成情况

### 7.1 静态门禁（达成）
- `frontend/src` 中 `window.alert / window.confirm / window.prompt` 为 0（`no-native-dialog.test.ts` 验证通过）。
- 所有变更只触及前端代码、前端测试及本专项文档。
- 不增加运行时依赖，不新增第二套全局令牌或主题。

### 7.2 自动化场景（达成）
- 每类确认均覆盖取消不发请求、确认发对应请求。
- 确认文案包含当前行/当前对象的可识别名称或编码。
- 表单未保存改动时离开拦截提示，无改动时不打扰。
- 高影响操作双击与网络请求 pending 期间具有防重复确认与提交互斥锁。
- 抽屉保存失败错误在抽屉内可见且输入内容保留。
- 18 个测试套件 179 个用例全部 PASS。

### 7.3 证据与排版基线说明
- 明确纠偏：历史 64 张视口截图已移除，不再作为当前弹窗专项验收证据。
- 动态弹窗、离开拦截与二次确认逻辑以 18 个自动化测试套件（179 项用例）为事实证据，且明确定位为自动化组件测试，不混淆为真实浏览器端到端验收。

## 8. 实施边界核对

1. 本轮只改前端，未改后端代码、API 契约、数据库、Liquibase 或权限模型。
2. 库存业务操作（入库/出库/调拨/盘点）保持页面内复核模式，未机械增加二次阻断弹窗。
3. 遵循装配原则，使用 Element Plus 现有组件与项目现有样式，不增加新依赖。

## 9. 实施与验收证据记录

### 9.1 修改与新增文件清单

| 类别 | 文件路径 | 修改内容说明 |
| --- | --- | --- |
| 页面/组件 | `src/modules/knowledge/pages/KnowledgeDraftManagePage.vue` | 替换原生 `window.confirm` 为 `ElMessageBox.confirm`；增加 `confirmingPublish` 防重复锁与按钮禁用 |
| 页面/组件 | `src/modules/site/pages/SiteManagePage.vue` | 挂载 `ui-managed-dialog`；添加主页发布/撤回二次确认；发布确认展示草稿名称与未保存警告；增加并发互斥锁与按钮禁用 |
| 页面/组件 | `src/modules/iam/pages/DepartmentManagePage.vue` | 挂载 `ui-managed-dialog` 与离开守卫；添加部门停用/删除确认与并发互斥锁及按钮禁用 |
| 页面/组件 | `src/modules/iam/pages/UserManagePage.vue` | 挂载 `ui-managed-dialog` 与离开守卫；删除改为模态带姓名+账号确认与并发互斥锁及按钮禁用 |
| 页面/组件 | `src/modules/iam/pages/RoleManagePage.vue` | 挂载 `ui-managed-dialog` 与离开守卫；删除改为模态带名称+编码确认与并发互斥锁及按钮禁用 |
| 页面/组件 | `src/modules/warehouse/pages/WarehouseItemsPage.vue` | 挂载 `ui-managed-dialog` 样式；抽屉内增加 `drawer-error-alert`；失败保留输入；停用/启用/确认导入/取消作业互斥锁覆盖完整请求周期（确认弹窗 + API pending + 刷新）及按钮禁用 |
| 页面/组件 | `src/modules/warehouse/pages/WarehouseLocationsPage.vue` | 挂载 `ui-managed-dialog` 样式；抽屉内增加 `drawer-error-alert`；失败保留输入；仓库与库位停用/启用互斥锁覆盖完整请求周期（确认弹窗 + API pending + 刷新）及按钮禁用 |
| 样式覆盖 | `src/shared/styles/element-plus-overrides.css` | `.ui-managed-dialog` 补充 `.el-drawer` 选择器适配与移动端 100% 宽度 |
| 工具函数 | `src/shared/utils/formLeaveGuard.ts` | 统一离开守卫工具函数；增加 `isPrompting` 提示防并发锁；精准捕获 `cancel` / `close`，非取消异常向外抛出 |
| 测试文件 | `src/shared/utils/formLeaveGuard.test.ts` | 增加 Esc/close、非取消异常向外抛出与并发提示去重用例 |
| 测试文件 | `src/shared/utils/no-native-dialog.test.ts` | 静态代码门禁：校验源码中原生 alert/confirm/prompt 出现次数为 0 |
| 测试文件 | `src/modules/knowledge/pages/KnowledgeDraftManagePage.test.ts` | 知识资料发布确认与防重复锁测试 |
| 测试文件 | `src/modules/site/pages/SiteManagePage.test.ts` | 主页发布/撤回确认、草稿名称一致性、未保存警告与防重复锁测试 |
| 测试文件 | `src/modules/iam/pages/DepartmentManagePage.test.ts` | 部门停用/删除确认、防重复锁、无修改直接关闭、before-close 测试 |
| 测试文件 | `src/modules/iam/pages/UserManagePage.test.ts` | 用户删除确认、防重复锁、无修改直接关闭、before-close 测试 |
| 测试文件 | `src/modules/iam/pages/RoleManagePage.test.ts` | 角色删除确认、防重复锁、无修改直接关闭、before-close 测试 |
| 测试文件 | `src/modules/warehouse/pages/WarehouseItemsPage.test.ts` | 物品抽屉内错误显示、保存失败输入保留、停用/启用/导入二次确认与防重复锁（含 deferred API pending 拦截与错误恢复）、无修改关闭、before-close 测试 |
| 测试文件 | `src/modules/warehouse/pages/WarehouseLocationsPage.test.ts` | 仓库与库位抽屉内错误显示、保存失败输入保留、停用/启用防重复锁（含 deferred API pending 拦截与错误恢复）、无修改关闭、before-close 测试 |
| 证据/扫描 | `docs/planning/evidence/admin-ui-usability/scan_data.json` | 历史视口扫描元数据记录（仅供参考，64 张历史截图已移除，不作为弹窗交互证据） |
| 专项文档 | `docs/planning/ADMIN_UI_DIALOG_AND_CONFIRMATION_OPTIMIZATION_DRAFT.md` | 本文档状态纠偏与 7 项缺陷治理记录 |

### 9.2 验证结果

- `npm test`: 18 test files passed (179 tests passed).
- `npm run build`: `vue-tsc --noEmit && vite build` passed (exit code 0).
- `git diff --check`: clean (0 errors, 0 whitespace issues).

