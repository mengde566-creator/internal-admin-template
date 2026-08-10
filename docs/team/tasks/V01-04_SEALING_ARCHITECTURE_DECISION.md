# V01-04：0.1 封版技术方案

> 状态：已确认<br>
> 版本：0.1<br>
> 主责角色：总设计师 / 总架构师<br>
> 日期：2026-08-09<br>
> 确认日期：2026-08-09<br>
> 确认来源：项目负责人确认采用第 3 至第 7 节方案，并采用第 9 节推荐的“登录安全”定位<br>
> 前置证据：[V01-03 技术研究证据](evidence/V01-03_TECHNICAL_RESEARCH.md)<br>
> 适用任务：V01-05 至 V01-10<br>

## 1. 核心决定

0.1 采用一条最小、可验证的封版主路径：后端运行时生成 OpenAPI，前端只生成类型并继续使用现有 Axios；组件测试使用 Vitest，浏览器闭环使用 Playwright；Session 和上传安全显式化；本地与 CI 只调用同一个质量入口。

本方案不替换现有前端请求层，不引入第二套 E2E 框架，不建设图片处理流水线，不扩展通用参数平台，也不把 0.2 Agent 能力带入 0.1。

## 2. 事实与决策状态

- **已确认事实**：0.1 必须具备 OpenAPI、前端测试、端到端验收、Session 安全、图片内容拒绝和本地/CI 统一门禁。
- **研究事实**：springdoc 3.1.0 的发布基线已升级至 Spring Boot 4.1.0，与本项目版本线一致；版本线一致不等于项目实际 schema 已验证。
- **架构建议**：第 3 至第 8 节是总设计师 / 总架构师收敛后的最小实施方案。
- **已确认决策**：新增运行时依赖、图片阈值、Session 策略和“登录安全”页面定位均已由项目负责人确认，可以按本方案派发代码实现。

## 3. OpenAPI 与前端类型

### 3.1 采用

1. 后端采用 `springdoc-openapi` **3.1.0**，只引入生成规范所需的 API starter，不引入 Swagger UI。
2. 前端采用 `openapi-typescript` **7.13.0**，只生成 TypeScript 类型。
   - 复核修正（2026-08-09）：该版本声明的 TypeScript peer 范围为 `^5.x`，不能与前端 TypeScript 6 在同一依赖树中通过标准 `npm ci`；生成器改在独立 `tools/openapi` 工具目录中使用锁定的 TypeScript 5.9.3，生成结果再由前端 TypeScript 6 / `vue-tsc` 校验。禁止用 `--legacy-peer-deps` 或 `.npmrc` 忽略不兼容 peer。
3. 继续保留现有 Axios 请求封装、CSRF、Session、401/403 和错误处理主路径。
4. OpenAPI 只在专用 `contract` profile 中启用；生产 profile 默认禁用 `/v3/api-docs`。
5. 生成的规范与类型进入版本库：
   - `docs/system/api/openapi.json`
   - `frontend/src/generated/api-schema.ts`
6. 两个生成文件必须可识别为“机器生成、禁止手工修改”：TypeScript 文件使用文件头注释，标准 JSON 不插入注释，改用顶层 `x-generated-by` 扩展或同目录说明文件记录生成来源；权威来源仍是后端控制器、DTO 和显式 schema 定义。
7. 质量门禁重新生成规范和类型，若与版本库内容不同则失败。
8. 前端业务 API 的请求/响应类型必须引用生成的 `paths` 或 `components` 类型；生成文件不能仅提交但不被业务代码使用，也不能再维护同义手写 DTO 作为平行契约。

### 3.2 必须验证

- `Long` ID 的 OpenAPI schema 必须是 JSON `string`，与 Jackson 3 实际序列化一致；
- `ApiResponse<T>`、分页、枚举、nullable、日期和空响应必须与真实 JSON 一致；
- 用户和角色创建响应统一为 `{ data: { id: string } }`；
- 生成类型必须通过 TypeScript 6 和 `vue-tsc`；
- springdoc 必须在 Java 25、最终 fat jar 和当前 Security 配置下实际生成规范。

#### 3.2.1 验证分层修正（2026-08-09，项目负责人确认继续派发）

为避免契约生成错误依赖数据库写入，验证拆为两层，但不降低最终发布门槛：

