# V01-06 文件上传真实内容校验与失败清理：实施报告

> 状态：研发自审通过，待总设计师 / 总架构师验收；本报告不改变任务书“进行中”状态。
> 实施日期：2026-08-10
> 执行角色：研发工程师（甲）

## 1. 当前结论与实现范围

`FileStorageService` 已从“信任客户端 MIME 与扩展名、直接写入最终路径”改为：受控临时文件 → 实际字节数限制 → ImageIO 真实格式、尺寸、像素、单帧和完整解码校验 → 客户端声明一致性校验 → 解码器派生的随机最终路径与 Content-Type → 元数据登记。验证、移动或 Mapper 登记任一步失败，都会删除临时及已经创建的最终文件；清理失败不会被吞掉。

JPEG、PNG 使用 JDK ImageIO；WebP 由 `com.twelvemonkeys.imageio:imageio-webp:3.14.0` 的标准 ImageIO SPI reader 解码。客户端 MIME 和扩展名只参与一致性校验，最终 `relativePath` 扩展名和 `contentType` 都由实际解码结果决定。

本次只修改 V01-06 任务书第 5 节授权的依赖管理、module-file 代码/测试/能力包和本报告。共享工作区原有的 `FileController.java` 及其他模块未提交差异保持原样，未整理、恢复或覆盖。

## 2. 实际修改

| 文件 | 实际修改 |
| --- | --- |
| `backend/pom.xml` | 锁定 TwelveMonkeys ImageIO 版本 `3.14.0`，在 dependencyManagement 中管理 `imageio-webp`。 |
| `backend/modules/module-file/pom.xml` | 引入受管理的 `imageio-webp` 和现有 Spring Boot 测试依赖。 |
| `backend/modules/module-file/src/main/java/com/internaladmin/module/file/service/FileStorageService.java` | 实现真实 ImageIO 解码、资源限制、临时文件流程、声明一致性校验、解码器派生元数据及失败清理。 |
| `backend/modules/module-file/src/test/java/com/internaladmin/module/file/service/FileStorageServiceTest.java` | 新增 12 个无数据库单元测试，使用真实临时目录与 Mock `FileAssetMapper`；本轮整改将 8 条安全拒绝用例锁定为精确错误消息，并加入 WebP SHA-256 与双帧数机械断言。 |
| `backend/modules/module-file/capability/AI_PROMPT.md`、`CONTRACT.md`、`TEST.md` | 将能力包从旧双白名单/手工验证描述同步为当前真实实现、测试边界和剩余风险。 |

### WebP 正常样本的可追溯性

