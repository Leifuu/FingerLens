import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import zlib from 'node:zlib';
import { parseArgs } from 'node:util';
import { createRequire } from 'node:module';

const require = createRequire(new URL('../../ant-frontend/package.json', import.meta.url));
const { chromium, expect } = require('@playwright/test');
const { values } = parseArgs({ options: {
  serial: { type: 'string' }, output: { type: 'string' }, right: { type: 'string' }, left: { type: 'string' },
  fixture: { type: 'boolean' }, cancel: { type: 'boolean' }, 'capture-b': { type: 'boolean' },
  url: { type: 'string', default: 'http://127.0.0.1:5173' },
  chrome: { type: 'string', default: 'C:/Program Files/Google/Chrome/Application/chrome.exe' },
} });
if (!values.output || (!values.fixture && !values.serial) || (values.fixture && (!values.left || !values.right))) throw new Error('需要新 --output；实测需要 --serial；合成双设备需要 --fixture --left --right');
const output = path.resolve(values.output);
await fs.mkdir(output, { recursive: false });
const browser = await chromium.launch({ executablePath: values.chrome, headless: true });
const context = await browser.newContext();
const page = await context.newPage();
const report = { synthetic: !!values.fixture, serial: values.serial, checks: {}, jobs: [], errors: [], external: [], failures: [], code: 1 };
const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
page.on('pageerror', error => report.errors.push(error.message));
page.on('requestfailed', request => { if (!request.failure()?.errorText?.includes('ERR_ABORTED')) report.failures.push(request.url()); });
const origin = new URL(values.url).origin;
await context.route('**/*', route => {
  const url = route.request().url();
  if (/^https?:/.test(url) && new URL(url).origin !== origin) { report.external.push(url); return route.abort(); }
  return route.continue();
});
let fixtureJobs = new Map();
if (values.fixture) {
  const documents = [await fs.readFile(values.left), await fs.readFile(values.right)];
  await context.route('**/api/**', async route => {
    const request = route.request(), pathname = new URL(request.url()).pathname;
    const respond = (data, status = 200) => route.fulfill({ status, json: { data, message: '', code: status < 400 ? 0 : status } });
    if (pathname === '/api/devices') return respond(['fixture-A', 'fixture-B'].map(serial => ({ serial, status: 'device', model: '合成测试设备', busy: false })));
    if (pathname === '/api/jobs' && request.method() === 'POST') {
      const serial = request.postDataJSON().serial, id = crypto.randomUUID();
      const file = documents[serial === 'fixture-A' ? 0 : 1];
      const job = { id, serial, state: 'collecting', progress: 25, message: '合成进度，不是设备实测', fileName: `fingerprint-${id}.json`, bytes: file.length, created: Date.now(), file };
      fixtureJobs.set(id, job); return respond({ ...job, file: undefined }, 202);
    }
    const match = pathname.match(/^\/api\/jobs\/([^/]+)(\/file|\/cancel)?$/);
    const job = match && fixtureJobs.get(match[1]);
    if (!job) return respond(null, 404);
    if (match[2] === '/file') return route.fulfill({ body: job.file, contentType: 'application/json' });
    if (match[2] === '/cancel') { job.state = 'cancelled'; job.collectionState = 'cancelled'; return respond({ ...job, file: undefined }); }
    if (Date.now() - job.created > 3500 && job.state !== 'cancelled') { job.state = 'ready'; job.progress = 100; job.collectionState = 'partial'; }
    return respond({ ...job, file: undefined });
  });
}

