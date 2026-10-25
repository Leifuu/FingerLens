import type { MergedDisplayRow } from './display-compare';

// 业务块：只聚合默认 TLS 的摘要字段；完整哈希及未知元数据参与概览状态，不改原字段或列表。
export function tlsDisplayRows(rows: MergedDisplayRow[]): MergedDisplayRow[] | undefined {
  const field = (name: string) => rows.find(row => row.label === name);
  const provider = field('providerName'), protocol = field('contextProtocol');
  if (!provider || !protocol) return undefined;
  for (const side of ['left', 'right'] as const) {
    if (rows.some(row => row[side] !== null) && (provider[side] === null || protocol[side] === null)) return undefined;
  }
  const used = new Set<MergedDisplayRow>(), result: MergedDisplayRow[] = [];
  for (const [name, label] of [['providerName', '提供者'], ['contextProtocol', '上下文协议名'], ['available', '参数可用'], ['status', '参数状态'], ['entryLimit', '每组明细上限']]) {
    const row = field(name);
    if (row) { used.add(row); result.push({ ...row, label }); }
  }
  const aggregate = (key: string, label: string, fields: MergedDisplayRow[], render: (side: 'left' | 'right') => string): MergedDisplayRow => {
    const left = fields.some(row => row.left !== null), right = fields.some(row => row.right !== null);
    return { key, label, left: left ? render('left') : null, right: right ? render('right') : null,
      state: !left ? 'right-only' : !right ? 'left-only' : fields.some(row => row.state !== 'same') ? 'changed' : 'same' };
  };
  for (const [key, label] of [['supportedProtocols', '支持的 TLS 协议'], ['defaultProtocols', '默认启用的 TLS 协议'], ['supportedCipherSuites', '支持的密码套件'], ['defaultCipherSuites', '默认启用的密码套件']]) {
    const names = [key + 'Count', key + 'Truncated', key + 'Sha256'];
    const fields = rows.filter(row => names.includes(row.label));
    if (!fields.length) continue;
    fields.forEach(row => used.add(row));
    result.push(aggregate(key, label, fields, side => {
      const count = field(key + 'Count')?.[side], truncated = field(key + 'Truncated')?.[side];
      return `数量 ${count === '' ? '（空字符串）' : count ?? '未知'} · 截断 ${truncated === 'true' ? '是' : truncated === 'false' ? '否' : truncated === '' ? '（空字符串）' : truncated ?? '未知'}`;
    }));
  }
  const other = rows.filter(row => !used.has(row));
  if (other.length) result.push(aggregate('other', '其他元数据', other, side => `${other.filter(row => row[side] !== null).length} 个字段（展开查看）`));
  return result;
}
