'use strict';

/**
 * AURORA 社区服务端。
 *
 * 邮箱验证码登录（免密码）+ 曲线分享 + 喜欢 + 短评论。
 *
 * 身份从「每台设备一个匿名句柄」换成了「一个邮箱一个账号」：邮箱只用于登录和找回，
 * 永远不出现在公开信息里；对外展示的只有昵称和头像。旧的匿名身份在首次登录时可以带着
 * anonId 一起提交，服务端会把内容和喜欢并到新账号上，不会凭空消失。
 *
 * 隐私立场与 aurora-feedback 相同：不记录来源 IP，限流窗口在内存里。
 */

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const tls = require('tls');
const { DatabaseSync } = require('node:sqlite');

const PORT = Number(process.env.AURORA_COMMUNITY_PORT || 5003);
const HOST = '127.0.0.1';
const DATA_DIR = process.env.AURORA_COMMUNITY_DATA || path.join(__dirname, 'data');
const DB_FILE = path.join(DATA_DIR, 'community.db');

const MAIL_FILE = process.env.AURORA_COMMUNITY_MAIL || path.join(__dirname, 'mail.json');
const MAIL_STDOUT = process.env.AURORA_COMMUNITY_MAIL_STDOUT === '1';
const AVATAR_DIR = path.join(DATA_DIR, 'avatars');
/** 512 KB 是 512×512 JPEG 的四倍有余，够用且能挡住误传原图。 */
const AVATAR_MAX = 512 * 1024;

const MAX_BODY = 32 * 1024;
const WINDOW_MS = 60 * 1000;
const MAX_PER_WINDOW = 60;
const PAGE_SIZE = 20;
const CURVE_VALUES = 6;
const CURVE_RANGE = 120; // 0.1 dB 单位，与 Settings 里的编码一致
const FLAT_LIMIT = 5; // 全段都在 ±0.5 dB 以内，等于没改
const CODE_TTL_MS = 10 * 60 * 1000;
const CODE_RESEND_MS = 60 * 1000;
const CODE_TRIES = 5;
const CODE_PER_DAY = 10;
const SESSION_DAYS = 90;
const NICKNAME_MAX = 16;

fs.mkdirSync(DATA_DIR, { recursive: true });
fs.mkdirSync(AVATAR_DIR, { recursive: true });
const db = new DatabaseSync(DB_FILE);

db.exec(`
CREATE TABLE IF NOT EXISTS accounts (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  anon_id TEXT UNIQUE NOT NULL,
  recovery_key TEXT UNIQUE NOT NULL,
  nickname TEXT NOT NULL DEFAULT '',
  avatar TEXT NOT NULL DEFAULT '',
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
  preset TEXT NOT NULL DEFAULT '',
  strength INTEGER NOT NULL DEFAULT 100,
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
CREATE TABLE IF NOT EXISTS codes (
  email TEXT PRIMARY KEY,
  code_hash TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  tries INTEGER NOT NULL DEFAULT 0,
  sent_at INTEGER NOT NULL,
  sent_day TEXT NOT NULL DEFAULT '',
  sent_count INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS sessions (
  token_hash TEXT PRIMARY KEY,
  account_id INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  last_seen INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_presets_created ON presets(hidden, id DESC);
CREATE INDEX IF NOT EXISTS idx_presets_owner ON presets(account_id, id DESC);
CREATE INDEX IF NOT EXISTS idx_comments_preset ON comments(preset_id, id);
CREATE INDEX IF NOT EXISTS idx_sessions_account ON sessions(account_id);
`);

/**
 * Columns added after the table shipped. `CREATE TABLE IF NOT EXISTS` leaves an existing table
 * alone, so the live database needs the columns bolted on rather than recreated.
 */
function addColumn(table, name, decl) {
  const columns = db.prepare(`PRAGMA table_info(${table})`).all().map((c) => c.name);
  if (!columns.includes(name)) db.exec(`ALTER TABLE ${table} ADD COLUMN ${name} ${decl}`);
}
addColumn('presets', 'preset', "TEXT NOT NULL DEFAULT ''");
addColumn('presets', 'strength', 'INTEGER NOT NULL DEFAULT 100');
addColumn('accounts', 'email', 'TEXT');
addColumn('accounts', 'email_verified_at', 'INTEGER');
/** 'user' or 'admin'. Only an admin may remove other people's content or ban an account. */
addColumn('accounts', 'role', "TEXT NOT NULL DEFAULT 'user'");
addColumn('accounts', 'banned_at', 'INTEGER');
addColumn('accounts', 'ban_reason', "TEXT NOT NULL DEFAULT ''");
// 头像只有两种：上传过的（img:*）和用内置默认（空）。老库里残留的图标 key 一律归空。
db.exec("UPDATE accounts SET avatar = '' WHERE avatar <> '' AND avatar NOT LIKE 'img:%'");
// 老账号没有邮箱，所以唯一索引必须是 partial，否则多个 NULL 也不允许重复。
db.exec('CREATE UNIQUE INDEX IF NOT EXISTS idx_accounts_email ON accounts(email) WHERE email IS NOT NULL');


/** 验证码用 HMAC 存：库泄漏也拿不到能用的 6 位数字。 */
const codeSecret = (() => {
  const file = path.join(DATA_DIR, 'code-secret');
  try {
    return fs.readFileSync(file, 'utf8').trim();
  } catch (e) {
    const fresh = crypto.randomBytes(32).toString('hex');
    fs.writeFileSync(file, fresh, { mode: 0o600 });
    return fresh;
  }
})();