1. V01-05 必须建立无数据库契约上下文，从真实 Controller、DTO、Jackson/OpenAPI 定制器和必要的 MVC/Security 配置生成规范；上下文不得扫描主应用的数据源配置，且必须机械断言不存在 `DataSource`、Liquibase、MyBatis `SqlSessionFactory` 等数据库基础设施 Bean。
2. V01-05 完成 Controller/DTO → OpenAPI → TypeScript → 业务 API 的生成、断言、漂移检查、前端类型检查和构建闭环；真实 Jackson 序列化使用不访问数据库的单元测试验证。
3. Java 25、最终 fat jar、完整应用上下文、真实 Security 链、Liquibase/数据库和端到端运行验证保留在 V01-12，由项目负责人在外部允许环境触发；V01-12 未通过时 0.1 不得发布。
4. 无数据库契约上下文是生成与开发门禁，不得被描述为完整应用运行证据；完整运行验证也不得反向阻塞 V01-05 生成物和后续前端开发。

### 3.3 不采用

- 不使用 springdoc 2.x；
- 不用 `openapi-fetch` 替换 Axios；
- 不采用 OpenAPI Generator 完整重写请求客户端；
- 不把低活跃的 springdoc Maven plugin 作为唯一规范抓取入口；
- 不同时维护一份手写 OpenAPI 作为第二事实源。

## 4. 前端测试与浏览器验收

### 4.1 组件与组合测试

- 采用 Vitest **4.1.7**、Vue Test Utils 2 和 `jsdom`；
- 首批只覆盖高风险行为：登录失败、首次改密路由、权限路由、表单校验、请求失败可见、保存后缓存失效；
- 不使用仅快照测试，不把后端完整业务流程复制成前端 Mock 流程。

选择 `jsdom` 而不是 `happy-dom`，是因为 0.1 更重视 Element Plus、Teleport 和常见浏览器 API 的兼容成熟度，暂不为更快的模拟环境承担额外适配不确定性。

### 4.2 E2E

- 采用 Playwright **1.62.1**；
- 每次完整质量门禁只运行 Chromium；
- Firefox 和 WebKit 留到发布前扩展验证，不进入 0.1 每次提交门禁；
- E2E 使用真实前端、真实后端和隔离 SQLite 测试文件，覆盖：登录、改密、权限、编辑、上传、草稿、预览、发布、匿名查看、草稿隔离、撤回；
- 测试数据、端口、上传目录和数据库文件必须按运行实例隔离。

不引入 Cypress，不建设第二套浏览器测试体系。

## 5. Session 与 Cookie 安全

### 5.1 部署假设

0.1 正式部署按“前端与 API 同站、生产使用 HTTPS”设计。真正跨站 Cookie 不属于 0.1。

### 5.2 明确配置

| 项目 | 0.1 决定 |
| --- | --- |
| Session Cookie HttpOnly | `true` |
| Session Cookie SameSite | `Lax` |
| Session Cookie Path | `/` |
| Session 空闲超时 | `30m` |
| 本地 HTTP Secure | `false`，仅本地 profile |
| 生产 HTTPS Secure | `true`，生产 profile 强制 |
| XSRF-TOKEN HttpOnly | `false`，保持 SPA 可读取 |
| XSRF-TOKEN SameSite / Path | `Lax` / `/` |
| XSRF-TOKEN Secure | 本地 `false`、生产 `true` |

### 5.3 登录与退出实现边界

- 保留现有 JSON 登录和退出 API，避免改变前端产品流程；
- 手工登录必须调用明确的 `SessionAuthenticationStrategy`，不再把 `request.changeSessionId()` 当成完整会话策略；
- 退出使用 Spring Security 标准 `SecurityContextLogoutHandler` 或等价标准处理链，清理 Session、SecurityContext repository 和 CSRF 状态；
- 必须通过真实 HTTP 验证最终 `Set-Cookie`，同时用集成测试验证 Session ID 轮换、旧 Session 失效和退出后 401。

0.1 不增加 Remember-Me、并发登录控制、Session 持久化和跨应用单点登录。

## 6. 图片真实内容验证

### 6.1 采用

- JPEG、PNG 使用 JDK ImageIO；
- WebP 使用 TwelveMonkeys ImageIO **3.14.0**；
- 保留 10MB 上传字节上限；
- 单边最大尺寸为 **8192 像素**；
- 总像素数最大为 **40,000,000**；
- 0.1 拒绝动画或多帧图片；
- 实际格式、最终扩展名和响应 Content-Type 均从解码器识别结果派生，不信任客户端声明。

### 6.2 处理顺序

```text
受控临时文件
→ 字节上限
→ 格式识别
→ 宽高与总像素限制
→ 完整解码
→ 单帧策略
→ 生成随机最终文件名
→ 写入最终文件与元数据
→ 任一后续失败清理临时/最终文件
```

异常样本至少覆盖 MIME 伪装、扩展名伪装、截断、损坏、签名正确但不可解码、超大像素、动画 WebP 和格式声明不一致。

