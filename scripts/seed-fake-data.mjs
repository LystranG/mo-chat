// 一次性假数据生成器：为已部署的 MoChat 生成 5 个用户、2 个群聊、若干私聊/群聊历史。
//
// 认证：本系统没有密码，登录靠 username + X25519 公钥。这里为每个用户生成
//   X25519 keypair，私钥保存到 seed-output/keys.json，公钥写入 users.public_key。
// 私聊加密：端到端，服务端只存不透明 payload_base64。本脚本按如下方案加密：
//   - 共享密钥 = X25519(senderPriv, recipientPub) 原始 32 字节
//   - 对称加密 = AES-256-GCM，12 字节随机 nonce，无 AAD
//   - 密文 = encrypted || 16 字节 auth tag（与 Java AES/GCM 默认布局一致）
// 解密时：shared = X25519(myPriv, peerPub)；用 shared 作 AES-256 key，
//   nonce 取 EncryptedText.nonce 前 12 字节，尾 16 字节为 tag。
// 群聊：按服务端约束 (MessageIngestService.validateGroupPayload)，群聊 payload
//   只能用 PlainText，不能用 EncryptedText，因此群聊消息以明文 protobuf 存储。

import crypto from 'node:crypto';
import { writeFileSync, mkdirSync } from 'node:fs';

const OUT_DIR = new URL('./seed-output/', import.meta.url).pathname;
mkdirSync(OUT_DIR, { recursive: true });

// ---------- protobuf 手写编码（这些 message 结构很简单，无需 protoc） ----------
function varint(n) {
  const out = [];
  n = BigInt(n);
  do {
    let b = Number(n & 0x7fn);
    n >>= 7n;
    if (n) b |= 0x80;
    out.push(b);
  } while (n);
  return Buffer.from(out);
}
const tag = (field, wire) => varint((BigInt(field) << 3n) | BigInt(wire));
const bytes = (field, buf) => Buffer.concat([tag(field, 2), varint(buf.length), buf]);
const strField = (field, s) => bytes(field, Buffer.from(s, 'utf8'));
const varField = (field, n) => Buffer.concat([tag(field, 0), varint(n)]);

function encryptedText(nonce, ciphertext) {
  return Buffer.concat([bytes(1, nonce), bytes(2, ciphertext)]);
}
function plainText(text) {
  return strField(1, text);
}
function contentEncrypted(et) { return bytes(1, et); }       // oneof encryptedText = 1
function contentPlain(pt)    { return bytes(2, pt); }       // oneof plainText = 2

function privateMessageReq({ sessionId, clientMsgId, conversationId, toUid, contents }) {
  const parts = [];
  if (sessionId)      parts.push(strField(1, sessionId));
  if (clientMsgId)   parts.push(varField(2, clientMsgId));
  if (conversationId)parts.push(varField(3, conversationId));
  if (toUid)         parts.push(varField(4, toUid));
  for (const c of contents) parts.push(bytes(10, c));        // repeated contents = 10
  return Buffer.concat(parts);
}
function groupMessageReq({ sessionId, clientMsgId, conversationId, groupId, contents }) {
  const parts = [];
  if (sessionId)      parts.push(strField(1, sessionId));
  if (clientMsgId)   parts.push(varField(2, clientMsgId));
  if (conversationId)parts.push(varField(3, conversationId));
  if (groupId)       parts.push(varField(4, groupId));
  for (const c of contents) parts.push(bytes(10, c));
  return Buffer.concat(parts);
}

// ---------- 用户与密钥 ----------
const users = [
  { id: 1001, name: 'alice' },
  { id: 1002, name: 'bob' },
  { id: 1003, name: 'carol' },
  { id: 1004, name: 'dave' },
  { id: 1005, name: 'eve' },
];
const b64url = s => Buffer.from(s, 'base64url');
for (const u of users) {
  const { privateKey, publicKey } = crypto.generateKeyPairSync('x25519');
  const privJwk = privateKey.export({ format: 'jwk' });
  const pubJwk  = publicKey.export({ format: 'jwk' });
  u.privKey = privateKey;
  u.pubKey  = publicKey;
  u.priv = b64url(privJwk.d);   // 32 bytes
  u.pub  = b64url(pubJwk.x);    // 32 bytes
  u.pubB64  = u.pub.toString('base64');
  u.privB64 = u.priv.toString('base64');
}
const byName = Object.fromEntries(users.map(u => [u.name, u]));
const byId   = Object.fromEntries(users.map(u => [u.id, u]));

function encryptFor(senderName, recipientName, plain) {
  const shared = crypto.diffieHellman({
    privateKey: byName[senderName].privKey,
    publicKey:  byName[recipientName].pubKey,
  });
  const nonce = crypto.randomBytes(12);
  const c = crypto.createCipheriv('aes-256-gcm', shared, nonce);
  const enc = Buffer.concat([c.update(plain, 'utf8'), c.final()]);
  const tag = c.getAuthTag();
  return { nonce, ciphertext: Buffer.concat([enc, tag]) }; // 密文 || tag
}

