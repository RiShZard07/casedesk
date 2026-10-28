import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { chromium } from 'playwright';

const server=spawn('java',['-cp','build','CaseDesk','0',':memory:'],{cwd:process.cwd(),stdio:['ignore','pipe','inherit']});
let browser;
try {
  const url=await new Promise((resolve,reject)=>{
    const timer=setTimeout(()=>reject(Error('Java server startup timed out')),10000);
    server.once('exit',code=>{clearTimeout(timer);reject(Error(`Java exited ${code}`));});
    let text='';server.stdout.on('data',chunk=>{text+=chunk;const match=text.match(/http:\/\/localhost:\d+/);if(match){clearTimeout(timer);resolve(match[0]);}});
  });
  browser=await chromium.launch({headless:true,args:['--no-sandbox']});
  const page=await browser.newPage();page.setDefaultTimeout(10000);
  const exceptions=[];page.on('pageerror',e=>exceptions.push(e.message));
  await page.goto(url);await page.getByRole('status').filter({hasText:'Ready'}).waitFor();
  const rows=()=>page.locator('tbody tr');
  async function create(title,priority='3'){
    await page.getByLabel('Title',{exact:true}).fill(title);
    await page.getByLabel('Priority',{exact:true}).selectOption(priority);
    await page.getByRole('button',{name:'Create',exact:true}).click();
    await page.getByRole('status').filter({hasText:'Done'}).waitFor();
  }
  await create('Routine fictional ticket');assert.equal(await rows().count(),1);
  console.log('PASS React create updates state and renders a row');
  await create('Urgent fictional ticket','5');
  await page.getByRole('button',{name:'Claim highest priority'}).click();
  await page.getByRole('status').filter({hasText:'Done'}).waitFor();
  const urgent=rows().filter({hasText:'Urgent fictional ticket'});
  assert.match(await urgent.innerText(),/ASSIGNED/);
  assert.match(await rows().filter({hasText:'Routine fictional ticket'}).innerText(),/OPEN/);
  console.log('PASS React priority claim respects backend ordering');
  await urgent.getByRole('button',{name:'Close',exact:true}).click();
  await page.getByRole('status').filter({hasText:'Done'}).waitFor();
  assert.match(await urgent.innerText(),/CLOSED/);
  assert.match(await page.getByTestId('metrics').innerText(),/"closed": 1/);
  console.log('PASS React close action refreshes ticket and telemetry');
  const hostile='<img src=x onerror=window.__injected=1>';
  await create(hostile);
  assert.equal(await rows().filter({hasText:hostile}).locator('img').count(),0);
  assert.equal(await page.evaluate(()=>window.__injected),undefined);
  console.log('PASS React escapes untrusted ticket titles');
  const before=(await (await page.request.get(url+'/tickets')).json()).length;
  let dropped=false;
  await page.route('**/tickets',async route=>{
    if(route.request().method()==='POST'&&!dropped){await route.fetch();dropped=true;await route.abort();}
    else await route.continue();
  });
  await page.getByLabel('Title',{exact:true}).fill('Retry after lost response');
  await page.getByRole('button',{name:'Create',exact:true}).click();
  await page.getByRole('alert').waitFor();
  assert.equal((await (await page.request.get(url+'/tickets')).json()).length,before+1);
  await page.getByRole('button',{name:'Create',exact:true}).click();
  await page.getByRole('status').filter({hasText:'Done'}).waitFor();
  assert.equal((await (await page.request.get(url+'/tickets')).json()).length,before+1);
  await page.unroute('**/tickets');
  console.log('PASS React retry preserves idempotency key after lost response');
  // Three tickets remain OPEN: routine, escaped title, retry fixture.
  for(let i=0;i<3;i++){
    await page.getByRole('button',{name:'Claim highest priority'}).click();
    await page.getByRole('status').filter({hasText:'Done'}).waitFor();
  }
  await page.getByRole('button',{name:'Claim highest priority'}).click();
  await page.getByRole('alert').filter({hasText:'no open tickets'}).waitFor();
  assert.deepEqual(exceptions,[]);
  console.log('PASS React displays backend errors without page exceptions');
  await page.screenshot({path:'build/react-ui.png',fullPage:true});
  console.log('6 browser test scenarios passed.');
} finally {
  if(browser)await browser.close();
  server.kill('SIGTERM');
}
