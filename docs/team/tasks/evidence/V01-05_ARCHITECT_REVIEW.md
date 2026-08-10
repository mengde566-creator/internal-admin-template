# V01-05 总设计师 / 总架构师验收记录

> 结论：未通过，V01-05 保持“进行中”<br>
> 验收角色：总设计师 / 总架构师<br>
> 验收日期：2026-08-09<br>
> 被验收交付：[研发实现报告](V01-05_IMPLEMENTATION_REPORT.md)<br>
> 任务书：[V01-05_OPENAPI_CONTRACT_TASK.md](../V01-05_OPENAPI_CONTRACT_TASK.md)

## 1. 核心判断

研发已经建立了 Maven Wrapper、springdoc 配置、契约脚本、断言脚本和三处“登录安全”术语同步，但交付尚未形成可工作的契约闭环。当前存在两个可复现的工程失败和两个未满足的核心完成条件，因此不能标记完成，也不能进入 V01-06。

## 2. 阻断项

### P0-1：新增后端配置无法编译

独立执行：

```bash
cd backend
./mvnw -Djava.version=17 -DskipTests -pl apps/app-server -am test-compile
```

结果：退出 1。`OpenApiContractConfig.java:54` 将返回 `void` 的 `OpenAPI#addExtension` 作为链式返回值，编译器报告“`void` 无法转换为 `OpenAPI`”。这不是 JDK 25 或数据库限制导致的问题；当前新增主代码本身不可编译。

整改标准：拆开 `OpenAPI` 创建、`addExtension` 调用和返回语句，并重新通过不启动应用、不连接数据库的 `test-compile`。

### P0-2：标准 npm 依赖解析失败

独立执行：

```bash
cd frontend
npm install --package-lock-only --ignore-scripts --dry-run
```

结果：退出 1，`ERESOLVE`。`openapi-typescript@7.13.0` 声明 `typescript@^5.x`，项目为 `typescript@6.0.3`。研发使用 `--legacy-peer-deps` 生成锁文件只绕过了冲突，不构成受支持或可重复的标准安装路径。

架构修正：生成器移入独立 `tools/openapi` 工具目录，使用锁定的 TypeScript 5.9.3；前端保留 TypeScript 6.0.3，并对生成文件执行 `vue-tsc`。禁止用 `--legacy-peer-deps`、`--force` 或 `.npmrc` 隐藏冲突。

### P0-3：必需生成物缺失

以下任务书指定并要求进入版本库的文件不存在：

- `docs/system/api/openapi.json`；
- `frontend/src/generated/api-schema.ts`。

研发如实记录了受数据库只读环境限制未执行运行时生成，这一点符合纪律，但“符合纪律”不等于“达到完成标准”。在外部允许环境生成、断言并完成漂移检查之前，V01-05 不能完成。

### P0-4：生成类型没有进入业务类型链

`frontend/src/shared/api/http.ts` 新增了手写 `IdResult`，用户和角色创建接口引用的仍是手写类型。即使未来提交生成文件，后端契约变化也不会机械约束这些业务 API，仍会重复本任务要解决的“后端、文档和前端手写类型漂移”。

整改标准：生成物产生后，用户和角色创建接口直接引用生成的 `paths` 或 `components` 类型；删除同义手写 `IdResult`。Axios 实例和错误处理保持不变。

## 3. 其他验收发现

1. `OpenApiContractConfig` 通过属性名全局改写 ID、枚举和 nullable。该方案可以作为 0.1 最小实现，但必须用运行时规范断言和真实 JSON 对照证明没有把错误序列化“美化”为正确 schema。
2. 当前 `frontend/node_modules` 在研发执行失败的 `npm ci` 后处于不完整状态；本次复验 `npm run typecheck` 退出 127（`vue-tsc` 不存在），`npm ls --depth=0` 退出 1。因此研发早先记录的前端通过结果当前不可复现，需在干净依赖目录重新验证。
3. `git diff --check`、Shell 语法检查和 Node 脚本语法检查通过；三处“登录安全”文案静态对齐通过。
4. Maven Wrapper 成功下载并启动 Maven 3.9.16，说明 Wrapper 引导本身有效；本次只执行编译阶段，没有启动应用、测试、Liquibase 或任何数据库操作。

## 4. 复验入口

研发完成整改后，至少提交以下证据：

1. 后端 `test-compile` 退出 0；
2. 前端和独立契约工具目录均能使用标准 `npm ci`，无 `legacy-peer-deps`；
3. 两份运行时生成物存在并通过 `generate`、`check`；
4. 用户、角色创建接口消费生成类型，前端 TypeScript 6 `typecheck` 与 `build` 退出 0；
5. Java 25、运行时 springdoc、Security 放行、Jackson 实际 JSON 和契约测试在外部允许环境形成真实证据；
6. 研发实现报告追加修正记录，不覆盖本次失败事实。

上述项目全部满足后，由总设计师 / 总架构师重新验收并决定是否将 V01-05 标记完成。

## 5. 第二次验收（2026-08-09）

> 结论：整改代码通过复验，但 V01-05 整体仍未通过；状态调整为“阻塞（待外部验证）”。

### 5.1 已关闭的整改项

