import { InboxOutlined, FileSearchOutlined, ReloadOutlined } from '@ant-design/icons';
import { Card, Upload, Typography, Space, Tag, Button } from 'antd';
import type { Imported } from '../core/types';
import { useState } from 'react';
import { useAdbCapture } from '../core/useAdbCapture';
import type { useAdbDevices } from '../core/useAdbDevices';
import { AdbCaptureControls } from './AdbCaptureControls';
import { CaptureProgress } from './CaptureProgress';

const { Dragger } = Upload;

export function FileImportCard({ side, value, original, busy, onFile, adb }: { side: 'A' | 'B'; value?: Imported; original?: File; busy: boolean; onFile: (file: File) => Promise<void>; adb: ReturnType<typeof useAdbDevices> }) {
  const [selected, setSelected] = useState<string>();
  const capture = useAdbCapture(onFile);
  const disabled = busy || capture.active;
  const upload = (file: File) => { void onFile(file).catch(() => {}); return false as const; };
  return <Card className={`file-card file-card-${side.toLowerCase()}`} size="small" title={<Space><Tag color={side === 'A' ? 'blue' : 'cyan'}>{side}</Tag><span>设备 {side}</span></Space>} extra={<AdbCaptureControls side={side} devices={adb.devices} selected={selected} onSelect={setSelected} active={capture.active} disabled={busy} loaded={!!value} refreshing={adb.refreshing} onRefresh={() => void adb.refresh()} onStart={() => void capture.start(selected || '')} />}>
    <CaptureProgress active={capture.active} cancelling={capture.cancelling} progress={capture.job?.progress || 0} phase={capture.phase} error={capture.error} serial={capture.job?.serial} onCancel={() => void capture.cancel()} />
    {value ? <div className="loaded-file"><FileSearchOutlined className="loaded-file-icon" /><div className="loaded-file-text"><Typography.Text strong ellipsis={{ tooltip: value.name }}>{value.name}</Typography.Text><Typography.Text type="secondary">{(value.size / 1024 / 1024).toFixed(2)} MiB · SHA-256 {value.sha256.slice(0, 16)}…</Typography.Text></div><Space><Button aria-label={`下载设备 ${side} 原件`} disabled={!original} onClick={() => { if (!original) return; const url = URL.createObjectURL(original); const link = document.createElement('a'); link.href = url; link.download = original.name; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000); }}>下载原件</Button><Upload accept=".json,.json.gz" multiple={false} showUploadList={false} disabled={disabled} beforeUpload={upload}><Button disabled={disabled} icon={<ReloadOutlined />}>替换</Button></Upload></Space></div> : <Dragger accept=".json,.json.gz" multiple={false} showUploadList={false} disabled={disabled} beforeUpload={upload}><p className="ant-upload-drag-icon"><InboxOutlined /></p><p className="ant-upload-text">拖入设备 {side} 指纹文件</p><p className="ant-upload-hint">支持 .json 与 .json.gz，也可在上方选择设备采集</p></Dragger>}
  </Card>;
}
