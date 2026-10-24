import { describe, expect, it } from 'vitest';
import { fixture, fixtureItem } from '../core/fixtures';
import type { Imported, Row } from '../core/types';
import { buildDiffReportHtml, reportFileName } from './report';

function imported(name: string, sessionId: string, value: string): Imported {
  const fingerprint = fixture([fixtureItem('build/test', value)]);
  fingerprint.data.sessionId = sessionId;
  return { name, size: value.length, sha256: 'a'.repeat(64), fingerprint };
}

function row(left: Imported, right: Imported): Row {
  return { id: 'build/test#0', key: 'build/test', name: '测试字段', category: 'build', state: 'different', left: left.fingerprint.data.items[0], right: right.fingerprint.data.items[0], changedPaths: ['/value'], ignored: 0 };
}

describe('HTML 差异报告', () => {
  it('生成单文件报告并转义设备值', () => {
    const left = imported('a.json', 'left/<script>', '<script>alert(1)</script>');
    const right = imported('b.json', 'right', 'safe');
    const html = buildDiffReportHtml({ left, right, rows: [row(left, right)], rules: { semantic: false, ignoreDynamic: false }, warnings: ['采集状态不同'], view: { category: 'all', search: '', onlyDiff: true, status: 'all', scope: '完整差异' } });
    expect(html).toContain('<!doctype html>');
    expect(html).toContain('&lt;script&gt;alert(1)&lt;/script&gt;');
    expect(html).toContain('值不同');
    expect(html).toContain('采集状态不同');
    expect(html).toContain('name="report-diff-filter"');
    expect(html).not.toContain('<script>alert(1)</script>');
  });

  it('忽略目录元数据批次，并保留报告内筛选控件', () => {
    const left = imported('a.json', 'left', 'a');
    const right = imported('b.json', 'right', 'b');
    const directoryRow = { ...row(left, right), id: 'directories/chunk/00000#0', key: 'directories/chunk/00000', category: 'directories' };
    const html = buildDiffReportHtml({ left, right, rows: [directoryRow, row(left, right)], rules: { semantic: false, ignoreDynamic: false }, warnings: [], view: { category: 'all', search: '', onlyDiff: false, status: 'all', scope: '当前视图' } });
    expect(html).not.toContain('directories/chunk/00000');
    expect(html).toContain('已忽略 1 个目录元数据批次');
    expect(html).toContain('仅值不同');
  });

  it('忽略 PackageManager.getInstalledPackages 产生的具体对比项', () => {
    const left = imported('a.json', 'left', 'a');
    const right = imported('b.json', 'right', 'b');
    const packageRow = { ...row(left, right), id: 'packages/com.example#0', key: 'packages/com.example', category: 'packages', left: { ...left.fingerprint.data.items[0], source: { ...left.fingerprint.data.items[0].source, api: 'PackageManager.getInstalledPackages(134238223)' } }, right: { ...right.fingerprint.data.items[0], source: { ...right.fingerprint.data.items[0].source, api: 'PackageManager.getInstalledPackages(134238223)' } } };
    const html = buildDiffReportHtml({ left, right, rows: [packageRow], rules: { semantic: false, ignoreDynamic: false }, warnings: [], view: { category: 'all', search: '', onlyDiff: false, status: 'all', scope: '当前视图' } });
    expect(html).not.toContain('packages/com.example');
    expect(html).toContain('已忽略 1 个 PackageManager.getInstalledPackages 对比项');
  });

  it('文件名只保留安全字符', () => {
    const left = imported('a.json', 'left/session/1', 'a');
    const right = imported('b.json', 'right session', 'b');
    expect(reportFileName(left, right)).toMatch(/^fingerprint-diff-left-session-1-right-session-.*\.html$/);
  });

  it('按行渲染对比表，并对一致与不同的行着色', () => {
    const left = imported('a.json', 'left', 'same\nleft-only');
    const right = imported('b.json', 'right', 'same\nright-only');
    const html = buildDiffReportHtml({ left, right, rows: [row(left, right)], rules: { semantic: false, ignoreDynamic: false }, warnings: [], view: { category: 'all', search: '', onlyDiff: false, status: 'all', scope: '完整差异' } });
    expect(html).toContain('class="comparison-table"');
    expect(html).toContain('comparison-row row-same');
    expect(html).toContain('comparison-row row-changed');
    expect(html).toContain('background:#ecf8f0');
    expect(html).toContain('background:#fff0ef');
    expect(html).toContain('position:sticky');
    expect(html).toContain('top:0');
    expect(html).toContain('data-filter-toggle');
    expect(html).toContain('只显示差异行');
    expect(html).toContain('hide-same-rows');
    expect(html).toContain('当前检测项没有差异行');
  });
});