const mailConfig = (() => {
  try {
    const raw = JSON.parse(fs.readFileSync(MAIL_FILE, 'utf8'));
    if (!raw || !raw.host || !raw.user || !raw.pass) return null;
    return {
      host: String(raw.host),
      port: Number(raw.port || 465),
      user: String(raw.user),
      pass: String(raw.pass),
      from: String(raw.from || raw.user),
    };
  } catch (e) {
    return null;
  }
})();

const hits = new Map();
function rateLimited(ip, limit = MAX_PER_WINDOW, windowMs = WINDOW_MS) {
  const now = Date.now();
  const record = hits.get(ip);
  if (!record || now > record.reset) {
    hits.set(ip, { count: 1, reset: now + windowMs });
    return false;
  }
  record.count += 1;
  return record.count > limit;
}

// 发信额度是全局的，不是每 IP 的：SMTP 服务商的每小时配额一撞上，所有用户都收不到验证码。
// 队列把并发压成串行，避免同一个瞬间开十几条 TLS 连接被服务商限流。
const MAIL_PER_HOUR = 80;
let mailWindow = { start: Date.now(), count: 0 };
let mailQueue = Promise.resolve();

function takeMailSlot() {
  const now = Date.now();
  if (now - mailWindow.start > 3600 * 1000) mailWindow = { start: now, count: 0 };
  if (mailWindow.count >= MAIL_PER_HOUR) return false;
  mailWindow.count += 1;
  return true;
}

function sendCodeQueued(email, code) {
  const run = mailQueue.then(() => sendCode(email, code), () => sendCode(email, code));
  mailQueue = run.catch(() => {});
  return run;
}

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

function curveIsFlat(joined) {
  return joined.split(',').every((value) => Math.abs(Number(value)) <= FLAT_LIMIT);
}

// 每账号的写入额度。没有它，一个账号按 IP 限额连发就能把公共 feed 或某条曲线的评论区
// 永久占死：feed 只看最新 20 条，评论区只取最早 200 条。
function overQuota(table, accountId, hourCap, dayCap) {
  const now = Date.now();
  const hour = db.prepare(`SELECT COUNT(*) AS c FROM ${table} WHERE account_id = ? AND created_at > ?`)
    .get(accountId, now - 3600 * 1000).c;
  if (hour >= hourCap) return true;
  const day = db.prepare(`SELECT COUNT(*) AS c FROM ${table} WHERE account_id = ? AND created_at > ?`)
    .get(accountId, now - 86400 * 1000).c;
  return day >= dayCap;
}

function normalizeEmail(input) {
  if (typeof input !== 'string') return null;
  const value = input.trim().toLowerCase();
  if (value.length < 6 || value.length > 120) return null;
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(value)) return null;
  return value;
}

function hashToken(token) {
  return crypto.createHash('sha256').update(token).digest('hex');
}

function hashCode(email, code) {
  return crypto.createHmac('sha256', codeSecret).update(email + '|' + code).digest('hex');
}

function dayKey(now) {
  return new Date(now).toISOString().slice(0, 10);
}

function newRecoveryKey() {
  const bytes = crypto.randomBytes(12);
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  let raw = '';
  for (let i = 0; i < 12; i++) raw += alphabet[bytes[i] % alphabet.length];
  return raw.slice(0, 4) + '-' + raw.slice(4, 8) + '-' + raw.slice(8);
}

/** 每个账号出生时给一个随机昵称，用户登录后可以改。 */
function defaultNickname() {
  return '调音师' + String(1000 + crypto.randomInt(9000));
}

function accountById(id) {
  return db.prepare('SELECT * FROM accounts WHERE id = ?').get(id);
}

function profile(account) {
  return {
    nickname: account.nickname,
    avatar: account.avatar,
    email: account.email || '',
    role: account.role || 'user',
    banned: Boolean(account.banned_at),
    createdAt: account.created_at,
  };
}

/** A banned account keeps its rows but loses its voice: no sign-in, no writes, content hidden. */
function isAdmin(account) {
  return Boolean(account) && account.role === 'admin';
}

function requireAccount(req, res) {
  const account = accountFor(req);
  if (!account) {
    reply(res, 401, { ok: false, error: 'unauthorized' });
    return null;
  }
  if (account.banned_at) {
    reply(res, 403, { ok: false, error: 'banned', message: '这个账号已被封禁，不能发布或互动。' });
    return null;
  }
  return account;
}

function requireAdmin(req, res) {
  const account = requireAccount(req, res);
  if (!account) return null;
  if (!isAdmin(account)) {
    reply(res, 403, { ok: false, error: 'forbidden', message: '只有管理员能做这件事。' });
    return null;
  }
  return account;
}

// ---- 发信 ----------------------------------------------------------------

/**
 * 最小 SMTP 客户端：隐式 TLS（465）+ AUTH LOGIN。
 *
 * <p>只发一种邮件，所以不引第三方库 —— 生产机上少一个依赖，也少一次供应链。
 */