### 6.3 接受的残余风险

0.1 不统一重编码图片，因此不承诺剥离全部 Exif、ICC、XMP、尾随数据或所有 polyglot 风险。当前以“真实格式、资源限制、完整解码、非公开临时区和公开引用控制”满足范围内安全要求；统一重编码属于后续独立图片处理能力，不暗中扩入 0.1。

## 7. 工具链与 CI

### 7.1 锁定基线

| 工具 | 0.1 基线 |
| --- | --- |
| JDK | 25 |
| Maven | 3.9.16，通过 Maven Wrapper 调用 |
| Node.js | 24.15.0 LTS |
| npm | 11.12.1 |
| CI runner | GitHub Actions `ubuntu-latest` |
| E2E 浏览器 | Playwright 锁文件对应的 Chromium |

Node 24 是当前 LTS，并满足现有锁文件中 `>=24.11.0` 的最高可见引擎要求。Maven 3.9.16 是当前稳定版；不采用 Maven 4 预览版本。

### 7.2 单一入口

CI 只负责准备锁定环境、执行 `npm ci`、安装锁文件对应的 Chromium、调用仓库质量入口和上传报告。所有质量规则继续由仓库脚本负责，CI 不复制第二套命令。

建议门禁顺序：

```text
1. 后端 clean verify
2. 空库迁移与表结构断言
3. OpenAPI 生成、关键 schema 断言和漂移检查
4. 前端 typecheck
5. Vitest
6. 前端 build
7. Playwright Chromium E2E
```

- 增加 Maven Wrapper，质量脚本不得再依赖系统 `mvn`；
- CI 使用 `npm ci`，质量脚本不自行安装或升级依赖；
- 将空库迁移和 12 表断言收敛为后端自动化验证，移除质量脚本对 Python 的隐藏依赖；
- 测试数据库、上传目录、端口和进程必须唯一且可清理；
- `scripts/quality.sh` 任一步失败立即失败，不允许跳过后继续交付。

数据库写入类验证由项目负责人在外部允许环境触发；当前 AI 角色只编写和静态审核对应测试与脚本，不执行。

## 8. 生成物与文档责任

- 行为或契约变化由研发工程师同步直接相关的能力包、接口说明和运行文档；
- `module-audit` 必须补齐 `AI_PROMPT.md`、`CONTRACT.md`、`TEST.md`；
- 研究报告不是架构事实源，本文件确认后才作为 V01-05 至 V01-10 的实现依据；
- `V0_1_RELEASE_PLAN.md` 继续由总设计师 / 总架构师独占维护；
- 实际测试结果只写已执行事实，禁止根据方案或代码存在推断为已通过。

## 9. 登录安全页面确认结论

推荐保留现有强制改密开关，但将产品入口从泛化的“系统设置”收窄为“登录安全”：

- 只允许维护 `force_password_change`；
- 不提供任意参数新增、删除、键编辑或通用参数能力；
- 将其确认为 0.1 “首次改密最小系统参数”的产品例外；
- 同步 0.1 页面清单和验收范围。

项目负责人已确认采用该推荐方案，不采用“移除页面并固定启用”的备选方案。

## 10. 反方审查

### 10.1 最强反对意见

1. 0.1 同时增加 OpenAPI、Vitest、Playwright、springdoc 和 TwelveMonkeys，会增加依赖与封版工作量。
2. 提交生成的规范和类型可能增加仓库噪声。
3. 图片不重编码仍然存在元数据和尾随内容风险。
4. E2E 依赖写测试库，与当前角色的数据库只读边界冲突。

### 10.2 回应

1. 这些能力分别直接对应已确认质量门禁、安全和验收要求；没有引入第二客户端、第二 E2E 或图片流水线。
2. 生成物用于代码审查和漂移门禁，必须标记为机器生成，不成为独立事实源。
3. 已明确残余风险和不包含范围；如果要求清除元数据，应作为独立范围调整，而不是隐藏实现。
4. 角色只编写验证资产，实际数据库写入由项目负责人在外部允许环境触发，权限边界没有被扩大。

## 11. 完成与退出条件

以下条件均满足后，V01-04 标记完成并派发研发任务：

1. 项目负责人确认第 3 至第 7 节新增依赖与安全策略；
2. 项目负责人确认第 9 节“系统设置”页面选择；
3. 总设计师 / 总架构师把确认结果记录到发布任务总表；
4. 每个研发任务获得互不重叠的文件范围和可执行完成标准；
5. 未执行的兼容性验证继续保持可见，不被写成已验证事实。
