# module-agent Gate B 能力

本分片只装配一个 Session+CSRF 保护的 Conversation SSE 运行入口：Controller 在线程内解析 `Authentication` 的用户 ID，通过 `IamActorApi` 固化不可变 `AgentRunContext`，异步线程不读取 `SecurityContextHolder`。运行态、用户/助手 History 由 module-agent 持有；运行步骤通过窄 `AiObservationRecorder` 写入观测模块。

模型只获得 `warehouse_stock_by_item` 工具定义，工具上下文不进入提示词，适配器重新解析当前 IAM Actor 后调用 `WarehouseQueryApi`。Gate B 不包含 Task、Memory、第二个 Tool、前端入口、管理页或断点续传。
