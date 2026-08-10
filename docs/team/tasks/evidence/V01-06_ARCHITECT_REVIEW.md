# V01-06 总设计师独立验收记录

> 状态：第二次验收通过<br>
> 验收日期：2026-08-10<br>
> 验收角色：总设计师 / 总架构师

## 1. 核心结论

V01-06 第二次独立验收通过，任务已完成。生产实现、机制特异性测试、固定样本、失败清理、文档同步和文件边界均满足 AC-01 至 AC-07；完整应用、fat jar、SPI 打包发现和更广回归仍按原边界留给 V01-12。

下列第 2 至第 4 节保留首次验收的历史证据、当时阻塞项和整改要求；这些阻塞项已由第 5 节关闭，不代表当前状态。

## 2. 首次验收已独立复现（历史）

- JDK 17：`./mvnw -Djava.version=17 -pl modules/module-file -am -Dtest=FileStorageServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`，退出 0，12/12 通过。
- JDK 25.0.4：同一精确测试退出 0，12/12 通过；Mockito 仍报告动态加载 Byte Buddy agent 的未来兼容警告，该项纳入 V01-12，不在本轮隐瞒或误报为已消除。
- 从 TwelveMonkeys 官方 tag `twelvemonkeys-3.14.0` 下载的 `small_1x1.webp` 为 94 字节，SHA-256 为 `2f34799482dd5349b549d113fdaa188714d9737fe414e71541b752627bedbde3`，与测试常量及实施报告一致。
- 以当前测试方法生成的动态 WebP 为 224 字节；TwelveMonkeys reader 报告 2 帧。调用真实 `FileStorageService` 时抛出“仅支持单帧图片”，`FileAssetMapper` 调用数为 0，临时根目录常规文件数为 0。
- 完整测试已覆盖 Mapper 插入失败后的最终文件清理；工作区差异检查未发现研发甲越过 V01-06 文件所有权。
- `git diff --check` 退出 0；未启动应用、Liquibase、MyBatis 或数据库测试，未执行 `clean`。

## 3. 首次验收阻塞项（已关闭）

1. 拒绝测试共用的 `assertRejected` 只断言 `BUSINESS_REJECTED`、Mapper 零交互和无文件残留，没有断言具体规则原因。
2. 超单边和超总像素样本是仅含 PNG 头的截断文件。即使未来删除尺寸限制，它们仍可能因完整解码失败而通过现有测试，不能机械证明 AC-03。
3. 动态 WebP 测试没有在测试代码中锁定“双帧”属性或“仅支持单帧图片”的精确原因；当前行为经人工复现正确，但回归证据仍可漂移。
4. 固定 WebP 样本只在注释中记录 SHA-256，没有自动校验；实施报告链接到不存在的上游 `LICENSE`，实际文件名为 `LICENSE.txt`。

## 4. 首次验收最小整改要求（已完成）

- 各拒绝用例断言目标规则的精确错误消息或等价的机制特异性结果；
- 固定 WebP 样本增加 SHA-256 自动断言，动态样本锁定双帧属性或精确单帧拒绝原因；
- 修正许可证链接并更新实施报告中的整改和复验事实；
- 重新执行 JDK 17 module-file 完整单测与 `git diff --check`，研发自审后重新申请验收。

本次发现已同步进入 `docs/team/VERSION_DELIVERY_PROTOCOL.md` 1.2，作为后续版本通用门禁，不只保留在本任务记录中。

## 5. 第二次独立验收

研发仅修改 `FileStorageServiceTest.java` 和实施报告，未改变已复现正确的生产实现。第二次验收结果如下：

- 8 条关键拒绝路径均同时断言 `BusinessException`、`BUSINESS_REJECTED`、精确错误消息、Mapper 零交互和无常规文件残留；尺寸/像素样本不再能由后续解码失败造成假绿。
- 固定 WebP 常量机械断言 SHA-256；动态 WebP 在进入生产调用前机械断言 `getNumImages(true) == 2`，随后精确断言“仅支持单帧图片”。
- 许可证链接已修正为 TwelveMonkeys tag 下的 `LICENSE.txt`；固定样本来源、版本、用途和许可保持可追溯。
- JDK 17 精确测试：12/12，退出 0。
- JDK 25.0.4 精确测试：12/12，退出 0；Mockito 动态 agent 未来兼容警告仍如实存在，保留 V01-12 处理。
- `git diff --check` 退出 0；没有运行应用、Liquibase、MyBatis、数据库测试或 `clean`，未发现文件所有权越界。

最终结论：AC-01 至 AC-07 全部满足，V01-06 验收通过，可由总设计师标记为“完成”。完整应用、fat jar、SPI 打包发现及更广回归仍按既定边界由 V01-12 验证，不被本结论冒充为已通过。
