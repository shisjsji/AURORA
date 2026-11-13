'use strict';

/**
 * AURORA 反馈接收端。
 *
 * 两个入口：
 *   POST /         设备档案（自动，只收探测结果）
 *   POST /report   用户反馈（手动，可附日志）
 *
 * 字段走白名单，避免被塞垃圾；不记录来源 IP —— 需要的是设备与链路信息，不是谁发来的。
 */

const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = 5002;
const HOST = '127.0.0.1';
const DATA_DIR = path.join(__dirname, 'data');
const MAX_BODY = 256 * 1024;
const MAX_STRING = 4000;
const MAX_LOG = 120 * 1024;
const WINDOW_MS = 60 * 1000;
const MAX_PER_WINDOW = 30;

const DEVICE_FIELDS = [
  'kind', 'stage', 'stageLabel', 'reason', 'model', 'soc', 'sdk', 'miui',
  'effects', 'outputs', 'route', 'versionCode', 'versionName', 'probedAt', 'backend', 'backendMessage', 'bandHz',
  'anonId'
];

const REPORT_FIELDS = [
  'kind', 'problem', 'text', 'model', 'sdk', 'miui', 'versionCode',
  'versionName', 'anonId'
];

fs.mkdirSync(DATA_DIR, { recursive: true });

const hits = new Map();

function rateLimited(ip) {
  const now = Date.now();
  const record = hits.get(ip);
  if (!record || now > record.reset) {
    hits.set(ip, { count: 1, reset: now + WINDOW_MS });
    return false;
  }
  record.count += 1;
  return record.count > MAX_PER_WINDOW;
}

function day() {
  const d = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
}

function clean(body, fields) {
  const out = {};
  for (const key of fields) {
    if (!(key in body)) continue;
    const value = body[key];
    if (typeof value === 'string') out[key] = value.slice(0, MAX_STRING);
    else if (typeof value === 'number' || typeof value === 'boolean') out[key] = value;
  }
  return out;
}

function reply(res, code, payload) {
  res.writeHead(code, { 'Content-Type': 'application/json' });
  res.end(payload);
}

const server = http.createServer((req, res) => {
  if (req.method !== 'POST') {
    res.writeHead(405, { 'Content-Type': 'text/plain' });
    res.end('method not allowed');
    return;
  }

  const route = (req.url || '/').split('?')[0];
  const isReport = route === '/report' || route === '/report/';
  const prefix = isReport ? 'report' : 'device';

  const ip = req.headers['x-real-ip'] || req.socket.remoteAddress || 'unknown';
  if (rateLimited(ip)) {
    res.writeHead(429, { 'Content-Type': 'text/plain' });
    res.end('too many requests');
    return;
  }

  let size = 0;
  const chunks = [];

  req.on('data', (chunk) => {
    size += chunk.length;
    if (size > MAX_BODY) {
      res.writeHead(413, { 'Content-Type': 'text/plain' });
      res.end('body too large');
      req.destroy();
      return;
    }
    chunks.push(chunk);
  });

  req.on('end', () => {
    if (res.writableEnded) return;

    let parsed;
    try {
      parsed = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    } catch (e) {
      res.writeHead(400, { 'Content-Type': 'text/plain' });
      res.end('bad json');
      return;
    }
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      res.writeHead(400, { 'Content-Type': 'text/plain' });
      res.end('bad payload');
      return;
    }

    const payload = clean(parsed, isReport ? REPORT_FIELDS : DEVICE_FIELDS);

    if (isReport) {
      if (typeof parsed.logs === 'string' && parsed.logs.length > 0) {
        payload.logs = parsed.logs.slice(0, MAX_LOG);
      }
      if (!payload.text || String(payload.text).trim().length < 4) {
        reply(res, 400, '{"ok":false,"error":"text too short"}');
        return;
      }
    }

    const record = { receivedAt: new Date().toISOString(), payload };
    const file = path.join(DATA_DIR, prefix + '-' + day() + '.jsonl');
    try {
      fs.appendFileSync(file, JSON.stringify(record) + '\n');
    } catch (e) {
      res.writeHead(500, { 'Content-Type': 'text/plain' });
      res.end('write failed');
      return;
    }

    reply(res, 200, '{"ok":true}');
  });
});

server.listen(PORT, HOST, () => {
  console.log('aurora-feedback listening on ' + HOST + ':' + PORT);
});