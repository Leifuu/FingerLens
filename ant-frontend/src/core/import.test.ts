import { describe, it, expect } from 'vitest';
import { gzipSync } from 'node:zlib';
import { existsSync, readFileSync } from 'node:fs';
import { parseFingerprint, readFingerprint, MAX_JSON, MAX_GZIP } from './import';
import { fixture, fixtureItem } from './fixtures';

describe('指纹导入边界', () => {
  const reportedFile = new URL('../../../fingerprint-6fac6d33-961b-4aaf-9e4d-c1069ef07723.json', import.meta.url);
  it.runIf(existsSync(reportedFile))('用户报告的原始文件可以直接导入，无需改写', () => { const fp = parseFingerprint(readFileSync(reportedFile, 'utf8')); expect(fp.data.sessionId).toBe('6fac6d33-961b-4aaf-9e4d-c1069ef07723'); expect(fp.data.items.length).toBe(1064); });
  it('无损保留中文、空白、换行、NUL 与 int64', () => { const fp = fixture([fixtureItem(), fixtureItem('long', '9223372036854775807', { dataType: 'int64' })]); expect(parseFingerprint(JSON.stringify(fp))).toEqual(fp); });
  it('拒绝重复 JSON 键，包括转义后同名键', () => { expect(() => parseFingerprint('{"data":{},"d\\u0061ta":{}}')).toThrow(/重复/); });
  it('接受 Android 传感器的 Float.MAX_VALUE 科学计数值', () => { const text = JSON.stringify(fixture([fixtureItem('sensors/list', [{ maximumRange: 3.4028235e38 }], { category: 'sensors' })])); expect(parseFingerprint(text).data.items[0].value).toEqual([{ maximumRange: 3.4028235e38 }]); });
  it('无损导入摄像头 Rational 的精确分数与特殊值对象，仍拒绝裸分数', () => {
    const values = [{ $type: 'android.util.Rational', value: '1/6', numerator: 1, denominator: 6 }, { $type: 'android.util.Rational', value: 'NaN', numerator: 0, denominator: 0 }, { $type: 'android.util.Rational', value: 'Infinity', numerator: 1, denominator: 0 }];
    const fp = fixture([fixtureItem('hardware/camera/0', { 'android.control.aeCompensationStep': values[0], calibration: values }, { category: 'hardware' })]);
    const text = JSON.stringify(fp);
    expect(parseFingerprint(text)).toEqual(fp);
    expect(() => parseFingerprint(text.replace('"numerator":1', '"numerator":1/6'))).toThrow(/非法 JSON/);
  });
  it('拒绝未知版本、非法类型和重复项目 key', () => { const fp = fixture(); fp.data.schemaVersion = '2.0' as '1.0'; expect(() => parseFingerprint(JSON.stringify(fp))).toThrow(); const bad = fixture([fixtureItem('a', 1, { dataType: 'string' })]); expect(() => parseFingerprint(JSON.stringify(bad))).toThrow(/类型/); expect(() => parseFingerprint(JSON.stringify(fixture([fixtureItem(), fixtureItem()])))).toThrow(/key 重复/); });
  it('拒绝深层、注释、尾逗号、非法数字、BOM 和缺字段', () => { for (const text of ['['.repeat(65) + '0' + ']'.repeat(65), '{/*注释*/"data":{}}', '{"x":1,}', '{"x":9007199254740993}', '{"x":1e999}', '\ufeff{}', '{}']) expect(() => parseFingerprint(text)).toThrow(); });
  it('合法空值与失败状态保持区分', () => { const fp = fixture([fixtureItem('ok', null), fixtureItem('denied', null, { status: 'denied' })]); expect(parseFingerprint(JSON.stringify(fp)).data.items.map(x => x.status)).toEqual(['ok', 'denied']); });
  it('gzip 与 JSON 得到相同指纹与摘要', async () => { const text = JSON.stringify(fixture()); const a = await readFingerprint(new File([text], 'fixture.json')); const b = await readFingerprint(new File([gzipSync(text)], 'fixture.json.gz')); expect(a.fingerprint).toEqual(b.fingerprint); expect(a.sha256).toBe(b.sha256); });
  it('拒绝扩展名、无效 UTF-8、截断 gzip 及输入超限', async () => { await expect(readFingerprint(new File(['x'], 'bad.zip'))).rejects.toThrow(); await expect(readFingerprint(new File([Uint8Array.of(0xff)], 'bad.json'))).rejects.toThrow(); await expect(readFingerprint(new File([Uint8Array.of(31, 139)], 'bad.json.gz'))).rejects.toThrow(); await expect(readFingerprint({ name: 'big.json', size: MAX_JSON + 1 } as File)).rejects.toThrow(/64 MiB/); await expect(readFingerprint({ name: 'big.json.gz', size: MAX_GZIP + 1 } as File)).rejects.toThrow(/16 MiB/); });
  it('流式解压拒绝压缩炸弹', async () => { const compressed = gzipSync(Buffer.alloc(MAX_JSON + 1, 32)); await expect(readFingerprint(new File([compressed], 'bomb.json.gz'))).rejects.toThrow(/解压后/); });
  it('原型键当作普通数据，不修改全局原型', () => { const fp = fixture([fixtureItem('safe', JSON.parse('{"__proto__":{"polluted":true}}'), { dataType: 'object' })]); expect(parseFingerprint(JSON.stringify(fp)).data.items[0].value).toEqual(fp.data.items[0].value); expect(({} as Record<string, unknown>).polluted).toBeUndefined(); });
});
