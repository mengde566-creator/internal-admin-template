# OpenAPI 契约生成

0.1 的 API 单一事实源是后端 Controller、DTO 与 `OpenApiContractConfig` 的显式映射。`openapi.json` 和 `frontend/src/generated/api-schema.ts` 是机器生成产物，禁止手工编辑。

## V01-05：无数据库生成与漂移检查

在仓库根目录执行：

```bash
cd tools/openapi && npm ci
cd ../..
./scripts/openapi-contract.sh generate
./scripts/openapi-contract.sh check
```

该脚本只运行 `NoDatabaseOpenApiContractTest`。它使用显式的专用测试应用装配全部 0.1 Controller、DTO、springdoc、`OpenApiContractConfig`、全局异常处理、Jackson 和 `SecurityConfig`；Controller 的 Service/API 协作者全部为 Mock。它不扫描主启动类、`AppDataSourceConfig`、Mapper、Service 实现或初始化器。

测试在请求 `/v3/api-docs` 前机械断言不存在 `DataSource`、Liquibase `SpringLiquibase` 和 MyBatis `SqlSessionFactory` Bean。MockMvc 返回的原始 springdoc JSON 先写入临时目录，再由 `normalize-openapi-json.mjs` 仅按对象键排序；数组顺序保持不变。随后运行关键路径、请求/响应、分页、字符串 ID、枚举、nullable、空响应和生成来源断言，并以独立工具目录中的 `openapi-typescript` 生成前端类型。

`check` 在临时目录重新生成两份产物并逐字比较；任何差异均失败。前端继续使用现有 Axios，用户和角色创建 API 直接索引生成的 `paths` 响应类型。

## V01-12：完整运行验证（未由本生成链替代）

下列验证不属于无数据库契约上下文，必须由 V01-12 在项目负责人允许的外部环境执行：Java 25、最终 fat jar、完整主应用上下文、真实 Security 链、Liquibase/数据库、`OpenApiContractTest` 与端到端运行验证。V01-05 的生成证据不能被描述为这些完整运行证据；V01-12 未通过时 0.1 仍不得发布。
