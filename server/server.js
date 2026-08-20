'use strict';

const crypto = require('crypto');
const fs = require('fs');
const http = require('http');
const path = require('path');
const { WebSocketServer, WebSocket } = require('ws');
const { createPasswordVerifier } = require('./password-auth');

const PORT = Number(process.env.PORT || 8295);
const VEHICLE_TOKEN = process.env.VEHICLE_TOKEN || '';
const OWNER_PASSWORD = process.env.OWNER_PASSWORD || '';
const OWNER_PASSWORD_HASH = process.env.OWNER_PASSWORD_HASH || '';
const PUBLIC_DIR = path.join(__dirname, 'public');
const DOWNLOAD_DIR = path.resolve(process.env.DOWNLOAD_DIR || '/downloads');
const DOWNLOAD_APK_PATH = process.env.DOWNLOAD_APK_PATH
  || '/downloads/Lynk10EV-RemoteView-test-release.apk';
const DOWNLOAD_FILENAME = String(process.env.DOWNLOAD_FILENAME || 'Lynk10EV-RemoteView.bin')
  .replace(/[^A-Za-z0-9._-]/g, '_');
const DEVICE_AUTH_FILE = path.resolve(process.env.DEVICE_AUTH_FILE
  || path.join(__dirname, 'state', 'device-auth.json'));
const MAX_MESSAGE_BYTES = 12 * 1024 * 1024;
const MAX_BODY_BYTES = 64 * 1024;
const CHALLENGE_TTL_MS = 2 * 60 * 1000;
const PRIMARY_SESSION_TTL_MS = 30 * 24 * 60 * 60 * 1000;
const PASSWORD_SESSION_TTL_MS = 30 * 60 * 1000;
const PASSWORD_ATTEMPT_WINDOW_MS = 15 * 60 * 1000;
const PASSWORD_MAX_ATTEMPTS = 5;

if (VEHICLE_TOKEN.length < 16) {
  console.error('VEHICLE_TOKEN must contain at least 16 characters.');
  process.exit(1);
}
let verifyOwnerPassword;
try {
  verifyOwnerPassword = createPasswordVerifier(OWNER_PASSWORD_HASH, OWNER_PASSWORD);
} catch (error) {
  console.error(`Owner password configuration error: ${error.message}`);
  process.exit(1);
}

const vehicles = new Map();
const vehicleMeta = new Map();
const viewers = new Set();
const pending = new Map();
const challenges = new Map();
const sessions = new Map();
const passwordAttempts = new Map();
let deviceCredential = loadDeviceCredential();

function loadDeviceCredential() {
  try {
    const value = JSON.parse(fs.readFileSync(DEVICE_AUTH_FILE, 'utf8'));
    if (!value.deviceId || value.publicKeyJwk?.kty !== 'EC' || value.publicKeyJwk?.crv !== 'P-256') {
      throw new Error('invalid device credential');
    }
    return value;
  } catch (error) {
    if (error.code !== 'ENOENT') console.error(`Unable to load device binding: ${error.message}`);
    return null;
  }
}

function saveDeviceCredential(value) {
  const directory = path.dirname(DEVICE_AUTH_FILE);
  fs.mkdirSync(directory, { recursive: true });
  const temporary = `${DEVICE_AUTH_FILE}.${process.pid}.tmp`;
  fs.writeFileSync(temporary, JSON.stringify(value, null, 2), { mode: 0o600 });
  fs.renameSync(temporary, DEVICE_AUTH_FILE);
}

function parseCookies(req) {
  const result = {};
  for (const item of String(req.headers.cookie || '').split(';')) {
    const separator = item.indexOf('=');
    if (separator < 1) continue;
    result[item.slice(0, separator).trim()] = item.slice(separator + 1).trim();
  }
  return result;
}

function cleanupDeviceAuth() {
  const now = Date.now();
  for (const [id, item] of challenges.entries()) if (item.expiresAt <= now) challenges.delete(id);
  for (const [id, item] of sessions.entries()) if (item.expiresAt <= now) sessions.delete(id);
  for (const [key, item] of passwordAttempts.entries()) {
    if (item.windowStartedAt + PASSWORD_ATTEMPT_WINDOW_MS <= now) passwordAttempts.delete(key);
  }
}

