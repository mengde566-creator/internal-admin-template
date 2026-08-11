# V01-12 固定 SHA 发布级全量验证

> 状态：完成（总设计师 / 总架构师验收通过）
> 所属版本：0.1
> 主责角色：运维工程师
> 设计与派发：总设计师 / 总架构师
> 最终验收：总设计师 / 总架构师
> 创建日期：2026-08-12
> 交付协议：[版本任务交付协议](../VERSION_DELIVERY_PROTOCOL.md)
> 执行对话：个人项目-运维工程师；`threadId=019ff08e-d309-7ca3-a7b6-7b670b7baaff`

## 1. 核心目标

对已提交并推送的固定 SHA `90d4d313b3483f07237a6e0cf342953d980f20f5` 执行一次可复现的发布级全量验证，证明干净来源、锁定依赖、完整质量入口、开发脚本生命周期、真实 Chromium 业务链和该 SHA 的远端 GitHub Actions 结果。

目标远端分支：`origin/codex/v0.1-release`。

首轮候选 SHA `ec98d2872d87b735978219507d92c435ce3b699f` 已冻结为失败证据：`dev.sh stop` 的约 2 秒等待窗口短于实测约 2.02 秒的正常 Spring graceful shutdown。V01-12A 已将等待窗口最小修复为 10 秒并形成当前固定 SHA；本轮必须从头执行，旧 SHA 的局部通过结果不得替代新 SHA 证据。

当前 SHA 的首次 worktree 已证明修复后的 `dev.sh stop` 首次退出 0，并在约 2 秒正常停止前后端；但持有本轮秘密的临时 PTY 在账号准备前丢失，无法安全恢复管理员密码，因此该 worktree 已冻结，不得通过读取进程环境或删除数据库续跑。最终轮使用同一固定 SHA 的全新 worktree 从头验证。

第二份 worktree 的常驻 PTY 已通过存活门禁，但错误地在子运行器内部生成秘密；子运行器因把 API 成功码 `"OK"` 当作数字 `0` 而退出后，父 PTY无法恢复该秘密。只读证据还发现运行器误用 `permissionCodes` 响应字段并把 64 位角色 ID 转成 JavaScript `Number`。该 worktree 已正常停止并冻结；最终运行器必须在执行前一次性对照当前 DTO/响应契约关闭这些运维脚本缺陷。

## 2. 固定环境与授权范围

- 最终干净验证目录固定为 `/Volumes/myProjects/internal-admin-template-v01-12-90d4d31-r3`；派发前已确认该路径不存在；此前 worktree 仅保留失败与修复证据，不参与最终轮运行；
- 只允许通过 `git worktree add --detach` 从当前仓库创建上述目录，并机械确认其 HEAD 等于固定 SHA、`git status --porcelain` 为空；禁止 clone 到其他路径、stash、reset、clean 或改变源分支；
- 允许在该干净目录按锁文件执行 `npm ci`，并产生其 `node_modules`、`target`、`dist` 与测试报告；不得修改 lock 文件；
- 允许 `./scripts/quality.sh --database` 通过项目正常入口写入该目录的隔离测试资产、脚本自有 `/tmp/internal-admin-quality.*` 和 `18080`；
- 允许 `./scripts/dev.sh` 在该目录创建/迁移其 `backend/data/internal-admin.db`、按需创建 `backend/data/uploads`，仅占用 `8080/5173`；禁止访问当前主工作区数据库、外部/共享数据库和手工 SQL；
- 允许为本轮生成只存在于进程环境的随机密码和唯一 `E2E_RUN_ID`，通过正常认证/API/页面准备三类测试账号；密码、Cookie、token 不得写入仓库、报告或用户可见日志；
- 本轮只检查 push 已触发的固定 SHA GitHub Actions，不得自行再次提交、推送、rerun 或创建 PR；
- 干净 worktree 在总设计师验收前保留，禁止删除；所有本轮归属服务必须停止。

## 3. 文件所有权

主工作区仅允许创建或修改：

- `docs/team/tasks/evidence/V01-12_FULL_VALIDATION_REPORT.md`。

干净验证目录只允许产生锁定依赖、构建、测试、隔离 SQLite、上传和日志等运行资产；禁止修改任何已跟踪源文件。若 `git status --porcelain` 在运行前或运行后出现已跟踪/未跟踪且未被忽略的变化，立即停止并报告。