// ---------- 关系拓扑 ----------
// friendship id == private conversation id (type=0)，约束 uid_1 < uid_2
const friendships = [
  { id: 5001, a: 'alice', b: 'bob' },
  { id: 5002, a: 'carol', b: 'dave' },
  { id: 5003, a: 'alice', b: 'eve' },
];
// group id == group conversation id (type=1)
const groups = [
  { id: 7001, name: '技术交流', owner: 'alice', members: ['alice', 'bob', 'carol', 'dave', 'eve'] },
  { id: 7002, name: '周末饭局', owner: 'bob',   members: ['bob', 'carol', 'dave', 'eve'] },
];

// ---------- 消息内容 ----------
const now = Date.parse('2026-06-25T03:00:00Z');
let ts = now;
let msgId = 200000n;
const clientMsgCounter = {}; // per sender
function nextClientMsgId(senderName) {
  clientMsgCounter[senderName] = (clientMsgCounter[senderName] || 0n) + 1n;
  return clientMsgCounter[senderName];
}

const messages = []; // {cols...}
function addPrivate(conv, senderName, recipientName, text) {
  const low  = Math.min(byName[senderName].id, byName[recipientName].id);
  const high = Math.max(byName[senderName].id, byName[recipientName].id);
  const seq = messages.filter(m => m.conversation_id === conv).length + 1;
  ts += 60000;
  msgId += 1n;
  const cmid = nextClientMsgId(senderName);
  const { nonce, ciphertext } = encryptFor(senderName, recipientName, text);
  const payload = privateMessageReq({
    sessionId: 'seed-' + msgId.toString(),
    clientMsgId: cmid,
    conversationId: conv,
    toUid: byName[recipientName].id,
    contents: [contentEncrypted(encryptedText(nonce, ciphertext))],
  });
  messages.push({
    msg_id: msgId, conversation_id: conv, seq, client_msg_id: cmid,
    kind: 'private', sender_uid: byName[senderName].id,
    peer_uid_low: low, peer_uid_high: high, group_id: null,
    server_ts_ms: ts, payload_base64: payload.toString('base64'),
    _text: text, _sender: senderName, _recipient: recipientName,
  });
}
function addGroup(groupId, senderName, text) {
  const seq = messages.filter(m => m.conversation_id === groupId).length + 1;
  ts += 60000;
  msgId += 1n;
  const cmid = nextClientMsgId(senderName);
  const payload = groupMessageReq({
    sessionId: 'seed-' + msgId.toString(),
    clientMsgId: cmid,
    conversationId: groupId,
    groupId,
    contents: [contentPlain(plainText(text))],
  });
  messages.push({
    msg_id: msgId, conversation_id: groupId, seq, client_msg_id: cmid,
    kind: 'group', sender_uid: byName[senderName].id,
    peer_uid_low: null, peer_uid_high: null, group_id: groupId,
    server_ts_ms: ts, payload_base64: payload.toString('base64'),
    _text: text, _sender: senderName,
  });
}

// 私聊对话
addPrivate(5001, 'alice', 'bob', '在吗？周末要不要一起看下 mo-chat 的部署');
addPrivate(5001, 'bob',   'alice','好啊，我刚把 persistence-service 跑起来了');
addPrivate(5001, 'alice', 'bob',  '那我这边造点假数据进去，你顺便验证下 /history');
addPrivate(5001, 'bob',   'alice','收到，注意私聊是 E2E，你得用我的公钥加密');
addPrivate(5001, 'alice', 'bob',  '明白，用的 X25519 + AES-256-GCM，12 字节 nonce');

addPrivate(5002, 'carol', 'dave', 'dave，群聊消息服务端是明文存的哦');
addPrivate(5002, 'dave',  'carol','对，validateGroupPayload 拒绝 EncryptedText');
addPrivate(5002, 'carol', 'dave', '那私聊就老老实实加密，群聊先走明文');

addPrivate(5003, 'alice', 'eve',  'eve 你被拉进「技术交流」群了');
addPrivate(5003, 'eve',   'alice','看到啦，我去打个招呼');

// 群聊对话
addGroup(7001, 'alice', '大家好，这是测试群，欢迎玩假数据');
addGroup(7001, 'bob',   '群消息是不是不加密？');
addGroup(7001, 'carol', '对，群走 PlainText，服务端只校验 groupId');
addGroup(7001, 'dave',  '那 history 拉出来就是明文 protobuf');
addGroup(7001, 'eve',   '收到，接入了~');
addGroup(7001, 'alice', '私聊记得用对端公钥加密，别搞错');