| 原阻断项 | 第二次独立复验 | 判断 |
| --- | --- | --- |
| P0-1 后端配置无法编译 | 执行 `./mvnw -Djava.version=17 -DskipTests -pl apps/app-server -am clean test-compile`，完整清理并重新编译 10 个模块，退出 0。 | 已关闭。 |
| P0-2 npm peer 依赖冲突 | 前端保持 TypeScript 6.0.3；生成器隔离到 `tools/openapi`，固定 `openapi-typescript` 7.13.0 + TypeScript 5.9.3。两个目录标准 `npm ci --ignore-scripts` 均退出 0，未使用 `--legacy-peer-deps` 或 `--force`；本地生成器版本为 7.13.0。 | 已关闭。 |
| P0-4 业务 API 仍使用手写类型 | 手写 `IdResult` 已删除；用户、角色创建接口直接索引生成 `paths` 中对应 POST 200 JSON 响应，Axios 主路径未替换。 | 代码结构已关闭；端到端类型检查仍依赖 P0-3。 |

前端第一次复验 `npm ci` 曾因旧 `node_modules/element-plus/.DS_Store` 导致 `ENOTEMPTY`，并把依赖目录留成不完整状态。将损坏目录移出工作区后，从同一锁文件执行干净 `npm ci` 成功，随后 `npm ls --depth=0` 退出 0。因此该次失败属于本机旧依赖目录清理干扰，不是锁文件或 peer 依赖再次冲突。

脚本静态检查、Node 语法检查、无参数用法保护和 `git diff --check` 均符合预期；本轮没有启动应用、测试、Liquibase 或访问数据库。

### 5.2 尚未关闭的唯一交付阻断

P0-3 仍然存在：

- `docs/system/api/openapi.json` 不存在；
- `frontend/src/generated/api-schema.ts` 不存在；
- 前端 `npm run typecheck` 退出 2，且当前只报告用户、角色 API 无法解析上述生成模块；
- 运行时 springdoc、Security 放行、Jackson 实际 JSON、Java 25、契约测试、生成漂移检查和生成后的前端构建均未形成真实证据。

这不是继续手写代码即可安全关闭的问题。受数据库严格只读规范约束，当前对话不能启动会执行 Liquibase 或写入隔离库的 `contract` 应用，也不能伪造生成文件。因此研发整改可以通过，任务完成验收不能通过。

### 5.3 最终解阻条件

由项目负责人在允许数据库写入的外部 JDK 25 环境执行实现报告第 7 节的修正后验证顺序，并交回以下全部结果：

1. 运行时生成并提交两份生成物；
2. `generate`、`check`、关键 schema 断言全部退出 0；
3. 生成后的前端 `typecheck`、`build` 全部退出 0；
4. Java 25 打包、契约回归测试、Security 放行和 Jackson 实际 JSON 对照通过；
5. 总设计师 / 总架构师核对生成物不是手写、类型确实进入业务链且工作区无契约漂移。

### 5.4 调度修正

首次验收中的“不能进入 V01-06”表述过严，与总表中 V01-06 只依赖 V01-04 的定义不一致，现予纠正：V01-05 在外部验证前保持阻塞，但不阻止相互独立的 V01-06、V01-07 开始；V01-08、V01-09 仍必须等待 V01-05 完成。

## 6. 第三次验收与最终结论（2026-08-09）

> 结论：V01-05 通过，状态标记为“完成”；V01-12 完整运行验证不在本结论覆盖范围内。

### 6.1 独立复验证据

1. 静态复核确认 `NoDatabaseOpenApiContractTest` 使用无组件扫描的显式测试应用，只导入六个 0.1 Controller、OpenAPI/Jackson/异常/Security 配置和 Mock 协作者；数据源、初始化、事务、JNDI、XA、Liquibase 自动配置均被排除，并机械断言不存在 `DataSource`、`SpringLiquibase`、`SqlSessionFactory` Bean。
2. 独立执行 `./scripts/openapi-contract.sh check` 退出 0：只运行上述两个无数据库测试，springdoc、关键 schema 断言、类型生成和两份产物逐字比较全部通过。
3. `check` 前后两份生成物 SHA-256 保持不变：OpenAPI 为 `3179f88341ec55c8b9f8b85d990fa77510f637dbc4f4a222a9dce52fbba2c2be`，TypeScript 为 `4e2b5d80709fd810dcea66452f05588f9b487d6ca53490de8daae08f5321cfe4`。
4. 使用现有错误类型文件作为受控 `OPENAPI_TYPE_PATH` 再执行 `check`，按预期退出 1，证明漂移门禁会拒绝差异且没有改动正式生成物。
5. 独立执行前端 `npm run typecheck`、`npm run build` 均退出 0；前端与独立生成工具的 `npm ls --depth=0` 均退出 0，用户和角色创建 API 直接消费生成 `paths` 类型。
6. Shell、Node 语法和 `git diff --check` 均通过；工作区未发现 `.db`、`.sqlite`、`.sqlite3` 或约定的契约存储目录副作用。

### 6.2 文档一致性纠正

研发首次第三轮自我复盘漏掉实现报告第 1 至第 4 节、脚本错误提示和一个 Javadoc 方法名仍沿用首轮口径，因此第三次验收曾暂缓。研发随后只修正文档、提示和 Javadoc，重新完成专项一致性复盘；当前实现报告已把现行事实与首次失败历史分开，生成器位置、生成类型消费、无数据库入口和 V01-12 边界均与代码一致。

### 6.3 本结论边界

V01-05 完成只证明无数据库的 Controller/DTO → OpenAPI → TypeScript → 业务 API 契约闭环。Java 25、最终 fat jar、完整主应用、真实部署 Security 链、Liquibase/数据库、`OpenApiContractTest` 和端到端运行验证仍由 V01-12 承担；任一未通过时 0.1 仍不得发布。
