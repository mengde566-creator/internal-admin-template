---
name: user-scenario-delivery
description: Plan, delegate, implement, or review user-facing functionality and interaction fixes in internal-admin-template from real user tasks through observable acceptance. Use for application features, UI interactions, assistant conversations, and their development handoffs; do not use for documentation-only changes, internal-only maintenance, or routine service commands.
---

# 用户任务驱动交付

同一真实用户任务必须贯穿功能确认、任务派发、研发实现和交付验收。技术成功不能代替用户完成任务。

## 先明确用户场景

- 明确使用者、实际目标、已知信息、操作顺序、最终可见结果，以及直接相关的失败或恢复方式。
- 不要求用户提供内部 ID、技术参数或无法获取的信息；不把开发术语当作产品提示。
- 只读取与当前用户任务相关的已确认需求、场景、实现和测试；区分已有能力、本轮缺口与非目标。

## 派发和实现使用同一条任务链

- 任务说明必须包含：谁在什么场景下做什么、当前问题、预期结果、允许修改范围和验证方式。
- 用户任务先于类名、接口名、协议或技术修复方案；简单问题也至少说明场景、问题和正确结果。
- 缺陷先增加真实触发路径的场景测试，确认旧实现失败，再做最小连贯修复；已有实现满足场景时只补必要验证。
- 测试样本必须直接对照真实生产者的字段、身份复用、事件顺序和状态变化，禁止用虚构数据形状制造通过。
- 测试直接驱动用户操作，并断言用户可见结果、连续步骤和与问题直接相关的异常；元素存在、HTTP 成功、Mock 成功和测试总数不等于任务完成。

## 按用户结果完成验收

- 由执行者先按真实用户操作完成走查，再交付；不要让项目负责人承担首次发现明显缺陷的工作。
- 只覆盖当前场景关联的多轮、重复点击、等待、失败恢复、权限或响应式变化；不要扩大为无关专项。
- 复用已有测试和正常入口；必要的正式模型调用要有明确场景和预算，不进行无意义探测。
- 不新增审批、角色、报告或架构层；同一问题仍未解决时重新核对用户目标、失败证据和直接根因。
