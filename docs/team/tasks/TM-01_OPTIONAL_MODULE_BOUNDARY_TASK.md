# TM-01 可选模块边界与裁剪改造

> 状态：执行中（TM-01A/B/C 已完成，等待外部数据库验证与 TM-01D）
> 主责角色：研发工程师
> 必要协作者：运维工程师（质量入口与真实运行）
> 创建日期：2026-08-12
> 交付协议：[版本任务交付协议](../VERSION_DELIVERY_PROTOCOL.md)

## 1. 目标与最高风险

- 完成结果：消除通用模块对 `module-site` 的反向业务耦合，建立可选模块唯一装配/裁剪契约和轻量边界门禁，并在长期派生样本中证明裁剪后的模板仍可运行。
- 最高风险：文件接口改用独立权限后，既有只拥有 `site:homepage:edit` 的自定义角色会失去上传与管理读取能力。
- 已确认取舍：**人工补权限**。不新增 Liquibase 数据迁移，不保留双权限兼容；系统管理员自动获得新权限，既有自定义角色由管理员在角色管理中手工补充，并在升级说明中明确。
- 更轻路径为何不足：只补一份裁剪文档不能防止后续反向耦合再次进入生产代码；只加静态扫描又无法说明 Maven、Liquibase、权限、前端和生成物等合法装配点。

本任务已经由独立项目审议者事前审议，结论为“有条件支持”；项目负责人已确认采用人工补权限。不得在实施中重新扩大为插件、自动发现、权限 SPI、模块描述器或一键卸载工程。

## 2. 唯一交接

| 主责 | 必要协作者 | 交接原因 | 文件/环境所有权 | 唯一回传点 |
| --- | --- | --- | --- | --- |
| 研发工程师 | 运维工程师 | `scripts/quality.sh` 与真实隔离运行属于运维资产/能力 | 研发负责 TM-01A、TM-01B 以及派生样本源码同步；运维负责 TM-01C 和 TM-01D 的运行命令 | 研发完成 A/B 并冻结源码后交接一次；运维完成 C/D 后回传一份最终结果 |

总设计师负责架构差异复核，不直接实施生产代码或运维资产。独立项目审议者不进入普通实现验收链。

## 3. 权威来源与边界

### 3.1 已确认来源

- `AGENTS.md`
- `docs/PROJECT_VISION.md`
- `docs/architecture/BACKEND_MODULES.md`
- `docs/architecture/FRONTEND_STRUCTURE.md`
- `docs/architecture/AUTHENTICATION.md`
- `docs/development/CAPABILITY_COMMON.md`
- `backend/modules/module-file/capability/{AI_PROMPT,CONTRACT,TEST}.md`
- `backend/modules/module-site/capability/{AI_PROMPT,CONTRACT,TEST}.md`
- `backend/modules/module-audit/capability/{AI_PROMPT,CONTRACT,TEST}.md`
- `backend/modules/_capability-template/CONTRACT.md`

### 3.2 允许修改

**TM-01A 通用模块边界清理（研发）**

- `backend/modules/module-iam/src/main/java/com/internaladmin/module/iam/api/PermissionCodes.java`
- `backend/modules/module-file/src/main/java/com/internaladmin/module/file/controller/FileController.java`
- `backend/modules/module-file/capability/{AI_PROMPT,CONTRACT,TEST}.md`
- `backend/modules/module-audit/src/main/java/com/internaladmin/module/audit/api/AuditRecordApi.java`
- `backend/modules/module-audit/src/main/java/com/internaladmin/module/audit/model/entity/AuditOperationDO.java`
- `backend/modules/module-audit/capability/{AI_PROMPT,CONTRACT,TEST}.md`
- `backend/modules/module-iam/capability/{AI_PROMPT,CONTRACT,TEST}.md`
- `backend/apps/app-server/src/test/java/com/internaladmin/app/IamFlowTest.java`
- `backend/apps/app-server/src/test/java/com/internaladmin/app/SiteFlowTest.java`
- `frontend/e2e/site-publish-flow.spec.ts`
- 为文件接口权限允许/拒绝与通用审计服务行为直接新增或修改的测试文件

**TM-01B 装配/裁剪契约（研发）**

- `backend/modules/module-site/capability/CONTRACT.md`
- `backend/modules/_capability-template/CONTRACT.md`
- `docs/architecture/AUTHENTICATION.md`
- 一份简短的模板派生指南（文件名由研发依照现有 `docs/development/` 结构确定）
- `README.md` 中该指南的单一入口及人工补权限升级说明

**TM-01C 轻量边界门禁（运维）**

- 一个只读静态检查脚本
- `scripts/quality.sh` 中对该脚本的一次调用
- 该脚本的直接静态测试（确有必要时）

**TM-01D 长期派生样本（研发 + 运维）**

- `/Volumes/myProjects/factory-equipment-management` 中与主模板共享的对应源码、契约和质量入口
- 主模板现有 `docs/TEMPLATE_MATURITY_AUDIT.md` 的最终实测结论

