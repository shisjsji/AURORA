# 服务端（AURORA 的两个自建服务）

这两个服务跑在 `lingluandf.online` 那台香港 ECS 上，只监听 `127.0.0.1`，由主域 nginx 反代暴露：

| 目录 | 服务 | 端口 | 路由 | 内容 |
| --- | --- | --- | --- | --- |
| `feedback/` | aurora-feedback | 5002 | `/feedback/` | 用户反馈与设备档案（`data/report-*.jsonl`、`data/device-*.jsonl`），`daily.js` 每天 20:00 生成摘要 |
| `community/` | aurora-community | 5003 | `/community/` | 预设分享、喜欢、评论；SQLite 在 `data/community.db` |

**为什么放进仓库**：此前它们只存在于服务器上，没有历史、没有评审，机器出事就是全丢。这里是与线上一致的版本，改代码请「本地改 → 语法检查 → 传服务器 → 重启 → 跑测试」。

## 部署约定

1. 代码放 `/opt/apps/<服务名>/`，数据在 `data/`，systemd 单元用目录里的 `.service` 文件。
2. 服务只监听 `127.0.0.1`；新路由要在**主域** `sites-available/lingluan-blog` 的 8443 server 块加反代（只在子域加会在主域落到 SPA 兜底、POST 变 405——这个坑踩过四次）。
3. 社区服务的 `admin-token` 存在 `/opt/apps/aurora-community/admin-token`（600 权限），**不进仓库**。
4. 白名单字段：`feedback/server.js` 用 `DEVICE_FIELDS` / `REPORT_FIELDS` 清洗 payload。**App 侧新增上报字段时必须同步改白名单**，否则字段被静默丢弃（1.3.1 的 backend 字段就这样丢过一轮）。
5. 改动社区服务后跑一遍 `community/test.sh`（覆盖恢复码冲突、点赞建户、计数下限、feed 状态）。