function smtpSend(cfg, to, subject, body) {
  return new Promise((resolve, reject) => {
    const message = [
      'From: ' + cfg.from,
      'To: ' + to,
      'Subject: =?UTF-8?B?' + Buffer.from(subject, 'utf8').toString('base64') + '?=',
      'MIME-Version: 1.0',
      'Content-Type: text/plain; charset=UTF-8',
      'Content-Transfer-Encoding: base64',
      'Date: ' + new Date().toUTCString(),
      'Message-ID: <' + crypto.randomBytes(12).toString('hex') + '@' + cfg.host + '>',
      '',
      Buffer.from(body, 'utf8').toString('base64').replace(/(.{76})/g, '$1\r\n'),
    ].join('\r\n');

    const steps = [
      'EHLO aurora',
      'AUTH LOGIN',
      Buffer.from(cfg.user, 'utf8').toString('base64'),
      Buffer.from(cfg.pass, 'utf8').toString('base64'),
      'MAIL FROM:<' + cfg.user + '>',
      'RCPT TO:<' + to + '>',
      'DATA',
      message + '\r\n.',
      'QUIT',
    ];

    let socket;
    let index = 0;
    let buffer = '';
    let settled = false;
    const finish = (error) => {
      if (settled) return;
      settled = true;
      try { socket.end(); } catch (e) { /* already gone */ }
      if (error) reject(error); else resolve(true);
    };

    socket = tls.connect({ host: cfg.host, port: cfg.port, servername: cfg.host }, () => {});
    socket.setTimeout(20000, () => finish(new Error('smtp timeout')));
    socket.on('error', (error) => finish(error));
    socket.on('data', (chunk) => {
      buffer += chunk.toString('utf8');
      let newline;
      while ((newline = buffer.indexOf('\r\n')) >= 0) {
        const line = buffer.slice(0, newline);
        buffer = buffer.slice(newline + 2);
        // 多行应答（250-...）要读完最后一行 250 空格才算一个响应。
        if (/^\d{3}-/.test(line)) continue;
        const code = Number(line.slice(0, 3));
        if (code >= 400) {
          finish(new Error('smtp ' + code + ': ' + line.slice(4)));
          return;
        }
        const next = steps[index++];
        // Past the last step (QUIT already answered): the message was accepted and the server said
        // goodbye, so this is where the promise resolves. Returning silently here made /auth/code
        // send the mail and then never answer the request at all.
        if (next === undefined) {
          finish(null);
          return;
        }
        socket.write(next + '\r\n');
      }
    });
  });
}

function loginMailText(code) {
  return [
    'AURORA 登录验证码：' + code,
    '',
    '10 分钟内有效，只能用来登录你自己的账号。',
    '如果不是你本人操作，忽略这封邮件即可 —— 没有验证码别人进不来。',
    '',
    'AURORA 音频模块',
  ].join('\r\n');
}

async function sendCode(email, code) {
  if (MAIL_STDOUT) {
    console.log('[mail] ' + email + ' -> ' + code);
    return { delivered: true, mode: 'stdout' };
  }
  if (!mailConfig) throw new Error('mail not configured');
  await smtpSend(mailConfig, email, 'AURORA 登录验证码', loginMailText(code));
  return { delivered: true, mode: 'smtp' };
}

// ---- 会话 ----------------------------------------------------------------

function issueSession(accountId) {
  const token = crypto.randomBytes(32).toString('hex');
  const now = Date.now();
  db.prepare('INSERT INTO sessions (token_hash, account_id, created_at, last_seen) VALUES (?, ?, ?, ?)')
    .run(hashToken(token), accountId, now, now);
  return token;
}

function accountFor(req) {
  const header = req.headers.authorization || '';
  const match = /^Bearer\s+([a-f0-9]{64})$/i.exec(header);
  if (!match) return null;
  const session = db.prepare('SELECT * FROM sessions WHERE token_hash = ?').get(hashToken(match[1].toLowerCase()));
  if (!session) return null;
  const now = Date.now();
  if (now - session.last_seen > SESSION_DAYS * 24 * 3600 * 1000) {
    db.prepare('DELETE FROM sessions WHERE token_hash = ?').run(session.token_hash);
    return null;
  }
  if (now - session.last_seen > 24 * 3600 * 1000) {
    db.prepare('UPDATE sessions SET last_seen = ? WHERE token_hash = ?').run(now, session.token_hash);
  }
  const account = accountById(session.account_id);
  if (!account) {
    db.prepare('DELETE FROM sessions WHERE token_hash = ?').run(session.token_hash);
    return null;
  }
  return account;
}

// ---- 卡片 ----------------------------------------------------------------

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
    preset: row.preset || '',
    strength: row.strength === null || row.strength === undefined ? 100 : row.strength,
    nickname: row.nickname,
    avatar: row.avatar,
    likes: row.likes_count,
    comments: row.comment_count,
    liked,
    mine: viewerAccountId ? row.account_id === viewerAccountId : false,
    createdAt: row.created_at,
  };
}

const FEED_SQL = `
SELECT p.*, a.nickname, a.avatar FROM presets p
JOIN accounts a ON a.id = p.account_id
WHERE p.hidden = 0 AND a.banned_at IS NULL`;

// ---- 账号归属迁移 --------------------------------------------------------

/**
 * Adopts whatever the device published before accounts existed.
 *
 * <p>The anonymous era minted one account per device handle, and every preset, like and comment
 * hangs off that row. Binding its email keeps the content; merging into an existing email account
 * moves it. Either way nothing published by this device is orphaned.
 */
function adoptLegacy(account, anonId) {
  const legacy = db.prepare('SELECT * FROM accounts WHERE anon_id = ?').get(anonId);
  if (!legacy || legacy.id === account.id) return account;
  // 被封禁的旧句柄不能被新邮箱洗白：内容会随迁移回到公开 feed，封禁记录也会跟着那行一起被删掉。
  if (legacy.email || legacy.banned_at) return account;

  db.exec('BEGIN');
  try {
    // 内容、喜欢、评论一起并到邮箱账号上，老行随即消失 —— 一个设备句柄不该继续占着内容。
    db.prepare('UPDATE presets SET account_id = ? WHERE account_id = ?').run(account.id, legacy.id);
    db.prepare('UPDATE comments SET account_id = ? WHERE account_id = ?').run(account.id, legacy.id);
    db.prepare(
      'INSERT OR IGNORE INTO likes (preset_id, account_id, created_at) ' +
      'SELECT preset_id, ?, created_at FROM likes WHERE account_id = ?'
    ).run(account.id, legacy.id);
    db.prepare('DELETE FROM likes WHERE account_id = ?').run(legacy.id);
    db.prepare('DELETE FROM accounts WHERE id = ?').run(legacy.id);
    db.exec('COMMIT');
    return accountById(account.id);
  } catch (e) {
    try { db.exec('ROLLBACK'); } catch (e2) { /* nothing to roll back */ }
    return account;
  }
}

