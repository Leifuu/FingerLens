import { describe, expect, it } from 'vitest';
import { matchesSearch } from './search';
import { fixture, fixtureItem } from './fixtures';
import { compare } from './compare';
import type { Json } from './types';

const row = (value: Json) => compare(fixture([fixtureItem('build/props/ro.system.build.fingerprint', value, { category: 'build', name: 'ro.system.build.fingerprint' })]), undefined, { semantic: false, ignoreDynamic: false }).find(item => item.key === 'build/props/ro.system.build.fingerprint')!;

describe('搜索索引', () => {
  it('内容模式通过嵌套值定位字段，输入未提交时不会参与这里的过滤', () => {
    const target = row({ value: 'samsung', nested: { fingerprint: 'SM-S9180' } });
    expect(matchesSearch(target, 'content', 'samsung')).toBe(true);
    expect(matchesSearch(target, 'content', 'sm-s9180')).toBe(true);
    expect(matchesSearch(target, 'field', 'ro.system.build')).toBe(true);
    expect(matchesSearch(target, 'name', 'samsung')).toBe(false);
  });

  it('内容索引缓存不改变原始对象', () => {
    const target = row({ brand: 'Samsung', fingerprint: 'a/b/c' });
    const before = JSON.stringify(target);
    matchesSearch(target, 'content', 'samsung');
    matchesSearch(target, 'content', 'fingerprint');
    expect(JSON.stringify(target)).toBe(before);
  });
});