addGroup(7002, 'bob',   '周末谁来吃饭？');
addGroup(7002, 'carol', '我可以');
addGroup(7002, 'dave',  '带上我');
addGroup(7002, 'eve',   '算我一个');

// ---------- 汇总 conversation latest_seq ----------
const convLatest = {};
for (const m of messages) {
  convLatest[m.conversation_id] = { seq: m.seq, ts: m.server_ts_ms };
}

// ---------- 生成 SQL ----------
function sqlStr(s) { return "'" + s.replace(/'/g, "''") + "'"; }

const lines = [];
lines.push("SET client_encoding TO 'UTF8';");
lines.push("BEGIN;");
lines.push("-- users");
for (const u of users) {
  lines.push(`INSERT INTO users (id, username, public_key) VALUES (${u.id}, ${sqlStr(u.name)}, decode('${u.pub.toString('hex')}', 'hex'));`);
}
lines.push("-- friendships + private conversations");
for (const f of friendships) {
  const u1 = Math.min(byName[f.a].id, byName[f.b].id);
  const u2 = Math.max(byName[f.a].id, byName[f.b].id);
  lines.push(`INSERT INTO user_friendships (id, uid_1, uid_2, status, blocked_by) VALUES (${f.id}, ${u1}, ${u2}, 'ok', NULL);`);
  const { seq, ts } = convLatest[f.id] || { seq: 0, ts: 0 };
  lines.push(`INSERT INTO conversations (id, type, latest_seq, latest_message_time, uid_1_seq, uid_2_seq) VALUES (${f.id}, 0, ${seq}, ${ts}, ${seq}, ${seq});`);
}
lines.push("-- groups + memberships + group conversations");
for (const g of groups) {
  lines.push(`INSERT INTO groups (id, owner_uid, name) VALUES (${g.id}, ${byName[g.owner].id}, ${sqlStr(g.name)});`);
  const { seq, ts } = convLatest[g.id] || { seq: 0, ts: 0 };
  lines.push(`INSERT INTO conversations (id, type, latest_seq, latest_message_time, uid_1_seq, uid_2_seq) VALUES (${g.id}, 1, ${seq}, ${ts}, 0, 0);`);
}
let memId = 8001n;
for (const g of groups) {
  for (const m of g.members) {
    const role = m === g.owner ? 'owner' : 'member';
    lines.push(`INSERT INTO group_memberships (id, group_id, user_id, role, status) VALUES (${memId}, ${g.id}, ${byName[m].id}, '${role}', 'active');`);
    memId += 1n;
  }
}
lines.push("-- messages");
for (const m of messages) {
  const peerLow = m.peer_uid_low === null ? 'NULL' : m.peer_uid_low;
  const peerHigh = m.peer_uid_high === null ? 'NULL' : m.peer_uid_high;
  const groupId = m.group_id === null ? 'NULL' : m.group_id;
  lines.push(`INSERT INTO messages (msg_id, conversation_id, seq, client_msg_id, kind, sender_uid, peer_uid_low, peer_uid_high, group_id, server_ts_ms, payload_base64) VALUES (${m.msg_id}, ${m.conversation_id}, ${m.seq}, ${m.client_msg_id}, '${m.kind}', ${m.sender_uid}, ${peerLow}, ${peerHigh}, ${groupId}, ${m.server_ts_ms}, ${sqlStr(m.payload_base64)});`);
}
lines.push("COMMIT;");

const sql = lines.join('\n') + '\n';
writeFileSync(OUT_DIR + 'seed.sql', sql, 'utf8');

// ---------- 保存密钥与说明 ----------
const keys = {
  scheme: {
    auth: 'login(username, base64(X25519 publicKey))；本系统无密码字段',
    privateMessage: {
      sharedSecret: 'X25519(senderPriv, recipientPub) 原始 32 字节',
      aead: 'AES-256-GCM, 12 字节随机 nonce, 无 AAD',
      ciphertextLayout: 'encrypted || 16 字节 authTag',
      decrypt: 'shared = X25519(myPriv, peerPub); tag=密文末16字节; AES-256-GCM 解密',
    },
    groupMessage: '服务端强制 PlainText（validateGroupPayload 拒绝 EncryptedText），故群聊不加密',
  },
  users: users.map(u => ({
    userId: u.id, username: u.name,
    publicKeyBase64: u.pubB64, privateKeyBase64: u.privB64,
  })),
};
writeFileSync(OUT_DIR + 'keys.json', JSON.stringify(keys, null, 2), 'utf8');

console.log(`生成完成：${users.length} 用户, ${friendships.length} 私聊会话, ${groups.length} 群聊, ${messages.length} 条消息`);
console.log(`SQL  -> ${OUT_DIR}seed.sql`);
console.log(`密钥 -> ${OUT_DIR}keys.json`);