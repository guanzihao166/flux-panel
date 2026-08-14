# Flux Panel 1.6.0

发布日期：2026-08-15

## 新功能

- 隧道支持多出口 agents：主备故障切换、等权轮询、加权轮询。
- 支持失败阈值（1-10）与故障恢复时间（5-3600 秒）。
- 支持有序转发链：入口 → 一个或多个中继 agents → 出口候选 → 目标。
- agents 1.6.0 增加原生平滑加权轮询选择器；面板对旧版 agents 通过候选展开保持兼容。
- agent 重连上报配置后，面板自动检查并恢复缺失的入口、chain、中继和出口服务。
- 为 Linux amd64、Linux arm64 和 Windows amd64 生成 agents 1.6.0 二进制。

## 修复与优化

- 修复转发列表 Mapper 未返回拨测字段，导致前端没有拨测结果的问题。
- “全部拨测”增加结果轮询，单条拨测直接合并后端返回结果。
- 拨测覆盖完整多跳路径，并显示可用路径数量、失败原因和拨测时间。
- 转发端口在入口、中继及所有出口节点上统一检查和分配。
- 拓扑创建失败时按反向顺序回滚已创建的 GOST 服务。
- 隧道拓扑编辑使用旧拓扑清理后再按新拓扑重建，避免残留服务。
- 暗色模式从接近纯黑改为低对比度灰色，降低强调色饱和度和亮度。

## 数据库迁移

执行 `doc/migration-1.6.0.sql`，新增：

- `forward.target_weights`
- `tunnel.out_node_ids`
- `tunnel.out_node_weights`
- `tunnel.chain_node_ids`
- `tunnel.balance_strategy`
- `tunnel.max_fails`
- `tunnel.fail_timeout`

## HA 边界

出口和中继故障由 GOST 数据平面自动切换。固定入口 agent/IP 自身故障无法由同一个监听地址透明切换；入口高可用仍需 DNS、Anycast 或外部四层负载均衡。
