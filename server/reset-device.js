'use strict';

const fs = require('fs');
const path = require('path');

const target = path.resolve(process.env.DEVICE_AUTH_FILE
  || path.join(__dirname, 'state', 'device-auth.json'));

try {
  fs.unlinkSync(target);
  console.log(`Removed primary phone binding: ${target}`);
} catch (error) {
  if (error.code === 'ENOENT') console.log('No primary phone binding exists.');
  else throw error;
}