function createAccount(email) {
  const now = Date.now();
  const result = db.prepare(
    'INSERT INTO accounts (anon_id, recovery_key, nickname, avatar, created_at, email, email_verified_at) ' +
    'VALUES (?, ?, ?, ?, ?, ?, ?)'
  ).run(
    'acct-' + crypto.randomBytes(8).toString('hex'),
    newRecoveryKey(),
    defaultNickname(),
    '',
    now,
    email,
    now
  );
  return accountById(Number(result.lastInsertRowid));
}

// ---- 头像 ----------------------------------------------------------------

/** "img:<file>" when the avatar is an uploaded picture, null for the built-in icon keys. */
function avatarFileOf(avatar) {
  const match = /^img:([a-f0-9]{4,16}-[a-f0-9]{16}\.(jpg|png))$/.exec(avatar || '');
  return match ? match[1] : null;
}

function removeAvatarFile(avatar) {
  const file = avatarFileOf(avatar);
  if (!file) return;
  fs.unlink(path.join(AVATAR_DIR, file), () => {});
}

/**
 * Stores one already-cropped square picture as the account's avatar.
 *
 * <p>The client sends a 512×512 JPEG it produced itself, so nothing here decodes pixels: the only
 * gates are the size, the magic bytes, and a filename this function generates (no client string
 * ever becomes a path).
 */
function handleAvatarUpload(req, res, buffer) {
  const account = requireAccount(req, res);
  if (!account) return;
  if (buffer.length < 256) return reply(res, 400, { ok: false, error: 'empty' });
  if (buffer.length > AVATAR_MAX) return reply(res, 413, { ok: false, error: 'tooLarge' });

  const isJpeg = buffer[0] === 0xff && buffer[1] === 0xd8 && buffer[2] === 0xff;
  const pngMagic = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  const isPng = buffer.length > 8 && buffer.subarray(0, 8).equals(pngMagic);
  if (!isJpeg && !isPng) return reply(res, 415, { ok: false, error: 'notImage' });

  const ext = isPng ? 'png' : 'jpg';
  const name = account.id.toString(16).padStart(4, '0') + '-' +
    crypto.randomBytes(8).toString('hex') + '.' + ext;
  fs.writeFileSync(path.join(AVATAR_DIR, name), buffer, { mode: 0o644 });

  const previous = account.avatar;
  db.prepare('UPDATE accounts SET avatar = ? WHERE id = ?').run('img:' + name, account.id);
  removeAvatarFile(previous);
  return reply(res, 200, { ok: true, avatar: 'img:' + name });
}

// ---- HTTP ---------------------------------------------------------------

const server = http.createServer((req, res) => {
  const rawRoute = (req.url || '/').split('?')[0].replace(/\/+$/, '') || '/';
  if (req.method === 'GET' && rawRoute === '/health') {
    return reply(res, 200, { ok: true, mail: Boolean(mailConfig) || MAIL_STDOUT });
  }
  if (!['POST', 'GET', 'DELETE', 'PATCH'].includes(req.method)) {
    res.writeHead(405, { 'Content-Type': 'text/plain' });
    return res.end('method not allowed');
  }

  // 头像图片是普通静态读，不是可写接口：算进限流等于让一屏二十张头像把接口额度吃光。
  const countable = !rawRoute.startsWith('/avatars/');
  const ip = req.headers['x-real-ip'] || req.socket.remoteAddress || 'unknown';
  if (countable && rateLimited(ip)) {
    res.writeHead(429, { 'Content-Type': 'text/plain' });
    return res.end('too many requests');
  }

  let size = 0;
  const chunks = [];
  // 头像上传是二进制，不能走 JSON 解析：先看路由，再决定怎么读体。
  const isAvatarUpload = req.method === 'POST' && rawRoute === '/me/avatar';
  const bodyLimit = isAvatarUpload ? AVATAR_MAX : MAX_BODY;
  req.on('data', (chunk) => {
    size += chunk.length;
    if (size > bodyLimit) {
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
    if (isAvatarUpload) {
      try {
        return handleAvatarUpload(req, res, Buffer.concat(chunks));
      } catch (e) {
        console.error('avatar upload failed:', e && e.message);
        return reply(res, 500, { ok: false, error: 'server error' });
      }
    }
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
      console.error('handler failed:', e && e.message);
      reply(res, 500, { ok: false, error: 'server error' });
    }
  });
});

