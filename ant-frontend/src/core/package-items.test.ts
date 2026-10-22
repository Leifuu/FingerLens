import { describe, it, expect } from 'vitest';
import { existsSync, readFileSync } from 'node:fs';
import { mergePackageInstallers } from './package-items';
import { parseFingerprint } from './import';
import { compare } from './compare';
import { fixture, fixtureItem } from './fixtures';

const key = 'packages/com.example.test';
const app = () => fixtureItem(key, { packageName: 'com.example.test', versionName: '1' }, { category: 'packages' });
const installer = () => fixtureItem(`${key}/installer`, null, { category: 'packages', source: { kind: 'android-api', api: 'getInstallerPackageName', uid: 10001, pid: 123, selinux: 'synthetic' } });
const raw = { semantic: false, ignoreDynamic: false };

describe('应用与安装来源合并', () => {
  it('将安装来源完整嵌入主记录，保留成功空值和身份，不修改输入', () => {
    const items = [app(), installer()], before = JSON.stringify(items), merged = mergePackageInstallers(items);
    expect(merged).toHaveLength(1); expect(merged[0].value).toEqual(items[0].value); expect(merged[0].installer).toEqual(items[1]);
    expect(merged[0].installer?.status).toBe('ok'); expect(merged[0].installer?.value).toBeNull(); expect(JSON.stringify(items)).toBe(before);
    expect(mergePackageInstallers(merged)).toEqual(merged);
  });
  it('安装来源失败独立保留；没有主记录的安装来源继续作为独立项', () => {
    const child = { ...installer(), status: 'denied' as const, error: { message: '合成拒绝' } };
    const merged = mergePackageInstallers([child, app()]); expect(merged[0].status).toBe('ok'); expect(merged[0].installer).toEqual(child);
    expect(mergePackageInstallers([child])).toEqual([child]);
  });
  it('重复键、嵌套与独立来源冲突、错误包归属不覆盖', () => {
    expect(() => mergePackageInstallers([app(), app()])).toThrow(/重复/);
    expect(() => mergePackageInstallers([{ ...app(), installer: installer() }, installer()])).toThrow(/同时/);
    expect(() => mergePackageInstallers([{ ...app(), installer: { ...installer(), key: 'packages/other/installer' } }])).toThrow(/不匹配/);
  });
  it('导入兼容两种结构，并检查嵌套类型和重复来源', () => {
    const old = fixture([app(), installer()]), normalized = parseFingerprint(JSON.stringify(old));
    expect(normalized.data.items).toHaveLength(1); expect(parseFingerprint(JSON.stringify(normalized))).toEqual(normalized);
    expect(() => parseFingerprint(JSON.stringify(fixture([{ ...app(), installer: { ...installer(), dataType: 'string' } }])))).toThrow(/类型/);
    expect(() => parseFingerprint(JSON.stringify(fixture([{ ...app(), installer: installer() }, installer()])))).toThrow(/同时/);
  });
  it('旧结构与已合并结构对齐为一行，安装来源PID不同不产生假差异', () => {
    const a = fixture([app(), installer()]), b = fixture(mergePackageInstallers([app(), { ...installer(), source: { ...installer().source, pid: 999 } }]));
    const rows = compare(a, b, raw).filter(x => x.category === 'packages'); expect(rows).toHaveLength(1); expect(rows[0].state).toBe('same');
  });
  it('安装来源内容、状态、缺失和受限仍进入主记录比较', () => {
    const a = fixture([app(), installer()]);
    const valueChanged = compare(a, fixture([app(), { ...installer(), dataType: 'string', value: 'store.example' }]), raw).find(x => x.key === key)!;
    expect(valueChanged.state).toBe('different'); expect(valueChanged.changedPaths).toContain('/installer/value');
    const denied = { ...installer(), status: 'denied' as const };
    const statusChanged = compare(a, fixture([app(), denied]), raw).find(x => x.key === key)!;
    expect(statusChanged.state).toBe('status'); expect(statusChanged.changedPaths).toContain('/installer/status');
    expect(compare(a, fixture([app()]), raw).find(x => x.key === key)?.state).toBe('different');
    expect(compare(fixture([app(), denied]), fixture([app(), denied]), raw).find(x => x.key === key)?.state).toBe('incomparable');
  });
  for (const [name, count] of [['fingerprint-d796074a-b5d1-4d0a-a65f-798e303b3def.json', 951], ['fingerprint-b19d58d7-8360-4803-8587-56c5d458cbbf.json', 804]] as const) {
    const file = new URL(`../../../${name}`, import.meta.url);
    it.runIf(existsSync(file))(`实际合并文件可导入：${name}`, () => {
      const fp = parseFingerprint(readFileSync(file, 'utf8'));
      expect(fp.data.items).toHaveLength(count); expect(fp.data.items.find(x => x.key === 'packages/com.life.fingerprint')?.installer?.key).toBe('packages/com.life.fingerprint/installer');
      expect(fp.data.items.some(x => x.key === 'packages/com.life.fingerprint/installer')).toBe(false);
    });
  }
});
