# 实现任务书：公开主页改版（布局 + 子内容）

> 任务性质：跨对话实现（本任务书 + 项目仓库 = 全部输入；实现者应通过学习项目仓库完成开发，不依赖任务书之外的口头信息）
> 需求依据：[PUBLIC_SITE_REDESIGN.md](./PUBLIC_SITE_REDESIGN.md)（已确认 REQ，含实体关系与表结构认识）
> 项目：internal-admin-template（GitHub: mengde566-creator/internal-admin-template）

## 1. 实现者必读材料（按此顺序，缺一不可）

| 顺序 | 材料 | 原因 |
| --- | --- | --- |
| 1 | `README.md` | 项目灵魂与文档入口 |
| 2 | `AGENTS.md` | 开发规范：AI 行为约束、§2.3 装配规则、§15 完成标准、§16 开发红线 |
| 3 | `requirements/V0_1_SCOPE.md` 及关联需求 | 0.1 范围（本需求在 0.1 闭环内） |
| 4 | `docs/development/ENGINEERING_CONVENTIONS.md` | 已核实技术事实（Boot 4/Jackson 3 等）、自查清单、红线 |
| 5 | `docs/development/CAPABILITY_COMMON.md` | 能力包通用规则（ID 字符串/变更集/跨模块/安全用例） |
| 6 | `backend/modules/module-site/capability/`（三件套） | module-site 现有契约、踩坑、测试要求 |
| 7 | 现有代码：`backend/modules/module-site/`、`frontend/src/modules/site/` | 实现的基础，先读再改 |
| 8 | `scripts/quality.sh` | 质量门禁（完成前必须全绿） |

## 2. 实现范围

### 后端（module-site）

1. **Liquibase 新增变更集**（禁止修改已发布变更集）：
   - `site_homepage_draft` / `site_homepage_publication` 加列 `layout_code`（VARCHAR，代码枚举）；
   - 新增 `site_homepage_draft_section`、`site_homepage_publication_section`（草稿区块/发布快照区块）：id、section_type、title、content、hero_file_id（可空）、sort_order；
   - 注意 SQLite 差异（见 CAPABILITY_COMMON §2）。
2. **区块 CRUD**（草稿区块）：增删改、排序；配图存在校验（经 `FileQueryApi`，禁止查 file_asset 表）；
3. **发布/撤回**：发布时事务内整体复制 布局 + 草稿区块 → 发布快照区块（沿用现有快照模式）；失败旧快照保持；
4. **公开读取**：公开主页返回 layout_code + 区块列表（按 sort_order），双配色沿用；
5. 权限：管理端接口 `site:homepage:edit`（编辑）/ `site:homepage:publish`（发布），与现有权限一致。

### 前端（frontend/src/modules/site/）

1. 管理端：布局选择（2 种代码枚举）+ 区块列表编辑（增删改/排序/配图上传，配图复用现有上传）；
2. 公开页：按 layout_code + 区块渲染，铺满浏览器，延续 Minimal Tech + GRAPHITE/AZURE 双配色；
3. 预览：草稿按所选布局渲染（复用公开页组件）。

## 3. 硬性约定（违反即返工）

- ID 一律字符串传输（tools.jackson `ToStringSerializer` 标 getter）——CAPABILITY_COMMON §1；
- 跨模块只走公开 API（FileQueryApi），禁止碰对方 Mapper/DO/表；
- 草稿/发布隔离：编辑草稿不影响线上；发布是整体快照复制；撤回 visible=false 草稿保留；
- 半发布禁止：发布/审计同事务（SQLite 单写者，审计成功随事务、失败外层记录）；
- 区块类型/布局为**代码定义枚举**（不建类型表/主题表）；
- 业务方法 Javadoc（方法名/执行链路/@link，禁 `<ol><li>`、禁 `\n` 字面量）；
- 写完立即自查（ENGINEERING_CONVENTIONS §3 清单）。

## 4. 验收标准（来自 REQ）

- 公开页按所选布局渲染且铺满浏览器；
- 区块增删改/排序/配图在管理端可用，发布后公开页生效；
- 修改草稿不影响已发布页面；撤回后公开页不可见；
- 双配色在改版后仍生效；
- 空区块集 → 公开页显示兜底（不空白报错）。

## 5. 完成标准（AGENTS §15 + 质量门禁）

- [ ] 功能符合 REQ，无超范围扩展；
- [ ] 后端编译通过、应用启动 + Liquibase 迁移成功；
- [ ] 前端 `npm run build` 通过；
- [ ] `scripts/quality.sh` 全绿；
- [ ] 关键用例手动验证（草稿/发布/撤回/公开渲染/区块 CRUD/越权 403）；
- [ ] 差异无死代码/占位/临时日志；module-site 能力包 CONTRACT 同步更新（表/接口变化）。

## 6. 红线提醒（AGENTS §16）

禁止删库/手工改库；禁止修改已发布 Liquibase 变更集；禁止物理删除有审计引用的数据；数据修复用新增变更集。