function handle(req, res, route, url, body) {
  // ---- 头像文件（公开读，文件名不可猜测） ----
  const avatarMatch = route.match(/^\/avatars\/([a-f0-9]{4,16}-[a-f0-9]{16}\.(jpg|png))$/);
  if (req.method === 'GET' && avatarMatch) {
    fs.readFile(path.join(AVATAR_DIR, avatarMatch[1]), (error, data) => {
      if (error) {
        res.writeHead(404, { 'Content-Type': 'text/plain' });
        return res.end('not found');
      }
      res.writeHead(200, {
        'Content-Type': avatarMatch[2] === 'png' ? 'image/png' : 'image/jpeg',
        'Content-Length': data.length,
        // 不解码上传内容，所以必须明确告诉浏览器不要嗅探类型。
        'X-Content-Type-Options': 'nosniff',
        'Content-Disposition': 'inline',
        'Cache-Control': 'public, max-age=604800',
      });
      res.end(data);
    });
    return;
  }
  // ---- 登录：发验证码 ----
  if (req.method === 'POST' && route === '/auth/code') {
    const email = normalizeEmail(body.email);
    if (!email) return reply(res, 400, { ok: false, error: 'badEmail' });

    // 封禁优先于发码：一封“验证码已发出”的邮件只会让被封的人白等。
    const existing = db.prepare('SELECT * FROM accounts WHERE email = ?').get(email);
    if (existing && existing.banned_at) {
      return reply(res, 403, { ok: false, error: 'banned', message: '这个账号已被封禁，暂时不能登录。' });
    }

    const now = Date.now();
    const row = db.prepare('SELECT * FROM codes WHERE email = ?').get(email);
    if (row && now - row.sent_at < CODE_RESEND_MS) {
      return reply(res, 429, {
        ok: false,
        error: 'tooSoon',
        retryAfter: Math.ceil((CODE_RESEND_MS - (now - row.sent_at)) / 1000),
      });
    }
    const day = dayKey(now);
    const sentToday = row && row.sent_day === day ? row.sent_count : 0;
    if (sentToday >= CODE_PER_DAY) return reply(res, 429, { ok: false, error: 'dailyLimit' });
    // 全局发信额度用完就直接拒绝：继续发只会把 SMTP 配额打爆，让所有人一起登不进来。
    if (!takeMailSlot()) {
      return reply(res, 429, {
        ok: false,
        error: 'mailBusy',
        message: '发信太频繁，请稍后再试。',
      });
    }

    const code = String(crypto.randomInt(0, 1000000)).padStart(6, '0');
    db.prepare(
      'INSERT INTO codes (email, code_hash, expires_at, tries, sent_at, sent_day, sent_count) ' +
      'VALUES (?, ?, ?, 0, ?, ?, ?) ' +
      'ON CONFLICT(email) DO UPDATE SET code_hash = excluded.code_hash, expires_at = excluded.expires_at, ' +
      'tries = 0, sent_at = excluded.sent_at, sent_day = excluded.sent_day, sent_count = excluded.sent_count'
    ).run(email, hashCode(email, code), now + CODE_TTL_MS, now, day, sentToday + 1);
    // 顺手清掉过期行，不用另设定时任务。
    db.prepare('DELETE FROM codes WHERE expires_at < ?').run(now);

    sendCodeQueued(email, code)
      .then(() => reply(res, 200, { ok: true, ttl: CODE_TTL_MS / 1000, resendIn: CODE_RESEND_MS / 1000 }))
      .catch((error) => {
        console.error('sendCode failed:', error && error.message);
        // 没发出去就别让用户等 60 秒重试：这条码作废。
        db.prepare('DELETE FROM codes WHERE email = ?').run(email);
        reply(res, 503, { ok: false, error: 'mailFailed' });
      });
    return;
  }

  // ---- 登录：验码换 token ----
  if (req.method === 'POST' && route === '/auth/verify') {
    const email = normalizeEmail(body.email);
    const code = typeof body.code === 'string' ? body.code.trim() : '';
    if (!email || !/^\d{6}$/.test(code)) return reply(res, 400, { ok: false, error: 'badRequest' });

    const now = Date.now();
    const row = db.prepare('SELECT * FROM codes WHERE email = ?').get(email);
    if (!row || row.expires_at < now) {
      return reply(res, 400, { ok: false, error: 'expired', message: '验证码已过期，重新发一次。' });
    }
    if (row.tries >= CODE_TRIES) {
      return reply(res, 429, { ok: false, error: 'tooMany', message: '错误次数太多，重新发一次验证码。' });
    }
    const expected = Buffer.from(row.code_hash, 'hex');
    const given = Buffer.from(hashCode(email, code), 'hex');
    if (expected.length !== given.length || !crypto.timingSafeEqual(expected, given)) {
      db.prepare('UPDATE codes SET tries = tries + 1 WHERE email = ?').run(email);
      const left = CODE_TRIES - (row.tries + 1);
      return reply(res, 400, { ok: false, error: 'badCode', left, message: '验证码不对，还可以试 ' + left + ' 次。' });
    }
    db.prepare('DELETE FROM codes WHERE email = ?').run(email);

    let account = db.prepare('SELECT * FROM accounts WHERE email = ?').get(email);
    if (account && account.banned_at) {
      return reply(res, 403, { ok: false, error: 'banned', message: '这个账号已被封禁，暂时不能登录。' });
    }
    const anonId = str(body.anonId, 64);
    if (!account) {
      account = createAccount(email);
      if (anonId) account = adoptLegacy(account, anonId);
    } else if (anonId) {
      account = adoptLegacy(account, anonId);
    }
    const token = issueSession(account.id);
    return reply(res, 200, { ok: true, token, profile: profile(account) });
  }

  if (req.method === 'POST' && route === '/auth/logout') {
    const header = req.headers.authorization || '';
    const match = /^Bearer\s+([a-f0-9]{64})$/i.exec(header);
    if (match) db.prepare('DELETE FROM sessions WHERE token_hash = ?').run(hashToken(match[1].toLowerCase()));
    return reply(res, 200, { ok: true });
  }

  // ---- 我的资料 ----
  if (route === '/me') {
    const account = requireAccount(req, res);
    if (!account) return;

    if (req.method === 'GET') return reply(res, 200, { ok: true, profile: profile(account) });

    if (req.method === 'PATCH') {
      const nickname = body.nickname === undefined ? undefined : str(body.nickname, NICKNAME_MAX);
      const avatar = body.avatar === undefined ? undefined : String(body.avatar).trim();
      if (body.nickname !== undefined && !nickname) {
        return reply(res, 400, { ok: false, error: 'badNickname' });
      }
      if (body.avatar !== undefined && avatar !== '') {
        return reply(res, 400, { ok: false, error: 'badAvatar' });
      }
      db.prepare('UPDATE accounts SET nickname = COALESCE(?, nickname), avatar = COALESCE(?, avatar) WHERE id = ?')
        .run(nickname ?? null, body.avatar === undefined ? null : avatar, account.id);
      // 换回内置图标时，上一张上传的图就不再被引用，顺手删掉，别把磁盘当回收站。
      if (body.avatar !== undefined) removeAvatarFile(account.avatar);
      return reply(res, 200, { ok: true, profile: profile(accountById(account.id)) });
    }

    if (req.method === 'DELETE') {
      // 注销：内容和喜欢一起删，邮箱立刻可以重新注册成一个空账号。
      const avatarToDrop = account.avatar;
      db.exec('BEGIN');
      try {
        db.prepare('DELETE FROM likes WHERE account_id = ?').run(account.id);
        db.prepare(
          'DELETE FROM likes WHERE preset_id IN (SELECT id FROM presets WHERE account_id = ?)'
        ).run(account.id);
        db.prepare(
          'DELETE FROM comments WHERE preset_id IN (SELECT id FROM presets WHERE account_id = ?)'
        ).run(account.id);
        db.prepare('DELETE FROM comments WHERE account_id = ?').run(account.id);
        db.prepare('DELETE FROM presets WHERE account_id = ?').run(account.id);
        db.prepare('DELETE FROM sessions WHERE account_id = ?').run(account.id);
        db.prepare('DELETE FROM accounts WHERE id = ?').run(account.id);
        db.exec('COMMIT');
      } catch (e) {
        try { db.exec('ROLLBACK'); } catch (e2) { /* nothing to roll back */ }
        return reply(res, 500, { ok: false, error: 'delete failed' });
      }
      removeAvatarFile(avatarToDrop);
      return reply(res, 200, { ok: true });
    }
    return reply(res, 405, { ok: false, error: 'method not allowed' });
  }

  // ---- 信息流 ----
  if (req.method === 'GET' && route === '/presets') {
    const viewer = accountFor(req);
    const mine = url.searchParams.get('mine') === '1';
    if (mine) {
      if (!viewer) return reply(res, 401, { ok: false, error: 'unauthorized' });
      const rows = db.prepare(`${FEED_SQL} AND p.account_id = ? ORDER BY p.id DESC LIMIT 60`).all(viewer.id);
      return reply(res, 200, { ok: true, items: rows.map((row) => card(row, viewer.id)), nextCursor: null });
    }
    const cursor = Number(url.searchParams.get('cursor') || '0');
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
    const nextCursor = rows.length > PAGE_SIZE ? page[page.length - 1].id : null;
    return reply(res, 200, {
      ok: true,
      items: page.map((row) => card(row, viewer ? viewer.id : undefined, likedIds.has(row.id))),
      nextCursor,
    });
  }

  // ---- 单条 ----
  const detailMatch = route.match(/^\/presets\/(\d+)$/);
  if (req.method === 'GET' && detailMatch) {
    const id = Number(detailMatch[1]);
    const viewer = accountFor(req);
    const row = db.prepare(`${FEED_SQL} AND p.id = ?`).get(id);
    if (!row) return reply(res, 404, { ok: false, error: 'not found' });
    const comments = db.prepare(
      // 取最近 200 条再按时间正序展示：原来取最早 200 条，一条曲线被刷满之后，
      // 之后所有人的真评论都进不了列表。被封账号的评论也一并挡住。
      `SELECT * FROM (
         SELECT c.*, a.nickname, a.avatar FROM comments c
         JOIN accounts a ON a.id = c.account_id
         WHERE c.preset_id = ? AND c.hidden = 0 AND a.banned_at IS NULL
         ORDER BY c.id DESC LIMIT 200
       ) ORDER BY id ASC`
    ).all(id);
    const out = card(row, viewer ? viewer.id : undefined);
    out.commentList = comments.map((c) => ({
      id: c.id, body: c.body, nickname: c.nickname, avatar: c.avatar, createdAt: c.created_at,
      // 删评论是按人判权限的：作者自己可以删，管理员可以删任何一条。
      authorId: c.account_id,
      mine: viewer ? c.account_id === viewer.id : false,
    }));
    return reply(res, 200, { ok: true, item: out });
  }

  // ---- 删除曲线（作者本人或管理员） ----
  if (req.method === 'DELETE' && detailMatch) {
    const account = requireAccount(req, res);
    if (!account) return;
    const id = Number(detailMatch[1]);
    const row = db.prepare('SELECT * FROM presets WHERE id = ?').get(id);
    if (!row) return reply(res, 404, { ok: false, error: 'not found' });
    if (row.account_id !== account.id && !isAdmin(account)) {
      return reply(res, 403, { ok: false, error: 'not owner', message: '这条曲线不是你发的。' });
    }
    db.exec('BEGIN');
    try {
      db.prepare('DELETE FROM comments WHERE preset_id = ?').run(id);
      db.prepare('DELETE FROM likes WHERE preset_id = ?').run(id);
      db.prepare('DELETE FROM presets WHERE id = ?').run(id);
      db.exec('COMMIT');
    } catch (e) {
      try { db.exec('ROLLBACK'); } catch (e2) { /* nothing to roll back */ }
      return reply(res, 500, { ok: false, error: 'delete failed' });
    }
    return reply(res, 200, { ok: true });
  }

  // ---- 发布 ----
  if (req.method === 'POST' && route === '/presets') {
    const account = requireAccount(req, res);
    if (!account) return;
    const name = str(body.name, 20);
    const curve = validCurve(body.curve);
    const model = str(body.model, 64);
    const deviceLabel = str(body.deviceLabel, 24);
    const bandHz = typeof body.bandHz === 'string' ? body.bandHz.slice(0, 40) : '';
    // 发布者当时的预设与强度，只用于在详情页说明来源。
    const preset = /^[a-z0-9_-]{1,24}$/.test(body.preset || '') ? body.preset : '';
    const rawStrength = Number(body.strength);
    const strength = Number.isFinite(rawStrength)
      ? Math.min(100, Math.max(0, Math.round(rawStrength)))
      : 100;
    if (!name || !curve || !model || !deviceLabel) {
      return reply(res, 400, { ok: false, error: 'name, curve, model, deviceLabel required' });
    }
    // 全段平直的曲线没有任何信息量：别人点开看到一条直线，等于骗一次点击。
    if (curveIsFlat(curve)) {
      return reply(res, 422, { ok: false, error: 'flat', message: '这条曲线所有频段都是 0，等于没调过，没必要发布。' });
    }
    if (overQuota('presets', account.id, 5, 20)) {
      return reply(res, 429, { ok: false, error: 'quota', message: '今天发布得有点多，歇一会儿再来。' });
    }
    // 同一个人发同样的曲线没有任何意义，直接指向已有的那条。
    const duplicate = db.prepare(
      'SELECT id, name FROM presets WHERE account_id = ? AND curve = ? AND hidden = 0 ORDER BY id DESC LIMIT 1'
    ).get(account.id, curve);
    if (duplicate) {
      return reply(res, 409, {
        ok: false,
        error: 'duplicate',
        id: duplicate.id,
        message: '你已经发过这条曲线了（「' + duplicate.name + '」），不用再发一次。',
      });
    }
    const result = db.prepare(
      'INSERT INTO presets (account_id, name, curve, model, device_label, band_hz, preset, strength, created_at) ' +
      'VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)'
    ).run(account.id, name, curve, model, deviceLabel, bandHz, preset, strength, Date.now());
    return reply(res, 200, { ok: true, id: Number(result.lastInsertRowid) });
  }

  // ---- 喜欢 ----
  const likeMatch = route.match(/^\/presets\/(\d+)\/like$/);
  if (likeMatch && (req.method === 'POST' || req.method === 'DELETE')) {
    const account = requireAccount(req, res);
    if (!account) return;
    const id = Number(likeMatch[1]);
    const preset = db.prepare('SELECT id FROM presets WHERE id = ? AND hidden = 0').get(id);
    if (!preset) return reply(res, 404, { ok: false, error: 'not found' });
    if (req.method === 'POST') {
      const result = db.prepare('INSERT OR IGNORE INTO likes (preset_id, account_id, created_at) VALUES (?, ?, ?)')
        .run(id, account.id, Date.now());
      if (result.changes > 0) {
        db.prepare('UPDATE presets SET likes_count = likes_count + 1 WHERE id = ?').run(id);
      }
      return reply(res, 200, { ok: true });
    }
    const result = db.prepare('DELETE FROM likes WHERE preset_id = ? AND account_id = ?').run(id, account.id);
    if (result.changes > 0) {
      db.prepare('UPDATE presets SET likes_count = MAX(likes_count - 1, 0) WHERE id = ?').run(id);
    }
    return reply(res, 200, { ok: true });
  }

  // ---- 评论 ----
  const commentMatch = route.match(/^\/presets\/(\d+)\/comments$/);
  if (req.method === 'POST' && commentMatch) {
    const account = requireAccount(req, res);
    if (!account) return;
    const id = Number(commentMatch[1]);
    const preset = db.prepare('SELECT id FROM presets WHERE id = ? AND hidden = 0').get(id);
    if (!preset) return reply(res, 404, { ok: false, error: 'not found' });
    const commentBody = str(body.body, 140);
    if (!commentBody) return reply(res, 400, { ok: false, error: 'body required' });
    if (overQuota('comments', account.id, 20, 100)) {
      return reply(res, 429, { ok: false, error: 'quota', message: '评论太频繁，过一会儿再试。' });
    }
    const result = db.prepare(
      'INSERT INTO comments (preset_id, account_id, body, created_at) VALUES (?, ?, ?, ?)'
    ).run(id, account.id, commentBody, Date.now());
    db.prepare('UPDATE presets SET comment_count = comment_count + 1 WHERE id = ?').run(id);
    return reply(res, 200, {
      ok: true,
      id: Number(result.lastInsertRowid),
      nickname: account.nickname,
      avatar: account.avatar,
    });
  }

  // ---- 我的喜欢 ----
  if (req.method === 'GET' && route === '/likes') {
    const account = requireAccount(req, res);
    if (!account) return;
    const rows = db.prepare(`
      SELECT p.*, a.nickname, a.avatar FROM likes l
      JOIN presets p ON p.id = l.preset_id
      JOIN accounts a ON a.id = p.account_id
      WHERE l.account_id = ? AND p.hidden = 0
      ORDER BY l.created_at DESC`).all(account.id);
    return reply(res, 200, { ok: true, items: rows.map((row) => card(row, account.id)) });
  }

  // ---- 管理 ----
  if (route === '/admin/presets' && req.method === 'GET') {
    const admin = requireAdmin(req, res);
    if (!admin) return;
    const cursor = Number(url.searchParams.get('cursor') || '0');
    const rows = cursor > 0
      ? db.prepare(
        'SELECT p.*, a.nickname, a.avatar, a.email, a.banned_at FROM presets p ' +
        'JOIN accounts a ON a.id = p.account_id WHERE p.id < ? ORDER BY p.id DESC LIMIT ?'
      ).all(cursor, PAGE_SIZE + 1)
      : db.prepare(
        'SELECT p.*, a.nickname, a.avatar, a.email, a.banned_at FROM presets p ' +
        'JOIN accounts a ON a.id = p.account_id ORDER BY p.id DESC LIMIT ?'
      ).all(PAGE_SIZE + 1);
    const page = rows.slice(0, PAGE_SIZE);
    const nextCursor = rows.length > PAGE_SIZE ? page[page.length - 1].id : null;
    return reply(res, 200, {
      ok: true,
      items: page.map((row) => {
        const item = card(row, admin.id);
        item.authorId = row.account_id;
        item.authorEmail = row.email || '';
        item.hidden = Boolean(row.hidden);
        item.authorBanned = Boolean(row.banned_at);
        return item;
      }),
      nextCursor,
    });
  }

  if (route === '/admin/users' && req.method === 'GET') {
    const admin = requireAdmin(req, res);
    if (!admin) return;
    const query = str(url.searchParams.get('q') || '', 64);
    const rows = query
      ? db.prepare(
        'SELECT * FROM accounts WHERE email LIKE ? OR nickname LIKE ? ORDER BY id DESC LIMIT 30'
      ).all('%' + query + '%', '%' + query + '%')
      : db.prepare('SELECT * FROM accounts ORDER BY id DESC LIMIT 30').all();
    return reply(res, 200, {
      ok: true,
      items: rows.map((account) => ({
        id: account.id,
        email: account.email || '',
        nickname: account.nickname,
        avatar: account.avatar,
        role: account.role || 'user',
        banned: Boolean(account.banned_at),
        banReason: account.ban_reason || '',
        createdAt: account.created_at,
        presets: db.prepare('SELECT COUNT(*) AS c FROM presets WHERE account_id = ?').get(account.id).c,
        comments: db.prepare('SELECT COUNT(*) AS c FROM comments WHERE account_id = ?').get(account.id).c,
      })),
    });
  }

  if (route === '/admin/ban' && req.method === 'POST') {
    const admin = requireAdmin(req, res);
    if (!admin) return;
    const targetId = Number(body.accountId);
    if (!targetId) return reply(res, 400, { ok: false, error: 'accountId required' });
    if (targetId === admin.id) return reply(res, 400, { ok: false, error: 'self' });
    const target = accountById(targetId);
    if (!target) return reply(res, 404, { ok: false, error: 'not found' });
    if (isAdmin(target)) {
      return reply(res, 400, { ok: false, error: 'admin', message: '先取消对方的管理员身份，再封禁。' });
    }
    const reason = str(body.reason, 80) || '';
    db.prepare('UPDATE accounts SET banned_at = ?, ban_reason = ? WHERE id = ?')
      .run(Date.now(), reason, targetId);
    // 封禁后现有登录立即失效，内容通过 FEED_SQL 一并下架。
    db.prepare('DELETE FROM sessions WHERE account_id = ?').run(targetId);
    return reply(res, 200, { ok: true });
  }

  if (route === '/admin/unban' && req.method === 'POST') {
    const admin = requireAdmin(req, res);
    if (!admin) return;
    const targetId = Number(body.accountId);
    if (!targetId) return reply(res, 400, { ok: false, error: 'accountId required' });
    db.prepare("UPDATE accounts SET banned_at = NULL, ban_reason = '' WHERE id = ?").run(targetId);
    return reply(res, 200, { ok: true });
  }

  if (route === '/admin/role' && req.method === 'POST') {
    const admin = requireAdmin(req, res);
    if (!admin) return;
    const targetId = Number(body.accountId);
    const role = body.role === 'admin' ? 'admin' : body.role === 'user' ? 'user' : null;
    if (!targetId || !role) return reply(res, 400, { ok: false, error: 'bad request' });
    // 把自己降级会把自己锁在门外，这类误操作直接拒绝。
    if (targetId === admin.id && role === 'user') {
      return reply(res, 400, { ok: false, error: 'self', message: '不能取消自己的管理员身份。' });
    }
    const target = accountById(targetId);
    if (!target) return reply(res, 404, { ok: false, error: 'not found' });
    db.prepare('UPDATE accounts SET role = ? WHERE id = ?').run(role, targetId);
    return reply(res, 200, { ok: true });
  }

  // ---- 删除评论（作者本人或管理员） ----
  const commentIdMatch = route.match(/^\/comments\/(\d+)$/);
  if (req.method === 'DELETE' && commentIdMatch) {
    const account = requireAccount(req, res);
    if (!account) return;
    const commentId = Number(commentIdMatch[1]);
    const row = db.prepare('SELECT * FROM comments WHERE id = ?').get(commentId);
    if (!row) return reply(res, 404, { ok: false, error: 'not found' });
    if (row.account_id !== account.id && !isAdmin(account)) {
      return reply(res, 403, { ok: false, error: 'not owner' });
    }
    db.prepare('DELETE FROM comments WHERE id = ?').run(commentId);
    db.prepare('UPDATE presets SET comment_count = MAX(comment_count - 1, 0) WHERE id = ?')
      .run(row.preset_id);
    return reply(res, 200, { ok: true });
  }

  // 只有一个管理入口：账号角色。软删（hidden）那套与硬删语义重叠，已移除。
  return reply(res, 404, { ok: false, error: 'not found' });
}

server.listen(PORT, HOST, () => {
  console.log('aurora community on http://' + HOST + ':' + PORT +
    ' (mail: ' + (MAIL_STDOUT ? 'stdout' : mailConfig ? mailConfig.host : 'not configured') + ')');
});