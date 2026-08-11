# V01-12 总设计师 / 总架构师独立验收

> 结论：通过
> 日期：2026-08-12
> 固定 SHA：`90d4d313b3483f07237a6e0cf342953d980f20f5`

## 1. 核心结论

V01-12 发布级全量验证通过。固定 SHA 在干净 worktree 中完成锁定依赖安装、数据库完整质量、开发脚本真实生命周期、全新状态样本的 Chromium 3/3 和远端 GitHub Actions；源文件零变化，服务、端口、PID 与临时秘密均安全收尾。

## 2. 独立原始证据

| 范围 | 结果 | 独立核对 |
| --- | --- | --- |
| 干净来源 | 通过 | r3 worktree HEAD 精确等于固定 SHA，最终 `git status --porcelain` 为空 |
| 锁定依赖 | 通过 | `frontend` 与 `tools/openapi` 两份 `npm ci` 退出 0，lock 未改 |
| 完整质量 | 通过 | `quality.sh --database` 退出 0；IAM 12/12、Site 4/4、OpenAPI 1/1，零失败/错误/跳过 |
| 空库迁移 | 通过 | 生产实例日志记录 Liquibase Run=16、Total change sets=16；18080 已释放 |
| 开发生命周期 | 通过 | start/status 后 8080 health UP、5173 可用；首次 stop 退出 0，正常 graceful shutdown |
| 真实浏览器 | 通过 | 新 run ID 的 Chromium 3/3，`.last-run.json` 为 `passed`、`failedTests=[]` |
| 远端 CI | 通过 | [Database quality #2](https://github.com/mengde566-creator/internal-admin-template/actions/runs/31545501343) 对固定 SHA 为 Success，总时长 1m53s |
| 安全收尾 | 通过 | 8080/5173/18080 无监听，PID 文件不存在，r3 临时认证目录/运行器已删除，父 PTY 已清空秘密并退出 |

## 3. 失败链与修正评价

- 旧 SHA 暴露 `dev.sh stop` 约 2 秒窗口短于实测约 2.02 秒正常关闭；V01-12A 将上限最小调整为 10 秒，仍只发送 TERM、提前退出立即结束、超时失败并保留 PID。新 SHA 的真实 stop 首次退出 0；
- 运维前几轮出现任务外 admin flag 断言、错误 E2E 工作目录、临时运行器响应契约和秘密宿主错误。它们均被如实保留，没有改成产品失败或通过；最终轮用父 PTY、静态契约和 cwd 门禁消除复发；
- 最终轮从新的干净 worktree 完整重跑，不复用前轮局部结果，没有通过降低断言、手工 SQL、外部数据库、重跑远端工作流或修改固定 SHA 取得通过。

## 4. 发布边界

V01-12 证明固定 SHA 的工程验证通过，不等于已经合并 `main`、创建版本标签或对外发布。发布决定由 V01-13 验收矩阵和项目负责人最终确认。