正常 WebP 样本固定为 TwelveMonkeys 官方仓库 tag `twelvemonkeys-3.14.0` 的 [`imageio/imageio-webp/src/test/resources/webp/small_1x1.webp`](https://github.com/haraldk/TwelveMonkeys/blob/twelvemonkeys-3.14.0/imageio/imageio-webp/src/test/resources/webp/small_1x1.webp)。SHA-256：`2f34799482dd5349b549d113fdaa188714d9737fe414e71541b752627bedbde3`，测试会机械断言该值，常量漂移会失败。样本仅用于验证 TwelveMonkeys 的正常 WebP 解码链，不是产品资源；其 BSD-3-Clause 版权和完整许可文本保留在测试源码注释中，并可在上游 [`LICENSE.txt`](https://github.com/haraldk/TwelveMonkeys/blob/twelvemonkeys-3.14.0/LICENSE.txt) 核对。双帧 WebP 拒绝样本仅以该已追溯静态 VP8 帧按 RIFF 动画容器规则组合；测试先机械断言实际帧数为 2，再断言生产路径精确拒绝“仅支持单帧图片”。

## 3. AC-01 至 AC-07 独立自我复盘

| 完成标准 | 复盘证据与结论 |
| --- | --- |
| AC-01 标准依赖与编译 | `./mvnw -Djava.version=17 org.apache.maven.plugins:maven-dependency-plugin:3.9.0:get -Dartifact=com.twelvemonkeys.imageio:imageio-webp:3.14.0` 退出 0；最终 `./mvnw -Djava.version=17 -pl modules/module-file dependency:tree -Dincludes=com.twelvemonkeys.imageio:imageio-webp` 退出 0，树显示 `imageio-webp:3.14.0:compile`；`./mvnw -Djava.version=17 -pl modules/module-file -am test-compile` 退出 0。未使用 `--force`、跳过依赖校验或其他绕过参数。 |
| AC-02 真实格式决定存储事实 | `storesJpegUsingDecoderDerivedExtensionAndContentType`、`storesPngUsingDecoderDerivedExtensionAndContentType`、`storesWebpUsingTwelveMonkeysReader` 均以实际字节上传，断言 Mapper 元数据 Content-Type 和随机最终路径扩展名为解码器结果。 |
| AC-03 资源与完整解码 | 测试覆盖实际流字节 `>10MB`、单边 `8193`、总像素 `8000 × 5001`、双帧 WebP，以及 PNG 签名/IHDR 正确但完整解码失败；每条均锁定对应精确原因，尺寸/像素必须为“图片尺寸或总像素超过限制”，不会因后续解码失败而假绿。生产代码先读取资源信息、要求一帧，再 `read(0)` 完整解码。 |
| AC-04 伪装和损坏拒绝 | MIME/扩展名伪装精确断言“图片内容与声明的类型或扩展名不一致”；截断 PNG 为“图片内容无法完整解码”；非图片为“仅支持 jpg/jpeg/png/webp 图片”；双帧为“仅支持单帧图片”；超字节为“文件大小不能超过 10MB”。这些用例同时断言 `BusinessException`、Mapper 零交互及无常规文件残留。 |
| AC-05 失败清理 | 所有拒绝路径使用真实 `@TempDir` 检查没有常规文件；`removesFinalFileWhenMetadataRegistrationFails` 让 Mock Mapper 抛出异常，断言最终文件已删除。生产清理同时覆盖临时和最终路径，并使清理 I/O 失败可见。 |
| AC-06 既有契约与能力包 | 上传方法仍返回 `Long` ID，由现有 Controller 保持字符串 ID 响应契约；读取端仍消费 `FileStorageInfo.contentType`，因此成功上传的响应类型为解码器派生值。能力包三个文件已同步；未修改读取/公开引用/Security/IAM/前端契约。 |
| AC-07 早期门禁、当前事实和范围 | 已在实现前完成标准依赖解析和目标模块 `test-compile`；最终再次执行编译、测试、差异检查和过期措辞/数据库基础设施扫描。`git diff --check` 退出 0；在本任务测试和 `FileStorageService` 中检索 `SpringBootTest`、`DataSource`、`Liquibase`、`SqlSessionFactory`、`@Sql` 无匹配；能力包中检索旧“待补充/均已手动验证/双白名单/直接落最终路径”无匹配。 |

### 最终测试命令与结果

```text
cd backend && ./mvnw -Djava.version=17 -pl modules/module-file -am test
```

2026-08-10 本轮整改后退出 0：`FileStorageServiceTest` 共 12 项，0 failure、0 error、0 skipped。测试总数保持 12 项：新增的是现有 8 条拒绝测试的精确原因断言，以及两个 WebP 固定事实断言，而不是新增生产行为或绕过性测试。该命令只运行 Maven 反应器中的无测试依赖模块及 module-file 单测；没有启动主应用、Liquibase、MyBatis 或数据库。JDK 17 输出仍有 Mockito/Byte Buddy 动态 agent 的未来兼容警告；没有通过跳过测试规避。

本轮测试后已执行 `git diff --check`，退出 0；同时检索测试源码，确认 8 条关键拒绝路径均有 `ErrorCode` 加精确消息断言，WebP 同时存在 SHA-256 与 `getNumImages(true) == 2` 断言。整改范围仅为 `FileStorageServiceTest.java` 与本报告，未修改生产实现。

## 4. 受限验证、未执行项与残余风险

- 未执行主应用、Liquibase、SQLite/任何数据库测试或数据库连接；这些均为本任务禁止项。
- 未执行 Maven `clean`，未删除共享 `target`，未提交或推送。
- 本轮在当前 JDK 17 完成模块编译和单测；JDK 25、打包后的完整应用启动/接口级验证及更广泛回归仍留给 V01-12，不能表述为已通过。
- 总设计师独立以 JDK 25 执行相同精确 module-file 测试为 12/12 通过，但同样出现 Mockito 动态 agent 的未来兼容警告；这是外部复核事实，不替代 V01-12 的完整运行验证，也未在本轮通过隐藏或规避该警告。
- 按已确认架构边界，未重编码，也不承诺剥离 Exif、ICC、XMP、尾随数据或消除所有 polyglot 风险。
- `dependency:tree` 在未加 `-am` 的单模块视图中提示内部 SNAPSHOT POM 不在本地仓库；命令仍退出 0 且明确显示本次 WebP 直接依赖。随后的 `-am test-compile` 与 `-am test` 在完整所需反应器中均退出 0。

## 5. 历史与验收整改记录（均已关闭，不是当前结论）

第一次测试使用的 WebP Base64 片段不能被 TwelveMonkeys 正常解码，已按要求跨对话报告 `[V01-06][阻塞]`，未降低完整解码断言，也未把来源不明片段作为固定样本。随后改用上述官方 tag 固定样本，记录精确路径、SHA-256、BSD-3-Clause 许可证及测试用途，并重新完整执行 12 项 module-file 无数据库测试（退出 0）。

总设计师随后独立复现生产实现正确，但指出原测试只断言 `ErrorCode`，可能由其他拒绝路径导致假绿。本轮仅整改测试与本报告：将每条关键拒绝用例锁定到精确消息/规则原因，固定 WebP 样本增加 SHA-256 断言，双帧样本增加实际帧数为 2 的断言，并修正上游许可证链接为 tag 下的 `LICENSE.txt`。生产实现未改动。

新增双帧帧数断言的首次本地复测曾因测试辅助代码错误调用 `ImageIO.createImageInputStream(byte[])` 得到 null 而退出 1；该问题已立即跨对话报告。本轮仅将测试改为显式内存 `ImageInputStream`，随后完整 JDK 17 module-file 测试退出 0。该失败是测试构造问题，不是生产单帧校验的替代通过或失败。

## 6. 研发自审结论

本轮逐项复核后无未解决问题：8 条关键拒绝路径均锁定到预期规则消息，WebP 固定样本和双帧事实均有机械断言，JDK 17 完整 module-file 测试为 12/12 通过。现再次提交总设计师 / 总架构师独立复验；验收前，研发工程师不自行将 V01-06 标记为完成。