### 3.3 明确禁止

- 禁止新增或修改 Liquibase 变更集，禁止自动修改现有角色数据；
- 禁止永久同时接受 `site:homepage:edit` 与 `file:manage`；
- 禁止插件系统、动态模块发现、权限贡献 SPI、模块描述器、自动 Liquibase 扫描、前端插件注册表和新代码生成器；
- 禁止扩展公开主页、开发设备台账或建立第二个业务模块；
- 禁止修改生产 `SecurityConfig`；现有 `SessionBootstrapController` 只存在测试上下文，不属于本任务整改范围；
- 禁止改写历史任务和历史证据；生成物只能按现有生成入口刷新；
- 禁止为 A、B、C、D 分别创建任务书、接收回执、实施报告或验收报告。

## 4. 工作包与完成条件

### TM-01A 通用模块边界清理

1. 在现有中央权限目录增加语义为“文件上传与管理端读取”的通用权限；默认候选编码为 `file:manage`，若研发发现与现有命名规则直接冲突，必须先停止报告，不得自行增加兼容别名。
2. 系统管理员的默认权限集合包含该权限；不改变其他初始化行为。
3. `FileController` 只使用通用文件权限，不再出现 Site 权限或 Site 专属职责表述。
4. 既有自定义角色不自动迁移：升级说明明确由管理员在角色管理中勾选“文件管理”；权限不足返回既有 403。
5. 回归测试至少证明：仅有旧 Site 编辑权限不能调用文件接口；拥有文件权限可以调用；Site 真实上传测试角色显式拥有文件权限。
6. `module-audit` 只定义通用审计记录字段、事务与失败语义；`SITE_*` 事实归 `module-site`，`USER_DELETE/ROLE_DELETE` 事实归 `module-iam`。通用 API 中的示例不得再只举 Site。
7. `IamFlowTest` 的通用角色引用样本不使用 Site 权限作为任意测试数据。

### TM-01B 装配/裁剪契约

1. `module-site/capability/CONTRACT.md` 是 Site 装配与裁剪的唯一事实源，覆盖：Maven reactor、app 依赖、MapperScan、Liquibase 聚合、权限目录、审计、Controller/OpenAPI、前端路由导航、package/Vitest/Playwright/E2E、质量入口和生成物。
2. 清单区分“源装配点”“需重新生成的产物”“只保留的历史证据”，不得把历史档案列为删除对象。
3. `_capability-template/CONTRACT.md` 增加同一必填章节，使后续可选模块创建时登记装配面。
4. 派生指南只说明读取契约、身份替换、模块裁剪、验证和升级记录，不复制 Site 清单，不承诺一键卸载。
5. 认证架构明确 `/api/public/**` 是模板级匿名命名空间；进入该命名空间的生产 Controller 必须主动接受匿名访问。裁剪时检查是否仍有遗留公开 Controller。

### TM-01C 轻量边界门禁

边界脚本以 `docs/architecture/BACKEND_MODULES.md` 为权威，只机械实现当前稳定规则：

- foundation 生产源码禁止依赖任何业务模块；
- `module-file`、`module-audit` 生产源码禁止依赖其他业务模块；
- `module-iam` 生产源码当前只允许依赖 `module-audit`；
- 通用模块方法权限只能使用自身已批准的权限命名空间；中央 `PermissionCodes` 是显式应用权限目录，精确豁免；
- `frontend/src/shared` 禁止导入具体业务模块；
- 只扫描生产源码，不扫描测试、能力包、历史证据和生成物。

不得维护一份逐个可选模块名称的平行注册表。未来正式改变模块定位时，先修改 `BACKEND_MODULES.md`，再同步脚本。专属 API 残留由裁剪步骤的一次定向搜索检查，不建设猜测式通用路径扫描。

### TM-01D 真实派生验证

1. A、B、C 在主模板冻结并形成固定提交后，研发将共享变化同步到长期样本 `factory-equipment-management`；不开发设备业务。
2. 依据 Site 契约复核现有 Site 裁剪，确认权限目录无 Site 权限、无遗留 Site Controller、通用模块无 Site 反向引用。
3. 运维在该派生样本的隔离环境只执行：边界检查、`quality.sh --database`、一次启动、登录/工作台和首次正常停止。
4. 不运行远端 CI、完整 Site E2E 或与裁剪无关的全链验证。
5. 只记录：外部装配点数量、实际耗时、返工轮次、失败类型、是否人工介入，并回写现有模板成熟度审计。

## 5. 最低充分证据与预算

| 完成条件 | 最低充分证据 | 执行者 | 最多完整轮次 |
| --- | --- | --- | --- |
| 文件权限解耦且行为明确 | 定向权限测试 + 相关模块编译；旧权限 403、新权限通过 | 研发 | 1 次最终轮；范围内测试缺陷可修正 1 次 |
| Audit 不再拥有消费者业务事实 | 定向生产源码/能力包扫描 + Audit 直接单元测试或现有最近证据 | 研发 | 1 |
| Site 装配契约完整且无第二清单 | 契约逐项对照当前源码 + 定向残留扫描 + diff 检查 | 研发 | 1 |
| 边界门禁可发现反向依赖 | 正例通过 + 至少一个受控反例失败 + `quality --no-database` | 运维 | 1 |
| 派生样本裁剪后仍成立 | `quality --database` + 一次登录/工作台 + 首次正常 stop | 研发/运维 | 1 |

