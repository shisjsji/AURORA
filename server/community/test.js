'use strict';

/** 一次性验收脚本：起一个临时实例，把登录与内容闭环全跑一遍。跑完自己清理。 */

const { spawn } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'aurora-test-'));
const port = 5099;
const base = 'http://127.0.0.1:' + port;

const child = spawn(process.execPath, [path.join(__dirname, 'server.js')], {
  env: {
    ...process.env,
    AURORA_COMMUNITY_PORT: String(port),
    AURORA_COMMUNITY_DATA: dir,
    AURORA_COMMUNITY_MAIL: path.join(dir, 'no-mail.json'),
    AURORA_COMMUNITY_MAIL_STDOUT: '1',
  },
  stdio: ['ignore', 'pipe', 'pipe'],
});

let log = '';
const codes = new Map();
child.stdout.on('data', (chunk) => {
  const text = chunk.toString();
  log += text;
  const match = /\[mail\] (\S+) -> (\d{6})/g;
  let m;
  while ((m = match.exec(text)) !== null) codes.set(m[1], m[2]);
});
child.stderr.on('data', (chunk) => { log += chunk.toString(); });

let passed = 0;
let failed = 0;
function check(name, ok, detail) {
  if (ok) { passed++; console.log('ok   ' + name); }
  else { failed++; console.log('FAIL ' + name + (detail ? ' :: ' + detail : '')); }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function api(method, route, body, token) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = 'Bearer ' + token;
  const res = await fetch(base + route, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  let json = null;
  try { json = await res.json(); } catch (e) { json = null; }
  return { status: res.status, json };
}

async function codeFor(raw) {
  const email = raw.toLowerCase();
  codes.delete(email);
  const res = await api('POST', '/auth/code', { email: raw });
  for (let i = 0; i < 60 && !codes.has(email); i++) await sleep(100);
  return { res, code: codes.get(email) };
}

async function main() {
  for (let i = 0; i < 60; i++) {
    try {
      const res = await fetch(base + '/health');
      if (res.ok) break;
    } catch (e) { /* not up yet */ }
    await sleep(100);
  }

  const health = await api('GET', '/health');
  check('health', health.status === 200 && health.json.mail === true, JSON.stringify(health.json));

  const anon = await api('POST', '/presets', { name: 'x', curve: [1, 2, 3, 4, 5, 6], model: 'm', deviceLabel: 'd' });
  check('publish without token -> 401', anon.status === 401, String(anon.status));

  const email = 'Tester@Example.com';
  const first = await codeFor(email);
  check('code sent', first.res.status === 200 && /^\d{6}$/.test(first.code || ''), JSON.stringify(first.res.json));

  const resend = await api('POST', '/auth/code', { email });
  check('resend within 60s -> 429 tooSoon', resend.status === 429 && resend.json.error === 'tooSoon', JSON.stringify(resend.json));

  const wrong = await api('POST', '/auth/verify', { email, code: first.code === '000000' ? '111111' : '000000' });
  check('wrong code -> 400 badCode with tries left', wrong.status === 400 && wrong.json.error === 'badCode' && wrong.json.left === 4, JSON.stringify(wrong.json));

  const verify = await api('POST', '/auth/verify', { email, code: first.code });
  check('verify -> token', verify.status === 200 && typeof verify.json.token === 'string' && verify.json.token.length === 64, JSON.stringify(verify.json).slice(0, 200));
  const token = verify.json.token;

  const me = await api('GET', '/me', undefined, token);
  check('me returns the verified email', me.status === 200 && me.json.profile.email === 'tester@example.com', JSON.stringify(me.json));

  const patch = await api('PATCH', '/me', { nickname: '小听感' }, token);
  check('patch nickname leaves avatar alone', patch.status === 200 && patch.json.profile.nickname === '小听感' && patch.json.profile.avatar === '', JSON.stringify(patch.json));

  const iconAvatar = await api('PATCH', '/me', { avatar: 'music' }, token);
  check('icon avatars are gone', iconAvatar.status === 400 && iconAvatar.json.error === 'badAvatar', JSON.stringify(iconAvatar.json));

  const resetAvatar = await api('PATCH', '/me', { avatar: '' }, token);
  check('empty avatar means default', resetAvatar.status === 200 && resetAvatar.json.profile.avatar === '', JSON.stringify(resetAvatar.json));

  // 头像上传：三个假字节开头当图片，服务端只看文件头与长度。
  const jpeg = Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), Buffer.alloc(512, 7), Buffer.from([0xff, 0xd9])]);
  const upload = await fetch(base + '/me/avatar', {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'image/jpeg' },
    body: jpeg,
  });
  const uploaded = await upload.json();
  check('avatar upload', upload.status === 200 && String(uploaded.avatar).startsWith('img:'), JSON.stringify(uploaded));

  const served = await fetch(base + '/avatars/' + String(uploaded.avatar).slice(4));
  const nosniff = served.headers.get('x-content-type-options');
  check('avatar served with nosniff', served.status === 200 && nosniff === 'nosniff', String(served.status) + ' ' + nosniff);
  await served.arrayBuffer();

  const clearUpload = await api('PATCH', '/me', { avatar: '' }, token);
  check('clearing an upload returns to default', clearUpload.status === 200 && clearUpload.json.profile.avatar === '', JSON.stringify(clearUpload.json));

  const flat = await api('POST', '/presets', {
    name: '平直', curve: [0, 2, -1, 0, 3, -2], model: 'pandora', deviceLabel: '17 Pro',
  }, token);
  check('flat curve -> 422 flat', flat.status === 422 && flat.json.error === 'flat', JSON.stringify(flat.json));

  const real = await api('POST', '/presets', {
    name: '现场低频', curve: [120, 90, 30, -70, -110, -120], model: 'pandora', deviceLabel: '17 Pro',
    preset: 'punch', strength: 80,
  }, token);
  check('publish -> id', real.status === 200 && real.json.id > 0, JSON.stringify(real.json));
  const id = real.json.id;

  const dup = await api('POST', '/presets', {
    name: '再发一次', curve: [120, 90, 30, -70, -110, -120], model: 'pandora', deviceLabel: '17 Pro',
  }, token);
  check('duplicate -> 409 with existing id', dup.status === 409 && dup.json.error === 'duplicate' && dup.json.id === id, JSON.stringify(dup.json));

  const feed = await api('GET', '/presets');
  check('feed public without token', feed.status === 200 && feed.json.items.length === 1 && feed.json.items[0].mine === false, JSON.stringify(feed.json).slice(0, 240));
  check('feed keeps preset metadata', feed.json.items[0].preset === 'punch' && feed.json.items[0].strength === 80, JSON.stringify(feed.json.items[0]));

  const like = await api('POST', '/presets/' + id + '/like', undefined, token);
  check('like', like.status === 200, JSON.stringify(like.json));

  const detail = await api('GET', '/presets/' + id, undefined, token);
  check('detail liked + mine flags', detail.status === 200 && detail.json.item.liked === true && detail.json.item.mine === true && detail.json.item.likes === 1, JSON.stringify(detail.json.item));

  const comment = await api('POST', '/presets/' + id + '/comments', { body: '低频很有弹性' }, token);
  check('comment', comment.status === 200 && comment.json.nickname === '小听感', JSON.stringify(comment.json));

  const likes = await api('GET', '/likes', undefined, token);
  check('my likes', likes.status === 200 && likes.json.items.length === 1, JSON.stringify(likes.json).slice(0, 200));

  const mine = await api('GET', '/presets?mine=1', undefined, token);
  check('my published list', mine.status === 200 && mine.json.items.length === 1, JSON.stringify(mine.json).slice(0, 200));

  const otherEmail = 'other@example.com';
  const otherCode = await codeFor(otherEmail);
  const otherVerify = await api('POST', '/auth/verify', { email: otherEmail, code: otherCode.code });
  const otherToken = otherVerify.json.token;
  const steal = await api('DELETE', '/presets/' + id, undefined, otherToken);
  check("cannot delete someone else's preset", steal.status === 403, String(steal.status));

  const remove = await api('DELETE', '/presets/' + id, undefined, token);
  const afterFeed = await api('GET', '/presets');
  check('delete own preset', remove.status === 200 && afterFeed.json.items.length === 0, JSON.stringify(afterFeed.json).slice(0, 160));

  // 老匿名身份迁移
  const legacyAnon = 'legacy-device-uuid';
  const { DatabaseSync } = require('node:sqlite');
  const admin = new DatabaseSync(path.join(dir, 'community.db'));
  admin.prepare(
    'INSERT INTO accounts (anon_id, recovery_key, nickname, avatar, created_at) VALUES (?, ?, ?, ?, ?)'
  ).run(legacyAnon, 'AAAA-BBBB-CCCC', '老设备', 'eq', Date.now());
  const legacyId = admin.prepare('SELECT id FROM accounts WHERE anon_id = ?').get(legacyAnon).id;
  admin.prepare(
    'INSERT INTO presets (account_id, name, curve, model, device_label, created_at) VALUES (?, ?, ?, ?, ?, ?)'
  ).run(legacyId, '老曲线', '10,20,30,40,50,60', 'pandora', '17 Pro', Date.now());
  admin.close();

  const migrateEmail = 'legacy@example.com';
  const migrateCode = await codeFor(migrateEmail);
  const migrateVerify = await api('POST', '/auth/verify', {
    email: migrateEmail, code: migrateCode.code, anonId: legacyAnon,
  });
  check('legacy verify ok', migrateVerify.status === 200, JSON.stringify(migrateVerify.json).slice(0, 160));
  const migrateToken = migrateVerify.json.token;
  const migratedMine = await api('GET', '/presets?mine=1', undefined, migrateToken);
  check('legacy content adopted by the email account',
    migratedMine.status === 200 && migratedMine.json.items.length === 1 && migratedMine.json.items[0].name === '老曲线',
    JSON.stringify(migratedMine.json).slice(0, 200));

  // 注销
  const del = await api('DELETE', '/me', undefined, migrateToken);
  const gone = await api('GET', '/me', undefined, migrateToken);
  const afterDelete = await api('GET', '/presets');
  check('delete account clears session and content',
    del.status === 200 && gone.status === 401 && afterDelete.json.items.length === 0,
    JSON.stringify({ del: del.status, gone: gone.status, items: afterDelete.json.items.length }));

  // 退出登录
  const out = await api('POST', '/auth/logout', undefined, token);
  const afterLogout = await api('GET', '/me', undefined, token);
  check('logout invalidates token', out.status === 200 && afterLogout.status === 401, String(afterLogout.status));

  const badEmail = await api('POST', '/auth/code', { email: 'not-an-email' });
  check('bad email rejected', badEmail.status === 400 && badEmail.json.error === 'badEmail', JSON.stringify(badEmail.json));

  console.log('\n' + passed + ' passed, ' + failed + ' failed');
  if (failed > 0) console.log('--- server log ---\n' + log);
  child.kill();
  fs.rmSync(dir, { recursive: true, force: true });
  process.exit(failed > 0 ? 1 : 0);
}

main().catch((error) => {
  console.error(error);
  console.log('--- server log ---\n' + log);
  child.kill();
  fs.rmSync(dir, { recursive: true, force: true });
  process.exit(1);
});