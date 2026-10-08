const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../../..');
for (const name of ['Dockerfile', 'backend/Dockerfile']) {
  test(name + ' uses verified Node runtime and excludes development dependencies', () => {
    const content = fs.readFileSync(path.join(root, name), 'utf8');
    assert.match(content, /^FROM node:24-slim$/m);
    assert.match(content, /^RUN npm ci --omit=dev$/m);
  });
}
test('backend declares the verified Node runtime for non-container hosting', () => {
  const config = JSON.parse(fs.readFileSync(path.join(root, 'backend/package.json'), 'utf8'));
  assert.equal(config.engines?.node, '24.x');
});