async function choose(side, serial) {
  await page.getByRole('combobox', { name: `设备 ${side} 的 ADB 设备`, exact: true }).click();
  await page.getByTitle(new RegExp(`^${serial.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`)).last().click();
}
async function start(side) {
  const response = page.waitForResponse(response => new URL(response.url()).pathname === '/api/jobs' && response.request().method() === 'POST');
  await page.getByRole('button', { name: `采集设备 ${side}`, exact: true }).click();
  const body = await (await response).json();
  expect(body.code).toBe(0); report.jobs.push({ side, ...body.data });
  console.log(`设备 ${side} 任务 ${body.data.id}`);
  return body.data;
}
async function waitImported(side) {
  const card = page.locator(`.file-card-${side.toLowerCase()}`);
  await expect(card.getByText('已载入', { exact: true })).toBeVisible({ timeout: 280000 });
  await expect(card.getByText(/已.*导入|已采集并导入/).first()).toBeVisible({ timeout: 30000 });
  const downloadTask = page.waitForEvent('download');
  await page.getByRole('button', { name: `下载设备 ${side} 原件`, exact: true }).click();
  const download = await downloadTask;
  const target = path.join(output, download.suggestedFilename());
  await download.saveAs(target);
  const bytes = await fs.readFile(target), raw = target.endsWith('.gz') ? zlib.gunzipSync(bytes) : bytes;
  const snapshot = JSON.parse(raw);
  const job = report.jobs.find(job => job.side === side);
  if (!values.fixture) {
    expect(snapshot.data.sessionId).toBe(job.id);
    expect(snapshot.data.app.uid).toBeGreaterThanOrEqual(10000);
    expect(snapshot.data.app.debuggable).toBe(false);
    expect(snapshot.data.app.versionName).toBe('1.1.0');
    const result = await page.request.get(`${values.url}/api/jobs/${job.id}`);
    const status = (await result.json()).data;
    expect(hash(raw)).toBe(status.sha256);
    report.checks[`${side}Capture`] = { ...status, observedState: snapshot.data.state, items: snapshot.data.items.length,
      downloaded: target, sha256: hash(raw), jsonBytes: raw.length, transferBytes: bytes.length };
  } else report.checks[`${side}FixtureBytes`] = hash(raw);
  console.log(`设备 ${side} 导入 ${snapshot.data.items.length} 项，${bytes.length} 字节`);
}

try {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto(values.url, { waitUntil: 'networkidle' });
  await expect(page.getByText('FingerLens • 观纹', { exact: true })).toBeVisible();
  await choose('A', values.fixture ? 'fixture-A' : values.serial);
  if (values.fixture) await choose('B', 'fixture-B');
  await start('A');
  if (values.cancel) {
    await expect(page.locator('.file-card-a').getByRole('button', { name: '取消采集' })).toBeEnabled({ timeout: 15000 });
    await page.locator('.file-card-a').getByRole('button', { name: '取消采集' }).click();
  }
  if (values.fixture) await start('B');
  await waitImported('A');
  if (values.fixture) await waitImported('B');
  else if (values['capture-b']) {
    await choose('B', values.serial); await start('B'); await waitImported('B');
  } else if (values.right) {
    await page.locator('.file-card-b input[type=file]').setInputFiles(values.right);
    await expect(page.locator('.file-card-b').getByText('已载入', { exact: true })).toBeVisible({ timeout: 30000 });
    report.checks.legacyImport = true;
  }
  await expect(page.getByRole('button', { name: '取消处理', exact: true })).toHaveCount(0, { timeout: 30000 });
  if (values.right || values['capture-b'] || values.fixture) report.checks.comparisonRows = await page.locator('.comparison-table tr[data-row-key]').count();
  for (const width of [1440, 768, 390]) {
    await page.setViewportSize({ width, height: 1000 });
    await page.waitForTimeout(300);
    const sizes = await page.evaluate(() => ({ scroll: document.documentElement.scrollWidth, width: window.innerWidth }));
    expect(sizes.scroll).toBeLessThanOrEqual(sizes.width);
    const select = await page.locator('.file-card-a .device-select').boundingBox();
    const button = await page.getByRole('button', { name: '采集设备 A', exact: true }).boundingBox();
    expect(Math.abs(select.y - button.y)).toBeLessThanOrEqual(2);
    await page.screenshot({ path: path.join(output, `${width}.png`), fullPage: false });
  }
  report.checks.controlsSameRow = true;
  expect(report.errors).toEqual([]); expect(report.external).toEqual([]); expect(report.failures).toEqual([]);
  report.code = 0;
} finally {
  await fs.writeFile(path.join(output, 'verification.json'), JSON.stringify(report, null, 2), 'utf8');
  await browser.close();
}
