'use strict';

/**
 * AURORA 社区服务端。
 *
 * 预设分享 + 喜欢 + 短评论。无账号体系：anonId 是当前设备的句柄，
 * 找回码是唯一身份凭证——卸载重装后凭它把内容、喜欢和昵称头像认领回来。
 *
 * 隐私立场与 aurora-feedback 相同：不记录来源 IP，限流窗口内存即可。
 */

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { DatabaseSync } = require('node:sqlite');

const PORT = 5003;
const HOST = '127.0.0.1';
const DATA_DIR = path.join(__dirname, 'data');
const DB_FILE = path.join(DATA_DIR, 'community.db');
const TOKEN_FILE = path.join(__dirname, 'admin-token');
const MAX_BODY = 32 * 1024;
const WINDOW_MS = 60 * 1000;
const MAX_PER_WINDOW = 30;
const PAGE_SIZE = 20;
const CURVE_VALUES = 6;
const CURVE_RANGE = 120; // 0.1 dB 单位，与 Settings 里的编码一致

fs.mkdirSync(DATA_DIR, { recursive: true });
const db = new DatabaseSync(DB_FILE);

db.exec(`
CREATE TABLE IF NOT EXISTS accounts (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  anon_id TEXT UNIQUE NOT NULL,
  recovery_key TEXT UNIQUE NOT NULL,
  nickname TEXT NOT NULL DEFAULT '',
  avatar TEXT NOT NULL DEFAULT 'eq',
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS presets (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  account_id INTEGER NOT NULL,
  name TEXT NOT NULL,
  curve TEXT NOT NULL,
  model TEXT NOT NULL,
  device_label TEXT NOT NULL,
  band_hz TEXT NOT NULL DEFAULT '',
  likes_count INTEGER NOT NULL DEFAULT 0,
  comment_count INTEGER NOT NULL DEFAULT 0,
  hidden INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS likes (
  preset_id INTEGER NOT NULL,
  account_id INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (preset_id, account_id)
);
CREATE TABLE IF NOT EXISTS comments (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  preset_id INTEGER NOT NULL,
  account_id INTEGER NOT NULL,
  body TEXT NOT NULL,
  hidden INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_presets_created ON presets(hidden, id DESC);
CREATE INDEX IF NOT EXISTS idx_comments_preset ON comments(preset_id, id);
`);

const adminToken = (() => {
  try {
    return fs.readFileSync(TOKEN_FILE, 'utf8').trim();
  } catch (e) {
    return '';
  }
})();

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

// 不含易混淆字符的字母表；12 位 ≈ 75 bit，足够当成身份凭证。
// 必须用 CSPRNG：Math.random 是可预测的伪随机，恢复码等于身份本身。
const KEY_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
function newRecoveryKey() {
  // 32 整除 256，取模无偏。
  const bytes = crypto.randomBytes(12);
  let raw = '';
  for (let i = 0; i < 12; i++) {
    raw += KEY_ALPHABET[bytes[i] % KEY_ALPHABET.length];
  }
  return raw.slice(0, 4) + '-' + raw.slice(4, 8) + '-' + raw.slice(8);
}

/** Accepts lowercase, missing dashes or stray spaces; returns the stored form. */
function normalizeKey(input) {
  const clean = String(input).replace(/[^A-Za-z0-9]/g, '').toUpperCase();
  if (clean.length !== 12) return String(input).trim().toUpperCase();
  return clean.slice(0, 4) + '-' + clean.slice(4, 8) + '-' + clean.slice(8);
}

function accountByAnon(anonId) {
  return db.prepare('SELECT * FROM accounts WHERE anon_id = ?').get(anonId);
}

function accountById(id) {
  return db.prepare('SELECT * FROM accounts WHERE id = ?').get(id);
}

function ensureAccount(anonId) {
  let account = accountByAnon(anonId);
  if (account) return { account, created: false };
  const nickname = '调音师' + String(1000 + Math.floor(Math.random() * 9000));
  const key = newRecoveryKey();
  const result = db.prepare(
    'INSERT INTO accounts (anon_id, recovery_key, nickname, avatar, created_at) VALUES (?, ?, ?, ?, ?)'
  ).run(anonId, key, nickname, 'eq', Date.now());
  account = accountById(Number(result.lastInsertRowid));
  return { account, created: true };
}

function profile(account) {
  return { nickname: account.nickname, avatar: account.avatar, createdAt: account.created_at };
}