function viewerSession(req) {
  cleanupDeviceAuth();
  const sessionId = parseCookies(req).lynk_viewer_session;
  const session = sessionId ? sessions.get(sessionId) : null;
  return session && session.expiresAt > Date.now() ? session : null;
}

function isViewerAuthenticated(req) {
  return Boolean(viewerSession(req));
}

function issueViewerSession(res, authMethod) {
  const primary = authMethod === 'device';
  const ttl = primary ? PRIMARY_SESSION_TTL_MS : PASSWORD_SESSION_TTL_MS;
  const sessionId = crypto.randomBytes(32).toString('base64url');
  sessions.set(sessionId, { authMethod, expiresAt: Date.now() + ttl });
  const persistent = primary ? `; Max-Age=${Math.floor(ttl / 1000)}` : '';
  res.setHeader('Set-Cookie', `lynk_viewer_session=${sessionId}${persistent}; Path=/; HttpOnly; Secure; SameSite=Strict`);
}

function sendJson(res, status, payload) {
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff'
  });
  res.end(JSON.stringify(payload));
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let length = 0;
    req.on('data', chunk => {
      length += chunk.length;
      if (length > MAX_BODY_BYTES) {
        reject(new Error('request too large'));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))); }
      catch { reject(new Error('invalid json')); }
    });
    req.on('error', reject);
  });
}

function requestHasExpectedOrigin(req) {
  const origin = req.headers.origin;
  if (!origin) return true;
  const forwarded = String(req.headers['x-forwarded-proto'] || '').split(',')[0].trim();
  const protocol = forwarded || (req.socket.encrypted ? 'https' : 'http');
  return origin === `${protocol}://${req.headers.host}`;
}

function publicKeyFromJwk(jwk) {
  if (!jwk || jwk.kty !== 'EC' || jwk.crv !== 'P-256'
    || typeof jwk.x !== 'string' || typeof jwk.y !== 'string') {
    throw new Error('unsupported public key');
  }
  return crypto.createPublicKey({ key: jwk, format: 'jwk' });
}

function sameVehicleToken(candidate) {
  const left = Buffer.from(String(candidate || ''));
  const right = Buffer.from(VEHICLE_TOKEN);
  return left.length === right.length && crypto.timingSafeEqual(left, right);
}

function sameOwnerPassword(candidate) {
  const value = String(candidate || '');
  if (value.length < 1 || value.length > 1024) return false;
  return verifyOwnerPassword(value);
}

function requestClientKey(req) {
  const cloudflare = String(req.headers['cf-connecting-ip'] || '').trim();
  const forwarded = String(req.headers['x-forwarded-for'] || '').split(',')[0].trim();
  return cloudflare || forwarded || req.socket.remoteAddress || 'unknown';
}

function passwordAttemptAllowed(req) {
  cleanupDeviceAuth();
  const item = passwordAttempts.get(requestClientKey(req));
  return !item || item.attempts < PASSWORD_MAX_ATTEMPTS;
}

function recordPasswordFailure(req) {
  const key = requestClientKey(req);
  const now = Date.now();
  const existing = passwordAttempts.get(key);
  const item = !existing || existing.windowStartedAt + PASSWORD_ATTEMPT_WINDOW_MS <= now
    ? { attempts: 0, windowStartedAt: now } : existing;
  item.attempts += 1;
  passwordAttempts.set(key, item);
  return PASSWORD_MAX_ATTEMPTS - item.attempts;
}

function clearPasswordFailures(req) {
  passwordAttempts.delete(requestClientKey(req));
}

function acceptOwnerPassword(req, res, candidate) {
  if (!passwordAttemptAllowed(req)) {
    sendJson(res, 429, { error: '密码错误次数过多，请15分钟后再试' });
    return false;
  }
  if (!sameOwnerPassword(candidate)) {
    const remaining = recordPasswordFailure(req);
    sendJson(res, remaining > 0 ? 403 : 429, {
      error: remaining > 0 ? `密码不正确，还可尝试${remaining}次` : '密码错误次数过多，请15分钟后再试'
    });
    return false;
  }
  clearPasswordFailures(req);
  return true;
}