源冻结后只执行一次最终完整验证。局部修正先运行最近的局部证据，禁止每次修正都重跑数据库、服务或全链质量入口。

## 6. 停止与恢复

- 网络、依赖、编译、测试、启动和外部工具连续 60 秒无可观察进展时停止等待并报告；不重复同一路径硬耗。
- 可恢复的命令目录、参数和静态检查脚本错误，由原执行者在原范围内最小修正一次。
- 测试缺陷只能在原授权文件中修正，禁止减弱断言。
- 出现以下任一情况立即停止：需要数据库迁移、需要保留双权限兼容、需要新增模块注册框架、需要扩大公开主页或设备业务、派生样本存在来源不明的用户改动、修改范围无法继续按所有权分离。
- 同一工作包第三次失败或实际验证轮次超过预算两倍时，停止继续修补，由总设计师重新检查任务设计，不追加报告或角色。

## 7. 当前结果

- 结论：TM-01A/B/C 已完成并冻结；模块边界受控反例与主模板无数据库质量入口均已通过。文件权限真实 SQLite 行为验证受当前运行环境的更高优先级数据库只读限制约束，尚未执行；TM-01D 尚未开始，因此本任务不标记完成。
- 实际证据：
  - 权限目录新增 `file:manage`（“文件管理”），并加入系统管理员默认权限与注册选项；`FileController` 仅以该通用权限保护上传/管理端读取，不再依赖或表述 `site:homepage:edit`。
  - `IamFlowTest` 新增仅主页编辑角色被文件接口拒绝、仅文件管理角色可上传并读取的回归；`SiteFlowTest` 登录后的系统管理员角色显式断言拥有 `file:manage`。角色删除样本改用 `iam:user:manage`，不再以 Site 权限作任意数据。
  - module-audit 的公开 API/实体/能力包收敛为调用方定义 action/target/result、默认事务和失败可见的通用契约，不再复制 IAM/Site 动作清单；新增 `AuditRecordServiceTest` 覆盖字段映射、时间写入和 Mapper 异常可见。
  - module-site CONTRACT 补齐 Maven reactor、应用依赖/扫描、Liquibase 聚合、权限、审计、Controller/OpenAPI、OpenAPI 断言/测试、应用级 Site/IAM 测试、前端 modules/site 测试与 router/E2E fixture、旧 smoke 入口、质量入口、现行文档、生成物与历史证据边界；模板 CONTRACT 增加同一必填章节；新增模板派生指南不复制 Site 专属清单。
  - 认证架构补充 `/api/public/**` 模板级匿名命名空间与裁剪残留检查；新增模板派生指南，并在 README 登记唯一入口及人工补权限说明。
  - 早期探路及整改后的 JDK 25 无数据库编译：`./mvnw -Djava.version=25 -DskipTests -pl apps/app-server -am test-compile`，退出 0；Audit 单测 `./mvnw -Djava.version=25 -pl apps/app-server -am -Dtest=AuditRecordServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` 2/2 退出 0；`git diff --check` 退出 0；定向检索确认 module-audit 不含消费者动作清单，FileController 不含旧 Site 权限，Site 契约包含新增外部装配点。
  - TM-01C 新增只读边界脚本并接入无数据库质量入口；当前仓库正例通过。临时夹具中的 foundation 生产源码违规导入业务模块时，脚本退出 1 并输出精确规则，证明门禁不是空检查；夹具未进入项目工作区。
  - 主模板 `./scripts/quality.sh --no-database` 退出 0：边界检查、Session 安全、文件内容校验、OpenAPI 漂移、Vitest 8/8、Playwright 3 条清单、TypeScript 与前端构建共 8 步通过。
- 未执行项与剩余风险：当前运行环境存在高于项目规范的数据库绝对只读限制，因此未执行 `IamFlowTest`/`SiteFlowTest` 的隔离 SQLite 权限回归；不能据此声称旧权限 403、新权限放行的数据库链路已验证。首次 Audit 单测因 JDK25 Mockito inline agent 自附加失败（2 errors）后已在测试文件内改为 JDK Proxy，无新增依赖，复验通过。人工补权限是已确认升级策略；正式使用自定义角色的部署方必须按升级说明操作。TM-01D 长期派生样本尚未执行。
- 文件与副作用范围：仅修改本任务 TM-01A/B 允许的生产权限/Controller、能力包、审计通用 Javadoc、IAM/Site 测试、Site/模板契约、认证架构、模板派生指南、README 与本任务书；未新增实施/验收报告，未修改 Liquibase、角色数据、生产 SecurityConfig、其他任务状态或生成物。
