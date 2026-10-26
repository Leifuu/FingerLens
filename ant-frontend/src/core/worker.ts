/// <reference lib="webworker" />
import { readFingerprint } from './import';
import { compare, conditions } from './compare';
import { verifyAttestation } from './attestation';
import type { Imported, Rules } from './types';

const files = new Map<string, Imported>();
self.onmessage = async (event: MessageEvent) => {
  const { id, type } = event.data;
  try {
    if (type === 'restore') { files.set(event.data.side, event.data.result); return; }
    if (type === 'import') { const result = await readFingerprint(event.data.file); files.set(event.data.side, result); self.postMessage({ id, type, side: event.data.side, result }); }
    else if (type === 'compare') { const left = files.get('left'), right = files.get('right'); if (!left && !right) throw new Error('请先采集或导入一份指纹'); self.postMessage({ id, type, rows: compare(left?.fingerprint, right?.fingerprint, event.data.rules as Rules), conditions: left && right ? conditions(left.fingerprint, right.fingerprint) : [] }); }
    else if (type === 'attestation') { const file = files.get(event.data.side); if (!file) throw new Error('缺少指纹'); self.postMessage({ id, type, side: event.data.side, result: await verifyAttestation(file.fingerprint) }); }
  } catch (error) { self.postMessage({ id, type: 'error', side: event.data.side, message: error instanceof Error ? error.message : String(error) }); }
};
