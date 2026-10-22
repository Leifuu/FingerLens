import fs from 'node:fs/promises';
import path from 'node:path';
import { createRequire } from 'node:module';
import { parseArgs } from 'node:util';
import crypto from 'node:crypto';
const require = createRequire(new URL('../../ant-frontend/package.json', import.meta.url));
const { chromium, expect } = require('@playwright/test');
const { values } = parseArgs({ options: { left: { type: 'string' }, right: { type: 'string' }, output: { type: 'string' }, 'package-key': { type: 'string' }, url: { type: 'string', default: 'http://127.0.0.1:5173' }, chrome: { type: 'string', default: 'C:/Program Files/Google/Chrome/Application/chrome.exe' } } });
if (!values.left || !values.output) throw new Error('用法：node browser-qa.mjs --left <JSON> [--right <JSON>] --output <新目录> [--url <URL>]');
const output = path.resolve(values.output);
await fs.mkdir(output, { recursive: false });
const browser = await chromium.launch({ executablePath: values.chrome, headless: true });
const context = await browser.newContext({ permissions: ['clipboard-read', 'clipboard-write'] });
const page = await context.newPage();
const external = [], errors = [], failures = [];
const origin = new URL(values.url).origin;
page.on('pageerror', e => errors.push(e.message));
page.on('requestfailed', request => { if (!request.failure()?.errorText.includes('ERR_ABORTED')) failures.push({ url: request.url(), error: request.failure()?.errorText }); });
await context.route('**/*', route => { const url = route.request().url(); if (/^https?:/.test(url) && new URL(url).origin !== origin) { external.push(url); return route.abort(); } return route.continue(); });
const report = { fixtureKind: values.right ? 'two-specified-files' : 'same-file-self-comparison-not-two-devices', inputs: { left: path.resolve(values.left), right: path.resolve(values.right || values.left) }, screenshots: [], external, errors, failures, checks: {} };
try {
  await page.setViewportSize({ width: 1440, height: 1100 });
  await page.goto(values.url);
  await page.screenshot({ path: path.join(output, 'empty-1440.png'), fullPage: true });
  await page.locator('input[type=file]').nth(0).setInputFiles(values.left);
  await expect(page.locator('.ready-label')).toHaveCount(1, { timeout: 30000 });
  await page.locator('input[type=file]').nth(1).setInputFiles(values.right || values.left);
  await expect(page.locator('.ready-label')).toHaveCount(2, { timeout: 30000 });
  await expect(page.locator('.cancel-processing')).toHaveCount(0, { timeout: 30000 });
  await expect(page.locator('.notice.error')).toHaveCount(0);
  await expect(page.locator('.table-row').first()).toBeVisible();
  report.checks.import = 'passed';
  report.checks.rawRows = await page.locator('.results-title h2 span').textContent();
  for (const width of [1440, 768, 390]) {
    await page.setViewportSize({ width, height: 1100 });
    await page.screenshot({ path: path.join(output, `comparison-${width}.png`), fullPage: true });
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > innerWidth);
    if (overflow) throw new Error(`页面横向溢出：${width}`);
    report.screenshots.push({ width, horizontalOverflow: overflow });
  }
  await page.setViewportSize({ width: 1440, height: 1100 });
  const first = page.locator('.table-row').first(); await first.focus(); await page.keyboard.press('Enter');
  await expect(page.getByRole('dialog')).toBeVisible();
  const raw = await page.locator('.detail-columns pre').first().textContent();
  await page.getByRole('button', { name: '格式化显示', exact: true }).click();
  await page.getByRole('button', { name: '还原显示', exact: true }).click();
  expect(await page.locator('.detail-columns pre').first().textContent()).toBe(raw);
  await page.getByRole('button', { name: '复制设备A原始值' }).click();
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(raw);
  await page.screenshot({ path: path.join(output, 'detail-1440.png'), fullPage: true });
  await page.keyboard.press('Escape'); await expect(page.getByRole('dialog')).toHaveCount(0);
  await page.keyboard.press('/'); await expect(page.getByRole('searchbox')).toBeFocused();
  await page.getByRole('searchbox').fill('no-such-field-qa'); await expect(page.getByText('当前条件下没有结果')).toBeVisible();
  await page.getByRole('searchbox').fill('');
  report.checks.formatCopyKeyboard = 'passed';
  const downloadTask = page.waitForEvent('download'); await page.getByRole('button', { name: '设备 A · 对照设备下载原件' }).click(); const download = await downloadTask; const downloaded = path.join(output, 'downloaded-original.json'); await download.saveAs(downloaded);
  const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
  expect(hash(await fs.readFile(downloaded))).toBe(hash(await fs.readFile(values.left)));
  report.checks.originalBytesUnchanged = true;
  if (values['package-key']) {
    await page.getByRole('searchbox').fill(values['package-key']);
    await expect(page.locator('.table-row')).toHaveCount(1);
    await page.locator('.table-row').click();
    await expect(page.locator('.detail-top > div > code')).toHaveText(values['package-key']);
    await expect(page.getByRole('region', { name: '设备A安装来源', exact: true })).toBeVisible();
    await expect(page.getByRole('region', { name: '设备B安装来源', exact: true })).toBeVisible();
    const rawInstaller = await page.locator('.installer-details pre').first().textContent();
    await page.getByRole('button', { name: '格式化显示', exact: true }).click();
    await page.getByRole('button', { name: '还原显示', exact: true }).click();
    expect(await page.locator('.installer-details pre').first().textContent()).toBe(rawInstaller);
    await page.getByRole('button', { name: '复制设备A安装来源原始值', exact: true }).click();
    expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(rawInstaller);
    report.checks.packageMerge = { key: values['package-key'], rows: 1, installerValue: rawInstaller, installerFormatCopy: 'passed', viewports: [] };
    for (const width of [1440, 768, 390]) {
      await page.setViewportSize({ width, height: 1100 });
      const dialog = page.getByRole('dialog');
      expect(await dialog.evaluate(element => element.scrollWidth > element.clientWidth)).toBe(false);
      await dialog.screenshot({ path: path.join(output, `installer-${width}.png`) });
      report.checks.packageMerge.viewports.push({ width, horizontalOverflow: false });
    }
    await page.keyboard.press('Escape'); await page.setViewportSize({ width: 1440, height: 1100 });
    await page.getByRole('searchbox').fill('');
  }
  await page.getByRole('button', { name: '语义视图', exact: true }).click();
  await expect(page.locator('.cancel-processing')).toHaveCount(0, { timeout: 30000 });
  await page.getByLabel('忽略时间与随机认证材料').check();
  await expect(page.locator('.cancel-processing')).toHaveCount(0, { timeout: 30000 });
  report.checks.semanticRows = await page.locator('.results-title h2 span').textContent();
  await page.getByRole('button', { name: '离线认证检查', exact: true }).click();
  await expect(page.getByRole('dialog')).toBeVisible({ timeout: 30000 });
  report.checks.attestationText = (await page.getByRole('dialog').textContent()).slice(0, 500);
  await page.screenshot({ path: path.join(output, 'attestation-1440.png'), fullPage: true });
  await page.keyboard.press('Escape');
  expect(external).toHaveLength(0); expect(errors).toHaveLength(0); expect(failures).toHaveLength(0);
  report.code = 0;
} catch (error) { report.code = 1; report.error = error.stack; await page.screenshot({ path: path.join(output, 'failure.png'), fullPage: true }); process.exitCode = 1; }
finally { await fs.writeFile(path.join(output, 'verification.json'), JSON.stringify(report, null, 2), 'utf8'); await browser.close(); }
console.log(JSON.stringify(report, null, 2));
