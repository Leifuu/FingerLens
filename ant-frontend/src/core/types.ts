export type Status = 'ok' | 'denied' | 'unsupported' | 'error' | 'timeout' | 'truncated' | 'skipped';
export type Json = null | boolean | number | string | Json[] | { [key: string]: Json };
export interface Item {
  key: string; category: string; name: string;
  source: { kind: string; api: string; uid: number; pid: number; selinux: string };
  dataType: string; status: Status; value: Json;
  error?: Record<string, Json>; truncation?: Record<string, Json>; rule?: Record<string, Json>;
  installer?: Item;
}
export interface Fingerprint {
  data: { schemaVersion: '1.0'; collectorVersion: string; sessionId: string; startedAt: string; finishedAt: string | null;
    state: 'running' | 'complete' | 'partial' | 'cancelled' | 'interrupted'; config: Record<string, Json>; permissions: Record<string, Json>; app: Record<string, Json>; items: Item[] };
  message: string; code: 0 | 1 | 2;
}
export type DiffState = 'same' | 'different' | 'left-only' | 'right-only' | 'status' | 'incomparable';
export interface Row { id: string; key: string; name: string; category: string; state: DiffState; left?: Item; right?: Item; changedPaths: string[]; ignored: number }
export interface Rules { semantic: boolean; ignoreDynamic: boolean }
export interface Imported { name: string; size: number; sha256: string; fingerprint: Fingerprint }
export const categories: Record<string, string> = {
  packages: '应用清单', input: '输入设备', display: '屏幕显示', build: 'Build 与属性', binder: 'Binder 服务',
  kernel: '内核与进程', storage: '分区与存储', hardware: '硬件与图形', sensors: '传感器', battery: '电池',
  sim: 'SIM 与电话', network: '网络', environment: '语言与环境', security: '安全与完整性', attestation: '密钥认证',
  virtualization: '虚拟化线索', directories: '目录清单', collection: '采集状态',
};
export const diffLabels: Record<DiffState, string> = { same: '相同', different: '值不同', 'left-only': '仅左侧', 'right-only': '仅右侧', status: '状态不同', incomparable: '不可比较' };
export const statusLabels: Record<Status, string> = { ok: '成功', denied: '权限拒绝', unsupported: '不支持', error: '异常', timeout: '超时', truncated: '截断', skipped: '未执行' };
