# 工程实现约定与已知陷阱

> 状态：已确认（2026-08-03 沉淀，含多次踩坑记录）
> 目的：把已核实的技术事实与踩坑教训固化为规则材料，避免 AI/开发者凭记忆或习惯重写而重复犯错

## 1. 已核实的技术事实（Spring Boot 4.1 时代差异）

| 主题 | 事实 | 陷阱 |
| --- | --- | --- |
| JSON 序列化 | Boot 4 默认 **Jackson 3**（`tools.jackson` 包） | 用 `com.fasterxml.jackson` 注解不生效 |
| ID 字符串传输 | 64 位 ID 必须按字符串传输（防前端精度丢失），`@JsonSerialize(using = ToStringSerializer.class)` 必须标在 **getter** 上（Jackson 用 getter 序列化） | 标在字段上不生效 |
| Liquibase 集成 | Boot 4 拆为独立 starter：`spring-boot-starter-liquibase` | 只用 `liquibase-core` 时迁移完全不执行 |
| MyBatis-Plus 分页 | 3.5.9+ 分页插件拆到 `mybatis-plus-jsqlparser` 模块 | 只引 starter 时 `PaginationInnerInterceptor` 找不到 |
| 组件扫描 | `@SpringBootApplication(scanBasePackages = "com.internaladmin")` + 显式 `@MapperScan` 各模块 mapper 包 | 默认只扫 app 包，业务模块组件全部不生效 |
| 方法级权限异常 | Spring Security 7 抛 `AuthorizationDeniedException`（非旧 `AccessDeniedException`） | 处理器要覆盖它；多 `@RestControllerAdvice` 时用 `@Order(HIGHEST_PRECEDENCE)` 保证优先 |
| CSRF（SPA） | token 延迟生成（GET 不种 cookie），需 `CsrfCookieFilter` 每个响应种 `XSRF-TOKEN`；用 `CsrfTokenRequestAttributeHandler`（非 Xor）支持 cookie 直读 | 登录请求 403 |
| 手动登录 | 手动 `setAuthentication` 后必须显式 `HttpSessionSecurityContextRepository.saveContext(context, request, response)` | 会话不保持（me 401） |
| CORS | `HttpSecurity` 链必须显式 `.cors(Customizer.withDefaults())` + `CorsConfigurationSource` bean | 只有 bean 不启用 |
| SQLite 单写者 | 不支持并发写事务；审计等 REQUIRES_NEW 独立写会 `SQLITE_BUSY` | 成功审计随主事务；失败审计由外层在事务回滚后记录 |
| 上传大小 | Spring 默认 multipart 1MB，与应用层白名单不一致会 500 `MaxUploadSizeExceededException` | `spring.servlet.multipart.max-file-size` 需与应用层一致（10MB） |

## 2. 代码约定（写新代码前先核对）

- **包名**：数据对象用 `model.entity`（**禁止 `model.do`——`do` 是 Java 关键字**）；DTO 用 `model.dto`；跨模块契约放 `api`
- **Javadoc**：多行文本**直接写真实换行**，禁止用 `\n` 转义序列表示换行（工具参数转义后残留为字面量，污染注释）
- **ID 字段**：DTO 的 ID getter 加 `ToStringSerializer`；前端一律 `string` 类型
- **依赖方向**：业务模块按需依赖基础模块；跨模块只走 `api` 公开契约（FileQueryApi、AuditRecordApi），禁止碰对方 Mapper/表
- **权限**：接口 `@PreAuthorize` 用 `PermissionCodes` 常量；前端路由 meta.permission 仅体验层
- **多文件编辑**：一次工具调用只编辑一个文件；跨文件改动分开调用

## 3. 提交前的自查清单

写完后（不是"等会统一修"）立即检查：

1. `grep -rnF '\' + 'n'` —— 无字面量 `\n` 残留（用 python 按反斜杠+n 检查，grep 的 \n 匹配换行会误报）；
2. import 包名与当前目录一致（尤其 `model.entity`、`tools.jackson`）；
3. 新依赖是否已在父 POM `dependencyManagement` 锁定；
4. 新 Controller 是否遗漏 `@PreAuthorize` / 新 Mapper 是否加入 `@MapperScan` 列表；
5. 前后端契约（字段名/类型/ID 字符串）一致。

## 4. 教训来源

本文档由 2026-08-03 工程实现过程中多次踩坑沉淀而成，后续新发现的事实与陷阱持续追加。

## 5. 深度复盘：低级问题的根因与预防（2026-08-03）

### 5.1 六类低级问题及其根因

| 类别 | 代表问题 | 根因 |
| --- | --- | --- |
| 凭旧经验写新框架 | Liquibase starter、jsqlparser、Jackson 3、multipart 默认值 | 未先核实框架版本差异就动手 |
| 凭肌肉记忆写包名 | model.do、Jackson 2 注解反复 | 项目已变的事实未固化，靠记忆 |
| 明知约定不遵守 | ID 字符串传输（文档明确但实现为数字） | 写码时不对照已确认约定 |
| "写完再统一修" | Javadoc `\n` 字面量反复 7-8 次 | 自查延迟，允许先错后改 |
| 验证有盲区 | CORS（curl 不受限）、组件扫描（编译过功能无）、精度（python int 无损） | 编译过 ≠ 功能对；测试未覆盖真实路径 |
| 分析不完整就动手 | 图片回显改 3 次（img 不走 axios） | 未完整推演请求链路 |

### 5.2 核心教训：先核实后写，而非先写后验证

对任何**不确定的事实**（框架版本差异、项目约定、工具行为、环境假设），动手前先花一分钟核实：查官方文档、查项目已确认文档、查依赖、查现有代码；把核实结果固化为材料，而不是靠运行错误来发现。

### 5.3 工具环境事实（防重复踩坑）

- **工具参数转义**：JSON 参数中 `\\n` 解码后是反斜杠+n 字面量；要让 python 源码得到 `\\n`（匹配字面量），bash 命令参数需写四重反斜杠 `\\\\n`；
- **grep 的 `\n` 匹配换行符**（GNU 扩展）：检查字面量 `\n` 必须用 python（`'\\\\n' in text`）而非 grep；
- **Windows python 无 /tmp**：python 读不了 git bash 的 /tmp，跨工具传文件用项目内路径（如 data/）；
- **子 shell 后台进程会被清理**：`(npm run dev &)` 在命令结束后被杀，长驻服务必须用保持运行的后台任务；
- **curl 不受 CORS 限制**：CORS 验证必须看响应头（Access-Control-Allow-Origin）而非请求是否成功。

### 5.4 开发纪律（写入流程）

1. **新文件写前对照**第 2 节"代码约定"（包名/Jackson/ID 字符串/Boot 4 差异）；
2. **写完立即自查**（第 3 节清单），禁止"等会统一修"；
3. **一次工具调用只改一个文件**；跨文件改动分开调用；
4. **验证分级**：编译 → 启动 → 接口 → 真实浏览器路径（img/跨域/代理/精度）；
5. **新功能前先列"已确认事实"**：如上传先确认框架默认 multipart 限制再定白名单。
