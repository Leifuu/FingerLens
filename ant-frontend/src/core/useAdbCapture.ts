import { useEffect, useRef, useState } from 'react';
import { adbRequest, captureFile } from './adb';
import type { CaptureJob } from './adb';

export function useAdbCapture(onFile: (file: File) => Promise<void>) {
  const [job, setJob] = useState<CaptureJob>();
  const [active, setActive] = useState(false), [cancelling, setCancelling] = useState(false);
  const [error, setError] = useState(''), [phase, setPhase] = useState('');
  const locked = useRef(false), mounted = useRef(false), current = useRef<CaptureJob | undefined>(undefined);
  const deliver = useRef(onFile); deliver.current = onFile;
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);

  async function start(serial: string) {
    if (locked.current || !serial) return;
    locked.current = true; setActive(true); setError(''); setJob(undefined); setPhase('正在创建采集任务');
    try {
      let next = await adbRequest<CaptureJob>('/jobs', { serial });
      current.current = next;
      while (mounted.current) {
        setJob(next); setPhase(next.message);
        if (next.state === 'error') throw new Error(next.message);
        if (next.state === 'ready' || next.state === 'cancelled') {
          setPhase('正在导入本轮原始指纹');
          const file = await captureFile(next);
          if (mounted.current) await deliver.current(file);
          if (mounted.current) setPhase(next.collectionState === 'cancelled' ? '已取消，已导入保留的部分结果' : next.collectionState === 'interrupted' ? '采集曾中断，已导入保留结果' : next.collectionState === 'partial' ? '已导入，部分项目受限或截断' : '已采集并导入');
          return;
        }
        await new Promise(resolve => window.setTimeout(resolve, 1000));
        if (!mounted.current) return;
        next = await adbRequest<CaptureJob>(`/jobs/${next.id}`);
        current.current = next;
      }
    } catch (cause) {
      if (mounted.current) { setError(cause instanceof Error ? cause.message : '采集失败'); setPhase('采集或导入失败，原有文件保留'); }
    } finally {
      locked.current = false;
      if (mounted.current) { setActive(false); setCancelling(false); }
    }
  }

  async function cancel() {
    const target = current.current;
    if (!target || !locked.current) return;
    setCancelling(true);
    try { await adbRequest(`/jobs/${target.id}/cancel`, {}); }
    catch (cause) { setError(cause instanceof Error ? cause.message : '取消失败'); setCancelling(false); }
  }
  return { job, active, error, phase, cancelling, start, cancel };
}
