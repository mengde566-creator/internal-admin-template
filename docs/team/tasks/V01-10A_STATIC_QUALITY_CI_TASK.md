# V01-10A 安全开发脚本、无数据库质量层与 CI 基础

> 状态：完成（无数据库阶段验收通过；完整层留 V01-10B）
> 所属版本：0.1
> 主责角色：运维工程师
> 设计与派发：总设计师 / 总架构师
> 最终验收：总设计师 / 总架构师
> 创建日期：2026-08-11
> 执行对话：个人项目-运维工程师；`threadId=019ff08e-d309-7ca3-a7b6-7b670b7baaff`；`hostId=local`
> 上游总设计师对话：个人项目-总设计师；`threadId=019fe56b-38da-7b20-904d-819794357e46`；`hostId=local`
> 交付协议：[版本任务交付协议](../VERSION_DELIVERY_PROTOCOL.md)

## 1. 核心目标

在 V01-08 并行开发期间先关闭不依赖数据库的 V01-10 工作：开发脚本不误杀进程，质量入口提供明确的无数据库门禁，GitHub Actions 只复用该入口而不复制命令。

本任务是 V01-10 的第一阶段，不得把数据库迁移、完整后端集成测试或真实 E2E 描述为已接入；这些在 V01-08 冻结后由 V01-10B 收口。

## 2. 范围与非目标

### 必须完成

- `dev.sh stop` 只停止 PID 文件记录且经归属核验的本项目进程，删除宽泛 `pkill -f`；陈旧 PID 必须可见并安全清理；
- `dev.sh` 的构建提示统一使用 `backend/mvnw`，依赖提示使用锁文件对应的 `npm ci`，状态检查不把任意占用端口的 HTTP 服务认作本项目；
- `quality.sh` 增加明确的无数据库模式，使用 Maven Wrapper、禁止 `clean`，串行执行已存在的无数据库后端门禁、OpenAPI 漂移检查、Vitest、Playwright `--list`、TypeScript 与构建；
- 无数据库模式缺少 JDK、Node、锁文件依赖或 OpenAPI 工具依赖时快速失败并给出精确动作，不自动安装/升级；
- 新建唯一 GitHub Actions 工作流，只准备锁定 JDK 25、Node 24 和 npm 依赖，然后调用仓库无数据库质量模式；工作流不复制测试命令；
- 记录 V01-10B 尚待的数据库迁移、V01-08 集成测试和真实 E2E 接入点。

### 明确不做

- 不运行应用、Liquibase、SQLite、真实浏览器 E2E、完整 `verify` 或 `clean`；
- 不修改 Java/TypeScript/Vue 生产代码、测试、POM、package/lock、OpenAPI 生成物、README/RUNBOOK、V01-08 文件或任务总表；
- 不修改 `reset-dev-db.sh`、`smoke_public_site.sh`，不建设部署、容器、缓存平台或矩阵 CI；
- 不把本阶段静态 CI 表述为完整 0.1 质量门禁。

## 3. 权威来源与当前事实

| 类型 | 来源 | 当前结论 | 状态 |
| --- | --- | --- | --- |
| 已确认需求 | `requirements/V0_1_SCOPE.md` REQ-V01-002 | 本地与 CI 必须复用核心质量入口，失败可见 | 已确认 |
| 架构决定 | `V01-04` 第 7 节 | JDK 25、Maven Wrapper 3.9.16、Node 24、`ubuntu-latest`，CI 不复制命令 | 生效 |
| 代码事实 | `scripts/quality.sh` | 当前使用系统 Maven、`clean`、Python 和固定端口，且缺少 V01-05/V01-09 门禁 | 已核实 |
| 代码事实 | `scripts/dev.sh` | 当前 stop 使用宽泛 `pkill`，构建提示仍为系统 Maven | 已核实 |
| 代码事实 | `.github/workflows/` | 当前不存在项目 CI 工作流 | 已核实 |
| 官方材料 | GitHub `actions/checkout`、`setup-java`、`setup-node`、`upload-artifact` 官方仓库 | 使用当前受支持主版本；JDK/Node 版本必须显式指定 | 已核验，实施时复核 |

