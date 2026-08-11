# V01-12 发布级全量验证只读预检报告

> 状态：只读预检验收通过；**不具备干净来源条件，正式验证等待项目负责人授权与总设计师协调**
> 日期：2026-08-11
> 执行角色：运维工程师
> 任务书：[V01-12 发布级全量验证预检](../V01-12_PREFLIGHT_TASK.md)

## 1. 核心结论

当前机器具备 JDK、Wrapper、Node、锁文件、Chromium 缓存、构建产物和空闲端口等本地运行前提，但当前共享工作区不是干净来源：`main` 在 `dcb03c49c48b013e6885a30c746f44a161cec923`，共有 66 项未提交条目（31 项已跟踪修改、35 项未跟踪文件）。因此不得在此工作区把任何运行结果描述为 V01-12 的“干净环境”发布证据。

远端已配置，但当前没有提交、推送、创建验证副本、安装依赖、启动服务、写数据库或真实 E2E 的授权。本轮只执行了下述只读检查；没有执行测试、安装、`start`/`stop`、Liquibase、数据库连接、浏览器、`clean`、提交、推送、stash、reset 或 worktree 操作。

## 2. 当前只读事实

| 范围 | 结果 | 判断 |
| --- | --- | --- |
| Git 来源 | 分支 `main`；HEAD `dcb03c49c48b013e6885a30c746f44a161cec923`；非 shallow；唯一列出的 worktree 为当前仓库 | 当前来源不干净，不能直接进入正式发布验证。 |
| 工作区 | 66 项未提交条目（31 tracked modified、35 untracked） | 必须先由总设计师审查并形成已提交的目标 SHA；不得通过 stash/reset 掩盖现状。 |
| Remote | `origin` fetch/push 均为 `git@github.com:mengde566-creator/internal-admin-template.git` | 已有远端地址；本轮未联网验证凭据、权限或 Actions 运行资格。 |
| JDK / Maven | Oracle JDK `25.0.4`，当前 `java` 同为 25.0.4；`backend/mvnw` 可执行，Wrapper 3.3.4 / Maven 3.9.16 | 本地锁定 Java/Maven 入口具备。 |
| Node / 锁文件 | Node `v24.15.0`、npm `11.12.1`；`frontend/package-lock.json` 与 `tools/openapi/package-lock.json` 均存在 | Node 24 与两份锁文件具备；干净副本仍须用 `npm ci` 安装锁定依赖。 |
| 前端测试工具 | 已声明 `@playwright/test` 1.62.1、Vitest 4.1.7、TypeScript 6.0.3；当前工作区的前端与 OpenAPI `node_modules` 入口存在 | 当前可发现，但不能替代干净副本中的锁定安装证据。 |
| Chromium | `/Users/mengde/Library/Caches/ms-playwright/chromium-1234`、`ffmpeg-1011` 存在；Chrome for Testing 可执行文件存在 | 已有本机缓存，本轮未安装或启动浏览器。 |
| 端口 | `8080`、`5173`、`18080` 均无监听 | 可作为后续启动前的再次核验基线，不能代替正式运行前检查。 |
| 既有运行资产 | `backend/data/internal-admin.db`、`backend/data/uploads`、生产 JAR、前端依赖目录存在 | 本轮未读取或连接数据库；正式验证不得删除、重建或把当前数据冒充干净数据。 |
| 开发脚本遗留记录 | `logs/backend.pid=90532`、`logs/frontend.pid=90535`，对应 PID 已不存在，端口也未监听 | 后续在该工作区运行 `dev.sh start` 会先因 PID 文件拒绝；只能在正式授权时由 `dev.sh stop` 的归属校验路径处理，当前未执行。 |

## 3. 现有入口与验证边界

- `./scripts/quality.sh --database` 是唯一完整本地质量入口：先运行七步无数据库门禁，再运行 IAM、Site、OpenAPI 隔离 SQLite 测试，构建生产 JAR，以 `mktemp` 创建的 `/tmp/internal-admin-quality.*` 空库在 `18080` 启动、health 检查并解析 Liquibase `Run/Total change sets`。成功时仅清理自身临时目录；失败时保留 `app.log`。
- `./scripts/dev.sh` 是唯一开发服务生命周期入口：仅在 PID、命令、工作目录和端口归属均可验证时认定或终止服务；后端固定 `8080`，前端固定 `5173`。
- `.github/workflows/quality.yml` 在 push / pull request 上只执行锁定 npm 安装，然后调用同一 `./scripts/quality.sh --database`；远端工作流不运行真实 Chromium 业务链路。
- Playwright 固定 Chromium、`workers: 1`、`retries: 0`，不自动启动应用；真实运行需要外部提供 `E2E_FRONTEND_URL`、唯一 `E2E_RUN_ID` 及三类账号变量。V01-09 的运行原则仍有效：必须新的 run ID 和新的首次改密账号，禁止复用已改变状态的首次账号，不使用 SQL 或网络拦截替代真实页面。

## 4. 唯一串行发布验证方案（待授权后执行）

