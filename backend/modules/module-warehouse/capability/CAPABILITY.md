# module-warehouse 能力包

> 通用工程规则见 [`CAPABILITY_COMMON.md`](../../../../docs/development/CAPABILITY_COMMON.md)。

## 1. 定位与非目标

提供物品、仓库、库位、精确库存余额、入库/出库/调拨/盘点和只读查询 API；不提供 AI 写操作、审批、订单、批次、成本、撤销或删除流水。

## 2. 特有约束

- 数量外部为十进制字符串，内部为四位缩放 BIGINT；超过四位、缩放溢出、加减溢出均拒绝。
- `requestId + fingerprint` 幂等；重复逻辑行、负库存、版本冲突和跨部门请求均明确拒绝。
- `wh_inventory_movement` 只追加；误录只能用新的盘点并通过 `correctedOperationId` 关联。
- 普通用户仅当前部门，SYSTEM_ADMIN 全部门；Service 重新校验可信范围。

## 3. 公开与跨模块契约

公开 `WarehouseQueryApi` 四个只读类型化方法和稳定 DTO；只依赖 IAM 的 `IamActorApi`/`DepartmentQueryApi` 与 AuditRecordApi，不暴露 DO、Mapper、Wrapper 或分页实现。

## 4. 数据所有权

本模块拥有 `wh_item`、`wh_warehouse`、`wh_location`、`wh_stock_balance`、`wh_inventory_operation`、`wh_inventory_movement` 六张表；跨模块只存部门/操作者标识，不建跨模块外键。

## 5. 依赖与组合

依赖 platform-kernel/web/data/security、module-iam、module-audit；app-server 装配 Mapper 与 Liquibase。IAM 通过 `DepartmentReferenceChecker` 调用本模块检查启用仓库引用。

## 6. 装配与裁剪

同步 Maven reactor、app-server 依赖和 Mapper 扫描、Liquibase 聚合、三个权限编码、Controller/OpenAPI、`frontend/src/modules/warehouse` 路由导航及其测试。移除本模块后 IAM 仍可在无检查器装配下构建。

## 7. 风险与验证入口

定向仓储测试覆盖缩放/溢出、幂等、CAS、事务回滚、调拨原子性、盘点竞态、权限范围、部门引用和不可变流水；无数据库测试覆盖公开 API 形状；前端组件、typecheck/build 与 OpenAPI 检查覆盖契约。

## 8. 素材与许可证

无外部视觉素材；前端复用项目现有组件和语义令牌。
