import type { Row } from './types';

// 业务块：Root 视图复用已有证据，不修改原始分类，也不依据单项线索生成结论。
const rootEvidenceKeys = new Set([
  'security/root-path-summary',
  'security/root-mount-summary',
  'virtualization/path/system/bin/su', 'virtualization/path/system/xbin/su',
  'virtualization/path/sbin/su', 'virtualization/path/vendor/bin/su', 'virtualization/path/data/adb',
  'virtualization/build-evidence', 'virtualization/build-markers', 'virtualization/risk-summary',
  'build/Build/TAGS', 'build/Build/TYPE', 'build/risk-properties-summary',
  'security/selinux-summary', 'security/file/sys/fs/selinux/enforce',
  'security/file/proc/self/attr/current', 'kernel/mount-summary',
  'kernel/file/proc/self/mountinfo', 'kernel/file/proc/self/mounts', 'kernel/maps-summary',
]);
const rootPropertyNames = new Set([
  'ro.secure', 'ro.debuggable', 'ro.adb.secure', 'ro.build.tags', 'ro.build.type',
  'ro.boot.verifiedbootstate', 'ro.boot.flash.locked', 'ro.boot.vbmeta.device_state',
  'ro.boot.veritymode', 'ro.boot.selinux', 'ro.boot.warranty_bit',
]);

export function navigationGroups(row: Pick<Row, 'key' | 'category'>): string[] {
  const { key, category } = row;
  // 旧 JSON 的 environment 分类照常保留，WebView 在工作台单独导航。
  if (key === 'environment/webview-provider' || key === 'environment/webview-user-agent' || key === 'environment/webview-package-summary') return ['webview'];
  const rootEvidence = rootEvidenceKeys.has(key)
    || key.startsWith('build/native-property/') && rootPropertyNames.has(key.slice('build/native-property/'.length));
  if (rootEvidence && category !== 'root') return [category, 'root'];
  return [category];
}