function send(socket, payload) {
  if (socket && socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(payload));
}

function broadcastStatus(deviceId, online) {
  const meta = vehicleMeta.get(deviceId) || {};
  for (const viewer of viewers) send(viewer, {
    type: 'vehicle_status', deviceId, online,
    armed: Boolean(meta.armed), lastSeen: meta.lastSeen || null,
    appVersion: meta.appVersion || ''
  });
}

function serveStatic(req, res) {
  const requestPath = req.url === '/' ? '/index.html' : req.url.split('?')[0];
  const normalized = path.normalize(requestPath)
    .replace(/^[/\\]+/, '')
    .replace(/^(\.\.[/\\])+/, '');
  const filePath = path.join(PUBLIC_DIR, normalized);
  if (!filePath.startsWith(PUBLIC_DIR)) {
    res.writeHead(403).end('Forbidden');
    return;
  }
  fs.readFile(filePath, (error, data) => {
    if (error) {
      res.writeHead(404).end('Not found');
      return;
    }
    const ext = path.extname(filePath);
    const contentType = ext === '.html' ? 'text/html; charset=utf-8'
      : ext === '.js' ? 'application/javascript; charset=utf-8'
        : 'application/octet-stream';
    res.writeHead(200, {
      'Content-Type': contentType,
      'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff',
      'Content-Security-Policy': "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; connect-src 'self' ws: wss:"
    });
    res.end(data);
  });
}

function downloadId(fileName) {
  return crypto.createHash('sha256').update(fileName).digest('hex').slice(0, 24);
}

async function listDownloads() {
  let entries;
  try {
    entries = await fs.promises.readdir(DOWNLOAD_DIR, { withFileTypes: true });
  } catch {
    return [];
  }
  const files = [];
  for (const entry of entries) {
    if (!entry.isFile() || entry.name.startsWith('.')) continue;
    const filePath = path.join(DOWNLOAD_DIR, entry.name);
    const stat = await fs.promises.stat(filePath);
    files.push({
      id: downloadId(entry.name),
      name: entry.name,
      size: stat.size,
      modifiedAt: stat.mtime.toISOString(),
      filePath
    });
  }
  return files.sort((left, right) => left.name.localeCompare(right.name, 'zh-CN'));
}

async function resolveLegacyDownload() {
  try {
    const stat = await fs.promises.stat(DOWNLOAD_APK_PATH);
    if (stat.isFile()) {
      return { filePath: DOWNLOAD_APK_PATH, name: path.basename(DOWNLOAD_APK_PATH) };
    }
  } catch { /* use the newest file from the repository */ }
  const files = await listDownloads();
  return files.sort((left, right) => new Date(right.modifiedAt) - new Date(left.modifiedAt))[0] || null;
}

