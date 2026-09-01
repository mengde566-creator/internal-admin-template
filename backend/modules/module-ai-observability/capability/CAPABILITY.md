# module-ai-observability Gate B 能力

只记录 `runId/stepId/parentStepId/sequence`、显式步骤/Attempt句柄、步骤类型、结构化检索/Tool标识、耗时、状态、错误码和可选 token 数；不接受或保存 Prompt、回复、Tool 参数/结果、Cookie、Key 或隐藏推理。通过 `AiObservationRecorder` 向 Agent 提供显式生命周期窄写契约，并由自身服务按有界批次清理90天前的观测数据。05B另拥有本人完整助手回答的固定枚举反馈及受 `ai:observability:view` 保护的有界概览/运行时间线查询；反馈按180天有界清理，管理查询不返回正文、参数或秘密。
05C评测资产同样由本模块拥有：只登记固定版本资源和哈希，通过不携带expected值的窄执行契约接收生产链观测；无实际执行器时仅作STATIC_VALIDATION/NOT_EVALUATED，受控执行器产生的结果明确标记CALLBACK_ORCHESTRATION或PUBLIC_SERVICE_DETERMINISTIC，真实模型路由才标记END_TO_END_PROVIDER，禁止把fixture自洽报告为通过。按caseId保存稳定结果和门禁状态；不保存输入、回答、工具参数、知识正文或秘密，结果按180天有界清理。
