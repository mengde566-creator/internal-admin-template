# module-agent 能力包

## 1. 定位与非目标

SLICE-00 已通过 Gate A、Gate B，提供 Agent 默认关闭、DeepSeek 纵向链、一个仓储只读 Tool、Session+CSRF SSE、History、运行终态/有界重试和最小观测。多轮 Task、完整 Tool 集合、前端入口和管理页仍未实现。

SLICE-01 第一开发块已落地服务端 Conversation/History 契约：Conversation ID 由服务端生成，提供本人有界分页与稳定顺序 History，Run 请求正式使用 `text`，运行必须引用已存在且归属当前用户的 Conversation，不再懒创建。Task、澄清、多轮 Memory 和 Vue 入口仍未实现。

## 2. 特有约束

- Agent 默认关闭；关闭时不创建 ChatModel、EmbeddingModel、知识数据源或对话入口。
- 开启时模型固定 `deepseek-v4-flash`，Spring AI 内建 RetryTemplate 最大尝试为 1；普通流式探针不把隐藏推理写入任何项目数据。
- `app.ai.*` 由唯一强类型 `AiProperties` 绑定并由启动校验器一次性校验。

## 3. 公开与跨模块契约

`GET /api/ai/capabilities` 只返回 `enabled`、`availableAdapters`、`uiModes`、`features`；关闭时 `enabled=false` 且其余字段为空数组，开启后仅对具有 `warehouse:read` 的当前 Session 返回 `warehouse`、三种 UI 模式和本阶段已实现特性，其余已认证用户的能力数组为空。不返回 Provider、模型、地址、密钥或权限集合。

## 4. 数据所有权

module-agent 持有 Conversation、Run、Message 和 SSE 编排；运行观测由 module-ai-observability 持有。模型调用仅由显式启用的 Provider Bean 使用。

## 5. 依赖与组合

依赖 `module-knowledge` 的公开配置类型和基础 Web 能力；不依赖仓储内部实现，不引入第二 AI 框架或前端运行时。

## 6. 装配与裁剪

由 `app-server` 装配；`AgentConfiguration` 始终注册配置属性与能力 Controller，Provider 配置仅在 `app.ai.enabled=true` 时生效。移除该模块不会改变既有仓储模块。

## 7. 风险与验证入口

`AiConfigurationValidatorTest`、`AiCapabilitiesControllerTest`、Agent 运行/协议/并发测试和适配器测试覆盖默认关闭、配置校验、SSE、History、终态、重试、观测与仓储只读 Tool；Gate A、Gate B均已取得模块及本地真实Provider验证。

## 8. 素材与许可证

无外部素材。
