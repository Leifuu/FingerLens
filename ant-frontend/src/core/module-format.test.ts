import { describe, expect, it } from 'vitest';
import { formattedText, rawText } from './format';
import { formatModule } from './module-format';
import type { Item } from './types';

function item(key: string, value: Item['value'], category = key.split('/')[0]): Item {
  return { key, category, name: key, source: { kind: 'api', api: key === 'build/getprop' ? '/system/bin/getprop' : key, uid: 1000, pid: 1, selinux: 'u:r:untrusted_app:s0' }, dataType: typeof value, status: 'ok', value };
}

describe('模块专属格式化', () => {
  it('getprop 只展示 stdout，不泄漏命令包装字段', () => {
    const value = { argv: ['/system/bin/getprop'], stdout: '[ro.test]: [one]\n', stderr: 'ignored', exitCode: 0 };
    const text = formattedText(value, '/system/bin/getprop', 'build', 'build/getprop');
    expect(text).toBe('[ro.test]: [one]\n');
    expect(text).not.toContain('argv');
    expect(text).not.toContain('exitCode');
  });

  it('显示器、输入设备和网络使用各自的分组布局', () => {
    expect(formatModule(item('display/0', { widthPixels: 720, heightPixels: 1280, supportedModes: [{ modeId: 1 }] }, 'display')).text).toContain('分辨率与刷新');
    expect(formatModule(item('input/device/1', { name: 'touch', motionRanges: [{ axis: 1 }] }, 'input')).text).toContain('运动范围（1）');
    expect(formatModule(item('network/interfaces', [{ name: 'wlan0', up: true, addresses: [{ address: '10.0.0.1' }] }], 'network')).text).toContain('wlan0 · up');
    expect(formatModule(item('security/self', { uid: 10103, pid: 42, debuggable: false }, 'security')).text).toContain('uid: 10103');
  });

  it('格式化不改变原始值', () => {
    const value = { a: '<script>bad()</script>' };
    const before = rawText(value);
    formattedText(value, '', '', 'generic/value');
    expect(rawText(value)).toBe(before);
  });

  it('多行 proc 文本按行展示，单行相同内容可单独判定', () => {
    const value = 'a  b  c\nsecond line\nthird line';
    const sections = formatModule(item('kernel/proc-diskstats', value, 'kernel'));
    expect(sections.text).toContain('second line');
    expect(sections.text).toContain('third line');
  });
});
