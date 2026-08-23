# module-agent Gate B 能力

本分片只装配一个 Session+CSRF 保护的 Conversation SSE 运行入口：Controller 在线程内解析 `Authentication` 的用户 ID，通过 `IamActorApi` 固化不可变 `AgentRunContext`，异步线程不读取 `SecurityContextHolder`。运行态、用户/助手 History 由 module-agent 持有；运行步骤通过窄 `AiObservationRecorder` 写入观测模块。

Gate B历史验证曾使用 `warehouse_stock_by_item` 精确事实工具；当前SLICE-01正式模型入口已改为关键词库存与近期变化两个任务工具，精确工具不再注册给模型。工具上下文不进入提示词，适配器重新解析当前 IAM Actor 后调用 `WarehouseQueryApi`。完整Task持久化、其余工具、知识问答和断点续传仍不在本分片。
