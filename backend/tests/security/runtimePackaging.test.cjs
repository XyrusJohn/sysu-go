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
    assert.match(content, /^USER node$/m);
    assert.match(content, /^ENV NODE_ENV=production \\\r?\n\s+PORT=8080$/m);
    assert.match(content, /\/api\/health/);
    assert.doesNotMatch(content, /localhost:3001/);
  });
}
test('compose exposes a configurable API port and uses a runtime env file', () => {
  const compose = fs.readFileSync(path.join(root, 'compose.yaml'), 'utf8');
  assert.match(compose, /env_file:\s*\n\s+- \.env/);
  assert.match(compose, /\$\{HOST_PORT:-8080\}:\$\{PORT:-8080\}/);
  assert.match(compose, /FIREBASE_SERVICE_ACCOUNT_JSON:\s*\$\{FIREBASE_SERVICE_ACCOUNT_JSON:-\}/);
});
test('container environment example has no credential value', () => {
  const example = fs.readFileSync(path.join(root, '.env.container.example'), 'utf8');
  assert.match(example, /^SYSU_ENV=staging$/m);
  assert.match(example, /^FIREBASE_PROJECT_ID=your-/m);
  assert.doesNotMatch(example, /PRIVATE KEY|AIza[\w-]{20,}/);
});
test('backend declares the verified Node runtime for non-container hosting', () => {
  const config = JSON.parse(fs.readFileSync(path.join(root, 'backend/package.json'), 'utf8'));
  assert.equal(config.engines?.node, '24.x');
});
