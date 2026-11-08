// Real manual UI against real owner HTTP; source hints are checked by the API.
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const { chromium } = require('/runtime/node_modules/playwright');
const env = process.env;
assert.match(env.BILLING_BROWSER_ORIGIN, /^http:\/\/localhost:\d+$/);
assert.match(env.BILLING_BROWSER_PROXY, /^http:\/\/billing-e2e-[a-f0-9]+-front:80$/);
(async () => {
  const browser = await chromium.launch({ headless: true, proxy: { server: env.BILLING_BROWSER_PROXY },
    args: ['--proxy-bypass-list=<-loopback>'] });
  const page = await browser.newPage(); const origin = env.BILLING_BROWSER_ORIGIN;
  const steps = []; let authorization;
  page.on('request', request => {
    if (request.url().startsWith(origin + '/api/v1/manual-charges'))
      authorization = request.headers().authorization || authorization;
  });
  try {
    await page.goto(origin + '/login');
    await page.locator('#sign-in-email').fill(env.BILLING_BROWSER_EMAIL);
    await page.locator('#sign-in-password').fill(env.BILLING_BROWSER_PASSWORD);
    await page.getByRole('button', { name: 'Iniciar sesión', exact: true }).click();
    await page.waitForURL(url => url.pathname.startsWith('/app')); steps.push('real-dentist-login');
    const query = new URLSearchParams(JSON.parse(env.BILLING_BROWSER_SOURCE));
    await page.goto(origin + '/app/billing/manual-charges/new?' + query);
    await page.getByRole('heading', { name: 'Registrar cargo manual', exact: true }).waitFor();
    await page.getByLabel('Tipo de cargo').selectOption('ADDITIONAL_MATERIAL');
    await page.getByLabel('Concepto', { exact: true }).fill('Exact manual concept');
    await page.getByLabel('Cantidad', { exact: true }).fill('0.5000');
    await page.getByLabel('Precio unitario (COP)', { exact: true }).fill('37.11');
    await page.getByLabel('Justificación', { exact: true }).fill('Protected owner reason');
    let first, key;
    for (let attempt = 0; attempt < 2; attempt++) {
      const pending = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/api/v1/manual-charges'));
      await page.getByRole('button', { name: 'Registrar cargo', exact: true }).click();
      const response = await pending; assert.equal(response.status(), 201);
      const value = await response.json(); assert.equal(value.unitPrice, '37.11'); assert.equal(value.quantity, '0.5000');
      const requestKey = response.request().headers()['idempotency-key'];
      if (attempt === 0) { first = value; key = requestKey; } else { assert.equal(value.id, first.id); assert.equal(requestKey, key); }
      await page.getByText('Cargo manual registrado correctamente.', { exact: true }).waitFor();
      steps.push(attempt === 0 ? 'manual-created-through-ui' : 'manual-replayed-through-ui');
    }
    await page.getByRole('button', { name: 'Descartar formulario', exact: true }).click();
    await page.screenshot({ path: '/evidence/manual-success.png', fullPage: true });
    const logout = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/auth/logout'));
    await page.getByRole('button', { name: 'Cerrar sesión', exact: true }).first().click();
    assert.equal((await logout).status(), 204); await page.waitForURL(url => url.pathname === '/login');
    assert.ok(authorization);
    const revoked = await page.evaluate(async ({ id, token }) => (await fetch('/api/v1/manual-charges/' + id,
      { headers: { Authorization: token } })).status, { id: first.id, token: authorization });
    assert.equal(revoked, 401); steps.push('revoked-manual-session-denied');
    await fs.writeFile('/evidence/result.json', JSON.stringify({ passed: true, steps, chargeId: first.id, idempotencyKey: key }, null, 2));
    console.log('BROWSER_MANUAL:PASS');
  } catch (error) {
    await fs.writeFile('/evidence/result.json', JSON.stringify({ passed: false, steps }, null, 2));
    console.error(String(error.message).replaceAll(env.BILLING_BROWSER_PASSWORD, '[redacted]')); process.exitCode = 1;
  } finally { await browser.close(); }
})();
