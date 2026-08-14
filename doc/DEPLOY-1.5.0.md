# Flux Panel 1.5.0 增量部署指南

本文档适用于从官方 `1.4.3` 镜像升级本仓库的增强版。升级包含数据库结构变更，必须先备份。

## 新增功能

- 自动/手动明暗主题（自动模式：本地时间 06:00–18:00 浅色，其余时间深色）
- 公告发布、普通用户登录弹窗、不再显示、首页历史公告
- Cloudflare Turnstile 登录验证（空密钥时自动关闭）
- 转发每 15 分钟整链路 TCP 拨测与延迟展示
- 首页流量卡片合并并展示账户到期时间

## 1. 备份

```bash
cd /root
mkdir -p /root/flux-backup-$(date +%Y%m%d-%H%M%S)
BACKUP_DIR=$(ls -dt /root/flux-backup-* | head -1)
cp -a docker-compose.yml .env gost.sql flux-nginx.conf flux-origin.crt flux-origin.key "$BACKUP_DIR"/
set -a; . /root/.env; set +a
docker exec gost-mysql mysqldump -uroot -p"$DB_PASSWORD" --single-transaction --routines --triggers "$DB_NAME" > "$BACKUP_DIR/database.sql"
docker inspect springboot-backend vite-frontend > "$BACKUP_DIR/containers.json"
```

不要把 `.env`、数据库备份或 TLS 私钥提交到 Git。

## 2. 应用数据库迁移

```bash
set -a; . /root/.env; set +a
docker exec -i gost-mysql mysql -uroot -p"$DB_PASSWORD" "$DB_NAME" < doc/migration-1.5.0.sql
```

迁移只新增公告表、公告忽略表、Turnstile 配置和转发拨测字段，不应删除或覆盖现有用户、节点、隧道和转发数据。

## 3. 构建镜像

```bash
docker build -t flux-panel/backend:1.5.0 ./springboot-backend
docker build -t flux-panel/frontend:1.5.0 ./vite-frontend
```

后端 Dockerfile 使用 Java 21；前端 Dockerfile 使用 Node 20 和 Nginx。

前端镜像构建已改为 `npm install --legacy-peer-deps --no-audit --no-fund`。项目没有提交 lockfile，npm 严格 peer 解析会把 `@heroui/theme@2.4.19` 与 HeroUI 组件要求的 `>=2.4.24` 判定为冲突，`--legacy-peer-deps` 是官方镜像与本地 pnpm 构建都验证过的方式。

## 4. 配置 Turnstile

登录管理员后台，在“网站配置”中填写：

- `turnstile_enabled=true`
- Turnstile 站点密钥
- Turnstile 服务器密钥

只有开关为 true 且两项密钥均非空时才生效。服务器密钥只允许管理员接口读取，不会返回登录页。

也可在迁移完成后通过数据库写入，但不要把真实密钥写进迁移 SQL 或 shell 历史。

## 5. 灰度验证后切换

建议先用临时名称和端口运行后端，验证 `/flow/test` 返回 200，再修改 compose 镜像：

```yaml
backend:
  image: flux-panel/backend:1.5.0
frontend:
  image: flux-panel/frontend:1.5.0
```

现网前端使用自定义 `/root/flux-nginx.conf` 和 Cloudflare 源站证书，保留原 volumes 挂载：

```yaml
volumes:
  - /root/flux-nginx.conf:/etc/nginx/nginx.conf:ro
  - /root/flux-origin.crt:/etc/nginx/flux-origin.crt:ro
  - /root/flux-origin.key:/etc/nginx/flux-origin.key:ro
```

切换：

```bash
docker compose up -d --no-deps backend frontend
docker compose ps
curl -fsS http://127.0.0.1:20001/flow/test
curl -fsSI https://flux.m7mt.com
```

## 6. 验收清单

1. 管理员登录不弹公告；普通用户登录后弹最新公告。
2. “知道了”后页面切换不再弹；退出重新登录或关闭浏览器重开后再次弹。
3. “不再显示”后该用户在任何设备上均不再弹该公告。
4. 首页底部显示历史公告。
5. Turnstile 空配置时登录不显示小部件；完整配置后必须验证才能登录。
6. 转发卡片展示未测/延迟/异常状态；管理员可立即全部拨测。
7. 自动拨测每 15 分钟运行，节点离线不会中断其他转发拨测。
8. 首页流量卡显示“已用 / 总量”，独立卡片显示到期时间。

## 7. 回滚

```bash
# 将 compose 镜像改回：
# bqlpfy/springboot-backend:1.4.3
# bqlpfy/vite-frontend:1.4.3
docker compose up -d --no-deps backend frontend
```

数据库新增表和字段可保留，旧版程序会忽略它们。若必须完全回滚数据库，使用备份的 `database.sql` 在维护窗口恢复。

## 8. 发布记录

完整功能与改动总结见 [CHANGELOG-1.5.0.md](CHANGELOG-1.5.0.md)。

2026-08-15 已按本文档执行：

- 备份目录：`/root/flux-backup-20260815-002402`
- 新镜像：`flux-panel/backend:1.5.0`、`flux-panel/frontend:1.5.0`
- 迁移后线上验证：后端健康检查 200、Turnstile 公开配置生效、登录后公告历史/转发列表接口正常、定时拨测已写入整链路延迟。
- 回滚镜像仍保留在服务器：`bqlpfy/springboot-backend:1.4.3`、`bqlpfy/vite-frontend:1.4.3`。
