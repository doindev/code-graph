const assert=require('node:assert/strict');

// Real application, connection tree, templates and editor; profile storage is isolated.
module.exports=async(browser,base)=>{
  const context=await browser.newContext({viewport:{width:1440,height:960}}),page=await context.newPage(),errors=[],writes=[];
  const cases=[
    ['pg','postgresql','jdbc:postgresql://reports.example:5544/finance%20archive?sslmode=verify-full&ApplicationName=DBA',['reports.example','5544','finance archive']],
    ['ipv6','postgresql','jdbc:postgresql://[2001:db8::7]/ledger?sslmode=require',['[2001:db8::7]','5432','ledger']],
    ['mysql','mysql','jdbc:mysql://mysql.example:3308/sales?useUnicode=true',['mysql.example','3308','sales']],
    ['maria','mariadb','jdbc:mariadb://maria.example:3309/accounts?sslMode=verify-full',['maria.example','3309','accounts']],
    ['sqlserver','sqlserver','jdbc:sqlserver://sql.example:1444;encrypt=true;databaseName={sales;west}}archive};applicationName=DBA',['sql.example','1444','sales;west}archive']],
    ['sql-default','sqlserver','jdbc:sqlserver://sql.example;encrypt=true',['sql.example','1433','']],
    ['oracle-service','oracle','jdbc:oracle:thin:@//ora.example:1527/accounts.example?oracle.net.CONNECT_TIMEOUT=5000',['ora.example','1527','accounts.example']],
    ['oracle-sid','oracle','jdbc:oracle:thin:@ora.example:1528:XE',['ora.example','1528','XE']],
    ['db2','db2','jdbc:db2://db2.example:50007/ACCOUNTS:currentSchema=REPORTS;',['db2.example','50007','ACCOUNTS']],
    ['snowflake','snowflake','jdbc:snowflake://acme-europe.snowflakecomputing.com/?query_tag=DBA',null],
    ['multi','postgresql','jdbc:postgresql://primary.example:5432,standby.example:5433/app?targetServerType=primary',null],
    ['descriptor','oracle','jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=ora.example)(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=app)))',null],
    ['h2','h2','jdbc:h2:mem:saved;DB_CLOSE_DELAY=-1',null]
  ];
  let profiles=cases.map(([id,templateId,url])=>({id,templateId,url,name:'Saved '+id,username:'reports_user',driverClass:'saved.Driver',jars:['C:/drivers/saved.jar'],driverBundle:{groupId:'saved.group',artifactId:'saved-artifact',version:'9.8.7'},hasCredential:true,readOnly:false,secretPropertyNames:['custom.secret'],properties:templateId==='postgresql'?{connectTimeout:'17',sslrootcert:'C:/certs/root.pem',tcpKeepAlive:'true',currentSchema:'reporting',legacyPublic:'saved override'}:templateId==='snowflake'?{warehouse:'ANALYTICS',db:'FINANCE',schema:'REPORTS',role:'READER',authenticator:'snowflake_jwt',private_key_file:'C:/keys/snowflake.p8'}:{},pool:{maximumPoolSize:7,minimumIdle:1,connectionTimeout:23000,validationTimeout:4500,idleTimeout:90000,maxLifetime:600000},color:'#2468ac'}));
  profiles.push({id:'mongo-native',templateId:'mongodb-native',name:'Saved Mongo native',url:'mongodb://mongo.example:27027',username:'mongo_reader',readOnly:false,hasCredential:true,nativeOptions:{database:'accounts',tls:true,authDatabase:'identity',authMechanism:'SCRAM-SHA-1',topology:'replica_set',seeds:['mongodb://mongo2.example:27027'],connectTimeoutMS:13000,socketTimeoutMS:47000,replicaSet:'finance-rs',readPreference:'secondaryPreferred',idleTimeoutMS:94000,maximumPoolSize:6}},
    {id:'redis-native',templateId:'redis-native',name:'Saved Redis native',url:'redis://sentinel.example:26389',username:'redis_reader',readOnly:true,hasCredential:true,secretPropertyNames:['sentinelPassword'],nativeOptions:{database:'4',tls:false,topology:'sentinel',seeds:['redis://sentinel2.example:26389'],sentinelMaster:'accounts-main',sentinelUsername:'sentinel_reader',connectTimeoutMS:14000,socketTimeoutMS:48000,idleTimeoutMS:95000}});
  page.on('pageerror',e=>errors.push(e.message));page.on('dialog',dialog=>dialog.accept());
  try{
    await page.route('**/api/dba/connections',route=>route.fulfill({json:profiles}));
    await page.route('**/api/dba/connections/**',route=>{
      if(route.request().url().endsWith('/state'))return route.fulfill({json:{connected:false,busy:false}});
      assert.equal(route.request().method(),'PUT');const draft=route.request().postDataJSON();writes.push(draft);
      const index=profiles.findIndex(p=>p.id===draft.connectionId);assert.ok(index>=0);profiles[index]={...profiles[index],...draft};return route.fulfill({json:profiles[index]});
    });
    await page.route('**/api/dba/setup/driver-status',route=>route.fulfill({json:{id:'fixture-driver'}}));
    await page.route('**/api/dba/jobs/fixture-driver',route=>route.fulfill({json:{state:'complete',result:{installed:[],latestAvailable:false,message:'Fixture driver lookup'}}}));
    await page.goto(base+'/dba');await page.waitForFunction(()=>document.querySelector('#connection-count')?.textContent==='15 connections');
    async function open(id){
      await page.locator('[data-connection="'+id+'"] > .connection-title .connection-select').click({button:'right'});
      await page.getByRole('menuitem',{name:'Edit Connection',exact:true}).click();await page.locator('#connection-editor').waitFor();
    }
    async function value(key,expected){assert.equal(await page.locator('#ce-'+key).inputValue(),expected,key);}
    for(const [id,templateId,url,address] of cases){
      await open(id);await value('name','Saved '+id);await value('url',url);await value('username','reports_user');await value('password','');await value('password-action','Keep');
      assert.equal(await page.locator('#ce-password').getAttribute('placeholder'),'Unchanged unless replaced');
      if(address){
        for(const [i,key] of ['host','port','database'].entries()){await value(key,address[i]);assert.equal(await page.locator('#ce-'+key).isEditable(),false,'URL mode address is a synchronized view');}
        await page.locator('#ce-url-mode').selectOption('Generate from fields');await value('url',url);
        await page.locator('#ce-url-mode').selectOption('Edit URL');await value('url',url);
      }else if(id==='snowflake'){
        await value('account','acme-europe.snowflakecomputing.com');await value('sf-warehouse','ANALYTICS');await value('sf-db','FINANCE');await value('sf-schema','REPORTS');await value('sf-role','READER');await page.locator('#ce-tab-2').click();await value('authenticator','snowflake_jwt');await value('key-file','C:/keys/snowflake.p8');await page.locator('#ce-tab-0').click();
        await page.locator('#ce-url-mode').selectOption('Generate from fields');await value('url',url);
      }else if(['multi','descriptor'].includes(id)){
        for(const key of ['host','port','database'])await value(key,'');
        assert.equal(await page.locator('#ce-url-mode option').first().evaluate(option=>option.disabled),true,'Complex URLs stay intact in Edit URL mode');
      }
      if(id==='h2'){await page.locator('#ce-tab-4').click();assert.equal(await page.getByRole('textbox',{name:'DB_CLOSE_DELAY',exact:true}).inputValue(),'-1');}
      await page.locator('#ce-tab-1').click();await value('driverClass','saved.Driver');await value('jars','C:/drivers/saved.jar');await value('groupId','saved.group');await value('artifactId','saved-artifact');await value('version','9.8.7');
      await page.locator('#ce-tab-5').click();await value('pool-maximumPoolSize','7');await value('pool-minimumIdle','1');await value('pool-connectionTimeout','23000');await value('pool-validationTimeout','4500');await value('pool-idleTimeout','90000');await value('pool-maxLifetime','600000');
      await page.locator('#ce-close').click();await page.locator('#connection-editor').waitFor({state:'hidden'});
    }
    for(const [id,expected] of [
      ['mongo-native',{0:{url:'mongodb://mongo.example:27027',database:'accounts',username:'mongo_reader','native-access':'Allow reviewed writes'},2:{tls:'true',authDatabase:'identity',authMechanism:'SCRAM-SHA-1'},3:{topology:'replica_set',seeds:'mongodb://mongo2.example:27027',connectTimeoutMS:'13000',socketTimeoutMS:'47000',replicaSet:'finance-rs',readPreference:'secondaryPreferred'},5:{idleTimeoutMS:'94000',maximumPoolSize:'6'}}],
      ['redis-native',{0:{url:'redis://sentinel.example:26389',database:'4',username:'redis_reader','native-access':'Read only'},2:{tls:'false',sentinelUsername:'sentinel_reader',sentinelPassword:'','sentinel-password-action':'Keep'},3:{topology:'sentinel',seeds:'redis://sentinel2.example:26389',sentinelMaster:'accounts-main',connectTimeoutMS:'14000',socketTimeoutMS:'48000'},5:{idleTimeoutMS:'95000'}}]
    ]){
      await open(id);for(const [tab,fields] of Object.entries(expected)){await page.locator('#ce-tab-'+tab).click();for(const [key,want] of Object.entries(fields))await value(key,want);}await page.locator('#ce-close').click();
    }
    await open('pg');await page.locator('#ce-tab-2').click();assert.equal(await page.getByRole('textbox',{name:'sslrootcert',exact:true}).inputValue(),'C:/certs/root.pem');assert.equal(await page.getByRole('combobox',{name:'sslmode',exact:true}).inputValue(),'verify-full');assert.equal(await page.getByRole('combobox',{name:'sslmode',exact:true}).isDisabled(),true);
    await page.locator('#ce-tab-4').click();assert.equal(await page.getByRole('textbox',{name:'currentSchema',exact:true}).inputValue(),'reporting');assert.equal(await page.getByRole('textbox',{name:'legacyPublic',exact:true}).inputValue(),'saved override');assert.equal(await page.getByRole('textbox',{name:'custom.secret',exact:true}).inputValue(),'');assert.equal(await page.getByRole('textbox',{name:'custom.secret',exact:true}).getAttribute('placeholder'),'Saved · keep unless replaced');assert.equal(await page.getByRole('textbox',{name:'ApplicationName',exact:true}).inputValue(),'DBA');
    await page.locator('#ce-tab-3').click();assert.equal(await page.getByRole('combobox',{name:'tcpKeepAlive',exact:true}).inputValue(),'true');await page.getByRole('textbox',{name:'connectTimeout',exact:true}).waitFor();assert.equal(await page.getByRole('textbox',{name:'connectTimeout',exact:true}).inputValue(),'17');
    await page.locator('#ce-tab-0').click();await page.locator('#ce-url').fill('jdbc:postgresql://new.example:5549/new%20db?sslmode=verify-full&ApplicationName=DBA');
    await value('host','new.example');await value('port','5549');await value('database','new db');
    await page.locator('#ce-url-mode').selectOption('Generate from fields');await page.locator('#ce-host').fill('edited.example');await page.locator('#ce-database').fill('saved db');
    const updated='jdbc:postgresql://edited.example:5549/saved%20db?sslmode=verify-full&ApplicationName=DBA';await value('url',updated);
    await page.locator('#ce-save-untested').click();await page.locator('#connection-editor').waitFor({state:'hidden'});
    assert.equal(writes.length,1);assert.equal(writes[0].url,updated);assert.equal(writes[0].connectionId,'pg');assert.equal(writes[0].username,'reports_user');assert.equal('password' in writes[0],false);assert.deepEqual(writes[0].properties,{connectTimeout:'17',sslrootcert:'C:/certs/root.pem',tcpKeepAlive:'true',currentSchema:'reporting',legacyPublic:'saved override'});assert.equal(writes[0].readOnly,false);assert.deepEqual(writes[0].secretProperties,{});assert.deepEqual(writes[0].pool,{maximumPoolSize:7,minimumIdle:1,connectionTimeout:23000,validationTimeout:4500,idleTimeout:90000,maxLifetime:600000});
    await open('pg');await value('host','edited.example');await value('database','saved db');await page.locator('#ce-close').click();
    await open('sqlserver');await page.locator('#ce-url-mode').selectOption('Generate from fields');await page.locator('#ce-database').fill('east;archive}');await value('url','jdbc:sqlserver://sql.example:1444;encrypt=true;databaseName={east;archive}}};applicationName=DBA');await page.locator('#ce-close').click();
    await page.locator('#add').click();await page.locator('[data-database=postgresql]').click();await page.locator('#driver-choice').waitFor();await page.locator('#driver-cancel').click();
    await value('name','');await value('username','');await value('host','localhost');await value('port','5432');await value('database','database');await value('url','jdbc:postgresql://localhost:5432/database');assert.equal(await page.locator('#ce-host').isEditable(),true);await page.locator('#ce-close').click();
    assert.deepEqual(errors,[]);console.log('Connection editor browser checks passed: saved values from tree, all six tabs, 13 JDBC URL forms, MongoDB/Redis settings, mode round trips, options, write-only secrets, saved edit/reopen, and new defaults.');
  }finally{await context.close();}
};
