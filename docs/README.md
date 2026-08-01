# 项目文档索引

`docs/` 记录项目自己产生的规划、架构、决策、数据库和部署说明。正式产品行为由 `requirements/` 中状态为“已确认”的需求定义，外部输入材料存放在 `references/`。

## 当前文档

- [PROJECT_PLAN.md](PROJECT_PLAN.md)：长期早期规划初稿，不作为 0.1 实现依据；
- [architecture/BACKEND_MODULES.md](architecture/BACKEND_MODULES.md)：已确认的后端模块、依赖边界和微服务演进原则；
- [architecture/FRONTEND_STRUCTURE.md](architecture/FRONTEND_STRUCTURE.md)：已确认的单 Vue 应用结构、布局边界和未来客户空间方向；
- [architecture/AUTHENTICATION.md](architecture/AUTHENTICATION.md)：已确认的当前认证主路径和长期多端、多服务演进边界；
- [planning/REQUIREMENTS_ROADMAP.md](planning/REQUIREMENTS_ROADMAP.md)：需求分析阶段路线图；
- [database/V0_1_SCHEMA_PROPOSAL.md](database/V0_1_SCHEMA_PROPOSAL.md)：待确认的 0.1 最小表结构；
- [frontend/V0_1_UI_PROPOSALS.md](frontend/V0_1_UI_PROPOSALS.md)：待确认的 0.1 页面与视觉方案。

后续根据真实内容按需增加：

```text
docs/decisions/
docs/development/
docs/deployment/
```

禁止提前创建没有内容的空目录和占位文档。
