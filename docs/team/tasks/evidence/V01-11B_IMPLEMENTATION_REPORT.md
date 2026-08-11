# V01-11B 现行文档事实收口实施报告

> 状态：总设计师 / 总架构师二次验收通过
> 日期：2026-08-11
> 任务书：[V01-11B 现行文档事实收口](../V01-11B_CURRENT_DOCUMENT_STATE_TASK.md)

## 1. 核心结论

已仅在任务书授权的九份现行入口文档中收口当前事实，并新增本报告。当前文档统一说明：后端使用 Maven Wrapper，前端与 OpenAPI 工具按锁文件使用 `npm ci`；质量入口必须显式选择 `--no-database` 或 `--database`；初始化管理员按账号 `admin` 而非固定 ID 保护；初始密码配置使用 `APP_ADMIN_INITIAL_PASSWORD`；V01-08/V01-10 已验收，V01-12 发布级证据仍待执行。

未修改任何历史任务书、实施报告、验收记录、代码、脚本、CI、迁移或数据库材料。

## 2. 修改范围与逐项事实

| 授权文件 | 已修正的当前事实 | 保留的正确事实 |
| --- | --- | --- |
| `README.md` | 系统 Maven 改为 `backend/mvnw`；`npm install` 改为 `npm ci`；质量入口拆分为两种显式模式；初始密码环境变量改为当前的 `APP_ADMIN_INITIAL_PASSWORD`，并注明 V01-12 边界 | SQLite 零配置启动、12 张业务表说明 |
| `requirements/V0_1_SCOPE.md` | 仅更新统一质量入口的当前机制：Wrapper、锁文件安装、两种 quality 模式 | 已确认的 0.1 产品范围与 12 张业务表 |
| `docs/development/RUNBOOK.md` | 构建改用 Wrapper；质量验证改为两种显式模式；依赖安装改为 `npm ci`；初始密码环境变量改为 `APP_ADMIN_INITIAL_PASSWORD` | `dev.sh` 是标准启动/停止路径 |
| `docs/ai/ACCEPTANCE_GUIDE.md` | 质量验收改为两种模式、Wrapper 和锁文件事实，并区分 V01-12 发布级验证 | 人工验收与自动化验收的职责边界 |
| `docs/system/api/iam/USER_DELETE.md` | 初始化管理员保护改为唯一账号 `admin`，并注明 ID 为应用生成值 | 路径参数仍是目标用户主键，软删除与审计语义不变 |
| `module-iam` 的 `AI_PROMPT.md`、`TEST.md` | 显式质量模式；删除保护不使用固定 ID；V01-08/V01-10 完成与 V01-12 待验证的边界 | 真实 SQLite `IamFlowTest` 12/12、无数据库会话覆盖与未覆盖手动项 |
| `module-file`、`module-site` 的 `AI_PROMPT.md` | 显式质量模式、Wrapper 和 `npm ci`；标注已验收质量链与 V01-12 边界 | Site 主页单例仍明确为 `id=1`，文件和发布业务规则不变 |

## 3. 独立自我复盘（AC-01 至 AC-06）

| 完成标准 | 复盘结论与证据 |
| --- | --- |
| AC-01 | `README.md`、RUNBOOK、验收指南均只写 `backend/mvnw`、`npm ci` 和两种显式质量模式；与 `scripts/quality.sh` 的用法及 CI 的 `--database` 调用一致。README/RUNBOOK 的初始密码环境变量还与 `AdminInitializer` 的 `app.admin-initial-password` 映射一致。 |
| AC-02 | `requirements/V0_1_SCOPE.md` 仅改动“统一质量命令”一条，未改变产品范围、版本完成标准或 12 张业务表事实。 |
| AC-03 | 删除用户契约和 IAM 能力包均写明按账号 `admin` 识别初始化管理员；只保留 Site 的业务单例 `id=1`，没有混入 IAM 删除保护。静态对照 `UserService#delete` 的 `INITIAL_ADMIN_USERNAME = "admin"`。 |
| AC-04 | IAM/Site 能力包均明确 V01-08 与 V01-10 的已验收事实；V01-12 的干净来源、开发脚本生命周期、真实 Chromium 和远端 CI 均仍列为待执行。 |
| AC-05 | 未修改 schema、迁移或历史证据；12 张业务表、Liquibase 16 个 change sets 和 Site 单例 `id=1` 均保持原有正确表述。 |
| AC-06 | 已执行 `git diff --check`，退出 0；定向差异仅覆盖任务书授权的现行入口文档。本报告是第十个授权文件。共享工作区存在其他任务的未提交差异，未整理、覆盖或纳入本任务。 |

