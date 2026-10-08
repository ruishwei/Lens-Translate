/** Real Electron UI test. Set LENS_LIVE_TEST=1 to authorize one synthetic DeepSeek translation. */
const { _electron: electron } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');

(async () => {
  const userData = await fs.mkdtemp(path.join(os.tmpdir(), 'lens-ui-test-'));
  const artifacts = path.join(__dirname, '../../artifacts');
  await fs.mkdir(artifacts, {recursive:true});
  const env = {...process.env, LENS_USER_DATA_DIR:userData};
  delete env.ELECTRON_RUN_AS_NODE;
  const app = await electron.launch({args:[path.join(__dirname,'..')], env, timeout:30000});
  const originalClipboard = await app.evaluate(({clipboard}) => clipboard.readText());
  const errors = [];
  try {
    const page = await app.firstWindow();
    page.on('pageerror', e => errors.push(e.message));
    await page.getByRole('heading', {name:'跨过语言，继续探索。'}).waitFor();
    await page.waitForFunction(() => !!window.lens);
    const status = await page.evaluate(() => window.lens.getStatus());
    assert.equal(status.platform, 'win32');
    assert.ok(status.ocrLanguages.length, 'At least one installed OCR language is required for this smoke test');
    assert.equal(await page.evaluate(() => typeof window.require), 'undefined');
    assert.equal(await page.evaluate(() => typeof window.process), 'undefined');
    await page.screenshot({path:path.join(artifacts,'windows-home.png'),fullPage:true});

    // Render a synthetic image; no personal screen contents are sent to a provider.
    const dataUrl = await page.evaluate(() => {
      const canvas = document.createElement('canvas'); canvas.width=1000;canvas.height=180;
      const ctx=canvas.getContext('2d');ctx.fillStyle='#fff';ctx.fillRect(0,0,1000,180);
      ctx.fillStyle='#111';ctx.font='48px Arial';ctx.fillText('Hello screen translation',25,95);
      return canvas.toDataURL('image/png');
    });
    const ocr = await page.evaluate(dataUrl => window.lens.recognizeImage({dataUrl,language:'auto'}),dataUrl);
    assert.match(ocr.text,/screen translation/i);
    if(process.env.DEEPSEEK_API_KEY) assert.equal((await page.locator('body').innerText()).includes(process.env.DEEPSEEK_API_KEY),false);
    await page.getByLabel('待翻译文本').fill('Screen translation is ready.');
    const translated = process.env.LENS_LIVE_TEST === '1';
    if (translated) {
      assert.ok(status.hasApiKey,'Live test requires DEEPSEEK_API_KEY');
      await page.getByRole('button',{name:'开始翻译'}).click();
      await page.locator('.translated-text').waitFor({timeout:75000});
      const text=await page.locator('.translated-text').innerText(); assert.match(text,/[\u3400-\u9fff]/u);
      await page.getByRole('button',{name:'复制译文',exact:true}).click();
      assert.equal(await app.evaluate(({clipboard}) => clipboard.readText()),text);
      await page.screenshot({path:path.join(artifacts,'windows-translation.png'),fullPage:true});
      await page.getByRole('button',{name:'翻译历史',exact:true}).click();
      assert.equal(await page.locator('.history-card').count(),1);
      await page.getByLabel('搜索翻译历史').fill('no matching history');
      await page.getByText('暂时没有匹配的记录').waitFor();
      await page.getByLabel('搜索翻译历史').fill('');
      await page.getByRole('button',{name:'清空历史',exact:true}).click();
      await page.getByRole('dialog').getByRole('button',{name:'清空历史',exact:true}).click();
      await page.getByText('你的翻译旅程，从这里开始').waitFor();
    }
    await page.getByRole('button',{name:'偏好设置',exact:true}).click();
    await page.getByRole('switch',{name:'保存翻译历史'}).click();
    assert.equal((await page.evaluate(() => window.lens.getSettings())).saveHistory,false);
    await page.getByRole('button',{name:'忠于原文',exact:false}).click();
    assert.equal((await page.evaluate(() => window.lens.getSettings())).style,'literal');
    await page.screenshot({path:path.join(artifacts,'windows-settings.png'),fullPage:true});
    await page.getByRole('button',{name:'图片翻译',exact:true}).click();
    await page.locator('.image-drop').evaluate((element,dataUrl) => {
      const binary=atob(dataUrl.split(',')[1]);const bytes=Uint8Array.from(binary,c=>c.charCodeAt(0));
      const transfer=new DataTransfer();transfer.items.add(new File([bytes],'synthetic-ocr.png',{type:'image/png'}));
      element.dispatchEvent(new DragEvent('drop',{bubbles:true,dataTransfer:transfer}));
    },dataUrl);
    await page.waitForFunction(() => /screen translation/i.test(document.querySelector('textarea').value));
    await page.screenshot({path:path.join(artifacts,'windows-image.png'),fullPage:true});
    await page.getByRole('button',{name:'屏幕翻译',exact:true}).click();
    const capturePromise = app.waitForEvent('window');
    await page.getByRole('button',{name:'框选屏幕翻译',exact:false}).click();
    const capture = await capturePromise;
    await capture.getByText('拖动框选你想读懂的文字').waitFor();
    await capture.keyboard.press('Escape').catch(error => {if(!capture.isClosed())throw error;});
    await page.waitForFunction(() => !document.querySelector('.capture-card').disabled);
    // A second capture proves cancellation releases capture/busy state.
    const secondWindow=app.waitForEvent('window');
    await page.getByRole('button',{name:'框选屏幕翻译',exact:false}).click();
    const second=await secondWindow;
    await second.getByRole('button',{name:'取消截图'}).waitFor();
    await second.getByRole('button',{name:'取消截图'}).click().catch(error => {if(!second.isClosed())throw error;});
    await page.waitForFunction(() => !document.querySelector('.capture-card').disabled);
    // Capture a synthetic test window, crop it in the real selector, and verify OCR returns to the editor.
    await app.evaluate(async ({BrowserWindow,screen}) => {
      const display=screen.getDisplayNearestPoint(screen.getCursorScreenPoint());
      const fixture=new BrowserWindow({x:display.bounds.x+50,y:display.bounds.y+100,width:900,height:220,frame:false,alwaysOnTop:true,skipTaskbar:true,focusable:false,webPreferences:{sandbox:true}});
      fixture.setTitle('Lens synthetic OCR fixture');
      await fixture.loadURL('data:text/html,'+encodeURIComponent('<html><body style="margin:0;background:white;color:black;font:48px Arial"><div style="padding:65px 30px">Screen translation is ready</div></body></html>'));
      fixture.showInactive();
    });
    const cropWindow=app.waitForEvent('window');
    await page.evaluate(() => window.lens.startCapture());
    const crop=await cropWindow;
    await crop.getByText('拖动框选你想读懂的文字').waitFor();
    await crop.mouse.move(50,100);await crop.mouse.down();await crop.mouse.move(940,315,{steps:10});await crop.mouse.up().catch(error=>{if(!crop.isClosed())throw error;});
    await page.waitForFunction(() => /Screen translation is ready/i.test(document.querySelector('textarea')?.value ?? ''));
    await app.evaluate(({BrowserWindow}) => {for(const w of BrowserWindow.getAllWindows()){if(w.getTitle()==='Lens synthetic OCR fixture')w.close();}});
    assert.deepEqual(errors,[]);
    console.log(JSON.stringify({passed:true,liveTranslation:translated,ocrLanguage:ocr.language,ocrText:ocr.text,checks:['sandbox','OCR IPC',...(translated?['translation and clipboard','history search and clearing']:[]),'settings persistence','image drop OCR','capture cancellation twice','screen crop OCR','no renderer errors']},null,2));
  } finally {
    await app.evaluate(({clipboard}, text) => clipboard.writeText(text), originalClipboard).catch(() => {});
    await app.close();
    // Deliberately keep isolated test userData for forensic diagnosis; no credentials are stored there.
  }
})().catch(e => {const key=process.env.DEEPSEEK_API_KEY;let msg=e.stack || String(e);if(key)msg=msg.replaceAll(key,'[REDACTED]');console.error(msg);process.exitCode=1;});
