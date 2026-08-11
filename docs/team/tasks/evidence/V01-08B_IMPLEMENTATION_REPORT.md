# V01-08B 实施报告：发布原子性、公开文件与审计测试

> 状态：总设计师 / 总架构师验收通过
> 日期：2026-08-11
> 实施角色：研发工程师（甲）

## 1. 实施范围

仅修改以下文件：

- `backend/apps/app-server/src/test/java/com/internaladmin/app/SiteFlowTest.java`
- 本报告。

未修改生产代码、POM、Liquibase、数据库、前端、E2E、IAM、V01-08A 或 V01-10A 文件；未新增依赖。

## 2. 已实现的测试证据

`publicationRollbackKeepsCompleteAAndAuditsEveryOutcome` 以真实 HTTP 入口组织以下场景，已由运维在隔离 SQLite 上执行通过：

1. 保存并发布完整 A（站点名、简介、GRAPHITE、GRID_SPLIT、ABOUT 区块标题与内容）；公开响应逐字段断言完整 A。
2. 上传一个公开快照引用图片和一个未引用图片；发布后前者匿名读取为 200，后者为 404。
3. 保存所有关键字段与区块均机械不同的 B（站点名、简介、AZURE、BANNER_SPLIT、SERVICE 区块标题与内容）。
4. 使用 Spring Test 7.0.8 的 `@MockitoSpyBean` 包装真实 `HomepagePublicationSectionMapper`，只在 `insert(HomepagePublicationSectionDO)` 这个发布区块写入点以 `doThrow` 注入异常；该 Spy 默认每个测试方法后重置。
5. 断言发布请求为 HTTP 500，并以调用顺序锁定发布区块先删除、再尝试插入内容为 B 的区块；随后公开响应仍逐字段断言完整 A，防止发布主表已更新或区块已清空导致的半快照假绿。
6. 以 `AuditOperationMapper` 对固定主页目标 `1` 精确按 action/result 计数，断言本例分别新增一条 `SITE_PUBLISH/SUCCESS`、`SITE_PUBLISH/FAILURE` 和 `SITE_WITHDRAW/SUCCESS`。
7. 成功撤回后断言公开主页与原公开图片均为 404；管理端草稿仍精确返回 B 的字段和区块。

既有上传、匿名写入拒绝、主发布/撤回链仍保留；未重复 V01-06 的文件内容解码覆盖。

## 3. 静态机制核验

