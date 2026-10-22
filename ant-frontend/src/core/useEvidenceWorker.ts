import { useCallback, useEffect, useRef } from 'react';
import type { Imported } from './types';

export function useEvidenceWorker() {
  const ref = useRef<Worker | null>(null), sequence = useRef(0);
  const pending = useRef(new Map<number, { resolve: (data: any) => void; reject: (error: Error) => void }>());
  const saved = useRef(new Map<string, Imported>());
  const create = useCallback(() => {
    if (ref.current) return ref.current;
    const worker = new Worker(new URL('./worker.ts', import.meta.url), { type: 'module' });
    worker.onmessage = ({ data }) => { const task = pending.current.get(data.id); if (!task) return; pending.current.delete(data.id); if (data.type === 'error') task.reject(new Error(data.message)); else { if (data.type === 'import') saved.current.set(data.side, data.result); task.resolve(data); } };
    worker.onerror = () => { for (const task of pending.current.values()) task.reject(new Error('处理进程异常，已保留导入原件，请重试')); pending.current.clear(); worker.terminate(); ref.current = null; };
    for (const [side, result] of saved.current) worker.postMessage({ type: 'restore', side, result });
    ref.current = worker; return worker;
  }, []);
  const request = useCallback((type: string, payload: Record<string, unknown>) => new Promise<any>((resolve, reject) => { const id = ++sequence.current; pending.current.set(id, { resolve, reject }); create().postMessage({ id, type, ...payload }); }), [create]);
  const cancel = useCallback(() => { ref.current?.terminate(); ref.current = null; for (const task of pending.current.values()) task.reject(new Error('已取消处理')); pending.current.clear(); }, []);
  useEffect(() => () => cancel(), [cancel]);
  return { request, cancel };
}