## 4. 唯一串行执行顺序

1. 从当前仓库创建固定路径的 detached worktree，核对 SHA、remote 和空 status；
2. 在 `frontend` 与 `tools/openapi` 分别执行标准 `npm ci`；连续 60 秒无可见进展即停止，不换版本、不改 lock、不反复尝试；
3. 使用 Oracle JDK 25.0.4 与项目 Maven Wrapper 执行 `./scripts/quality.sh --database`；首个失败立即停止，保留脚本指定的日志和临时目录；
4. 重新核对 `8080/5173` 空闲；先在父常驻 PTY 生成并导出全部秘密，再由子运行器只读取既有环境变量，禁止子运行器生成或覆盖秘密；通过同一 PTY 承载 `./scripts/dev.sh start`、账号准备和 E2E，验证 `status`、8080 health、5173 HTTP 200；
5. 通过正常应用接口准备本轮唯一的首次改密、无内容维护权限和内容编辑账号。受限/编辑账号先通过正常认证与改密接口完成首次状态准备；首次改密账号保持未改密且不得复用历史账号；
6. 设置 `E2E_FRONTEND_URL`、`E2E_RUN_ID` 与任务现有八个账号/密码环境变量，执行 `npm run test:e2e`；必须得到 Chromium 3/3，失败时保留首个 trace/截图/页面状态，不重试、不降断言；
7. 只通过 `./scripts/dev.sh stop` 停止本轮已核验归属服务，复核 PID 与 `8080/5173/18080` 无监听；
8. 检查 GitHub 上固定 SHA 的 `quality` 工作流首次运行结果，记录 run URL、SHA 和结论；本机没有 `gh`，可使用 GitHub 公共 Actions 页面/API或已登录浏览器，只读查看；
9. 核对干净 worktree 的已跟踪源文件无变化，保留 worktree 和全部必要证据，提交实施报告并申请总设计师验收。

## 5. 完成标准

| 编号 | 完成标准 | 必须证据 |
| --- | --- | --- |
| AC-01 | 验证源为固定 SHA 的干净 detached worktree | 路径、HEAD、空 status、remote |
| AC-02 | 两份锁文件均以 `npm ci` 成功安装且 lock 未变 | 命令退出码、依赖版本、Git 状态 |
| AC-03 | 完整数据库质量模式退出 0 | 七步结果、IAM 12/12、Site 4/4、OpenAPI 1/1、Liquibase Run=Total>0、health 与收尾 |
| AC-04 | `dev.sh` start/status/stop 生命周期完整 | PID/端口归属、8080 health、5173 200、停止后无监听 |
| AC-05 | 新状态样本的真实 Chromium 业务链通过 | 3/3、run ID、last-run/日志、隔离数据库与上传边界；不得泄露密码 |
| AC-06 | 固定 SHA 的远端 Actions 首次运行成功 | GitHub run URL、SHA、workflow 名称与 conclusion |
| AC-07 | 源文件无变化且副作用全部在授权路径 | 运行前后 status、数据库/上传/日志路径、端口与 PID 复核 |

## 6. 停止与协作规则

- 任一步首个失败立即停止后续步骤，保留现场并向总设计师发送 `[V01-12][阻塞]`；同一路径不得盲目重试；
- 在生成任何秘密前必须机械证明常驻 PTY 可持续交互；秘密必须在父 PTY 生成并保留，子运行器只验证所需变量 nonempty。PTY 丢失即停止并重建全新隔离环境，禁止读取进程环境或猜测秘密；
- 临时账号运行器必须先通过语法检查和 DTO/响应契约静态检查：CSRF 捕获丢弃正文且只接受 HTTP 401；API 成功码只接受字符串 `OK`；当前用户权限字段使用 `permissions`；角色 ID 作为字符串传入 `roleIds`，禁止转成 JavaScript `Number`；步骤标签在函数返回后必须恢复；
- E2E 前必须机械确认当前目录为 `frontend` 且 `package.json` 存在 `scripts.test:e2e`；
- 安装、构建、启动、浏览器或远端状态连续 60 秒无可见进展时停止并报告，不能长时间等待；
- 发现源代码缺陷只报告，不在运维任务中修改；由总设计师另派研发，修复后必须形成新提交/新 SHA，并从第 1 步重新验证；
- 全部通过后发送 `[V01-12][申请验收]`，不自行标记 V01-12 或 0.1 发布完成。
