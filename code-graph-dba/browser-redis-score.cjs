const assert=require('node:assert/strict');
module.exports=async(browser,base)=>{
  const context=await browser.newContext({viewport:{width:1280,height:900}}),page=await context.newPage(),errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  try{
    await page.goto(base+'/dba');
    await page.evaluate(async()=>{
      const {NativeWorkspace}=await import('/dba/native-workspace.js');
      const host=document.createElement('div');host.id='score-fixture';host.style='position:fixed;inset:0;z-index:100;background:#101722';document.body.append(host);
      const f=window.scoreFixture={commands:[],applies:0,accounts:[],score:1.25,phase:'complete'};
      const api=async(path,method,input)=>{
        if(path==='/native/prepare'){f.command=input.command;f.commands.push(structuredClone(input));return{id:'score-review',targetRevision:'score-r1',mutation:!!input.command.transaction,expiresAt:Date.now()+60000,target:{connectionName:'Score fixture',database:input.database},after:{nativeCommand:input.command},transactionNotice:'Redis binary64 scores. Current state, not change history. GEO indexes also use zset; changing score changes rank.'};}
        if(path==='/native/apply'){f.applies++;if(f.lostReply)throw Error('Lost reply');return{id:'score-job'};}
        if(path==='/native/execute')return{id:'score-job'};
        if(method==='DELETE'){f.releases=(f.releases??0)+1;return{};}
        if(path.endsWith('/cancel')){f.cancelled=true;f.phase='cancelled';return{};}
        if(path==='/jobs/score-job'){
          if(f.phase==='running')return{id:'score-job',state:'running'};
          if(f.phase==='cancelled')return{id:'score-job',state:'cancelled',finished:Date.now(),error:'Cancelled; execution outcome unknown'};
          const write=!!f.command.transaction;
          const result=write?{kind:'transaction',outcome:f.error?'conflict':'acknowledged',entries:f.error?[]:[{index:0,state:'acknowledged',value:f.receipt??0}]}:
            {kind:'pipeline',entries:[f.type??'zset',f.score,f.count??2,f.ttl??60000].map((value,index)=>({index,state:'acknowledged',value}))};
          return{id:'score-job',state:write&&f.error?'failed':'complete',finished:Date.now(),result,error:f.error};
        }throw Error('Unexpected API '+path);
      };
      f.workspace=new NativeWorkspace({api,profile:{id:'score-1',name:'Score fixture',transport:'redis',readOnly:false},state:{database:'3',commandText:'["ZADD",{"base64":"a2V5"},"9",{"base64":"AP8="}]'},changed:()=>{},account:bytes=>f.accounts.push(bytes)});f.workspace.mount(host);
    });
    await page.getByRole('button',{name:'Edit Redis sorted-set score',exact:true}).click();
    const editor=page.getByRole('region',{name:'Redis sorted-set score editor'}),member=editor.getByRole('textbox',{name:'Sorted-set member',exact:true}),score=editor.getByRole('textbox',{name:'Sorted-set score',exact:true});
    const load=editor.getByRole('button',{name:'Load member score'}),save=editor.getByRole('button',{name:'Save member score'}),revert=editor.getByRole('button',{name:'Revert score draft'}),reset=editor.getByRole('button',{name:'Choose another member'}),review=page.getByRole('dialog',{name:'Review native database change'});
    assert.equal(await member.inputValue(),'AP8=');assert.equal(await score.isDisabled(),true);assert.equal(await editor.getByRole('button',{name:'Stage member insertion'}).count(),0);
    assert.equal(await page.getByRole('button',{name:'Edit Redis set member'}).isDisabled(),true);assert.equal(await page.getByRole('textbox',{name:'Database',exact:true}).isDisabled(),true);
    await load.click();await editor.getByRole('status').filter({hasText:'Score loaded'}).waitFor();assert.equal(await score.inputValue(),'1.25');assert.equal(await save.isDisabled(),true);assert.equal(await member.getAttribute('readonly'),'');
    assert.deepEqual((await page.evaluate(()=>scoreFixture.commands.at(-1))).command,{pipeline:[['TYPE',{base64:'a2V5'}],['ZSCORE',{base64:'a2V5'},{base64:'AP8='}],['ZCARD',{base64:'a2V5'}],['PTTL',{base64:'a2V5'}]]});
    for(const value of ['','NaN','Infinity','1e999','1e-999','0x10',' 1']){await score.fill(value);assert.equal(await save.isDisabled(),true);assert.equal(await score.getAttribute('aria-invalid'),'true');}
    await score.fill('1.250');assert.equal(await save.isDisabled(),true,'Equivalent binary64 value is not a change');await score.fill('2.5');assert.equal(await save.isDisabled(),false);assert.equal(await page.evaluate(()=>scoreFixture.applies),0);
    await revert.click();assert.equal(await score.inputValue(),'1.25');await score.fill('2.5');
    page.once('dialog',d=>d.dismiss());await editor.getByRole('button',{name:'Close member editor'}).click();assert.equal(await editor.isVisible(),true);
    await page.evaluate(()=>{scoreFixture.workspace.unmount();scoreFixture.workspace.mount(document.querySelector('#score-fixture'));});assert.equal(await score.inputValue(),'2.5');
    await save.click();await review.waitFor();assert.match(await review.innerText(),/GEO/);await review.getByRole('button',{name:'Cancel',exact:true}).click();await editor.getByRole('status').filter({hasText:'Cancelled before execution'}).waitFor();assert.equal(await page.evaluate(()=>scoreFixture.applies),0);
    await save.click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();await editor.getByRole('status').filter({hasText:'Score updated'}).waitFor();
    const update=await page.evaluate(()=>scoreFixture.commands.at(-1));assert.equal(update.database,'3');assert.equal(update.expectedTargetRevision,'score-r1');assert.deepEqual(update.command,{transaction:[['ZADD',{base64:'a2V5'},'2.5',{base64:'AP8='}]],watch:[{key:{base64:'a2V5'},scoreMember:{base64:'AP8='},expected:'1.25'}]});assert.equal(await save.isDisabled(),true);
    await score.fill('-3.75');await page.evaluate(()=>scoreFixture.error='Redis transaction conflict; no commands executed');await save.click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();await editor.getByRole('status').filter({hasText:'transaction conflict'}).waitFor();assert.equal(await score.inputValue(),'-3.75');
    await page.evaluate(()=>{scoreFixture.error=null;scoreFixture.receipt=1;});await save.click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();await editor.getByRole('status').filter({hasText:'outcome uncertain'}).waitFor();assert.equal(await save.isDisabled(),true);assert.equal(await revert.isDisabled(),true);assert.equal(await reset.isDisabled(),true);
    await page.evaluate(()=>{scoreFixture.score=2.5;scoreFixture.receipt=0;});page.once('dialog',d=>d.accept());await load.click();await editor.getByRole('status').filter({hasText:'Score loaded'}).waitFor();assert.equal(await score.inputValue(),'2.5');
    await score.fill('1e-100');await save.click();await review.waitFor();await page.evaluate(()=>scoreFixture.phase='running');await review.getByRole('button',{name:'Apply once'}).click();await page.waitForFunction(()=>scoreFixture.workspace.operation?.id==='score-job');await page.getByRole('button',{name:'Cancel current operation'}).click();await editor.getByRole('status').filter({hasText:'outcome uncertain'}).waitFor();assert.equal(await score.inputValue(),'1e-100');assert.equal(await save.isDisabled(),true);
    await page.evaluate(()=>scoreFixture.phase='complete');page.once('dialog',d=>d.accept());await load.click();await editor.getByRole('status').filter({hasText:'Score loaded'}).waitFor();await reset.click();await member.fill('');
    await page.evaluate(()=>scoreFixture.score=null);await load.click();await editor.getByRole('status').filter({hasText:'existing member with a finite score'}).waitFor();assert.equal(await save.isDisabled(),true);assert.equal(await score.isDisabled(),true);
    await page.evaluate(()=>scoreFixture.score=0);await load.click();await editor.getByRole('status').filter({hasText:'Score loaded: 0'}).waitFor();assert.equal(await score.inputValue(),'0');await score.fill('.5');assert.equal(await save.isDisabled(),false);
    const checked=await page.evaluate(async()=>{
      const {redisScore,redisMemberSnapshot}=await import('/dba/redis-set-editor.js');
      for(const text of ['1\n','1\r','\t1','1\t']){let rejected=false;try{redisScore(text);}catch{rejected=true;}if(!rejected)throw Error('Whitespace score accepted');}
      const result=(value,type='zset',ttl=-1)=>({kind:'pipeline',entries:[type,value,1,ttl].map((value,index)=>({index,state:'acknowledged',value}))});
      return{valid:['-0','+1e-3','1.7976931348623157e308','4.9e-324'].map(redisScore),bad:[{},result(null),result('1'),result(Infinity),result(1,'set'),result(1,'zset',-2)].map(r=>{try{redisMemberSnapshot(r,true);return false;}catch{return true;}})};
    });assert.equal(checked.bad.every(Boolean),true);assert.equal(checked.valid[1],.001);assert.equal(checked.valid[2],Number.MAX_VALUE);assert.equal(checked.valid[3],Number.MIN_VALUE);
    await page.setViewportSize({width:420,height:600});await save.scrollIntoViewIfNeeded();assert.equal(await save.evaluate(e=>{const r=e.getBoundingClientRect();return r.top>=0&&r.bottom<=innerHeight;}),true);await page.screenshot({path:'code-graph-dba/target/redis-score-editor.png'});
    const before=await page.evaluate(()=>scoreFixture.applies);await page.evaluate(async()=>{scoreFixture.workspace.profile.readOnly=true;scoreFixture.workspace.stringEditor.sync();await scoreFixture.workspace.stringEditor.save();});assert.equal(await save.isDisabled(),true);assert.equal(await score.isDisabled(),true);assert.equal(await page.evaluate(()=>scoreFixture.applies),before);
    await page.evaluate(()=>scoreFixture.workspace.invalidate('Connection removed; draft retained'));assert.equal(await load.isDisabled(),true);await page.evaluate(()=>scoreFixture.workspace.dispose());assert.equal(await editor.count(),0);assert.equal(await page.evaluate(()=>scoreFixture.accounts.at(-1)),0);assert.deepEqual(errors,[]);
    console.log('Sorted-set score editor passed: binary identity, finite precision, exact review/guard, TTL notice, conflicts, unknown/cancelled outcomes, reconciliation, empty member/zero score, lifecycle and narrow layout.');
  }finally{await context.close();}
};
