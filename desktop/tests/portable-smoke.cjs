/** Read-only startup check of the actual Windows portable release. No translation or history writes. */
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const { spawn, execFile } = require('node:child_process');
const net = require('node:net');
const path = require('node:path');
const { setTimeout: delay } = require('node:timers/promises');

(async () => {
  const executablePath = path.resolve(process.argv[2] || path.join(__dirname, '../release/Lens-Translate-0.1.0-x64.exe'));
  const env = { ...process.env };
  delete env.ELECTRON_RUN_AS_NODE;
  const server = net.createServer();
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  // The portable NSIS wrapper does not forward Electron's inspector stdout, so
  // connect to its loopback-only DevTools port after extraction and startup.
  const child = spawn(executablePath, [`--remote-debugging-port=${port}`, '--remote-debugging-address=127.0.0.1'], { env, windowsHide: true, stdio: 'ignore' });
  let exited = false;
  const completion = new Promise(resolve => child.once('exit', () => { exited = true; resolve(); }));
  let launchError;
  child.once('error', error => { launchError = error; });
  let browser;
  let stage = 'startup';
  try {
    const deadline = Date.now() + 90000;
    while (!browser && Date.now() < deadline) {
      if (launchError) throw launchError;
      if (exited) throw new Error('Portable process exited before opening the app.');
      try { browser = await chromium.connectOverCDP(`http://127.0.0.1:${port}`, { timeout: 1000 }); }
      catch { await delay(500); }
    }
    assert.ok(browser, 'Portable app should expose its test-only loopback DevTools port');
    stage = 'page';
    const context = browser.contexts()[0];
    const page = context.pages()[0] || await context.waitForEvent('page', { timeout: 30000 });
    await page.getByRole('heading', { name: '跨过语言，继续探索。' }).waitFor({ timeout: 30000 });
    stage = 'packaged URL';
    assert.match(page.url(), /app\.asar\/dist\/index\.html/);
    stage = 'status';
    const status = await page.evaluate(() => window.lens.getStatus());
    assert.equal(status.platform, 'win32');
    stage = 'renderer isolation';
    assert.equal(await page.evaluate(() => typeof window.require), 'undefined');
    assert.equal(await page.evaluate(() => typeof window.process), 'undefined');
    assert.ok(status.ocrLanguages.length, 'Packaged OCR helper should find the installed Windows OCR recognizer');
    console.log(JSON.stringify({ passed: true, packaged: true, version: status.version, archive: true, platform: status.platform, ocrLanguages: status.ocrLanguages, shortcut: status.shortcut }));
  } catch (error) {
    console.error(`${stage}: ${error.message}`);
    process.exitCode = 1;
  } finally {
    if (browser) {
      try { const devtools = await browser.newBrowserCDPSession(); await Promise.race([devtools.send('Browser.close'), delay(1000)]); } catch {}
      await Promise.race([browser.close().catch(() => {}), delay(1000)]);
      await Promise.race([completion, delay(5000)]);
    }
    if (!exited && child.pid) {
      // Only clean up the process tree created by this test if normal shutdown failed.
      await new Promise(resolve => execFile('taskkill.exe', ['/PID', String(child.pid), '/T', '/F'], { windowsHide: true }, resolve));
    }
  }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
