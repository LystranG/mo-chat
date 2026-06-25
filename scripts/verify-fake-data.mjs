// 端到端解密验证：从 DB 取私聊 payload，用接收方 X25519 私钥 + 发送方公钥算共享密钥，AES-256-GCM 解密。
import crypto from 'node:crypto';
import { readFileSync } from 'node:fs';
import { execSync } from 'node:child_process';

const keys = JSON.parse(readFileSync(new URL('./seed-output/keys.json', import.meta.url), 'utf8'));
const byId = Object.fromEntries(keys.users.map(u => [u.userId, u]));

const PKCS8 = Buffer.from('302e020100300506032b656e04220420', 'hex');
const SPKI  = Buffer.from('302a300506032b656e032100', 'hex');
const privKey = u => crypto.createPrivateKey({ key: Buffer.concat([PKCS8, Buffer.from(u.privateKeyBase64, 'base64')]), format: 'der', type: 'pkcs8' });
const pubKey  = u => crypto.createPublicKey({ key: Buffer.concat([SPKI, Buffer.from(u.publicKeyBase64, 'base64')]), format: 'der', type: 'spki' });

// minimal protobuf length-delimited scan: return map of field->{firstValueBuf or bigint}
function readVarint(buf, off) {
  let v = 0n, s = 0n;
  while (true) { const b = buf[off++]; v |= BigInt(b & 0x7f) << s; s += 7n; if (!(b & 0x80)) break; }
  return [v, off];
}
function scan(buf) {
  const out = {}; let off = 0;
  while (off < buf.length) {
    const [t, o1] = readVarint(buf, off); off = o1;
    const field = Number(t >> 3n), wire = Number(t & 7n);
    if (wire === 2) {
      const [len, o2] = readVarint(buf, off); off = o2;
      out[field] = buf.subarray(off, off + Number(len)); off += Number(len);
    } else {
      const [v, o2] = readVarint(buf, off); off = o2;
      out[field] = v;
    }
  }
  return out;
}

// 取一条 alice(1001)->bob(1002) 私聊
const line = execSync('docker exec mochat-postgres-1 psql -U mochat -d mochat -At -c "SELECT sender_uid || \'|\' || payload_base64 FROM messages WHERE kind=\'private\' AND conversation_id=5001 ORDER BY seq LIMIT 1;"', { encoding: 'utf8' }).trim();
const [senderIdStr, payloadB64] = line.split('|');
const senderId = Number(senderIdStr);
const sender = byId[senderId];
const recipient = byId[1002];

const body = Buffer.from(payloadB64, 'base64');
const top = scan(body);
const content = scan(top[10]);        // MessageContent
const encryptedTextBuf = content[1];  // oneof encryptedText = 1
const et = scan(encryptedTextBuf);
const nonce = et[1];
const ct = et[2];

const shared = crypto.diffieHellman({ privateKey: privKey(recipient), publicKey: pubKey(sender) });
const d = crypto.createDecipheriv('aes-256-gcm', shared, nonce);
d.setAuthTag(ct.subarray(ct.length - 16));
const dec = Buffer.concat([d.update(ct.subarray(0, ct.length - 16)), d.final()]).toString('utf8');

console.log(`✓ 解密成功`);
console.log(`  发送方: ${sender.username} (id=${sender.id})`);
console.log(`  接收方: ${recipient.username} (id=${recipient.id})`);
console.log(`  nonce(${nonce.length}B): ${nonce.toString('hex')}`);
console.log(`  密文长度: ${ct.length}B (含 16B tag)`);
console.log(`  明文: ${dec}`);