function card(row, viewerAccountId, likedKnown) {
  const liked = likedKnown !== undefined
    ? likedKnown
    : (viewerAccountId
      ? !!db.prepare('SELECT 1 FROM likes WHERE preset_id = ? AND account_id = ?')
          .get(row.id, viewerAccountId)
      : false);
  return {
    id: row.id,
    name: row.name,
    curve: row.curve,
    model: row.model,
    deviceLabel: row.device_label,
    bandHz: row.band_hz,
    nickname: row.nickname,
    avatar: row.avatar,
    likes: row.likes_count,
    comments: row.comment_count,
    liked,
    createdAt: row.created_at,
  };
}

const FEED_SQL = `
SELECT p.*, a.nickname, a.avatar FROM presets p
JOIN accounts a ON a.id = p.account_id
WHERE p.hidden = 0`;

function reply(res, code, payload) {
  res.writeHead(code, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(payload));
}

function str(value, max) {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  return trimmed.length === 0 || trimmed.length > max ? null : trimmed;
}

function validCurve(curve) {
  if (!Array.isArray(curve) || curve.length !== CURVE_VALUES) return null;
  const out = [];
  for (const item of curve) {
    const n = typeof item === 'number' ? item : Number(item);
    if (!Number.isFinite(n) || Math.abs(n) > CURVE_RANGE) return null;
    out.push(Math.round(n));
  }
  return out.join(',');
}

const server = http.createServer((req, res) => {
  if (req.method === 'GET' && (req.url || '').split('?')[0] === '/health') {
    return reply(res, 200, { ok: true });
  }
  if (req.method !== 'POST' && req.method !== 'GET' && req.method !== 'DELETE') {
    res.writeHead(405, { 'Content-Type': 'text/plain' });
    return res.end('method not allowed');
  }

  const ip = req.headers['x-real-ip'] || req.socket.remoteAddress || 'unknown';
  if (rateLimited(ip)) {
    res.writeHead(429, { 'Content-Type': 'text/plain' });
    return res.end('too many requests');
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

    const url = new URL(req.url || '/', 'http://localhost');
    const route = url.pathname.replace(/\/+$/, '') || '/';
    let body = {};
    if (chunks.length > 0) {
      try {
        body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
      } catch (e) {
        return reply(res, 400, { ok: false, error: 'bad json' });
      }
      if (!body || typeof body !== 'object' || Array.isArray(body)) {
        return reply(res, 400, { ok: false, error: 'bad payload' });
      }
    }

    try {
      handle(req, res, route, url, body);
    } catch (e) {
      reply(res, 500, { ok: false, error: 'server error' });
    }
  });
});

