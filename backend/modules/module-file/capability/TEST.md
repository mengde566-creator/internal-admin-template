# module-file 测试清单

> 最后核对：2026-08-10（V01-06 无数据库自动化测试已补齐）。

## 自动化测试边界

`FileStorageServiceTest` 只构造 `FileStorageService`、真实 JDK 临时目录和 Mock `FileAssetMapper`；不启动 Spring 应用、Liquibase、MyBatis 或数据库。所有失败场景均断言 Mapper 不调用且临时目录没有常规文件残留；Mapper 插入失败额外断言已产生的最终文件被清理。

WebP 正常样本来自 TwelveMonkeys 官方仓库 tag `twelvemonkeys-3.14.0` 的 `imageio/imageio-webp/src/test/resources/webp/small_1x1.webp`，SHA-256 为 `2f34799482dd5349b549d113fdaa188714d9737fe414e71541b752627bedbde3`，仅用于验证解码器路径；其 BSD-3-Clause 版权与许可文本保留在测试源码中。动态 WebP 拒绝样本由该静态 VP8 帧按 WebP RIFF 动画容器规则组合为双帧，仅用于验证单帧策略。

## 必测用例

| # | 场景 | 预期 | 自动化状态 |
| --- | --- | --- | --- |
| 1 | 真实 JPEG 上传，客户端扩展名为 `.jpeg` | JDK ImageIO 解码通过；元数据为 `image/jpeg`，最终路径为系统生成 `.jpg` | ✅ |
| 2 | 真实 PNG 上传 | JDK ImageIO 解码通过；元数据和最终扩展名均为 PNG | ✅ |
| 3 | 真实 WebP 上传 | TwelveMonkeys ImageIO 3.14.0 解码通过；元数据和最终扩展名均为 WebP | ✅ |
| 4 | MIME 伪装 | 实际 PNG 声明为 JPEG 被拒绝；Mapper 不调用、无残留文件 | ✅ |
| 5 | 扩展名伪装 | 实际 PNG 使用 `.jpg` 被拒绝；Mapper 不调用、无残留文件 | ✅ |
| 6 | 截断/签名正确但不可解码 PNG | 被完整解码检查拒绝；Mapper 不调用、无残留文件 | ✅ |
| 7 | 非图片内容 | 被真实格式识别拒绝；Mapper 不调用、无残留文件 | ✅ |
| 8 | 单边超过 8192 或总像素超过 40,000,000 | 在完整解码前按资源限制拒绝；Mapper 不调用、无残留文件 | ✅ |
| 9 | 双帧 WebP | 被单帧策略拒绝；Mapper 不调用、无残留文件 | ✅ |
| 10 | 实际字节数超过 10MB | 写临时文件时按实际流字节拒绝；Mapper 不调用、无残留文件 | ✅ |
| 11 | Mapper 元数据插入失败 | 已移动到最终路径的文件被删除，不留临时/最终文件 | ✅ |

## 保持的既有契约

- 上传仍返回字符串 `fileId`；
- 管理端读取仍使用 `FileStorageInfo.contentType` 作为响应 Content-Type，因此成功上传写入的是解码器派生值；
- 读取和公开引用权限边界未在 V01-06 修改；
- 未承诺重编码、剥离 Exif/ICC/XMP、尾随数据或全部 polyglot 风险。
