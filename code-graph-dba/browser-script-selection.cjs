const assert = require('node:assert/strict');

module.exports = async (browser, base, jar) => {
  const context = await browser.newContext({viewport: {width: 1440, height: 960}});
  const page = await context.newPage(), errors = [];
  page.on('pageerror', error => errors.push(error.message));
  try {
    await page.goto(base + '/dba');
    const profile = await page.evaluate(async jar => {
      const session = await (await fetch('/api/dba/bootstrap', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: '{}'})).json();
      const response = await fetch('/api/dba/connections', {method: 'POST', headers: {'Content-Type': 'application/json', 'X-Dba-CSRF': session.csrf}, body: JSON.stringify({name: 'Selection fixture', url: 'jdbc:h2:mem:selection_' + Date.now(), jar, driverClass: 'org.h2.Driver', username: 'sa', saveUntested: true})});
      if (!response.ok) throw Error(await response.text());
      return response.json();
    }, jar);
    await page.reload();
    await page.locator('#new-tab').click();
    await page.locator('.tab.active .tab-connection').selectOption(profile.id);
    const editor = page.locator('#sql');
    const state = () => editor.evaluate(input => ({start: input.selectionStart, end: input.selectionEnd, direction: input.selectionDirection, top: input.scrollTop, left: input.scrollLeft, focused: document.activeElement === input}));
    const select = async (sql, direction) => {
      const before = 'SELECT 1 AS unselected;\n' + '-- padding\n'.repeat(15);
      await editor.fill(before + sql + '\nSELECT 3 AS unselected;\n' + '-- padding\n'.repeat(50));
      await editor.evaluate((input, selection) => {
        input.focus(); input.setSelectionRange(selection.start, selection.end, selection.direction);
        input.scrollTop = 260;
      }, {start: before.length, end: before.length + sql.length, direction});
      return state();
    };
    for (const [control, endpoint, direction, keyboard] of [
      ['run', '/query/execute', 'backward', false],
      ['explain', '/query/explain', 'forward', false],
      ['run', '/query/execute', 'forward', true]
    ]) {
      const sql = 'SELECT 2 AS chosen;', expected = await select(sql, direction);
      let release;
      const gate = new Promise(resolve => release = resolve);
      await page.route('**/api/dba' + endpoint, async route => {await gate; await route.continue();}, {times: 1});
      const request = page.waitForRequest(request => request.url().endsWith(endpoint) && request.method() === 'POST');
      if (keyboard) await editor.press('Control+Enter'); else await page.locator('#' + control).click();
      assert.equal((await request).postDataJSON().sql, sql, 'Only the selected SQL is submitted');
      try {assert.deepEqual(await state(), expected, control + ' keeps the selection visibly focused during submission');}
      finally {release();}
      await page.waitForFunction(() => !document.querySelector('#run').disabled);
      assert.deepEqual(await state(), expected, control + ' keeps selection, direction and scrolling after completion');
    }
    // A failed Explain must also retain the selected text.
    const failed = await select('SELECT * FROM missing_selection_table;', 'backward');
    await page.locator('#explain').click();
    await page.locator('#error').waitFor({state: 'visible'});
    await page.waitForFunction(() => !document.querySelector('#run').disabled);
    assert.deepEqual(await state(), failed);
    // Completion must respect a later, deliberate focus change.
    await select('SELECT 4 AS chosen;', 'forward');
    let release;
    const gate = new Promise(resolve => release = resolve);
    await page.route('**/api/dba/query/execute', async route => {await gate; await route.continue();}, {times: 1});
    const submitted = page.waitForRequest(request => request.url().endsWith('/query/execute') && request.method() === 'POST');
    await page.locator('#run').click(); await submitted;
    await page.locator('#new-tab').focus(); release();
    await page.waitForFunction(() => !document.querySelector('#run').disabled);
    assert.equal(await page.evaluate(() => document.activeElement.id), 'new-tab');
    assert.deepEqual(errors, []);
    await page.screenshot({path: 'code-graph-dba/target/script-selection.png'});
    console.log('Script selection: Run, Explain, Ctrl+Enter, exact submitted SQL, selection direction/scrolling, failure retention and later focus changes passed.');
  } finally {await context.close();}
};
