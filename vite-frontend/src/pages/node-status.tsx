import { useCallback, useEffect, useState } from 'react';
import { Button } from '@heroui/button';
import { Spinner } from '@heroui/spinner';
import { toast } from 'react-hot-toast';
import { getUserNodeStatus } from '@/api';

interface NodeStatus {
  nodeId: number;
  nodeName: string;
  role: string;
  server: string;
  status: number;
  version?: string;
  tcpConnections?: number;
  udpConnections?: number;
  bytesReceived?: number;
  bytesTransmitted?: number;
  uploadSpeed?: number;
  downloadSpeed?: number;
  cpuUsage?: number;
  memoryUsage?: number;
  lastSeen?: number;
}

interface TunnelStatus {
  tunnelId: number;
  tunnelName: string;
  tunnelType: number;
  nodes: NodeStatus[];
}

const tunnelTypeLabel = (type: number) => type === 2 ? '隧道转发' : type === 3 ? '转发端点' : '端口转发';

const formatPercent = (value?: number) => typeof value === 'number' && Number.isFinite(value) ? `${value.toFixed(1)}%` : '-';
const formatBytes = (value?: number) => {
  if (typeof value !== 'number' || !Number.isFinite(value)) return '-';
  if (value === 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
  const index = Math.min(Math.floor(Math.log(value) / Math.log(1024)), units.length - 1);
  return `${(value / Math.pow(1024, index)).toFixed(index === 0 ? 0 : 2)} ${units[index]}`;
};
const formatRate = (value?: number) => typeof value === 'number' && Number.isFinite(value) ? `${formatBytes(value)} /s` : '-';

export default function UserNodeStatusPage() {
  const [items, setItems] = useState<TunnelStatus[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [updatedAt, setUpdatedAt] = useState<number>();

  const load = useCallback(async (manual = false) => {
    if (manual) setRefreshing(true);
    try {
      const response = await getUserNodeStatus();
      if (response.code === 0) {
        setItems(Array.isArray(response.data) ? response.data : []);
        setUpdatedAt(Date.now());
      } else if (manual) {
        toast.error(response.msg || '获取节点状态失败');
      }
    } catch (error) {
      console.error('获取节点状态失败:', error);
      if (manual) toast.error('获取节点状态失败');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    load();
    const timer = window.setInterval(() => load(), 5000);
    return () => window.clearInterval(timer);
  }, [load]);

  const nodeCount = items.reduce((sum, item) => sum + item.nodes.length, 0);
  const onlineCount = items.reduce((sum, item) => sum + item.nodes.filter(node => node.status === 1).length, 0);

  return (
    <div className="container mx-auto max-w-7xl px-3 lg:px-6 py-6 lg:py-8">
      <div className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between mb-6">
        <div>
          <h1 className="text-2xl font-bold text-foreground">节点状态</h1>
          <p className="text-sm text-default-500 mt-1">仅显示你有权限使用的隧道节点，服务器地址已脱敏</p>
        </div>
        <div className="flex items-center gap-3">
          <span className="text-xs text-default-500">
            {updatedAt ? `更新于 ${new Date(updatedAt).toLocaleTimeString('zh-CN', { hour12: false })}` : '正在同步'}
          </span>
          <Button size="sm" variant="flat" onPress={() => load(true)} isLoading={refreshing} startContent={!refreshing ? <span aria-hidden>↻</span> : undefined}>
            刷新
          </Button>
        </div>
      </div>

      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 mb-6">
        <div className="rounded-lg border border-default-200 bg-content1 px-4 py-3">
          <div className="text-xs text-default-500">隧道</div>
          <div className="text-2xl font-semibold text-foreground mt-1">{items.length}</div>
        </div>
        <div className="rounded-lg border border-default-200 bg-content1 px-4 py-3">
          <div className="text-xs text-default-500">节点</div>
          <div className="text-2xl font-semibold text-foreground mt-1">{nodeCount}</div>
        </div>
        <div className="rounded-lg border border-default-200 bg-content1 px-4 py-3 col-span-2 sm:col-span-1">
          <div className="text-xs text-default-500">在线节点</div>
          <div className="text-2xl font-semibold text-success mt-1">{onlineCount}</div>
        </div>
      </div>

      {loading ? (
        <div className="h-64 flex items-center justify-center"><Spinner label="加载节点状态" /></div>
      ) : items.length === 0 ? (
        <div className="rounded-lg border border-dashed border-default-300 py-16 text-center text-default-500">暂无可查看的隧道节点</div>
      ) : (
        <div className="rounded-lg border border-default-200 bg-content1 overflow-hidden">
          <div className="overflow-x-auto">
            <table className="min-w-[1180px] w-full text-sm">
              <thead className="bg-default-50 text-default-500 text-xs">
                <tr>
                  <th className="text-left px-4 py-3 font-medium">隧道</th>
                  <th className="text-left px-4 py-3 font-medium">节点</th>
                  <th className="text-left px-4 py-3 font-medium">角色</th>
                  <th className="text-left px-4 py-3 font-medium">服务器</th>
                  <th className="text-left px-4 py-3 font-medium">状态</th>
                  <th className="text-left px-4 py-3 font-medium">连接</th>
                  <th className="text-left px-4 py-3 font-medium">实时带宽</th>
                  <th className="text-left px-4 py-3 font-medium">累计流量</th>
                  <th className="text-left px-4 py-3 font-medium">资源</th>
                </tr>
              </thead>
              <tbody>
                {items.flatMap(item => item.nodes.map((node, index) => (
                  <tr key={`${item.tunnelId}-${node.nodeId}`} className="border-t border-default-100">
                    <td className="px-4 py-3 align-top">
                      <div className="font-medium text-foreground">{item.tunnelName}</div>
                      <div className="text-xs text-default-500 mt-1">{tunnelTypeLabel(item.tunnelType)}</div>
                    </td>
                    <td className="px-4 py-3 align-top text-foreground">{node.nodeName || `节点 ${index + 1}`}</td>
                    <td className="px-4 py-3 align-top text-default-600">{node.role}</td>
                    <td className="px-4 py-3 align-top font-mono text-default-600">{node.server}</td>
                    <td className="px-4 py-3 align-top">
                      <span className={`inline-flex items-center gap-1.5 ${node.status === 1 ? 'text-success' : 'text-danger'}`}>
                        <span className={`h-2 w-2 rounded-full ${node.status === 1 ? 'bg-success' : 'bg-danger'}`} />
                        {node.status === 1 ? '在线' : '离线'}
                      </span>
                      {node.version && <div className="text-xs text-default-500 mt-1">v{node.version}</div>}
                    </td>
                    <td className="px-4 py-3 align-top text-default-600">TCP {node.tcpConnections ?? 0} / UDP {node.udpConnections ?? 0}</td>
                    <td className="px-4 py-3 align-top text-default-600 whitespace-nowrap">↑ {formatRate(node.uploadSpeed)}<br />↓ {formatRate(node.downloadSpeed)}</td>
                    <td className="px-4 py-3 align-top text-default-600 whitespace-nowrap">↑ {formatBytes(node.bytesTransmitted)}<br />↓ {formatBytes(node.bytesReceived)}</td>
                    <td className="px-4 py-3 align-top text-default-600">CPU {formatPercent(node.cpuUsage)}<br />内存 {formatPercent(node.memoryUsage)}</td>
                  </tr>
                )))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}