function safeDownloadName(originalName, id) {
  const extension = path.extname(originalName);
  const base = path.basename(originalName, extension)
    .replace(/[\r\n"\\/]/g, '_')
    .slice(0, 80) || `software-${id.slice(0, 8)}`;
  const ascii = base.replace(/[^A-Za-z0-9._-]/g, '_') || `software-${id.slice(0, 8)}`;
  const utf8 = encodeURIComponent(`${base}.bin`).replace(/[!'()*]/g,
    character => `%${character.charCodeAt(0).toString(16).toUpperCase()}`);
  return `attachment; filename="${ascii}.bin"; filename*=UTF-8''${utf8}`;
}

function parseRange(rangeHeader, size) {
  if (!rangeHeader) return null;
  const match = /^bytes=(\d*)-(\d*)$/.exec(rangeHeader.trim());
  if (!match) return false;
  let start;
  let end;
  if (!match[1]) {
    const suffixLength = Number(match[2]);
    if (!Number.isSafeInteger(suffixLength) || suffixLength <= 0) return false;
    start = Math.max(0, size - suffixLength);
    end = size - 1;
  } else {
    start = Number(match[1]);
    end = match[2] ? Number(match[2]) : size - 1;
    if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end)
      || start < 0 || start >= size || end < start) return false;
    end = Math.min(end, size - 1);
  }
  return { start, end };
}

function serveDownload(req, res, filePath, originalName, id = 'legacy') {
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    res.writeHead(405, { Allow: 'GET, HEAD' }).end('Method not allowed');
    return;
  }
  fs.stat(filePath, (error, stat) => {
    if (error || !stat.isFile()) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('File not found on NAS');
      return;
    }
    const range = parseRange(req.headers.range, stat.size);
    if (range === false) {
      res.writeHead(416, { 'Content-Range': `bytes */${stat.size}` }).end();
      return;
    }
    const start = range ? range.start : 0;
    const end = range ? range.end : stat.size - 1;
    const headers = {
      'Content-Type': 'application/octet-stream',
      'Content-Disposition': originalName === DOWNLOAD_FILENAME
        ? `attachment; filename="${DOWNLOAD_FILENAME}"`
        : safeDownloadName(originalName, id),
      'Content-Length': end - start + 1,
      'Accept-Ranges': 'bytes',
      'Cache-Control': 'private, no-store',
      'X-Content-Type-Options': 'nosniff'
    };
    if (range) headers['Content-Range'] = `bytes ${start}-${end}/${stat.size}`;
    res.writeHead(range ? 206 : 200, headers);
    if (req.method === 'HEAD') {
      res.end();
      return;
    }
    const stream = fs.createReadStream(filePath, { start, end });
    stream.on('error', () => {
      if (!res.headersSent) res.writeHead(500);
      res.end();
    });
    stream.pipe(res);
  });
}

