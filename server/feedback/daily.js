'use strict';

/**
 * 每天 20:00 把当天的反馈和设备档案汇总成一份 markdown。
 *
 * 目的不是自动修 bug，而是让人每天花两分钟就能看清"今天谁遇到了什么"。
 */

const fs = require('fs');
const path = require('path');

const DIR = path.join(__dirname, 'data');
const OUT = path.join(__dirname, 'reports');

fs.mkdirSync(OUT, { recursive: true });

function today() {
  const d = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
}

function readLines(file) {
  if (!fs.existsSync(file)) return [];
  return fs.readFileSync(file, 'utf8')
    .split('\n')
    .filter((line) => line.trim().length > 0)
    .map((line) => {
      try {
        return JSON.parse(line);
      } catch (e) {
        return null;
      }
    })
    .filter(Boolean);
}

const PROBLEM_LABELS = {
  listen: '听不出区别',
  crash: '崩溃或闪退',
  ui: '界面异常',
  other: '其它问题'
};

function shortTime(iso) {
  const d = new Date(iso);
  const pad = (n) => String(n).padStart(2, '0');
  return pad(d.getHours()) + ':' + pad(d.getMinutes());
}

function renderReport(records, index) {
  const p = records.payload || {};
  const lines = [];
  lines.push('### ' + index + '. ' + (PROBLEM_LABELS[p.kind] || p.kind || '未分类') + ' · ' + shortTime(records.receivedAt));
  lines.push('');
  lines.push('机型 `' + (p.model || '未知') + '` · SDK ' + (p.sdk || '?') +
    ' · 系统 ' + (p.miui || '?') + ' · v' + (p.versionName || '?') + ' (' + (p.versionCode || '?') + ')');
  lines.push('');
  lines.push('> ' + String(p.text || '').replace(/\n/g, '\n> '));
  if (p.logs) {
    lines.push('');
    lines.push('<details><summary>运行日志</summary>');
    lines.push('');
    lines.push('```');
    lines.push(String(p.logs).trim());
    lines.push('```');
    lines.push('');
    lines.push('</details>');
  }
  lines.push('');
  return lines.join('\n');
}

function renderDevice(record) {
  const p = record.payload || {};
  const ok = Number(p.stage) === 0;
  return '- `' + (p.model || '未知') + '` SDK ' + (p.sdk || '?') +
    ' · ' + (ok ? '链路正常' : '卡在 ' + (p.stageLabel || ('第 ' + p.stage + ' 步'))) +
    (p.reason && !ok ? ' · ' + String(p.reason).slice(0, 120) : '') +
    (p.backend ? ' · 引擎 ' + p.backend : '');
}

const day = process.argv[2] || today();
const reports = readLines(path.join(DIR, 'report-' + day + '.jsonl'));
const devices = readLines(path.join(DIR, 'device-' + day + '.jsonl'));

const out = [];
out.push('# AURORA 反馈 · ' + day);
out.push('');
out.push('用户反馈 ' + reports.length + ' 条 · 设备档案 ' + devices.length + ' 条');
out.push('');

if (reports.length > 0) {
  out.push('## 用户反馈');
  out.push('');
  reports.forEach((record, i) => out.push(renderReport(record, i + 1)));
} else {
  out.push('## 用户反馈');
  out.push('');
  out.push('今天没有新的反馈。');
  out.push('');
}

if (devices.length > 0) {
  out.push('## 设备档案');
  out.push('');
  const failing = devices.filter((r) => Number((r.payload || {}).stage) !== 0);
  devices.forEach((record) => out.push(renderDevice(record)));
  out.push('');
  if (failing.length > 0) {
    out.push('其中 ' + failing.length + ' 台设备无法建立链路，需要单独适配。');
  } else {
    out.push('今天上报的设备链路全部正常。');
  }
  out.push('');
}

const file = path.join(OUT, day + '.md');
fs.writeFileSync(file, out.join('\n'), 'utf8');
console.log('wrote ' + file + ' (reports=' + reports.length + ', devices=' + devices.length + ')');