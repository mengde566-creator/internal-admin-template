# 运行手册（RUNBOOK）：启动、检查与落地验证

> 版本：0.1
> 状态：生效
> 关联：AGENTS.md §2.2 执行顺序、ENGINEERING_CONVENTIONS.md 第 3 节自查清单
>
> **本文件解决的问题：系统的落地不是"代码写完"，而是"从开发到用户使用全程可跑、可查、可复现"。**
> 启动服务不是临场发挥，是标准操作。任何 AI 会话、任何开发者，启动服务必须走本手册与 `scripts/dev.sh`。

## 1. 核心原则

1. **启动只有一条路**：`./scripts/dev.sh start`，禁止用临时命令、`&` 后台、手动 java -jar 等临场方式；
2. **AI 会话环境（进程会被 Job Object 回收）**：本环境的任务结束会静默清理派生的所有进程（含 DETACHED_PROCESS），服务必须用**常驻后台任务**方式运行（任务保持运行，服务即保持）；`dev.sh` 面向真实终端/CI/开发者；
3. **启动必须验证**：`start` 后必须执行 `./scripts/dev.sh status` 确认全部就绪（包括依赖检查）；
3. **停止也有标准方式**：`./scripts/dev.sh stop`，禁止 taskkill 乱杀；
4. **每次代码变更后重启前，先确认构建产物是最新的**（见第 4 节检查清单）；
5. **未来每增加一个伴随组件**（数据库、Redis、对象存储、消息队列等），必须在 `dev.sh` 与第 3 节检查清单中同步增加检查项——**能启动 ≠ 能落地**。

## 2. 一键启动

```bash
./scripts/dev.sh start    # 启动后端(8080)与前端(5173)，日志写入 logs/
./scripts/dev.sh status   # 检查环境依赖与服务状态
./scripts/dev.sh stop     # 停止前后端
```

- 日志：`logs/backend.log`、`logs/frontend.log`（进程 PID 也在此目录）；
- 后端启动较慢（约 6-10 秒），`status` 会显示健康检查结果；
- 首次启动自动创建 `backend/data/internal-admin.db` 并执行 Liquibase 迁移；管理员初始密码输出到 `logs/backend.log`（或用环境变量 `APP_ADMIN_INITIAL_PASSWORD` 指定）。

## 3. 启动检查清单（status 覆盖项）

| 检查项 | 判定 | 未来扩展位 |
| --- | --- | --- |
| JDK | 存在且为 25 | — |
| Node.js | 存在 | — |
| 后端构建产物 | `backend/apps/app-server/target/*.jar` 存在且为最新 | 校验时间戳 vs 源码 |
| 前端依赖 | `frontend/node_modules` 存在 | — |
| SQLite 开发库 | 文件存在（不存在则首次自动创建） | 切换 MySQL/PostgreSQL/Oracle 后改为连接探活 |
| 后端服务 | 8080 健康检查 UP | — |
| 前端服务 | 5173 返回 200 | — |
| **Redis** | （未启用） | `redis-cli ping` |
| **外部数据库** | （未启用） | `nc -z <host> <port>` 或 JDBC 探活 |
| **对象存储** | （未启用） | 连通性 + 写读探测 |

> 规则：任何一行"未启用"变为"启用"时，必须同时补上对应检查项——这是硬性要求，不是建议。

## 4. 变更后的启动流程（提交前自查的一部分）

代码修改后需要重启验证时，按此顺序（写进自查清单）：

1. `./scripts/dev.sh stop`（标准停止，不残留进程）；
2. 确认构建产物最新：后端在 `backend/` 下执行 `./mvnw -DskipTests package`；需要无数据库质量验证时执行 `./scripts/quality.sh --no-database`，需要完整隔离 SQLite 质量验证时执行 `./scripts/quality.sh --database`。前端由 dev 模式即时生效，依赖安装统一使用锁文件 `npm ci`；
3. `./scripts/dev.sh start`；
4. `./scripts/dev.sh status` 确认后端 UP、前端 200、依赖检查全绿；
5. 再做功能验证（curl / 页面操作）。

## 5. 故障排查速查

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 端口被占用 | 上次启动残留 | `./scripts/dev.sh stop`（含 pkill 兜底）后重启 |
| 前端页面打开但接口 401 | 服务重启后 Session 失效 | 重新登录 |
| 上传/请求 500 | 后端未启动或构建产物过期 | status 确认 + 重新 package |
| 数据库文件被锁 | 后端进程残留 | stop 后确认无 java 进程再操作 |
| 初始密码找不到 | 日志被覆盖 | 删库重来前先看 `logs/backend.log`（**禁止先删库**，见 AGENTS 红线 §16.1） |

## 6. 与质量门禁的关系

- `scripts/quality.sh --no-database` 负责不启动应用、不连接数据库的静态与回归验证；`scripts/quality.sh --database` 在其基础上负责隔离 SQLite 集成、空库迁移启动与构建验证。两种模式均使用 Maven Wrapper，前端与 OpenAPI 工具依赖须预先以 `npm ci` 安装；
- `scripts/dev.sh` 负责"运行时验证"（依赖/启动/健康）；
- **两者一起构成落地验证**：quality 绿 + dev status 绿，才能声明"可交付"。
