const assert=require('node:assert/strict');

// Exercise real SVG screen coordinates: path data alone cannot detect viewport scaling.
module.exports=async(browser,base)=>{
 const page=await browser.newPage({viewport:{width:1800,height:1000}}),errors=[];
 page.on('pageerror',error=>errors.push(error.message));
 try{
  await page.route('**/dba/app.js',route=>route.fulfill({contentType:'application/javascript',body:''}));
  await page.goto(base+'/dba');
  await page.evaluate(async()=>{
   const {VisualQueryBuilder}=await import('/dba/query-builder.js'),{emptyModel,column,connect}=await import('/dba/visual-model.js');
   document.body.replaceChildren();const host=document.createElement('div');host.style.cssText='position:fixed;inset:0';document.body.append(host);
   const calls=[],b=new VisualQueryBuilder({connectionId:'geometry',connectionName:'Canvas fixture',draft:emptyModel(),api:async(path)=>{calls.push(path);if(path==='/query-builder/compile')return {valid:true,sql:'SELECT 1',bindings:[],outputs:[],diagnostics:[]};throw Error('Unexpected request '+path);}});
   const columns=Array.from({length:14},(_,i)=>({name:'C'+i,type:'INTEGER'}));
   const left=b.insertSource({name:'Left table',reference:'LEFT_TABLE',columns}),right=b.insertSource({name:'Right table',reference:'RIGHT_TABLE',columns});
   for(const name of ['C0','C10'])connect(b.model,column(left.id,name),column(right.id,name));
   await b.initialize();b.mount(host);window.geometryFixture={b,calls};
  });
  await page.locator('.qb-connections path').nth(1).waitFor({state:'attached'});
  const measure=()=>page.evaluate(()=>[...document.querySelectorAll('.qb-connections path')].map((path,i)=>{
   const name=['C0','C10'][i],handles=[...document.querySelectorAll('.qb-source')].map(card=>{
    const r=card.querySelector('[data-column="'+name+'"].qb-column-handle').getBoundingClientRect(),s=card.querySelector('.qb-source-columns').getBoundingClientRect(),edge=card.getBoundingClientRect();
    return {left:edge.left,right:edge.right,y:Math.max(s.top+5,Math.min(s.bottom-5,r.top+r.height/2))};
   });
   const forward=handles[0].left<=handles[1].left,expected=[{x:forward?handles[0].right:handles[0].left,y:handles[0].y},{x:forward?handles[1].left:handles[1].right,y:handles[1].y}];
   const actual=[0,path.getTotalLength()].map(length=>{const p=path.getPointAtLength(length),v=new DOMPoint(p.x,p.y).matrixTransform(path.getScreenCTM());return {x:v.x,y:v.y};});
   return {expected,actual};
  }));
  async function aligned(stage){
   await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
   const lines=await measure();for(const line of lines)for(let i=0;i<2;i++)assert.ok(Math.hypot(line.actual[i].x-line.expected[i].x,line.actual[i].y-line.expected[i].y)<1,stage+': '+JSON.stringify(line));return lines;
  }
  async function drag(header,dx,dy){const r=await header.boundingBox();await page.mouse.move(r.x+100,r.y+r.height/2);await page.mouse.down();await page.mouse.move(r.x+100+dx,r.y+r.height/2+dy,{steps:8});await page.mouse.up();}
  const output=page.locator('.qb-output-card .qb-source-head');
  await page.getByRole('button',{name:'Arrange sources',exact:true}).click();
  const sourceGap=await page.locator('.qb-source').evaluateAll(cards=>cards[1].getBoundingClientRect().left-cards[0].getBoundingClientRect().right);assert.equal(sourceGap,60,'Arrange sources doubles the horizontal gap to 60 px');
  const before=await aligned('Initial wide canvas');
  const gap=await page.locator('.qb-connections path').first().evaluate(path=>{
   const matrix=path.getScreenCTM(),length=path.getTotalLength(),points=Array.from({length:33},(_,i)=>{const p=path.getPointAtLength(length*i/32);return new DOMPoint(p.x,p.y).matrixTransform(matrix);});
   return {xs:points.map(p=>p.x),mid:{x:points[16].x,y:points[16].y}};
  });
  assert.ok(gap.xs.every(x=>x>=before[0].expected[0].x-.5&&x<=before[0].expected[1].x+.5),'Join curve stays in the gap between adjacent cards');
  await page.mouse.click(gap.mid.x,gap.mid.y);await page.getByRole('dialog',{name:'Edit join',exact:true}).waitFor();await page.keyboard.press('Escape');
  const covered=await page.evaluate(point=>{
   const card=document.querySelector('.qb-output-card'),board=document.querySelector('.qb-board').getBoundingClientRect(),left=card.style.left,top=card.style.top;
   card.style.left=(point.x-board.left-10)+'px';card.style.top=(point.y-board.top-15)+'px';
   const covered=card.contains(document.elementFromPoint(point.x,point.y));card.style.left=left;card.style.top=top;return covered;
  },gap.mid);
  assert.equal(covered,true,'Cards stay above crossing joins so their text and controls remain unobstructed');
  await page.screenshot({path:'code-graph-dba/target/query-card-edges.png'});
  await page.setViewportSize({width:1800,height:1400});
  await drag(page.locator('.qb-divider'),0,260);assert.deepEqual(await aligned('Divider expands canvas height'),before,'Resizing the canvas leaves table endpoints and rendered lines unchanged');
  assert.ok(await page.locator('.qb-board').evaluate(e=>e.clientHeight>parseFloat(e.style.height)),'Exercise canvas minimum height larger than its content');
  await drag(page.locator('.qb-divider'),0,-430);assert.deepEqual(await aligned('Divider shrinks canvas height'),before);
  await page.locator('.qb-divider').focus();await page.locator('.qb-divider').press('ArrowUp');await aligned('Keyboard divider resize');
  await page.setViewportSize({width:1800,height:1000});await aligned('Window height resize');
  await drag(output,650,0);const expanded=await aligned('Query Output expands the canvas');assert.deepEqual(expanded,before,'Moving output leaves table endpoints and rendered lines unchanged');
  await drag(output,-650,100);assert.deepEqual(await aligned('Query Output shrinks canvas width and grows height'),before);
  await output.focus();await output.press('ArrowLeft');await aligned('Keyboard output movement');
  await page.getByRole('button',{name:'Zoom out',exact:true}).click();await page.getByRole('button',{name:'Zoom out',exact:true}).click();await aligned('Zoomed canvas');
  await drag(page.locator('.qb-output-card .qb-source-head'),100,-40);await aligned('Output movement with zoom');
  await drag(page.locator('.qb-source .qb-source-head').first(),30,20);const moved=await aligned('Table movement updates its own endpoints');assert.notDeepEqual(moved[0].expected,before[0].expected);
  await page.locator('.qb-source-columns').first().evaluate(e=>e.scrollTop=80);await aligned('Column scrolling');
  await page.setViewportSize({width:700,height:900});await aligned('Narrow canvas');
  await page.locator('.qb-canvas').evaluate(e=>{e.scrollLeft=180;e.scrollTop=60;});await aligned('Scrolled canvas');
  assert.equal(await page.evaluate(()=>geometryFixture.calls.some(path=>path==='/query/execute'||path==='/query/explain')),false);
  await page.evaluate(()=>geometryFixture.b.dispose());assert.deepEqual(errors,[]);
  console.log('Canvas geometry: Query Output drag/keyboard, divider/window resize, canvas growth/shrinkage, zoom, card-edge endpoints, unobstructed cards, clickable joins, source movement and scrolling passed.');
 }finally{await page.close();}
};
