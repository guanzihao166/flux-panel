# Flux Panel 1.5.0 变更与上线总结

本文档总结本次从本地开发、验证到现网发布的全部工作，作为 1.5.0 版本的变更记录和后续维护参考。

## 一、本次目标

根据需求完成以下改造，并在本地验证后发布到 flux.m7mt.com：

- 前端暗色模式，早晚自动切换，可手动调整
- 后台编辑公告、发布后普通用户登录弹窗，“知道了”/“不再显示”两种行为
- 首页下方展示历史公告
- 登录页集成 Cloudflare Turnstile，后台可配置密钥，空配置自动关闭
- 转发管理每 15 分钟定时拨测，展示整条链路延迟
- 首页总流量与已用流量合并为一张卡片，空出位置展示到期时间

## 二、前端实现

### 1. 暗色模式

- 新增 `theme-preference = auto | light | dark`，存储在 localStorage。
- 自动模式按本地时间 06:00–18:00 为浅色，其余时间为深色。
- 每分钟重算一次，切回前台时立即重算，避免长时间挂后台后主题过期。
- `index.html` 增加防闪烁内联脚本，首屏即应用正确主题。
- 导航栏新增三档主题切换器，管理端和 H5 布局均已接入。
- `safeLogout` 在清理 localStorage/sessionStorage 前保留主题选择。

### 2. 公告

- 新增后台公告管理页 `/announcement`：列表、新增、编辑、草稿、发布、删除。
- 管理端侧边栏、个人中心入口均已接入公告管理。
- 登录成功后在 H5/管理端布局挂载公告弹窗。
- 管理员永不弹窗。
- “知道了”只写入 sessionStorage：页面切换不重复弹，关闭浏览器重开或退出重新登录后再次弹出。
- “不再显示”调用后端持久化接口，并在本地缓存快速过滤；同一用户任何设备都不再弹。
- 首页底部新增“历史公告”区域，展示已发布公告。
- 公告管理页在非管理员访问时自动跳回仪表盘。

### 3. Cloudflare Turnstile

- 登录页动态加载 Turnstile 脚本并显式渲染小部件。
- 登录提交前强制校验验证码 token，失败/成功后重置小部件。
- 登录请求携带 `turnstileToken`。
- 登录页通过公开接口获取是否启用及站点密钥，未配置时完全不显示验证码。
- 类型声明、API 封装均已补齐。

### 4. 转发拨测展示

- 转发数据模型新增整链路延迟、拨测状态、拨测时间、拨测信息。
- 转发卡片展示未测/延迟正常/延迟偏高/异常状态。
- 单条转发可手动拨测，管理员可一键拨测全部转发。

### 5. 首页流量与到期时间

- 原“总流量”“已用流量”两块合并为一张“流量使用”卡片，带进度条。
- 新增独立“到期时间”卡片，兼容数字时间戳和字符串时间。

## 三、后端实现

### 1. 公告

- 新增 `announcement` 表和 `announcement_dismissal` 表。
- 新增实体、Mapper、Service、Controller。
- 接口：
  - `/api/v1/announcement/admin/list|save|delete`：管理员管理
  - `/api/v1/announcement/history`：历史公告
  - `/api/v1/announcement/pending`：当前用户待读公告，过滤已“不再显示”
  - `/api/v1/announcement/dismiss`：用户持久化“不再显示”
- 草稿状态、发布时间、删除时级联清理用户忽略记录均已处理。

### 2. Cloudflare Turnstile

- `vite_config` 新增 `turnstile_enabled`、`turnstile_site_key`、`turnstile_secret_key`。
- 公开接口 `/api/v1/config/login-security` 只返回“是否实际生效”和站点密钥，不返回服务器密钥。
- 管理员接口 `/api/v1/config/admin/list` 可读取全部配置用于编辑。
- 登录时若开关为 true 且两个密钥均非空，则调用 `https://challenges.cloudflare.com/turnstile/v0/siteverify` 服务端校验。
- 任一密钥为空或开关关闭时登录不校验，不显示小部件。
- 公开读取配置接口对 `turnstile_secret_key` 返回 403。

### 3. 转发拨测

