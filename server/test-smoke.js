'use strict';

const assert = require('assert');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const { WebSocket } = require('ws');
const { hashPassword, verifyPassword } = require('./password-auth');

const port = 18295;
const vehicleToken = 'test-vehicle-token-8295-only';
const ownerPassword = 'test-owner-password-8295-only';
const ownerPasswordHash = hashPassword(ownerPassword);
assert.equal(verifyPassword(ownerPassword, ownerPasswordHash), true);
assert.equal(verifyPassword('wrong-password', ownerPasswordHash), false);
const base = `ws://127.0.0.1:${port}`;
const downloadDir = path.resolve(__dirname, `.test-downloads-${process.pid}`);
const apkPath = path.join(downloadDir, 'Lynk10EV-RemoteView-test-release.apk');
const deviceAuthFile = path.resolve(__dirname, `.test-device-auth-${process.pid}.json`);
fs.mkdirSync(downloadDir, { recursive: true });
fs.writeFileSync(apkPath, Buffer.alloc(1_000_001, 0x41));
try { fs.unlinkSync(deviceAuthFile); } catch { /* no previous test binding */ }
const child = spawn(process.execPath, ['server.js'], {
  cwd: __dirname,
  env: {
    ...process.env,
    PORT: String(port),
    VEHICLE_TOKEN: vehicleToken,
    OWNER_PASSWORD_HASH: ownerPasswordHash,
    DOWNLOAD_DIR: downloadDir,
    DOWNLOAD_APK_PATH: path.join(downloadDir, 'old-fixed-name-that-does-not-exist.apk'),
    DEVICE_AUTH_FILE: deviceAuthFile
  },
  stdio: ['ignore', 'pipe', 'pipe']
});

function open(pathname, hello, headers = {}) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(base + pathname, { headers });
    const timeout = setTimeout(() => reject(new Error(`timeout opening ${pathname}`)), 5000);
    socket.once('open', () => { if (hello) socket.send(JSON.stringify(hello)); });
    socket.once('message', raw => {
      clearTimeout(timeout);
      resolve({ socket, first: JSON.parse(raw.toString()) });
    });
    socket.once('error', reject);
  });
}

function next(socket, type) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error(`timeout waiting for ${type}`)), 5000);
    const handler = raw => {
      const message = JSON.parse(raw.toString());
      if (message.type !== type) return;
      clearTimeout(timeout);
      socket.off('message', handler);
      resolve(message);
    };
    socket.on('message', handler);
  });
}

function expectWebSocketRejected(pathname) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(base + pathname);
    const timeout = setTimeout(() => reject(new Error('unauthorized WebSocket was not rejected')), 5000);
    socket.once('open', () => reject(new Error('unauthorized WebSocket opened')));
    socket.once('unexpected-response', (_request, response) => {
      clearTimeout(timeout);
      assert.equal(response.statusCode, 401);
      response.resume();
      resolve();
    });
  });
}

async function waitForServer() {
  for (let index = 0; index < 30; index += 1) {
    try {
      const response = await fetch(`http://127.0.0.1:${port}/health`);
      if (response.ok) return;
    } catch { /* server is still starting */ }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error('server did not start');
}

async function postJson(pathname, payload, headers = {}) {
  return fetch(`http://127.0.0.1:${port}${pathname}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Origin: `http://127.0.0.1:${port}`,
      ...headers
    },
    body: JSON.stringify(payload)
  });
}

