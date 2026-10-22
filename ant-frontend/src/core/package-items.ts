import type { Item } from './types';

function isPackage(item: Item): boolean {
  const value = item.value;
  return item.category === 'packages' && !!value && typeof value === 'object' && !Array.isArray(value) && typeof value.packageName === 'string' && /^[A-Za-z0-9_.]+$/.test(value.packageName) && item.key === `packages/${value.packageName}`;
}

// 只组合真实包与它的安装来源；来源记录完整嵌入，不能用安装来源状态覆盖包状态。
export function packageInstallerPairs(items: readonly Item[]): Map<number, number> {
  const indices = new Map<string, number>();
  items.forEach((item, index) => { if (indices.has(item.key)) throw new Error(`采集项 key 重复：${item.key}`); indices.set(item.key, index); });
  const pairs = new Map<number, number>();
  items.forEach((item, index) => {
    if (item.installer && (!isPackage(item) || item.installer.category !== 'packages' || item.installer.key !== `${item.key}/installer` || item.installer.installer)) throw new Error(`嵌套安装来源不匹配：${item.key}`);
    if (!isPackage(item)) return;
    const childIndex = indices.get(`${item.key}/installer`);
    if (childIndex === undefined) return;
    if (item.installer) throw new Error(`同时存在已合并和独立的安装来源：${item.key}`);
    if (items[childIndex].category !== 'packages' || items[childIndex].installer) throw new Error(`安装来源记录不匹配：${item.key}`);
    pairs.set(index, childIndex);
  });
  return pairs;
}

export function mergePackageInstallers(items: Item[]): Item[] {
  const pairs = packageInstallerPairs(items), children = new Set(pairs.values());
  return items.flatMap((item, index) => children.has(index) ? [] : [{ ...item, ...(pairs.has(index) ? { installer: items[pairs.get(index)!] } : {}) }]);
}