- `forward` 表新增 `latency_ms`、`probe_status`、`probe_time`、`probe_message`。
- 端口转发：入口节点 TCP ping 目标，计算整条链路延迟。
- 隧道转发：入口→出口 + 出口→目标，多目标取成功完整链路均值。
- 定时任务 `@Scheduled(initialDelay = 60000, fixedDelay = 900000)`，每 15 分钟执行。
- 单条异常隔离，不会中断其他转发；仅拨测启用状态转发。
- 接口：`/api/v1/forward/probe`、`/api/v1/forward/probe-all`（管理员）。
- 手动全部拨测异步执行，接口立即返回。

### 4. 日志安全

- `LogAspect` 新增统一脱敏，覆盖 JSON 字段和 `key=value` 形式。
- 脱敏字段包含密码、JWT token、节点 secret、Turnstile token、Turnstile 服务器密钥等。
- 登录请求参数和返回参数不再把敏感值写入日志。

### 5. 拦截器放行

- `WebMvcConfig` 放行 `/api/v1/config/list` 和 `/api/v1/config/login-security`，保证登录页能读取公开配置。

## 四、数据库变更

- 新增迁移脚本 `doc/migration-1.5.0.sql`，兼容 MySQL 5.7。
- 同步更新 `gost.sql` 全量结构。
- 变更内容：
  - `forward` 增加 4 个拨测字段
  - 新建 `announcement`
  - 新建 `announcement_dismissal`，含 `(announcement_id, user_id)` 唯一索引
  - `vite_config` 增加 3 条 Turnstile 配置，默认 `turnstile_enabled=true`、两把密钥为空
- 迁移只新增，不删除或覆盖现有业务数据。

## 五、构建与发布过程

### 1. 本地构建

- 前端使用 pnpm 补全直接依赖，`pnpm run build` 通过。
- 后端因本机无 Maven/Docker，先打包源码，在服务器临时 Maven 容器编译验证通过。

### 2. 镜像构建

- 发现前端 Dockerfile 在无 lockfile 时 `npm install` 会因 HeroUI peer 依赖冲突失败。
- 已改为 `npm install --legacy-peer-deps --no-audit --no-fund`，前后端镜像均构建成功。
- 新镜像：
  - `flux-panel/backend:1.5.0`
  - `flux-panel/frontend:1.5.0`

### 3. 线上发布

- 发布前完整备份到 `/root/flux-backup-20260815-002402`，包含数据库、compose、`.env`、nginx 配置、Cloudflare 源站证书和容器元数据。
- 执行 `doc/migration-1.5.0.sql`，验证拨测字段、公告表和 Turnstile 配置均已写入。
- 真实 Turnstile 密钥通过一次性 SQL 文件写入线上 `vite_config`，执行后立即删除临时文件；密钥未写入 Git、未进入日志、未留在服务器 `/tmp`。
- `/root/docker-compose.yml` 镜像切换到 1.5.0，仅重建 backend 和 frontend，MySQL 未动。
- 完整步骤与回滚见 `doc/DEPLOY-1.5.0.md`。

## 六、线上验收结果

- 后端健康检查 `/flow/test` 返回 200，容器状态 healthy。
- 公网 `https://flux.m7mt.com` 返回 200，加载到新前端资源。
- `/api/v1/config/login-security` 返回 `effectiveEnabled=true` 和站点密钥。
- 管理员浏览器已完成真实登录，随后成功调用公告历史、转发列表等登录后接口，证明 Turnstile 服务端校验通过。
- 定时拨测启动 60 秒后自动执行，成功转发已写入整条链路延迟，失败项写入异常状态。
- 现网 nginx 的 `location /` 使用 `try_files ... /index.html`，支持 `/announcement` 等前端路由刷新。

## 七、后续人工验收项

- 后台“公告管理”发布第一条测试公告后，用普通账号验证：
  - 登录弹窗出现
  - “知道了”后页面切换不重复弹，退出重新登录再弹
  - “不再显示”后不再弹
  - 首页历史公告展示
- 如需调整 Turnstile，可在后台“网站配置”修改站点密钥、服务器密钥或关闭开关。

## 八、回滚

```bash
# 将 /root/docker-compose.yml 中镜像改回：
# bqlpfy/springboot-backend:1.4.3
# bqlpfy/vite-frontend:1.4.3
cd /root
docker compose up -d --no-deps backend frontend
```

- 旧镜像仍保留在服务器。
- 数据库新增表和字段可保留，旧版程序会忽略；如需完整回滚数据库，使用备份目录中的 `database.sql`。
