# module-ai-observability Gate B 能力

只记录 `runId/stepId/parentStepId/sequence`、显式步骤/Attempt句柄、步骤类型、结构化检索/Tool标识、耗时、状态、错误码和可选 token 数；不接受或保存 Prompt、回复、Tool 参数/结果、Cookie、Key 或隐藏推理。通过 `AiObservationRecorder` 向 Agent 提供显式生命周期窄写契约，并由自身服务按有界批次清理90天前的观测数据。05B另拥有本人完整助手回答的固定枚举反馈及受 `ai:observability:view` 保护的有界概览/运行时间线查询；反馈按180天有界清理，管理查询不返回正文、参数或秘密。