## 4. 派发前可行性审查

| 维度 | 结论与处理 |
| --- | --- |
| 权限与副作用 | 只改脚本、单一工作流和报告；不触碰业务文件 |
| 数据库边界 | 无数据库模式及静态验证不写数据库；完整层留 V01-10B |
| 网络与依赖 | 本地不安装；CI 使用官方 Action 和 `npm ci`，缺失时失败，不改 registry |
| 工具链 | Maven Wrapper、JDK 25、Node 24、前端/工具锁文件均已存在 |
| 文件冲突 | 与 V01-08A/B 无文件重叠；并行期间不得执行 Maven/npm 全量门禁 |
| 进程安全 | 禁止宽泛 kill；PID、端口和命令归属必须同时可验证 |
| 等待预算 | 任一网络/构建操作 60 秒无进展立即停止；本阶段原则上不执行下载与长构建 |
| 浏览器 E2E | 只允许 `--list`；真实浏览器属于 V01-10B/V01-12 |
| 文档同步 | 只写本阶段报告；README/RUNBOOK 留 V01-11 |

### 最小探路验证

- 已执行 `bash -n` 核验现有脚本语法；已确认 Wrapper、两个 lockfile、前端测试命令和 OpenAPI 入口存在；
- GitHub 官方材料表明 Action 当前版本已经切换 Node 24 runtime，实施前必须再次打开官方仓库确认所选主版本，不从博客或示例猜测；
- 派发结论：**当前阶段可执行**。本任务只关闭无数据库与进程安全层，不等待 V01-08。

## 5. 文件所有权

### 允许修改

- `scripts/dev.sh`；
- `scripts/quality.sh`；
- `.github/workflows/quality.yml`（唯一工作流）；
- `docs/team/tasks/evidence/V01-10A_IMPLEMENTATION_REPORT.md`。

### 禁止修改/执行

- 禁止修改授权外文件；禁止启动/停止真实服务、运行数据库、真实 E2E、`clean`、提交或推送；
- 禁止在研发并行期间运行 Maven/npm 门禁；完成代码和静态自审后发送 `[V01-10A][等待验证窗口]`；
- 禁止工作流静默跳过必选无数据库门禁或使用 `continue-on-error` 掩盖失败。

## 6. 完成标准与证据路径

| 编号 | 完成标准 | 当前阶段证据 | 后续责任 |
| --- | --- | --- | --- |
| AC-01 | dev stop 无宽泛 kill，陈旧/非归属 PID 不被终止 | `bash -n`、静态反例扫描、`status` | V01-12 真实进程验证 |
| AC-02 | 无数据库质量模式只用 Wrapper、无 `clean`/Python/数据库副作用 | 脚本审查、命令图扫描 | 串行窗口实际运行 |
| AC-03 | 无数据库门禁包含 V01-05/06/07、Vitest、E2E list、typecheck/build | 脚本调用图、串行窗口结果 | 无 |
| AC-04 | CI 只准备锁定环境/依赖并调用同一脚本模式 | YAML 审查、Action 官方版本证据 | GitHub 首次运行留 V01-12 |
| AC-05 | V01-10B 缺口明确，未夸大当前完成度 | 实施报告与过期措辞扫描 | 总设计师派发 V01-10B |
| AC-06 | 文件范围和格式正确 | `git diff --check`、`bash -n` | 无 |

## 7. 接单、交付与回报

1. 完整读取根规范、README、需求索引、0.1 范围、工程约定、运维角色、交付协议、本任务书、V01-04 第 7 节和目标脚本；
2. 打开 GitHub 官方 Action 仓库复核当前受支持主版本，报告来源与选择；
3. 先发送 `[V01-10A][接收]`，完成文件/环境审查后再修改；
4. 完成后只做语法、静态反例、YAML 与范围自审，发送 `[V01-10A][等待验证窗口]`；
5. 总设计师安排串行无数据库验证后，再完成报告并发送 `[V01-10A][申请验收]`；
6. 不自行把 V01-10 标记完成，V01-10B 仍由总设计师另行派发。