| 顺序 | 唯一入口与副作用 | 成功证据 | 首次失败即停与责任 |
| --- | --- | --- | --- |
| 0. 固定干净来源 | 总设计师审查当前改动并形成目标 commit；经项目负责人授权，在指定的空本地目录建立一个干净副本（worktree 或 clone）。 | 目标 SHA、干净 `git status --porcelain`、remote 记录。 | 目标 SHA 未确认、工作区不净或副本建立失败：停止；总设计师协调来源，项目负责人决定是否授权。 |
| 1. 锁定依赖 | 在干净副本按两份 lock 执行 `npm ci`；不改 lock。 | npm 退出码、lock 文件与安装日志。 | 解析/网络 60 秒无进展或首次失败：停止并保留输出；项目负责人处理网络/镜像/凭据，不更换锁定版本。 |
| 2. 本地完整质量 | 以 JDK 25 运行唯一入口 `./scripts/quality.sh --database`。副作用仅为既有隔离测试 SQLite、`target`/`dist` 与脚本自有 `/tmp/internal-admin-quality.*`、`18080`。 | 总退出码；七步结果；IAM/Site/OpenAPI Surefire XML；fresh DB 的 health 和动态 Liquibase Run/Total；临时目录/PID/端口收尾。 | 任一门禁首次失败停止，不手工重跑子命令；运维保留脚本日志/临时 `app.log`，研发负责源代码缺陷。 |
| 3. 开发脚本生命周期 | 启动前重查 8080/5173 与 PID 文件归属；仅用 `./scripts/dev.sh start`、`status`、`stop`。干净副本的 `backend/data/internal-admin.db`、`backend/data/uploads` 由正常启动/迁移按需创建。 | PID、端口、8080 health、5173 HTTP、`logs/` 和停止后无监听。 | 所有权不明、端口占用或 health 失败：停止并保留日志；运维诊断，不使用宽泛终止或替代启动入口。 |
| 4. 真实 Chromium | 通过正常认证/API/页面准备本轮 E2E 账号；以新的 run ID 和新的首次改密账号运行三条 Chromium 用例。副作用为授权隔离 SQLite、上传目录和本轮测试数据。 | Playwright 退出码、3/3、last-run、失败时 trace/截图/页面状态；账号权限及首次改密前后状态。 | 首个失败立即停止，不重复同路径、不降断言；研发修复后以新的有状态样本从头重跑。 |
| 5. 远端 GitHub Actions | 仅在项目负责人明确授权提交与推送目标分支/PR 后触发既有 `quality.yml`。 | commit SHA、push/PR、Actions run URL、结论和日志；工作流应只调用 `--database`。 | 首个远端失败不反复触发；保留 run URL/log，由对应研发或运维按失败归属处理。 |
| 6. 收尾与交付 | 仅停止本轮已核验归属 PID；保留通过/失败证据。 | 端口复核、PID/临时目录状态、数据库与上传范围、未执行项。 | 任何进程不能确认归属：不终止，报告总设计师。 |

所有可能超过 60 秒的安装、质量、启动、浏览器与远端状态等待均以持续输出/产物/进程变化为进度信号；连续 60 秒无可见进展时停止一次、保存现场并报告。相同路径不得盲目第二次重试。

## 5. 最小授权清单

1. 总设计师先确认包含当前 66 项改动的审查后目标 SHA；项目负责人明确授权在指定本地目录创建一个干净验证副本。当前禁止的 worktree/clone、stash、reset 均不得作为替代方案自行执行。
2. 项目负责人授权在该干净副本通过锁文件执行 `npm ci`，以及通过 `quality.sh --database` 写入其隔离测试资产、`target`/`dist`、脚本自有 `/tmp/internal-admin-quality.*` 与验证端口 `18080`。
3. 项目负责人精确授权开发脚本在该干净副本通过项目正常入口创建/迁移其本地 SQLite `backend/data/internal-admin.db`、使用其配置上传目录 `backend/data/uploads`，并仅占用 `8080`、`5173`；不授权当前共享工作区数据库、外部/共享数据库、手工 SQL、删除或重建数据库。
4. 项目负责人提供仅存在于本次进程环境的认证来源，并授权通过正常应用路径准备新 run ID 的受限、编辑和首次改密账号；密码、Cookie 和 token 不写入报告、仓库或日志。
5. 项目负责人明确授权对 `origin` 的提交与推送目标（分支或 PR）、所需凭据及 GitHub Actions 首次远端运行。当前 remote 地址存在不等于已获写权限或推送授权。
6. Chromium 缓存存在安装完成标记和可执行文件，因此当前不需要下载授权；这不是浏览器实际运行证明。若正式执行前缓存缺失，必须另行申请锁定 Chromium 下载授权，不能换浏览器或降级为 `--list`。

## 6. 未执行项与当前阻断

- 未执行任何本地质量测试、npm 安装、数据库迁移、服务生命周期、真实 Chromium、远端 Actions、提交或推送；故本报告不构成 V01-12 正式验证或 0.1 发布通过。
- 当前唯一阻断是干净来源与相应外部授权尚未成立。工具链与端口不构成阻断，但必须在正式执行前再次检查。
- 当前可继续的工作仅为等待总设计师/项目负责人确认目标 SHA、干净副本位置和最小授权；在此之前保持工作区与既有运行资产不变。