(async () => {
  let vehicle;
  let viewer;
  try {
    await waitForServer();
    const deviceKey = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
    const deviceId = crypto.randomUUID();
    const beforeEnrollment = await fetch(`http://127.0.0.1:${port}/api/device/status`);
    assert.deepEqual(await beforeEnrollment.json(), {
      enrolled: false, authenticated: false, authMethod: null
    });

    const enrollment = await postJson('/api/device/enroll', {
      password: ownerPassword,
      deviceId,
      publicKeyJwk: deviceKey.publicKey.export({ format: 'jwk' })
    });
    assert.equal(enrollment.status, 201);
    assert.match(enrollment.headers.get('set-cookie') || '', /lynk_viewer_session=/);
    assert.match(enrollment.headers.get('set-cookie') || '', /Max-Age=/);

    const lockedPage = await fetch(`http://127.0.0.1:${port}/`, { redirect: 'manual' });
    assert.equal(lockedPage.status, 302);
    assert.match(lockedPage.headers.get('location') || '', /^\/unlock\.html/);
    await expectWebSocketRejected('/ws/viewer');

    const wrongPassword = await postJson('/api/auth/password', { password: 'wrong-password' });
    assert.equal(wrongPassword.status, 403);
    const passwordLogin = await postJson('/api/auth/password', { password: ownerPassword });
    assert.equal(passwordLogin.status, 200);
    const passwordCookieHeader = String(passwordLogin.headers.get('set-cookie') || '');
    assert.match(passwordCookieHeader, /lynk_viewer_session=/);
    assert.doesNotMatch(passwordCookieHeader, /Max-Age=/i);
    const passwordCookie = passwordCookieHeader.split(';')[0];
    const passwordStatus = await fetch(`http://127.0.0.1:${port}/api/device/status`, {
      headers: { Cookie: passwordCookie }
    });
    assert.deepEqual(await passwordStatus.json(), {
      enrolled: true, authenticated: true, authMethod: 'password'
    });
    const passwordViewer = await open('/ws/viewer', null, { Cookie: passwordCookie });
    assert.equal(passwordViewer.first.type, 'viewer_ready');
    passwordViewer.socket.close();

    const challengeResponse = await postJson('/api/device/challenge', { deviceId });
    assert.equal(challengeResponse.status, 200);
    const challenge = await challengeResponse.json();
    const signature = crypto.sign('sha256', Buffer.from(challenge.challenge, 'base64url'), {
      key: deviceKey.privateKey,
      dsaEncoding: 'ieee-p1363'
    }).toString('base64url');
    const verification = await postJson('/api/device/verify', {
      id: challenge.id, deviceId, signature
    });
    assert.equal(verification.status, 200);
    const viewerCookie = String(verification.headers.get('set-cookie') || '').split(';')[0];
    assert.match(viewerCookie, /^lynk_viewer_session=/);

    const unlockedPage = await fetch(`http://127.0.0.1:${port}/`, {
      headers: { Cookie: viewerCookie }
    });
    assert.equal(unlockedPage.status, 200);
    assert.match(await unlockedPage.text(), /领克10远程监看/);

    const apkResponse = await fetch(`http://127.0.0.1:${port}/download/app`, { method: 'HEAD' });
    assert.equal(apkResponse.status, 200);
    assert.equal(apkResponse.headers.get('content-type'), 'application/octet-stream');
    assert.match(apkResponse.headers.get('content-disposition') || '',
      /attachment; filename="Lynk10EV-RemoteView\.bin"/);
    assert.equal(apkResponse.headers.get('x-content-type-options'), 'nosniff');
    assert.ok(Number(apkResponse.headers.get('content-length')) > 1_000_000);

    const listResponse = await fetch(`http://127.0.0.1:${port}/api/downloads`);
    assert.equal(listResponse.status, 200);
    const files = await listResponse.json();
    const apk = files.find(file => file.name === 'Lynk10EV-RemoteView-test-release.apk');
    assert.ok(apk);
    assert.match(apk.id, /^[a-f0-9]{24}$/);

    const genericResponse = await fetch(
      `http://127.0.0.1:${port}/download/file/${apk.id}`,
      { headers: { Range: 'bytes=0-15' } }
    );
    assert.equal(genericResponse.status, 206);
    assert.equal(genericResponse.headers.get('content-type'), 'application/octet-stream');
    assert.doesNotMatch(genericResponse.headers.get('content-disposition') || '', /\.apk/i);
    assert.match(genericResponse.headers.get('content-disposition') || '', /\.bin/i);
    assert.equal(genericResponse.headers.get('content-range'), `bytes 0-15/${apk.size}`);
    assert.equal((await genericResponse.arrayBuffer()).byteLength, 16);
    console.log('Verified generic download headers:', {
      url: `/download/file/${apk.id}`,
      contentType: genericResponse.headers.get('content-type'),
      contentDisposition: genericResponse.headers.get('content-disposition'),
      nosniff: genericResponse.headers.get('x-content-type-options')
    });

    const vehicleConnection = await open('/ws/vehicle', {
      type: 'vehicle_hello', token: vehicleToken, deviceId: 'lynk10ev-test', armed: true,
      appVersion: 'test-webdav-lock-guard'
    });
    vehicle = vehicleConnection.socket;

    const viewerConnection = await open('/ws/viewer', null, { Cookie: viewerCookie });
    viewer = viewerConnection.socket;
    assert.equal(viewerConnection.first.type, 'viewer_ready');
    assert.deepEqual(viewerConnection.first.online, ['lynk10ev-test']);
    assert.equal(viewerConnection.first.vehicles[0].armed, true);

    const vehicleCapture = next(vehicle, 'capture');
    viewer.send(JSON.stringify({ type: 'capture', deviceId: 'lynk10ev-test' }));
    const request = await vehicleCapture;
    assert.ok(request.requestId);

    const completed = next(viewer, 'capture_complete');
    vehicle.send(JSON.stringify({
      type: 'capture_complete', requestId: request.requestId, capturedAt: 8295
    }));
    const response = await completed;
    assert.equal(response.requestId, request.requestId);
    assert.equal(response.capturedAt, 8295);

    const statusChanged = next(viewer, 'vehicle_status');
    vehicle.send(JSON.stringify({ type: 'heartbeat', armed: false, sentAt: Date.now() }));
    const offlineCamera = await statusChanged;
    assert.equal(offlineCamera.online, true);
    assert.equal(offlineCamera.armed, false);

    const rejectedCapture = next(viewer, 'capture_error');
    viewer.send(JSON.stringify({ type: 'capture', deviceId: 'lynk10ev-test' }));
    assert.match((await rejectedCapture).error, /尚未.*布防/);
    for (let attempt = 0; attempt < 4; attempt += 1) {
      const response = await postJson('/api/auth/password', { password: 'still-wrong' });
      assert.equal(response.status, 403);
    }
    const rateLimited = await postJson('/api/auth/password', { password: 'still-wrong' });
    assert.equal(rateLimited.status, 429);
    console.log('Smoke test passed: primary device auth, password fallback, rate limiting, downloads and capture relay.');
  } finally {
    if (viewer) viewer.close();
    if (vehicle) vehicle.close();
    child.kill();
    try { fs.unlinkSync(deviceAuthFile); } catch { /* already removed */ }
    fs.rmSync(downloadDir, { recursive: true, force: true });
  }
})().catch(error => {
  console.error(error);
  child.kill();
  process.exitCode = 1;
});
