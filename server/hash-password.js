'use strict';

const { hashPassword } = require('./password-auth');

const password = process.argv[2];
if (!password) {
  console.error('Usage: node hash-password.js "your-owner-password"');
  process.exit(1);
}

try {
  console.log(hashPassword(password));
} catch (error) {
  console.error(error.message);
  process.exit(1);
}
