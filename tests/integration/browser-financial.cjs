// Actual Chromium UI, actual owner HTTP. No routes, responses or sessions are stubbed.
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const { chromium } = require('/runtime/node_modules/playwright');
const env = process.env;
assert.match(env.BILLING_BROWSER_ORIGIN, /^http:\/\/localhost:\d+$/);
assert.match(env.BILLING_BROWSER_PROXY, /^http:\/\/billing-e2e-[a-f0-9]+-front:80$/);
assert.match(env.BILLING_BROWSER_INVOICE, /^[a-f0-9-]{36}$/);
(async () => {
  const browser = await chromium.launch({ headless: true,
    proxy: { server: env.BILLING_BROWSER_PROXY }, args: ['--proxy-bypass-list=<-loopback>'] });
  const page = await browser.newPage({ acceptDownloads: true });
  const origin = env.BILLING_BROWSER_ORIGIN;
  const detail = origin + '/app/billing/invoices/' + env.BILLING_BROWSER_INVOICE;
  const steps = [];
  const failures = [];
  page.on('response', response => {
    if (response.status() >= 400 && failures.length < 20)
      failures.push({ path: new URL(response.url()).pathname, status: response.status() });
  });
  const download = async label => {
    const pending = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Descargar PDF', exact: true }).click();
    const result = await pending;
    assert.equal(await result.failure(), null);
    const file = '/evidence/' + label + '.pdf'; await result.saveAs(file);
    assert.equal((await fs.readFile(file)).subarray(0, 5).toString(), '%PDF-');
    steps.push(label);
  };
  try {
    await page.goto(origin + '/login');
    await page.locator('#sign-in-email').fill(env.BILLING_BROWSER_EMAIL);
    await page.locator('#sign-in-password').fill(env.BILLING_BROWSER_PASSWORD);
    await page.getByRole('button', { name: 'Iniciar sesión', exact: true }).click();
    await page.waitForURL(url => url.pathname.startsWith('/app'));
    steps.push('real-iam-login');
    await page.goto(detail);
    await page.getByRole('heading', { name: 'Detalle de factura', exact: true }).waitFor();
    const issuance = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/issuances'));
    await page.getByRole('button', { name: 'Emitir factura', exact: true }).click();
    assert.equal((await issuance).status(), 200);
    await page.getByRole('button', { name: 'Descargar PDF', exact: true }).waitFor();
    steps.push('issued-through-ui'); await download('issued');
    for (const [amount, label] of [['4.01', 'partial'], ['6.00', 'paid']]) {
      await page.getByRole('button', { name: 'Registrar pago', exact: true }).click();
      await page.getByLabel('Valor del pago (COP)').fill(amount);
      await page.getByLabel('Método de pago').fill('SYNTHETIC_LOCAL');
      const payment = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/payments'));
      await page.getByRole('button', { name: 'Registrar pago', exact: true }).click();
      assert.equal((await payment).status(), 201);
      await page.getByText('Pago registrado. Saldo actualizado correctamente.', { exact: true }).waitFor();
      steps.push(label + '-payment-through-ui');
      await page.goto(detail);
      await page.getByRole('button', { name: 'Descargar PDF', exact: true }).waitFor();
      await download(label);
    }
    await page.getByText('Pagada', { exact: false }).first().waitFor();
    await page.screenshot({ path: '/evidence/paid-invoice.png', fullPage: true });
    const logout = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/auth/logout'));
    await page.getByRole('button', { name: 'Cerrar sesión', exact: true }).first().click();
    assert.equal((await logout).status(), 204, 'IAM must confirm actual session revocation');
    await page.waitForURL(url => url.pathname === '/login'); steps.push('real-iam-logout');
    await fs.writeFile('/evidence/result.json', JSON.stringify({ passed: true, steps, failures }, null, 2));
    console.log('BROWSER_FINANCIAL:PASS');
  } catch (error) {
    await fs.writeFile('/evidence/result.json', JSON.stringify({ passed: false, steps, failures }, null, 2));
    const safe = String(error.message).replaceAll(env.BILLING_BROWSER_PASSWORD, '[redacted]');
    console.error(safe); process.exitCode = 1;
  } finally { await browser.close(); }
})();