const server = http.createServer(async (req, res) => {
  if (req.url === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify({ ok: true }));
    return;
  }
  const requestUrl = new URL(req.url, 'http://localhost');
  if (requestUrl.pathname === '/api/device/status' && req.method === 'GET') {
    const session = viewerSession(req);
    sendJson(res, 200, {
      enrolled: Boolean(deviceCredential),
      authenticated: Boolean(session),
      authMethod: session?.authMethod || null
    });
    return;
  }
  if (requestUrl.pathname === '/api/auth/password' && req.method === 'POST') {
    if (!requestHasExpectedOrigin(req)) {
      sendJson(res, 403, { error: '请求来源不正确' });
      return;
    }
    try {
      const body = await readJson(req);
      if (!acceptOwnerPassword(req, res, body.password)) return;
      issueViewerSession(res, 'password');
      sendJson(res, 200, { ok: true, expiresInSeconds: PASSWORD_SESSION_TTL_MS / 1000 });
    } catch {
      sendJson(res, 400, { error: '密码验证失败' });
    }
    return;
  }
  if (requestUrl.pathname === '/api/device/enroll' && req.method === 'POST') {
    if (!requestHasExpectedOrigin(req)) {
      sendJson(res, 403, { error: '请求来源不正确' });
      return;
    }
    if (deviceCredential) {
      sendJson(res, 409, { error: '已经绑定过手机。如需更换，请先在NAS中重置绑定。' });
      return;
    }
    try {
      const body = await readJson(req);
      if (!acceptOwnerPassword(req, res, body.password)) return;
      const deviceId = String(body.deviceId || '').slice(0, 120);
      if (!deviceId) throw new Error('missing device id');
      publicKeyFromJwk(body.publicKeyJwk);
      const nextCredential = {
        deviceId,
        publicKeyJwk: body.publicKeyJwk,
        enrolledAt: new Date().toISOString()
      };
      saveDeviceCredential(nextCredential);
      deviceCredential = nextCredential;
      issueViewerSession(res, 'device');
      sendJson(res, 201, { ok: true });
    } catch (error) {
      console.error(`Unable to bind phone: ${error.message}`);
      sendJson(res, 400, { error: '绑定失败，请检查NAS状态后重试' });
    }
    return;
  }
  if (requestUrl.pathname === '/api/device/challenge' && req.method === 'POST') {
    if (!requestHasExpectedOrigin(req) || !deviceCredential) {
      sendJson(res, 403, { error: '当前不能验证设备' });
      return;
    }
    try {
      const body = await readJson(req);
      if (body.deviceId !== deviceCredential.deviceId) {
        sendJson(res, 403, { error: '这台设备没有绑定权限' });
        return;
      }
      cleanupDeviceAuth();
      const id = crypto.randomUUID();
      const challenge = crypto.randomBytes(32).toString('base64url');
      challenges.set(id, {
        deviceId: deviceCredential.deviceId,
        challenge,
        expiresAt: Date.now() + CHALLENGE_TTL_MS
      });
      sendJson(res, 200, { id, challenge });
    } catch {
      sendJson(res, 400, { error: '无法创建验证请求' });
    }
    return;
  }
  if (requestUrl.pathname === '/api/device/verify' && req.method === 'POST') {
    if (!requestHasExpectedOrigin(req) || !deviceCredential) {
      sendJson(res, 403, { error: '当前不能验证设备' });
      return;
    }
    try {
      const body = await readJson(req);
      cleanupDeviceAuth();
      const challenge = challenges.get(String(body.id || ''));
      challenges.delete(String(body.id || ''));
      if (!challenge || challenge.deviceId !== deviceCredential.deviceId
        || body.deviceId !== deviceCredential.deviceId) {
        sendJson(res, 403, { error: '验证请求已失效' });
        return;
      }
      const signature = Buffer.from(String(body.signature || ''), 'base64url');
      const verified = crypto.verify('sha256', Buffer.from(challenge.challenge, 'base64url'), {
        key: publicKeyFromJwk(deviceCredential.publicKeyJwk),
        dsaEncoding: 'ieee-p1363'
      }, signature);
      if (!verified) {
        sendJson(res, 403, { error: '设备签名不正确' });
        return;
      }
      issueViewerSession(res, 'device');
      sendJson(res, 200, { ok: true });
    } catch {
      sendJson(res, 400, { error: '设备验证失败' });
    }
    return;
  }
  if (requestUrl.pathname === '/api/downloads') {
    listDownloads().then(files => {
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
      res.end(JSON.stringify(files.map(({ filePath, ...file }) => file)));
    }).catch(() => res.writeHead(500).end('Unable to list files'));
    return;
  }
  if (requestUrl.pathname.startsWith('/download/file/')) {
    const id = requestUrl.pathname.slice('/download/file/'.length);
    listDownloads().then(files => {
      const file = files.find(candidate => candidate.id === id);
      if (!file) {
        res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' }).end('File not found');
        return;
      }
      serveDownload(req, res, file.filePath, file.name, file.id);
    }).catch(() => res.writeHead(500).end('Unable to open file'));
    return;
  }
  if (requestUrl.pathname === '/download/app') {
    resolveLegacyDownload().then(file => {
      if (!file) {
        res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end('下载目录还是空的');
        return;
      }
      serveDownload(req, res, file.filePath, DOWNLOAD_FILENAME);
    }).catch(() => res.writeHead(500).end('Unable to open file'));
    return;
  }
  if ((requestUrl.pathname === '/' || requestUrl.pathname === '/index.html')
    && !isViewerAuthenticated(req)) {
    res.writeHead(302, { Location: `/unlock.html?next=${encodeURIComponent(requestUrl.pathname)}`, 'Cache-Control': 'no-store' });
    res.end();
    return;
  }
  serveStatic(req, res);
});

const vehicleWss = new WebSocketServer({ noServer: true, maxPayload: MAX_MESSAGE_BYTES });
const viewerWss = new WebSocketServer({ noServer: true, maxPayload: MAX_MESSAGE_BYTES });

server.on('upgrade', (req, socket, head) => {
  const pathname = new URL(req.url, 'http://localhost').pathname;
  if (pathname === '/ws/viewer' && !isViewerAuthenticated(req)) {
    socket.write('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n');
    socket.destroy();
    return;
  }
  const target = pathname === '/ws/vehicle' ? vehicleWss
    : pathname === '/ws/viewer' ? viewerWss : null;
  if (!target) {
    socket.destroy();
    return;
  }
  target.handleUpgrade(req, socket, head, ws => target.emit('connection', ws, req));
});

