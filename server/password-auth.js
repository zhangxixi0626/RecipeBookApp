'use strict';

const crypto = require('crypto');

const KEY_LENGTH = 32;
const SCRYPT_N = 16384;
const SCRYPT_R = 8;
const SCRYPT_P = 1;
const MAX_MEMORY = 64 * 1024 * 1024;

function derive(password, salt, n = SCRYPT_N, r = SCRYPT_R, p = SCRYPT_P) {
  return crypto.scryptSync(password, salt, KEY_LENGTH, { N: n, r, p, maxmem: MAX_MEMORY });
}

function hashPassword(password) {
  const value = String(password || '');
  if (value.length < 12) throw new Error('password must contain at least 12 characters');
  const salt = crypto.randomBytes(16);
  const digest = derive(value, salt);
  return `scrypt$${SCRYPT_N}$${SCRYPT_R}$${SCRYPT_P}$${salt.toString('hex')}$${digest.toString('hex')}`;
}

function verifyPassword(password, encoded) {
  try {
    const [scheme, nText, rText, pText, saltHex, digestHex] = String(encoded || '').split('$');
    if (scheme !== 'scrypt') return false;
    const n = Number(nText);
    const r = Number(rText);
    const p = Number(pText);
    const expected = Buffer.from(digestHex, 'hex');
    if (expected.length !== KEY_LENGTH || !/^[a-f0-9]{32}$/i.test(saltHex)) return false;
    const actual = derive(String(password || ''), Buffer.from(saltHex, 'hex'), n, r, p);
    return crypto.timingSafeEqual(actual, expected);
  } catch {
    return false;
  }
}

function createPasswordVerifier(encodedHash, plainPassword) {
  if (encodedHash) {
    if (!String(encodedHash).startsWith('scrypt$')) throw new Error('OWNER_PASSWORD_HASH is invalid');
    return candidate => verifyPassword(candidate, encodedHash);
  }
  const generated = hashPassword(plainPassword);
  return candidate => verifyPassword(candidate, generated);
}

module.exports = { createPasswordVerifier, hashPassword, verifyPassword };