- 项目父 POM 锁定 Spring Boot `4.1.0`，本地解析的 `spring-test-7.0.8.jar` 实际包含 `org.springframework.test.context.bean.override.mockito.MockitoSpyBean`、`MockReset` 等类。
- 运维执行 `IamFlowTest` 已证实无参 `@SpringBootTest` 会因同包存在多个 `@SpringBootConfiguration` 而在上下文启动前产生歧义；`SiteFlowTest` 原先同样使用无参模式，属于同源阻断风险。
- 已将测试注解最小改为 `@SpringBootTest(classes = Application.class, properties = ...)`，显式绑定生产主应用 `com.internaladmin.app.Application`；原隔离 SQLite、管理员密码与上传目录 properties 保持不变。
- [Spring Framework 官方 MockitoBean/MockitoSpyBean 文档](https://docs.spring.io/spring/reference/7.0-SNAPSHOT/testing/annotations/integration-spring/annotation-mockitobean.html) 说明 `@MockitoSpyBean` 以 WRAP 方式包装唯一真实 Bean，并建议对 Spy 使用 `doThrow(...).when(spy)` 避免提前调用真实方法。
- [Spring Framework 官方 MockitoSpyBean API](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/test/context/bean/override/mockito/MockitoSpyBean.html) 说明默认重置策略为 `MockReset.AFTER`，不会将本例桩行为泄漏到其余测试。
- 静态注入点与生产代码一致：`SiteService.copySectionsToPublication()` 先删除发布区块，再将每个草稿区块调用 `publicationSectionMapper.insert(...)`；异常抛出至事务边界，`SiteController.publish()` 在回滚后记录失败审计。

## 4. 执行边界与真实验证窗口

研发阶段未执行 Maven `test`/`verify`、应用、Liquibase、SQLite 写入测试、服务或 `clean`，也未创建/连接数据库。格式化整改获得明确授权后，已额外执行一次不运行测试的 `test-compile`，结果见第 5 节。

运维在 Oracle JDK `25.0.4` 与 Maven Wrapper 下使用隔离测试配置执行精确目标类：

```bash
cd backend
./mvnw -Djava.version=25 -pl apps/app-server -am -Dtest=SiteFlowTest -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：退出 `0`，总耗时 `10.35s`；Surefire 报告为 `backend/apps/app-server/target/surefire-reports/TEST-com.internaladmin.app.SiteFlowTest.xml`，其中 `tests="4"`、`failures="0"`、`errors="0"`、`skipped="0"`。运维确认只使用隔离 `test-site.db` 与 `test-site-uploads`，未触及开发/外部数据库，未运行手工 SQL、服务或 `clean`。

## 5. 首个真实失败与格式化修复

运维首次执行 `SiteFlowTest` 时，`publicationRollbackKeepsCompleteAAndAuditsEveryOutcome` 在保存草稿阶段返回 HTTP 500。根因是 `draft()` 的 Java 字符串拼接未加括号：`.formatted(...)` 只作用于最后一个字符串片段，导致前 7 个 `%s`（包括 `heroFileId`）保留为字面量。

已将完整 JSON 拼接表达式置于括号中，再对完整模板调用 `.formatted(...)`；字段、样本、断言和生产代码均未改变。按本轮指令，后续仅执行无数据库格式化机械验证、`test-compile` 与差异检查，不执行 Maven `test`、SQLite、服务或 `clean`。

验证结果：

- 本地执行模式的 Java 格式化机械探针输出 `draft-format-mechanical-check=PASS`：返回 JSON 不含 `%s`，且站点名、简介、heroFileId、配色、布局、区块类型、标题和内容 8 个具名参数均落位；同时定向检索确认源码中完整拼接表达式被括号包围后才调用 `.formatted(...)`。
- `cd backend && ./mvnw -Djava.version=25 -pl apps/app-server -am test-compile`：退出 0，`app-server` 重编译 6 个测试源文件成功。该命令未执行测试；构建日志仅提示既有 `IamFlowTest` 的过时 API 编译警告。

## 6. 静态自我复核

- `git diff --check`：退出 0。
- 显式生产主应用绑定已静态核对为 `Application.class`，不再依赖测试扫描推断；原 properties 字面值未改。
- 定向检索：确认目标测试同时包含 A/B 字段差异、`@MockitoSpyBean`、`doThrow`、发布区块删除/插入顺序核验、三类审计、公开文件 200/404 与撤回后的草稿断言。
- 文件范围：本任务新增/修改仅为 `SiteFlowTest.java` 与本报告；共享工作区其他差异未触碰。
- [x] A/B 站点名、简介、配色、布局、区块类型、标题与内容均不同，并对公开 A 逐字段断言。
- [x] 受控异常仅在发布区块 Mapper `insert` 发生；未改生产开关、事务或依赖。
- [x] 保留完整 A 的公开基础字段和区块断言，覆盖删除旧区块之后的回滚风险。
- [x] 覆盖已引用、未引用和撤回后的匿名文件 HTTP 边界。
- [x] 覆盖撤回隐藏公开内容、但保留 B 草稿与区块。
- [x] 发布成功、发布失败、撤回成功三类审计均以固定目标 ID 和精确 action/result 增量断言。
- [x] 真实 Spring + SQLite 运行：运维 JDK 25.0.4 精确 `SiteFlowTest` 4/4 通过；Surefire XML 已复核。

## 7. AC-01 至 AC-07 独立复盘

| 完成标准 | 独立复盘证据 | 结论 |
| --- | --- | --- |
| AC-01 完整 A/B 隔离 | A/B 的站点名、简介、配色、布局、区块类型、标题和内容均不同；公开响应在 B 草稿保存后与失败发布后均逐字段锁定完整 A。 | 通过 |
| AC-02 发布失败原子性 | `@MockitoSpyBean` 仅在真实发布区块 Mapper 的 B 区块 `insert` 受控 `doThrow`；调用顺序锁定 delete 后 insert，HTTP 500 后公开基础字段和区块仍完整为 A。 | 通过 |
| AC-03 公开文件边界 | 已发布快照引用图片为 200；未引用图片为 404；撤回后原公开图片为 404。 | 通过 |
| AC-04 撤回保留草稿 | 撤回后公开主页为 404，管理端草稿仍逐字段返回 B 及其 SERVICE 区块。 | 通过 |
| AC-05 审计 | 对固定目标 `1` 的 `SITE_PUBLISH/SUCCESS`、`SITE_PUBLISH/FAILURE`、`SITE_WITHDRAW/SUCCESS` 分别以精确 action/result 计数增量断言。 | 通过 |
| AC-06 主链与拒绝回归 | 既有上传、匿名写入拒绝及发布/撤回主链仍在同一目标类；运维精确目标类 4/4 通过。 | 通过 |
| AC-07 范围与事实同步 | 本任务文件范围仅 `SiteFlowTest.java` 与本报告；生产、依赖、迁移和任务总表未改；`git diff --check` 退出 0；首次格式化失败和最终修复链均已保留。 | 通过 |

结论：从 AC-01 至 AC-07 独立复盘无未解决问题。V01-08B 尚待总设计师 / 总架构师验收，未由研发自行标记完成。