### 首次验收退回后的最小修正

- `IamFlowTest` 的测试上下文明确设置 `app.admin-initial-password=TestPass123`，因此测试清单第 1 项改为“配置初始密码”路径，且只陈述创建 admin 后可用该配置密码登录；不再将零配置随机生成、明文仅首次日志或 `mustChangePassword=true` 写成该自动测试已经证明；
- 测试清单第 12 项改为真实持久化无权限角色/用户的自动 403 证据，精确列出 `persistedUserWithoutPermissionsGets403FromProtectedApis` 已断言的用户列表、站点发布和文件上传边界；不再误写为手工验证或扩大为系统设置覆盖；
- 以上修正仅触及本报告和 `module-iam` 测试清单，未修改测试实现或扩大测试范围。

### 退回整改后的再次自我复盘

- 对照 `IamFlowTest` 的测试属性、`loginSucceeds` 与 `persistedUserWithoutPermissionsGets403FromProtectedApis`：测试清单的第 1、12 项均只描述其实际断言；
- `rg -n '初始化管理员（零配置）|随机密码仅日志|mustChangePassword=true.*IamFlowTest|越权访问.*手动验证|用户/角色/系统设置.*403' backend/modules/module-iam/capability/TEST.md || true` 整体退出 0，`rg` 无命中（无命中状态经 `true` 转为成功），确认没有残留反向陈述；
- `git diff --check` 再次退出 0；本轮仅改动允许的 `module-iam` 测试清单和本报告，没有执行测试、服务、数据库或依赖命令。

## 4. 实际验证与未执行项

### 实际执行（均为只读/静态检查）

```text
rg -n -P '(^|[^[:alnum:]_])mvn(?!w)' README.md requirements/V0_1_SCOPE.md docs/development/RUNBOOK.md docs/ai/ACCEPTANCE_GUIDE.md docs/system/api/iam/USER_DELETE.md backend/modules/module-iam/capability/AI_PROMPT.md backend/modules/module-iam/capability/TEST.md backend/modules/module-file/capability/AI_PROMPT.md backend/modules/module-site/capability/AI_PROMPT.md docs/team/tasks/evidence/V01-11B_IMPLEMENTATION_REPORT.md || true
退出 0；入口文档无系统 Maven 命令，报告中的命令文本不属于执行入口。

rg -n 'npm install|mvn clean|初始化管理员.*id=.?1|id=.?1.*初始化管理员|V01-10B|V01-12.*已(通过|完成|验收)' README.md requirements/V0_1_SCOPE.md docs/development/RUNBOOK.md docs/ai/ACCEPTANCE_GUIDE.md docs/system/api/iam/USER_DELETE.md backend/modules/module-iam/capability/AI_PROMPT.md backend/modules/module-iam/capability/TEST.md backend/modules/module-file/capability/AI_PROMPT.md backend/modules/module-site/capability/AI_PROMPT.md docs/team/tasks/evidence/V01-11B_IMPLEMENTATION_REPORT.md || true
退出 0；入口文档无命中，报告中的“npm install”仅用于说明已纠正的历史命令。

git diff --check
退出 0。

git diff --name-only -- README.md requirements/V0_1_SCOPE.md docs/development/RUNBOOK.md docs/ai/ACCEPTANCE_GUIDE.md docs/system/api/iam/USER_DELETE.md backend/modules/module-iam/capability/AI_PROMPT.md backend/modules/module-iam/capability/TEST.md backend/modules/module-file/capability/AI_PROMPT.md backend/modules/module-site/capability/AI_PROMPT.md
退出 0（输出仅为授权入口文档中的已跟踪差异）。
```

还静态核对了 `scripts/quality.sh`、`.github/workflows/quality.yml`、`UserService#delete`、`IamFlowTest`、V01-08/V01-10 架构验收和 V01-12 预检任务书。

### 未执行项

未运行 Maven、npm、服务、数据库、Liquibase、测试或 `clean`：任务书明确禁止这些操作，本任务的完成证据是文档与已验收权威来源的静态一致性，不将未执行运行验证描述为本轮通过。

## 5. 交付前反向检查

- 职责未与历史记录重叠：历史任务书、报告和验收记录零修改；
- 权限未扩大：未改代码、脚本、CI、依赖、数据库或版本状态；
- 规范未过重：只更新过期命令、状态和管理员保护事实，没有重写无关段落；
- 无反向陈述：未再把 `admin` 写为固定 `id=1`，也未把 Site 的正确单例 `id=1` 误改；未将 V01-12 未执行项冒充为已完成。