function handle(req, res, route, url, body) {
  // ---- 账户 ----
  if (req.method === 'POST' && route === '/account') {
    const anonId = str(body.anonId, 64);
    if (!anonId) return reply(res, 400, { ok: false, error: 'anonId required' });
    const nickname = body.nickname === undefined ? undefined : str(body.nickname, 16);
    const avatar = body.avatar === undefined ? undefined : str(body.avatar, 32);
    const { account, created } = ensureAccount(anonId);
    if (nickname !== undefined || avatar !== undefined) {
      db.prepare('UPDATE accounts SET nickname = COALESCE(?, nickname), avatar = COALESCE(?, avatar) WHERE id = ?')
        .run(nickname ?? null, avatar ?? null, account.id);
    }
    const fresh = accountById(account.id);
    return reply(res, 200, {
      ok: true,
      created,
      recoveryKey: created ? fresh.recovery_key : undefined,
      profile: profile(fresh),
    });
  }

  if (req.method === 'POST' && route === '/account/recover') {
    const input = str(body.recoveryKey, 32);
    const anonId = str(body.anonId, 64);
    if (!input || !anonId) return reply(res, 400, { ok: false, error: 'recoveryKey and anonId required' });
    const account = db.prepare('SELECT * FROM accounts WHERE recovery_key = ?').get(normalizeKey(input));
    if (!account) return reply(res, 404, { ok: false, error: 'unknown recovery key' });
    // The handle this device already uses may belong to another account (a like or a comment
    // created one before the old key was entered). Release that binding first, in one
    // transaction, or the UNIQUE constraint turns a normal recovery into a 500.
    try {
      db.exec('BEGIN');
      db.prepare(
        "UPDATE accounts SET anon_id = 'released-' || id || '-' || lower(hex(randomblob(4))) " +
        'WHERE anon_id = ? AND id != ?'
      ).run(anonId, account.id);
      db.prepare('UPDATE accounts SET anon_id = ? WHERE id = ?').run(anonId, account.id);
      db.exec('COMMIT');
    } catch (e) {
      try { db.exec('ROLLBACK'); } catch (e2) { /* nothing left to roll back */ }
      return reply(res, 500, { ok: false, error: 'recover failed' });
    }
    return reply(res, 200, { ok: true, profile: profile(account) });
  }

  if (req.method === 'GET' && route === '/account') {
    const anonId = str(url.searchParams.get('anonId') || '', 64);
    if (!anonId) return reply(res, 400, { ok: false, error: 'anonId required' });
    const account = accountByAnon(anonId);
    if (!account) return reply(res, 404, { ok: false, error: 'no account' });
    return reply(res, 200, { ok: true, profile: profile(account) });
  }

  if (req.method === 'POST' && route === '/account/rotate') {
    const anonId = str(body.anonId, 64);
    if (!anonId) return reply(res, 400, { ok: false, error: 'anonId required' });
    const account = accountByAnon(anonId);
    if (!account) return reply(res, 404, { ok: false, error: 'no account' });
    const key = newRecoveryKey();
    db.prepare('UPDATE accounts SET recovery_key = ? WHERE id = ?').run(key, account.id);
    return reply(res, 200, { ok: true, recoveryKey: key });
  }

  // ---- 信息流 ----
  if (req.method === 'GET' && route === '/presets') {
    const cursor = Number(url.searchParams.get('cursor') || '0');
    const viewer = url.searchParams.get('anonId') ? accountByAnon(str(url.searchParams.get('anonId'), 64) || '') : null;
    // cursor = 上一页最后一条的 id；首页没有 cursor，直接取最新。
    const rows = cursor > 0
      ? db.prepare(`${FEED_SQL} AND p.id < ? ORDER BY p.id DESC LIMIT ?`).all(cursor, PAGE_SIZE + 1)
      : db.prepare(`${FEED_SQL} ORDER BY p.id DESC LIMIT ?`).all(PAGE_SIZE + 1);
    const page = rows.slice(0, PAGE_SIZE);
    // One query for the whole page instead of one per card.
    let likedIds = new Set();
    if (viewer && page.length > 0) {
      const marks = page.map(() => '?').join(',');
      likedIds = new Set(
        db.prepare(`SELECT preset_id FROM likes WHERE account_id = ? AND preset_id IN (${marks})`)
          .all(viewer.id, ...page.map((row) => row.id))
          .map((row) => row.preset_id)
      );
    }
    return reply(res, 200, {
      ok: true,
      items: page.map((row) => card(row, viewer ? viewer.id : null, likedIds.has(row.id))),
      nextCursor: rows.length > PAGE_SIZE && page.length > 0 ? page[page.length - 1].id : null,
    });
  }

  if (req.method === 'GET' && /^\/presets\/(\d+)$/.test(route)) {
    const id = Number(route.match(/^\/presets\/(\d+)$/)[1]);
    const row = db.prepare(`${FEED_SQL} AND p.id = ?`).get(id);
    if (!row) return reply(res, 404, { ok: false, error: 'not found' });
    const viewer = url.searchParams.get('anonId') ? accountByAnon(str(url.searchParams.get('anonId'), 64) || '') : null;
    const comments = db.prepare(
      'SELECT c.id, c.body, c.created_at, a.nickname, a.avatar FROM comments c JOIN accounts a ON a.id = c.account_id WHERE c.preset_id = ? AND c.hidden = 0 ORDER BY c.id'
    ).all(id);
    const out = card(row, viewer ? viewer.id : null);
    out.commentList = comments.map((c) => ({
      id: c.id, body: c.body, nickname: c.nickname, avatar: c.avatar, createdAt: c.created_at,
    }));
    return reply(res, 200, { ok: true, item: out });
  }

  // ---- 发布 ----
  if (req.method === 'POST' && route === '/presets') {
    const anonId = str(body.anonId, 64);
    const name = str(body.name, 20);
    const curve = validCurve(body.curve);
    const model = str(body.model, 64);
    const deviceLabel = str(body.deviceLabel, 24);
    const bandHz = typeof body.bandHz === 'string' ? body.bandHz.slice(0, 40) : '';
    if (!anonId || !name || !curve || !model || !deviceLabel) {
      return reply(res, 400, { ok: false, error: 'name, curve, model, deviceLabel required' });
    }
    const { account, created } = ensureAccount(anonId);
    const result = db.prepare(
      'INSERT INTO presets (account_id, name, curve, model, device_label, band_hz, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)'
    ).run(account.id, name, curve, model, deviceLabel, bandHz, Date.now());
    return reply(res, 200, {
      ok: true,
      id: Number(result.lastInsertRowid),
      created,
      recoveryKey: created ? account.recovery_key : undefined,
    });
  }

  // ---- 喜欢 ----
  const likeMatch = route.match(/^\/presets\/(\d+)\/like$/);
  if (likeMatch) {
    const id = Number(likeMatch[1]);
    const preset = db.prepare('SELECT id FROM presets WHERE id = ? AND hidden = 0').get(id);
    if (!preset) return reply(res, 404, { ok: false, error: 'not found' });
    const anonId = str(body.anonId, 64);
    if (!anonId) return reply(res, 400, { ok: false, error: 'anonId required' });
    const { account, created } = ensureAccount(anonId);
    if (req.method === 'POST') {
      const result = db.prepare('INSERT OR IGNORE INTO likes (preset_id, account_id, created_at) VALUES (?, ?, ?)')
        .run(id, account.id, Date.now());
      if (result.changes > 0) {
        db.prepare('UPDATE presets SET likes_count = likes_count + 1 WHERE id = ?').run(id);
      }
      // A like can be the first thing a person does. Hand back the key when this call created the
      // account, so the identity is never born without its owner seeing the way back in.
      return reply(res, 200, {
        ok: true,
        created,
        recoveryKey: created ? account.recovery_key : undefined,
      });
    }
    if (req.method === 'DELETE') {
      const result = db.prepare('DELETE FROM likes WHERE preset_id = ? AND account_id = ?').run(id, account.id);
      if (result.changes > 0) {
        db.prepare('UPDATE presets SET likes_count = MAX(likes_count - 1, 0) WHERE id = ?').run(id);
      }
      return reply(res, 200, {
        ok: true,
        created,
        recoveryKey: created ? account.recovery_key : undefined,
      });
    }
  }

  // ---- 评论 ----
  const commentMatch = route.match(/^\/presets\/(\d+)\/comments$/);
  if (req.method === 'POST' && commentMatch) {
    const id = Number(commentMatch[1]);
    const preset = db.prepare('SELECT id FROM presets WHERE id = ? AND hidden = 0').get(id);
    if (!preset) return reply(res, 404, { ok: false, error: 'not found' });
    const anonId = str(body.anonId, 64);
    const commentBody = str(body.body, 140);
    if (!anonId || !commentBody) return reply(res, 400, { ok: false, error: 'anonId and body required' });
    const { account, created } = ensureAccount(anonId);
    const result = db.prepare(
      'INSERT INTO comments (preset_id, account_id, body, created_at) VALUES (?, ?, ?, ?)'
    ).run(id, account.id, commentBody, Date.now());
    db.prepare('UPDATE presets SET comment_count = comment_count + 1 WHERE id = ?').run(id);
    return reply(res, 200, {
      ok: true,
      id: Number(result.lastInsertRowid),
      nickname: account.nickname,
      avatar: account.avatar,
      created,
      recoveryKey: created ? account.recovery_key : undefined,
    });
  }

  // ---- 我的喜欢 ----
  if (req.method === 'GET' && route === '/likes') {
    const anonId = str(url.searchParams.get('anonId') || '', 64);
    if (!anonId) return reply(res, 400, { ok: false, error: 'anonId required' });
    const account = accountByAnon(anonId);
    if (!account) return reply(res, 200, { ok: true, items: [] });
    const rows = db.prepare(`
      SELECT p.*, a.nickname, a.avatar FROM likes l
      JOIN presets p ON p.id = l.preset_id
      JOIN accounts a ON a.id = p.account_id
      WHERE l.account_id = ? AND p.hidden = 0
      ORDER BY l.created_at DESC`).all(account.id);
    return reply(res, 200, { ok: true, items: rows.map((row) => card(row, account.id)) });
  }

  // ---- 管理 ----
  if (req.method === 'POST' && route === '/admin/hide') {
    if (!adminToken || body.token !== adminToken) {
      return reply(res, 403, { ok: false, error: 'forbidden' });
    }
    const type = body.type;
    const id = Number(body.id);
    if (type === 'preset') {
      db.prepare('UPDATE presets SET hidden = 1 WHERE id = ?').run(id);
    } else if (type === 'comment') {
      db.prepare('UPDATE comments SET hidden = 1 WHERE id = ?').run(id);
    } else {
      return reply(res, 400, { ok: false, error: 'type must be preset or comment' });
    }
    return reply(res, 200, { ok: true });
  }

  res.writeHead(404, { 'Content-Type': 'text/plain' });
  res.end('not found');
}

server.listen(PORT, HOST, () => {
  console.log('aurora-community listening on ' + HOST + ':' + PORT);
});
