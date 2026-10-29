export interface AdbDevice { serial: string; status: string; model: string; busy: boolean }
export interface CaptureJob {
  id: string; serial: string; state: 'starting' | 'collecting' | 'transferring' | 'ready' | 'cancelled' | 'error';
  progress: number; message: string; collectionState?: string; fileName?: string; bytes?: number; sha256?: string;
  databaseState?: 'stored' | 'disabled' | 'pending' | 'error'; databaseReportId?: number | null; databaseMessage?: string;
}

export async function adbRequest<T>(path: string, body?: object, timeoutMs = 15000): Promise<T> {
  const response = await fetch(`/api${path}`, {
    method: body ? 'POST' : 'GET', cache: 'no-store', signal: AbortSignal.timeout(timeoutMs),
    headers: body ? { 'Content-Type': 'application/json', 'X-FingerLens': '1' } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!response.headers.get('content-type')?.includes('application/json')) throw new Error('ADB 后端未启动，请启动本地 Python 服务（8765）');
  const result = await response.json();
  if (!response.ok || result.code !== 0) throw new Error(result.message || 'ADB 请求失败');
  return result.data as T;
}

export async function captureFile(job: CaptureJob): Promise<File> {
  if (!job.fileName || !/^fingerprint-[a-f0-9-]{36}\.json(\.gz)?$/.test(job.fileName)) throw new Error('采集文件名无效');
  const response = await fetch(`/api/jobs/${job.id}/file`, { cache: 'no-store', signal: AbortSignal.timeout(60000) });
  if (!response.ok) throw new Error('读取采集文件失败；可在 APK 中导出已保存结果');
  const file = new File([await response.blob()], job.fileName);
  if (file.size !== job.bytes) throw new Error('采集文件传输大小不一致');
  return file;
}
