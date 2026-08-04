# AI_PROMPT.md（模板）

> 复制后按模块实际填充；本文件是开发本模块时 AI 必须加载的提示词。

## 模块定位

（一句话：本模块解决什么问题，0.1 内提供哪些能力）

## 硬性约束（必须遵守）

### 结构

（包结构约定：controller/service/mapper/model.entity/model.dto/api）

### 依赖方向

（依赖哪些基础模块/其他模块；禁止依赖谁）

### 数据对象

（DO/DTO 规范：后缀、包名、ID 字符串传输、Jackson 3 注解位置等）

### 权限与审计

（权限编码定义位置、@PreAuthorize 用法、审计写入契约）

### 质量

（Javadoc 要求、写完自查、质量门禁命令）

## 本模块已知踩坑

（从开发复盘补充：数据库差异、框架差异、易错点——每个坑写「现象 + 根因 + 正确做法」）

## 禁止事项

（物理删除有审计引用的数据、修改已发布变更集、跨模块访问对方 Mapper/DO、删除数据库等）

## 开发新功能步骤

1. 对照 DATA_CONTRACT 确认表/字段（变更需新增 Liquibase 变更集）
2. 先查现有代码是否已有相同能力（避免重复逻辑）
3. 实现：DTO → Service（含 Javadoc）→ Controller（@PreAuthorize）→ 前端
4. 写完立即自查（ENGINEERING_CONVENTIONS §3）
5. 按 TEST.md 覆盖用例验证
