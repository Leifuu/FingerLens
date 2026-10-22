import { useEffect, useState } from 'react';
import { Alert, Card, Drawer, Space, Spin, Typography, message } from 'antd';
import type { AttestationResult } from '../core/attestation';
import { copyText } from '../core/clipboard';

export function AttestationDrawer({ open, onClose, request }: { open: boolean; onClose: () => void; request: (type: string, payload: Record<string, unknown>) => Promise<any> }) {
  const [results, setResults] = useState<{ side: string; result: AttestationResult[] }[]>([]);
  const [error, setError] = useState('');
  useEffect(() => {
    if (!open) return;
    let active = true;
    setResults([]); setError('');
    Promise.all(['left', 'right'].map(async side => ({ side, result: (await request('attestation', { side })).result as AttestationResult[] })))
      .then(value => { if (active) setResults(value); }).catch(cause => { if (active) { const text = String(cause); setError(text); message.error(text); } });
    return () => { active = false; };
  }, [open, request]);
  return <Drawer title="离线认证检查" open={open} onClose={onClose} width="min(1100px, 100vw)" className="comparison-drawer" destroyOnHidden>
    <Space direction="vertical" size="large" style={{ display: 'flex' }}>
      <Alert type="info" showIcon message="分析结果与设备采集值分开展示" description="仅检查导入证据和固定根快照；实时撤销未知，链签名有效不等同于 Play Integrity 通过。" />
      {!results.length ? <Spin /> : results.map(({ side, result }) => <Card key={side} title={`设备 ${side === 'left' ? 'A' : 'B'}`}><Typography.Paragraph className="device-value copyable-value" title="双击复制" onDoubleClick={() => void copyText(JSON.stringify(result, null, 2), `设备 ${side === 'left' ? 'A' : 'B'} 认证结果`)}>{JSON.stringify(result, null, 2)}</Typography.Paragraph></Card>)}
    </Space>
  </Drawer>;
}
