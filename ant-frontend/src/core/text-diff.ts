import { diffLines } from 'diff';

export type DiffLineStatus = 'same' | 'changed' | 'empty';

export interface DiffLine {
  left: string | null;
  right: string | null;
  leftNumber: number | null;
  rightNumber: number | null;
  status: DiffLineStatus;
}

function splitChunk(value: string): string[] {
  const normalized = value.replaceAll('\r\n', '\n');
  const lines = normalized.split('\n');
  if (normalized.endsWith('\n')) lines.pop();
  return lines;
}

function appendChanged(result: DiffLine[], removed: string[], added: string[], leftNumber: { value: number }, rightNumber: { value: number }): void {
  const count = Math.max(removed.length, added.length);
  for (let index = 0; index < count; index++) {
    const left = removed[index] ?? null;
    const right = added[index] ?? null;
    result.push({
      left,
      right,
      leftNumber: left === null ? null : leftNumber.value++,
      rightNumber: right === null ? null : rightNumber.value++,
      status: 'changed',
    });
  }
}

/** 使用 diffLines 对齐行，保持空行和两侧行号；不会改写输入文本。 */
export function diffText(left: string, right: string): DiffLine[] {
  const result: DiffLine[] = [];
  const leftNumber = { value: 1 };
  const rightNumber = { value: 1 };
  const changes = diffLines(left, right);
  for (let index = 0; index < changes.length; index++) {
    const change = changes[index];
    const lines = splitChunk(change.value);
    if (change.added) {
      appendChanged(result, [], lines, leftNumber, rightNumber);
      continue;
    }
    if (change.removed) {
      const next = changes[index + 1];
      if (next?.added) {
        appendChanged(result, lines, splitChunk(next.value), leftNumber, rightNumber);
        index++;
      } else {
        appendChanged(result, lines, [], leftNumber, rightNumber);
      }
      continue;
    }
    for (const line of lines) result.push({ left: line, right: line, leftNumber: leftNumber.value++, rightNumber: rightNumber.value++, status: 'same' });
  }
  return result;
}
