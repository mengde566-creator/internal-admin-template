# V01-11B 现行文档事实收口

> 状态：完成（总设计师 / 总架构师二次验收通过）
> 所属版本：0.1
> 主责角色：研发工程师（乙）
> 设计与派发：总设计师 / 总架构师
> 最终验收：总设计师 / 总架构师
> 创建日期：2026-08-11
> 交付协议：[版本任务交付协议](../VERSION_DELIVERY_PROTOCOL.md)
> 执行对话：个人项目-普通研发乙；`threadId=019fe909-ca2e-7581-98b5-d3f191aca9b9`

## 1. 核心目标

只修正当前仍被读者当作执行入口或产品事实的过期文档，使构建、质量验证、初始化管理员保护和 V01-12 边界与最终代码及 V01-10 验收结果一致；历史任务、失败证据和决策过程保持原样。

## 2. 范围与非目标

### 必须完成

- 当前后端构建入口统一为 `backend/mvnw`，前端锁文件安装入口统一为 `npm ci`；
- 当前快速质量入口写为 `./scripts/quality.sh --no-database`，完整隔离数据库质量入口写为 `./scripts/quality.sh --database`；
- 文档不得继续声称质量入口执行 `clean`、系统 Maven、Python/手工 SQLite 检查或只有旧测试数量；
- 初始化管理员保护事实统一为账号 `admin`，数据库 ID 为应用生成值，不得继续把 `id=1` 当作保护依据；
- V01-08/V01-10 已完成的测试状态与 V01-12 尚待执行的发布级证据明确分开；
- 保持当前 schema 为 12 张业务表、Liquibase 16 个 change sets，不因措辞扫描误改正确事实。

### 明确不做

- 不修改任何历史任务书、实施报告、架构验收记录和调度历史中的原始失败事实；
- 不改变已确认需求语义、产品范围、数据库设计、质量脚本或业务实现；
- 不重写整篇文档，不顺手统一无关术语和格式。

## 3. 权威来源与当前事实

| 类型 | 来源 | 当前结论 | 状态 |
| --- | --- | --- | --- |
| 已确认需求 | `requirements/V0_1_SCOPE.md` | 0.1 范围不扩张，12 张业务表保持不变 | 已确认 |
| 当前执行入口 | `scripts/quality.sh` | 支持 `--no-database` 与 `--database`；禁止无参数模糊执行 | 已核实 |
| CI | `.github/workflows/quality.yml` | CI 调用同一 `--database` 入口 | 已核实 |
| 管理员保护 | `UserService#delete`、`IamFlowTest` | 以用户名 `admin` 识别，ID 为应用生成值 | 已验证 |
| 质量证据 | `V01-10_ARCHITECT_REVIEW.md` | IAM 12/12、Site 4/4、OpenAPI 1/1、fresh Liquibase 16/16 | 已验收 |
| 历史记录 | `docs/team/tasks/evidence/` | 只保留当时真实过程，不作为当前入口说明 | 历史 |

## 4. 派发前可行性审查

| 维度 | 任务需要 | 当前是否具备 | 风险与处理 | 验证证据 |
| --- | --- | --- | --- | --- |
| 权限与副作用 | 仅文档编辑与只读扫描 | 是 | 禁止运行服务、测试和数据库 | 差异范围与扫描结果 |
| 数据库边界 | 无数据库操作 | 是 | 不打开或修改任何数据库文件 | 文件范围检查 |
| 网络与依赖 | 不需要 | 是 | 不联网、不安装依赖 | 无网络命令 |
| 工具链 | Markdown 与 `rg` | 是 | 不以重跑质量门禁代替事实核对 | 静态检查 |
| 历史保真 | 区分当前事实和历史记录 | 是 | 历史文件零修改 | `git diff --name-only` |
| 文档同步 | 九个现行入口文件 | 是 | 逐条对照权威实现，不做全项目改写 | 定向反向扫描 |

### 最小探路验证

- 总设计师已用 `rg` 找到系统 Maven、`npm install`、无参数质量入口、固定管理员 ID 和旧测试状态等候选；
- 其中 12 张业务表、Site 单例主页 `id=1`、历史任务中的旧事实属于有效当前事实或历史证据，不得机械替换；
- 本任务预计仅需数分钟，编辑与扫描应持续产生文件差异；连续 60 秒无进展即停止并发送阻塞，不重复等待；
- 不存在需要人工凭据、网络、数据库或运行环境介入的完成标准。

### 派发结论

- [x] 可执行：全部完成标准均可通过文档差异和权威实现静态核对证明。

## 5. 文件所有权

仅允许修改：

- `README.md`；
- `requirements/V0_1_SCOPE.md`（仅质量入口当前事实）；
- `docs/development/RUNBOOK.md`；
- `docs/ai/ACCEPTANCE_GUIDE.md`；
- `docs/system/api/iam/USER_DELETE.md`；
- `backend/modules/module-iam/capability/AI_PROMPT.md`；
- `backend/modules/module-iam/capability/TEST.md`；
- `backend/modules/module-file/capability/AI_PROMPT.md`；
- `backend/modules/module-site/capability/AI_PROMPT.md`；
- `docs/team/tasks/evidence/V01-11B_IMPLEMENTATION_REPORT.md`。

禁止修改代码、脚本、CI、迁移、数据库文件、依赖、历史任务/证据、任务总表或其他文档；禁止运行 Maven/npm、服务、数据库、`clean`、提交或推送。

## 6. 完成标准与证据路径

| 编号 | 完成标准 | 证据 |
| --- | --- | --- |
| AC-01 | README、RUNBOOK、验收指南的构建和质量命令与当前脚本一致 | 定向差异和命令扫描 |
| AC-02 | 已确认范围文档只修正质量机制的当前演进事实，不改变产品语义 | 逐行差异审查 |
| AC-03 | IAM 删除契约和能力包不再以固定 ID 识别初始化管理员 | `rg` 与 `UserService` 对照 |
| AC-04 | 能力包准确区分已通过的 V01-08/V01-10 与待执行的 V01-12 | 当前状态扫描 |
| AC-05 | 12 表、16 change sets、Site 主页单例和历史失败记录未被误改 | 反向范围审查 |
| AC-06 | `git diff --check` 通过，变更仅限授权文件 | Git 差异检查 |

## 7. 交付要求

完成后从 AC-01 至 AC-06 重新自我复盘，报告每个被纠正的旧事实、保留未改的相似但正确事实和全部未执行项；使用跨对话消息发送 `[V01-11B][申请验收]`，不得自行标记完成。
