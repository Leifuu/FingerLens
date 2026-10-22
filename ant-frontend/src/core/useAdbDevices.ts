import { useCallback, useEffect, useRef, useState } from 'react';
import { adbRequest } from './adb';
import type { AdbDevice } from './adb';

export function useAdbDevices() {
  const [devices, setDevices] = useState<AdbDevice[]>([]);
  const [error, setError] = useState('');
  const [refreshing, setRefreshing] = useState(false);
  const pending = useRef(false), mounted = useRef(false);
  const refresh = useCallback(async () => {
    if (pending.current) return;
    pending.current = true;
    setRefreshing(true);
    try {
      const result = await adbRequest<AdbDevice[]>('/devices');
      if (mounted.current) { setDevices(result); setError(''); }
    } catch (cause) {
      if (mounted.current) { setDevices([]); setError(cause instanceof Error ? cause.message : '无法读取 ADB 设备'); }
    } finally { pending.current = false; if (mounted.current) setRefreshing(false); }
  }, []);
  useEffect(() => {
    mounted.current = true;
    void refresh();
    return () => { mounted.current = false; };
  }, [refresh]);
  return { devices, error, refreshing, refresh };
}
