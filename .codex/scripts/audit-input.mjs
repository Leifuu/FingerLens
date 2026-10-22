import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { parseArgs } from 'node:util';
import { gzipSync } from 'node:zlib';
const require = createRequire(new URL('../../ant-frontend/package.json', import.meta.url));
const { visit } = require('jsonc-parser');
const { values, positionals } = parseArgs({ allowPositionals: true, options: { 'gzip-output': { type: 'string' } } });
const file = positionals[0];
if (!file) throw new Error('用法：node audit-input.mjs <指纹JSON路径> [--gzip-output <新文件.json.gz>]');
const text = fs.readFileSync(file, 'utf8');
const problems = [];
visit(text, { onLiteralValue(value, offset, length, line, column, getPath) {
  if (typeof value === 'number' && (!Number.isFinite(value) || /^-?\d+$/.test(text.slice(offset, offset + length)) && !Number.isSafeInteger(value))) {
    problems.push({ path: getPath(), token: text.slice(offset, offset + length), parsed: String(value), offset });
  }
} });
console.log(JSON.stringify({ data: { file: path.resolve(file), unsafeNumbers: problems }, message: '原始数字token检查，未修改输入文件', code: problems.length ? 1 : 0 }, null, 2));
if (values['gzip-output']) { const zipped = gzipSync(fs.readFileSync(file)); if (zipped.length > 16 * 1024 * 1024) throw new Error('压缩结果超过16MiB'); fs.writeFileSync(values['gzip-output'], zipped, { flag: 'wx' }); }
