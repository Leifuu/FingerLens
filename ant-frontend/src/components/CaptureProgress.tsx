import { Button, Progress, Typography } from 'antd';

interface Props { active: boolean; cancelling: boolean; progress: number; phase: string; error: string; serial?: string; onCancel: () => void }

export function CaptureProgress({ active, cancelling, progress, phase, error, serial, onCancel }: Props) {
  if (!phase && !error) return null;
  return <div className="capture-progress" aria-live="polite">
    <>
      <div className="capture-status"><Typography.Text>{phase}</Typography.Text>{active && <Button size="small" danger loading={cancelling} onClick={onCancel}>取消采集</Button>}</div>
      {serial && <Typography.Text type="secondary">{serial}</Typography.Text>}
      {active && <Progress percent={progress} size="small" status="active" />}
    </>
    {error && active && <Button danger loading={cancelling} onClick={onCancel}>取消采集</Button>}
  </div>;
}
