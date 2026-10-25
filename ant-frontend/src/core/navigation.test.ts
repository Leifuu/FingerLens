import { describe, expect, it } from 'vitest';
import { navigationGroups } from './navigation';
import { compare } from './compare';
import { fixture, fixtureItem } from './fixtures';
import { parseFingerprint } from './import';

describe('WebView 与 Root 证据导航', () => {
  it('旧格式 WebView 单独导航，保留原始分类、原值和比较结果', () => {
    const left = parseFingerprint(JSON.stringify(fixture([
      fixtureItem('environment/webview-provider', { versionName: '1' }, { category: 'environment' }),
      fixtureItem('environment/webview-user-agent', { userAgent: 'old' }, { category: 'environment' }),
      fixtureItem('environment/locale-summary', {}, { category: 'environment' }),
    ])));
    const right = fixture([fixtureItem('environment/webview-provider', { versionName: '2' }, { category: 'environment' })]);
    const original = JSON.stringify(left);
    const rows = compare(left, right, { semantic: false, ignoreDynamic: false });
    const webview = rows.filter(row => navigationGroups(row).includes('webview'));
    expect(webview.map(row => row.state)).toEqual(['different', 'left-only']);
    expect(webview.every(row => row.category === 'environment')).toBe(true);
    expect(rows.filter(row => navigationGroups(row).includes('environment')).map(row => row.key)).toEqual(['environment/locale-summary']);
    expect(JSON.stringify(left)).toBe(original);
  });

  it('Root 导航保留未命中、拒绝和单侧证据，也保留原模块入口', () => {
    const left = fixture([
      fixtureItem('virtualization/path/system/bin/su', { ok: false, errno: 2 }, { category: 'virtualization' }),
      fixtureItem('security/file/sys/fs/selinux/enforce', null, { category: 'security', status: 'denied' }),
      fixtureItem('build/native-property/ro.debuggable', '0', { category: 'build' }),
      fixtureItem('kernel/mount-summary', {}, { category: 'kernel' }),
    ]);
    const original = JSON.stringify(left);
    const rows = compare(left, fixture([]), { semantic: false, ignoreDynamic: false }).filter(row => row.category !== 'collection');
    expect(rows.every(row => navigationGroups(row).includes('root'))).toBe(true);
    expect(rows.every(row => navigationGroups(row).includes(row.category))).toBe(true);
    expect(rows.every(row => row.state === 'left-only')).toBe(true);
    expect(JSON.stringify(left)).toBe(original);
  });

  it('不把模拟器路径、证书信任根和相似字段误归入 Root', () => {
    for (const key of ['virtualization/path/dev/qemu_pipe', 'virtualization/path/system/bin/su-backup', 'build/native-property/ro.secure.extra', 'security/trust-store-summary', 'attestation/tee']) {
      expect(navigationGroups({ key, category: 'security' })).toEqual(['security']);
    }
    expect(navigationGroups({ key: 'environment/webview-provider-extra', category: 'environment' })).toEqual(['environment']);
  });
});
