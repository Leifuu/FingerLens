import { describe, expect, it } from 'vitest';
import { diffText } from './text-diff';

describe('格式化文本行级差异', () => {
  it('相同文本全部标记为 same', () => {
    const rows = diffText('a\n中文\n', 'a\n中文\n');
    expect(rows.every(row => row.status === 'same')).toBe(true);
    expect(rows.map(row => row.left)).toEqual(['a', '中文']);
  });

  it('删除和新增行左右对齐为 changed', () => {
    const rows = diffText('a\nb\n', 'a\nc\n');
    expect(rows[0].status).toBe('same');
    expect(rows[1]).toMatchObject({ left: 'b', right: 'c', status: 'changed' });
  });

  it('保留单侧新增、空行和行号', () => {
    const rows = diffText('a\n', 'a\n\nnew\n');
    expect(rows.some(row => row.left === null && row.right === 'new')).toBe(true);
    expect(rows.at(-1)?.rightNumber).toBe(3);
  });
});