vehicleWss.on('connection', socket => {
  let deviceId = null;
  socket.on('message', raw => {
    let message;
    try { message = JSON.parse(raw.toString()); } catch { return; }
    if (!deviceId) {
      if (message.type !== 'vehicle_hello' || !sameVehicleToken(message.token) || !message.deviceId) {
        socket.close(1008, 'pairing rejected');
        return;
      }
      deviceId = String(message.deviceId).slice(0, 80);
      const old = vehicles.get(deviceId);
      if (old && old !== socket) old.close(1012, 'replaced by newer vehicle connection');
      vehicles.set(deviceId, socket);
      vehicleMeta.set(deviceId, {
        armed: Boolean(message.armed),
        lastSeen: Date.now(),
        appVersion: String(message.appVersion || '').slice(0, 80),
        platform: String(message.platform || '').slice(0, 80)
      });
      send(socket, { type: 'vehicle_ready', deviceId });
      broadcastStatus(deviceId, true);
      return;
    }

    const meta = vehicleMeta.get(deviceId) || {};
    meta.lastSeen = Date.now();
    if (message.type === 'heartbeat') {
      meta.armed = Boolean(message.armed);
      vehicleMeta.set(deviceId, meta);
      broadcastStatus(deviceId, true);
      return;
    }
    vehicleMeta.set(deviceId, meta);
    if (!message.requestId) return;
    const viewer = pending.get(message.requestId);
    if (!viewer) return;
    const forwarded = { ...message };
    delete forwarded.token;
    send(viewer, forwarded);
    if (message.type === 'capture_complete' || message.type === 'capture_error') {
      pending.delete(message.requestId);
    }
  });

  socket.on('close', () => {
    if (deviceId && vehicles.get(deviceId) === socket) {
      vehicles.delete(deviceId);
      const meta = vehicleMeta.get(deviceId) || {};
      meta.armed = false;
      meta.lastSeen = Date.now();
      vehicleMeta.set(deviceId, meta);
      broadcastStatus(deviceId, false);
    }
  });
});

viewerWss.on('connection', socket => {
  viewers.add(socket);
  const online = [...vehicles.keys()];
  const vehicleStates = online.map(deviceId => ({
    deviceId,
    armed: Boolean((vehicleMeta.get(deviceId) || {}).armed),
    lastSeen: (vehicleMeta.get(deviceId) || {}).lastSeen || null,
    appVersion: (vehicleMeta.get(deviceId) || {}).appVersion || ''
  }));
  send(socket, { type: 'viewer_ready', online, vehicles: vehicleStates });

  socket.on('message', raw => {
    let message;
    try { message = JSON.parse(raw.toString()); } catch { return; }
    if (message.type !== 'capture' || !message.deviceId) return;
    const deviceId = String(message.deviceId).slice(0, 80);
    const vehicle = vehicles.get(deviceId);
    if (!vehicle || vehicle.readyState !== WebSocket.OPEN) {
      send(socket, { type: 'capture_error', error: '车辆当前不在线' });
      return;
    }
    const meta = vehicleMeta.get(deviceId);
    if (meta && !meta.armed) {
      send(socket, { type: 'capture_error', error: '车辆在线，但相机尚未在车机前台布防' });
      return;
    }
    const requestId = crypto.randomUUID();
    pending.set(requestId, socket);
    send(vehicle, { type: 'capture', requestId, requestedAt: Date.now() });
    send(socket, { type: 'capture_started', requestId });
    setTimeout(() => {
      if (pending.get(requestId) === socket) {
        pending.delete(requestId);
        send(socket, { type: 'capture_error', requestId, error: '60秒内没有收到车辆画面' });
      }
    }, 60_000).unref();
  });

  socket.on('close', () => {
    viewers.delete(socket);
    for (const [id, owner] of pending.entries()) if (owner === socket) pending.delete(id);
  });
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`Lynk 10 remote viewer is listening on port ${PORT}`);
  console.log('Put this server behind an HTTPS reverse proxy before accessing it over the internet.');
});
