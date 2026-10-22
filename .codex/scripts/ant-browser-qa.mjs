import fs from 'node:fs/promises';
import path from 'node:path';
import { createRequire } from 'node:module';
import { parseArgs } from 'node:util';
import crypto from 'node:crypto';

const require = createRequire(new URL('../../ant-frontend/package.json', import.meta.url));
const { chromium, expect } = require('@playwright/test');
const { values } = parseArgs({ options: {
  left: { type: 'string' }, right: { type: 'string' }, output: { type: 'string' },
  url: { type: 'string', default: 'http://127.0.0.1:5173' },
  chrome: { type: 'string', default: 'C:/Program Files/Google/Chrome/Application/chrome.exe' },
} });
if (!values.left || !values.right || !values.output) throw new Error('需要 --left、--right、--output');
const output = path.resolve(values.output);
await fs.mkdir(output, { recursive: false });
const browser = await chromium.launch({ executablePath: values.chrome, headless: true });
const context = await browser.newContext({ permissions: ['clipboard-read', 'clipboard-write'] });
const page = await context.newPage();
const external = [], errors = [], failures = [];
const origin = new URL(values.url).origin;
page.on('pageerror', error => errors.push(error.message));
page.on('requestfailed', request => { if (!request.failure()?.errorText?.includes('ERR_ABORTED')) failures.push({ url: request.url(), error: request.failure()?.errorText }); });
await context.route('**/*', route => {
  const url = route.request().url();
  if (/^https?:/.test(url) && new URL(url).origin !== origin) { external.push(url); return route.abort(); }
  return route.continue();
});
const report = { inputs: { left: path.resolve(values.left), right: path.resolve(values.right) }, screenshots: [], checks: {}, external, errors, failures, code: 1 };
try {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto(values.url, { waitUntil: 'networkidle' });
  const nav = page.locator('.ant-layout-sider');
  const navBefore = await nav.boundingBox();
  await page.locator('.app-layout > .ant-layout').evaluate(element => { element.scrollTop = element.scrollHeight; });
  const navAfter = await nav.boundingBox();
  expect(navAfter.y).toBe(navBefore.y);
  report.checks.sidebarFixed = true;
  await page.locator('input[type=file]').nth(0).setInputFiles(values.left);
  await expect(page.locator('.file-card-a').getByText('已载入', { exact: true })).toBeVisible({ timeout: 30000 });
  await page.locator('input[type=file]').nth(1).setInputFiles(values.right);
  await expect(page.locator('.file-card-b').getByText('已载入', { exact: true })).toBeVisible({ timeout: 30000 });
  await expect(page.getByRole('button', { name: '取消处理' })).toHaveCount(0, { timeout: 30000 });
  const dataRows = () => page.locator('.comparison-table .ant-table-tbody tr:not(.ant-table-measure-row)');
  await expect.poll(() => dataRows().count(), { timeout: 30000 }).toBeGreaterThan(0);
  report.checks.import = 'passed';
  report.checks.rowCount = await dataRows().count();
  report.checks.summaryChanged = await page.locator('.stat-cards .ant-statistic-content-value').first().textContent();
  const controlRow = page.locator('.file-card-a .capture-controls');
  const controlHeights = await Promise.all(['.device-select', 'button[aria-label="采集设备 A"]', '.capture-status-tag'].map(selector => controlRow.locator(selector).first().evaluate(element => element.getBoundingClientRect().height)));
  expect(Math.max(...controlHeights) - Math.min(...controlHeights)).toBeLessThanOrEqual(1);
  report.checks.captureControlHeights = controlHeights;

  await page.getByRole('menuitem', { name: /应用清单/ }).click();
  await expect(page.locator('.results-card')).toBeVisible();
  expect(await page.locator('.content-inner').evaluate(element => element.children.length)).toBe(1);
  await expect(page.locator('.hero, .file-card, .stat-cards, .action-row')).toHaveCount(0);
  report.checks.categoryPageShowsOnlyResults = true;
  await page.getByRole('menuitem', { name: /全部信息/ }).click();

  const statusSelect = page.getByLabel('筛选对比状态').first();
  await statusSelect.click();
  await page.getByText('值 / 状态不同', { exact: true }).last().click();
  await expect.poll(() => dataRows().count()).toBeGreaterThan(0);
  report.checks.statusDifferentRows = await dataRows().count();
  report.checks.statusDifferentVisible = report.checks.statusDifferentRows > 0;
  report.checks.statusDifferentTotal = await page.locator('.comparison-table .ant-pagination-total-text').textContent();
  expect(report.checks.statusDifferentTotal).toContain(report.checks.summaryChanged);
  await statusSelect.click();
  await page.getByText('全部状态', { exact: true }).last().click();

  const downloadTask = page.waitForEvent('download');
  await page.getByRole('button', { name: '下载设备 A 原件', exact: true }).click();
  const download = await downloadTask;
  const downloaded = path.join(output, 'downloaded-original.json');
  await download.saveAs(downloaded);
  const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
  expect(hash(await fs.readFile(downloaded))).toBe(hash(await fs.readFile(values.left)));
  report.checks.originalBytesUnchanged = true;

  // 首行可能是独立的采集会话元数据；详情结构化验收选择下一条设备字段。
  const first = dataRows().nth(1);
  await first.click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.waitForTimeout(350);
  const drawer = page.getByRole('dialog');
  const drawerText = await drawer.textContent();
  if (drawerText.includes('argv') || drawerText.includes('exitCode')) throw new Error('格式化详情混入命令包装字段');
  report.checks.formattedDefault = !drawerText.includes('原始值') && (await drawer.locator('.comparison-section').count()) > 0;
  expect(report.checks.formattedDefault).toBe(true);
  const copyValue = await drawer.locator('.copyable-value').first().textContent();
  if (copyValue !== null) {
    await drawer.locator('.copyable-value').first().dblclick();
    await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toBe(copyValue);
    report.checks.doubleClickCopy = true;
  }
  report.checks.sectionCards = await drawer.locator('.comparison-section').count();
  report.checks.tables = await drawer.locator('.comparison-section .ant-table').count();
  const header = drawer.locator('.ant-drawer-header');
  report.checks.stickyHeader = await header.evaluate(element => getComputedStyle(element).position === 'sticky');
  await page.screenshot({ path: path.join(output, 'detail-1440.png'), fullPage: true });
  await page.keyboard.press('Escape');

  await page.getByPlaceholder('搜索名称、字段或来源').fill('内置屏幕');
  await expect.poll(() => dataRows().count()).toBeGreaterThan(0);
  await dataRows().first().click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.waitForTimeout(350);
  const displayDrawer = page.getByRole('dialog');
  const displayText = await displayDrawer.textContent();
  report.checks.displaySubsections = ['分辨率与刷新', '支持模式'].every(title => displayText.includes(title));
  report.checks.displayTables = await displayDrawer.locator('.comparison-section .ant-table').count();
  expect(report.checks.displaySubsections).toBe(true);
  expect(report.checks.displayTables).toBeGreaterThanOrEqual(4);
  const beforeScroll = await displayDrawer.locator('.ant-drawer-header').boundingBox();
  await displayDrawer.locator('.ant-drawer-body').evaluate(element => { element.scrollTop = element.scrollHeight; });
  const afterScroll = await displayDrawer.locator('.ant-drawer-header').boundingBox();
  expect(afterScroll.y).toBe(beforeScroll.y);
  await expect(displayDrawer.locator('.ant-drawer-close')).toBeVisible();
  report.checks.headerAndCloseFixedOnScroll = true;
  await displayDrawer.locator('.ant-drawer-body').evaluate(element => { element.scrollTop = 0; });
  await page.screenshot({ path: path.join(output, 'display-1440.png'), fullPage: true });
  await page.keyboard.press('Escape');

  await page.getByPlaceholder('搜索名称、字段或来源').fill('/system/bin/getprop');
  await expect(dataRows()).toHaveCount(1);
  await dataRows().first().click();
  await expect(page.getByRole('dialog')).toBeVisible();
  const getpropText = await page.getByRole('dialog').textContent();
  for (const key of ['argv', 'exitCode', 'stderr']) expect(getpropText).not.toContain(key);
  expect(getpropText).toContain('设备属性 stdout');
  report.checks.getpropOnlyStdout = true;
  await page.keyboard.press('Escape');

  await page.getByPlaceholder('搜索名称、字段或来源').fill('diskstats');
  await expect(dataRows()).toHaveCount(1);
  await dataRows().first().click();
  await expect(page.getByRole('dialog')).toBeVisible();
  const diskstatsDrawer = page.getByRole('dialog');
  const diskstatsRows = diskstatsDrawer.locator('.comparison-section .ant-table-tbody tr');
  expect(await diskstatsRows.count()).toBeGreaterThan(2);
  expect(await diskstatsDrawer.locator('.row-same').count()).toBeGreaterThan(0);
  const diskstatsSameRows = await diskstatsDrawer.locator('.row-same').count();
  const onlyDiff = diskstatsDrawer.getByRole('button', { name: '只展示差异', exact: true });
  await expect(onlyDiff).toBeVisible();
  await onlyDiff.click();
  expect(await diskstatsDrawer.locator('.row-same').count()).toBe(0);
  report.checks.onlyDifferenceRows = { sameRowsBefore: diskstatsSameRows, sameRowsAfter: await diskstatsDrawer.locator('.row-same').count() };
  await onlyDiff.click();
  report.checks.multilineProcRows = true;
  await page.keyboard.press('Escape');

  await page.getByPlaceholder('搜索名称、字段或来源').fill('packages/enumeration');
  await dataRows().first().click();
  await expect(page.getByRole('dialog')).toBeVisible();
  expect(await page.getByRole('dialog').textContent()).not.toContain('visibility');
  report.checks.noCollectorVisibilityText = true;
  await page.keyboard.press('Escape');
  await page.getByPlaceholder('搜索名称、字段或来源').fill('');

  for (const width of [1440, 768, 390]) {
    await page.setViewportSize({ width, height: 1000 });
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > innerWidth);
    if (overflow) throw new Error(`页面横向溢出：${width}`);
    report.screenshots.push({ width, horizontalOverflow: overflow });
    await page.screenshot({ path: path.join(output, `comparison-${width}.png`), fullPage: true });
  }
  const fileCardHeights = await page.locator('.file-card .ant-card-body').evaluateAll(elements => elements.map(element => Math.round(element.getBoundingClientRect().height)));
  expect(new Set(fileCardHeights).size).toBe(1);
  report.checks.fileCardBodyHeights = fileCardHeights;
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.getByRole('button', { name: '离线认证检查', exact: true }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await expect(page.getByRole('dialog').locator('.ant-card')).toHaveCount(2, { timeout: 30000 });
  report.checks.attestation = true;
  await page.keyboard.press('Escape');
  await dataRows().nth(1).focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.keyboard.press('Escape');
  report.checks.keyboardOpenClose = true;
  await page.getByPlaceholder('搜索名称、字段或来源').blur();
  await page.locator('body').click({ position: { x: 10, y: 10 } });
  await page.keyboard.press('/');
  await expect(page.getByPlaceholder('搜索名称、字段或来源')).toBeFocused();
  report.checks.keyboardSearch = true;
  if (external.length || errors.length || failures.length) throw new Error('存在外部请求或页面错误');
  report.code = 0;
} catch (error) {
  report.error = error.stack || String(error);
  await page.screenshot({ path: path.join(output, 'failure.png'), fullPage: true }).catch(() => {});
  process.exitCode = 1;
} finally {
  report.external = external; report.errors = errors; report.failures = failures;
  await fs.writeFile(path.join(output, 'verification.json'), JSON.stringify(report, null, 2), 'utf8');
  await browser.close();
}
console.log(JSON.stringify(report, null, 2));
