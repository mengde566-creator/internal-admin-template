# module-ai-observability Gate B 能力

只记录 `runId/stepId/parentStepId/sequence`、步骤类型、attempt、耗时、状态、错误码和可选 token 数；不接受或保存 Prompt、回复、Tool 参数/结果、Cookie、Key 或隐藏推理。通过 `AiObservationRecorder` 向 Agent 提供窄写契约，不提供管理页面或查询 API。
