import { describe, it, expect } from 'vitest';
import { compare, conditions } from './compare';
import { rawText, formattedText } from './format';
import { fixture, fixtureItem } from './fixtures';
const raw = { semantic: false, ignoreDynamic: false }, semantic = { semantic: true, ignoreDynamic: false }, smart = { semantic: false, ignoreDynamic: true };
const find = (rows: ReturnType<typeof compare>, key: string) => rows.find(x => x.key === key)!;

describe('原始与语义比较', () => {
  it('覆盖相同、单侧、状态不同、类型不同与不可比较', () => { const a = fixture([fixtureItem('same'), fixtureItem('left'), fixtureItem('status'), fixtureItem('type', 1), fixtureItem('denied', null, { status: 'denied' })]); const b = fixture([fixtureItem('same'), fixtureItem('right'), fixtureItem('status', null, { status: 'denied' }), fixtureItem('type', '1'), fixtureItem('denied', null, { status: 'denied' })]); const rows = compare(a, b, raw); expect(find(rows, 'same').state).toBe('same'); expect(find(rows, 'left').state).toBe('left-only'); expect(find(rows, 'right').state).toBe('right-only'); expect(find(rows, 'status').state).toBe('status'); expect(find(rows, 'type').state).toBe('different'); expect(find(rows, 'denied').state).toBe('incomparable'); });
  it('默认保留数组顺序，已知集合语义排序', () => { const a = fixture([fixtureItem('sensors/list', [{ name: 'a' }, { name: 'b' }], { category: 'sensors' })]); const b = fixture([fixtureItem('sensors/list', [{ name: 'b' }, { name: 'a' }], { category: 'sensors' })]); expect(find(compare(a, b, raw), 'sensors/list').state).toBe('different'); expect(find(compare(a, b, semantic), 'sensors/list').state).toBe('same'); });
  it('目录按路径对齐并保留重复项，不覆盖', () => { const a = fixture([fixtureItem('directories/chunk/00000', [{ path: '/a' }, { path: '/a' }, { path: '/b' }], { category: 'directories' })]); const b = fixture([fixtureItem('directories/chunk/00001', [{ path: '/b' }, { path: '/a' }], { category: 'directories' })]); const rows = compare(a, b, semantic).filter(x => x.category === 'directories'); expect(rows).toHaveLength(3); expect(rows.filter(x => x.state === 'left-only')).toHaveLength(1); });
  it('不改变原件，只按显式规则过滤动态字段', () => { const a = fixture([fixtureItem('attestation/tee', { challengeBase64: 'a', stable: 1 })]); const b = fixture([fixtureItem('attestation/tee', { challengeBase64: 'b', stable: 1 })]); const before = JSON.stringify(a); expect(find(compare(a, b, raw), 'attestation/tee').state).toBe('different'); const filtered = find(compare(a, b, { semantic: true, ignoreDynamic: true }), 'attestation/tee'); expect(filtered.state).toBe('same'); expect(filtered.ignored).toBe(2); expect(JSON.stringify(a)).toBe(before); });
	it('智能过滤忽略 pid、uid、运行与启动时间，但保留稳定字段', () => { const a = fixture([fixtureItem('security/self', { pid: 10, uid: 1000, runTime: 100, startTime: 'a', stable: 'same' })]); const b = fixture([fixtureItem('security/self', { pid: 20, uid: 1001, runTime: 200, startTime: 'b', stable: 'changed' })]); const row = find(compare(a, b, smart), 'security/self'); expect(row.state).toBe('different'); expect(row.changedPaths).toContain('/value/stable'); expect(row.changedPaths.some(path => /pid|uid|runTime|startTime/.test(path))).toBe(false); expect(row.ignored).toBe(8); });
	it('应用清单只因 application.uid 变化时被智能过滤，稳定字段变化仍保留', () => {
		const a = fixture([fixtureItem('packages/com.example.app', { packageName: 'com.example.app', versionName: '1.0', application: { uid: 10001, processName: 'com.example.app' } }, { category: 'packages' })]);
		const b = fixture([fixtureItem('packages/com.example.app', { packageName: 'com.example.app', versionName: '1.0', application: { uid: 10042, processName: 'com.example.app' } }, { category: 'packages' })]);
		const same = find(compare(a, b, smart), 'packages/com.example.app');
		expect(same.state).toBe('same');
		expect(same.changedPaths).toEqual([]);
		expect(same.ignored).toBe(2);
		b.data.items[0].value = { packageName: 'com.example.app', versionName: '2.0', application: { uid: 10042, processName: 'com.example.app' } };
		const changed = find(compare(a, b, smart), 'packages/com.example.app');
		expect(changed.state).toBe('different');
		expect(changed.changedPaths).toContain('/value/versionName');
		expect(changed.changedPaths.some(path => path.endsWith('/uid'))).toBe(false);
	});
	it('智能过滤忽略电源与热状态快照，但保留稳定字段', () => {
		const a = fixture([fixtureItem('environment/power-thermal', { powerSaveMode: false, interactive: true, thermalStatus: 0, stable: 'a' }, { category: 'environment' })]);
		const b = fixture([fixtureItem('environment/power-thermal', { powerSaveMode: true, interactive: false, thermalStatus: 3, stable: 'b' }, { category: 'environment' })]);
		const row = find(compare(a, b, smart), 'environment/power-thermal');
		expect(row.state).toBe('different');
		expect(row.changedPaths).toEqual(['/value/stable']);
		expect(row.ignored).toBe(6);
	});
  it('智能过滤忽略内存瞬时值，但保留设备内存容量', () => {
    const a = fixture([fixtureItem('environment/memory-summary', { totalMemBytes: 8, availMemBytes: 4, runtimeTotalMemoryBytes: 3, runtimeFreeMemoryBytes: 2, heapUsedBytes: 1 }, { category: 'environment' })]);
    const b = fixture([fixtureItem('environment/memory-summary', { totalMemBytes: 16, availMemBytes: 6, runtimeTotalMemoryBytes: 5, runtimeFreeMemoryBytes: 1, heapUsedBytes: 4 }, { category: 'environment' })]);
    const row = find(compare(a, b, smart), 'environment/memory-summary');
    expect(row.state).toBe('different');
    expect(row.changedPaths).toEqual(['/value/totalMemBytes']);
    expect(row.ignored).toBe(8);
  });
  it('智能过滤忽略电话注册与信号动态摘要字段', () => {
    const a = fixture([
      fixtureItem('sim/service-summary', { serviceState: 0, registrationCount: 2, registeredCount: 2, domainCounts: { '1': 2 }, manualSelection: false }, { category: 'sim' }),
      fixtureItem('sim/signal-summary', { timestampMillis: '100', signalLevel: 2, dbmMin: -110, dbmMax: -80, levelCounts: { '2': 1 }, typeCounts: { Lte: 1 }, available: true }, { category: 'sim' }),
    ]);
    const b = fixture([
      fixtureItem('sim/service-summary', { serviceState: 1, registrationCount: 0, registeredCount: 0, domainCounts: {}, manualSelection: false }, { category: 'sim' }),
      fixtureItem('sim/signal-summary', { timestampMillis: '200', signalLevel: 4, dbmMin: -70, dbmMax: -50, levelCounts: { '4': 1 }, typeCounts: { Nr: 1 }, available: true }, { category: 'sim' }),
    ]);
    const service = find(compare(a, b, smart), 'sim/service-summary');
    const signal = find(compare(a, b, smart), 'sim/signal-summary');
    expect(service.state).toBe('same');
    expect(signal.state).toBe('same');
    expect(service.ignored).toBeGreaterThan(0);
    expect(signal.ignored).toBeGreaterThan(0);
  });
  it('智能过滤忽略 eUICC 可用内存变化，但保留能力字段', () => {
    const a = fixture([fixtureItem('sim/euicc-summary', { enabled: true, availableMemoryBytes: 4096 }, { category: 'sim' })]);
    const b = fixture([fixtureItem('sim/euicc-summary', { enabled: true, availableMemoryBytes: 2048 }, { category: 'sim' })]);
    const same = find(compare(a, b, smart), 'sim/euicc-summary');
    expect(same.state).toBe('same');
    expect(same.changedPaths).toEqual([]);
    expect(same.ignored).toBe(2);
    b.data.items[0].value = { enabled: false, availableMemoryBytes: 2048 };
    const changed = find(compare(a, b, smart), 'sim/euicc-summary');
    expect(changed.state).toBe('different');
    expect(changed.changedPaths).toEqual(['/value/enabled']);
  });
  it('USB 摘要语义比较按厂商与产品 ID 集合排序', () => {
    const left = fixture([fixtureItem('hardware/usb-summary', { vendorIds: [1204, 18], productIds: [4660, 52] }, { category: 'hardware' })]);
    const right = fixture([fixtureItem('hardware/usb-summary', { vendorIds: [18, 1204], productIds: [52, 4660] }, { category: 'hardware' })]);
    expect(find(compare(left, right, raw), 'hardware/usb-summary').state).toBe('different');
    expect(find(compare(left, right, semantic), 'hardware/usb-summary').state).toBe('same');
  });
  it('同一对象键顺序不产生差异，字段缺失区别于 null', () => { const a = fixture([fixtureItem('object', { x: 1, y: null })]); const b = fixture([fixtureItem('object', { y: null, x: 1 })]); expect(find(compare(a, b, raw), 'object').state).toBe('same'); b.data.items[0].value = { x: 1 }; expect(find(compare(a, b, raw), 'object').changedPaths).toContain('/value/y'); });
  it('提示 API、签名、授权、配置与非完整结果差异', () => { const a = fixture(), b = fixture(); b.data.app.androidSdk = 35; b.data.permissions.camera = true; b.data.config.changed = true; b.data.state = 'partial'; expect(conditions(a, b)).toHaveLength(4); });
  it('恶意内容保持文本，格式化不改原值', () => { const text = '{"x":"<script>globalThis.pwned=true</script>"}'; expect(formattedText(text)).toContain('\n'); expect(rawText(text)).toBe(text); expect((globalThis as Record<string, unknown>).pwned).toBeUndefined(); });
  it('输入设备按 descriptor 对齐，不把 id 漂移变成删除新增', () => { const a = fixture([fixtureItem('input/device/1', { descriptor: 'same', id: 1 }, { category: 'input' })]); const b = fixture([fixtureItem('input/device/9', { descriptor: 'same', id: 9 }, { category: 'input' })]); const rows = compare(a, b, semantic).filter(x => x.category === 'input'); expect(rows).toHaveLength(1); expect(rows[0].state).toBe('different'); });
});
