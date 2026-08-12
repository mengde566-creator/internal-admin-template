# 模板文档索引

`docs/` 先服务于模板使用、派生和维护，再保存参考实现与历史交付证据。正式行为由 `requirements/` 中状态为“已确认”的需求定义，外部输入材料存放在 `references/`。

## 1. 模板使用与架构

- [PROJECT_VISION.md](PROJECT_VISION.md)：模板优先级、目标使用者、模块能力包和演进边界；
- [development/RUNBOOK.md](development/RUNBOOK.md)：本地启动、质量检查和运行验证；
- [architecture/BACKEND_MODULES.md](architecture/BACKEND_MODULES.md)：已确认的后端模块、依赖边界和微服务演进原则；
- [architecture/FRONTEND_STRUCTURE.md](architecture/FRONTEND_STRUCTURE.md)：已确认的单 Vue 应用结构、布局边界和未来客户空间方向；
- [architecture/AUTHENTICATION.md](architecture/AUTHENTICATION.md)：已确认的当前认证主路径和长期多端、多服务演进边界；
- [development/ENGINEERING_CONVENTIONS.md](development/ENGINEERING_CONVENTIONS.md)：代码约定、已知陷阱与提交前检查；
- [development/CAPABILITY_COMMON.md](development/CAPABILITY_COMMON.md)：模块能力包的通用规则。

## 2. 参考实现

- [database/V0_1_SCHEMA_PROPOSAL.md](database/V0_1_SCHEMA_PROPOSAL.md)：已确认并已落地的 0.1 表结构；
- [frontend/V0_1_UI_PROPOSALS.md](frontend/V0_1_UI_PROPOSALS.md)：已确认的 0.1 页面与视觉方案。
- [system/requirements/PUBLIC_SITE_REDESIGN.md](system/requirements/PUBLIC_SITE_REDESIGN.md)：已确认且已实现的公开主页布局与内容区块需求；
- [system/api/iam/](system/api/iam/)：IAM 创建与删除接口的字段、错误语义和前后端对齐清单；
- [system/api/OPENAPI_CONTRACT.md](system/api/OPENAPI_CONTRACT.md)：参考应用的 OpenAPI 单一事实源与生成链。

参考实现用于证明模板能力，不定义模板必须长期提供的业务形态。`module-site` 与前端 `modules/site` 属于参考业务；模板通用能力与参考业务的边界见架构文档。

## 3. 开发方法与治理

- [planning/WORKING_MODEL.md](planning/WORKING_MODEL.md)：人机分工、材料体系和装配方法的草稿；
- [planning/REQUIREMENTS_ROADMAP.md](planning/REQUIREMENTS_ROADMAP.md)：需求分析阶段路线图；
- [ai/REQUIREMENT_GUIDE.md](ai/REQUIREMENT_GUIDE.md)：需求漏斗；
- [ai/ACCEPTANCE_GUIDE.md](ai/ACCEPTANCE_GUIDE.md)：验收引导；
- [team/VERSION_DELIVERY_PROTOCOL.md](team/VERSION_DELIVERY_PROTOCOL.md)：按 L0/L1/L2 分级的版本交付与成本止损规则；
- [team/templates/VERSION_ENGINEERING_TASK_TEMPLATE.md](team/templates/VERSION_ENGINEERING_TASK_TEMPLATE.md)：仅供 L2 高风险任务使用的任务模板。

## 4. 历史与早期材料

- [team/tasks/README.md](team/tasks/README.md)：0.1 封版任务、失败历史和验收证据索引；
- [PROJECT_PLAN.md](PROJECT_PLAN.md)：长期早期规划初稿，不作为当前实现依据。

后续根据真实内容按需增加：

```text
docs/decisions/
docs/development/
docs/deployment/
```

禁止提前创建没有内容的空目录和占位文档。
