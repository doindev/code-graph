// Native configuration is intentionally separate from JDBC properties and driver provisioning.
export function renderNativeConnection({template, profile, panel, field, el}) {
  const options=profile?.nativeOptions??{}, general=panel('General');
  field(general,'url','Native endpoint',profile?.url??template.url,{required:true,
    help:'Host endpoint only. Enter credentials and database separately; inline credentials and URL options are rejected.'});
  field(general,'database',template.transport==='redis'?'Logical database number':'Default database',options.database??(template.transport==='redis'?'0':''),{required:true});
  field(general,'username','Username / ACL user',profile?.username??'');
  field(general,'password','Password · write-only OS vault','',{type:'password',placeholder:profile?.hasCredential?'Unchanged unless replaced':'Not saved until Save'});
  field(general,'password-action','Saved password',profile?.hasCredential?'Keep':'Replace',{choices:['Keep','Replace','Remove']});
  field(general,'native-access','Operation access',profile?.readOnly===false?'Allow reviewed writes':'Read only',{choices:['Read only','Allow reviewed writes'],help:'Enabling writes does not grant agent permission. Normal agent approvals and browser mutation review still apply.'});
  panel('Driver').append(el('p',template.name+' uses a bundled native client ('+template.clientVersion+'). No JDBC driver download is required.'));
  const auth=panel('Authentication & TLS');
  field(auth,'tls','TLS',options.tls===undefined?'Endpoint default':String(options.tls),{choices:['Endpoint default','true','false'],
    help:'Server identity and certificate validation stay enabled. Client certificates and custom trust stores are not yet available.'});
  if(template.transport==='mongodb'){
    field(auth,'authDatabase','Authentication database',options.authDatabase??'admin');
    field(auth,'authMechanism','Authentication mechanism',options.authMechanism??'SCRAM-SHA-256',{choices:['SCRAM-SHA-256','SCRAM-SHA-1']});
  }
  const network=panel('Network');
  field(network,'topology','Topology',options.topology??'standalone',{choices:template.transport==='mongodb'?['standalone','replica_set','sharded','srv']:['standalone','sentinel','cluster'],
    help:template.transport==='mongodb'?'Explicit topology; unsupported discovery fails rather than falling back.':'Cluster requires database 0. Sentinel uses its primary name; authenticated Sentinel endpoints are not yet supported.'});
  field(network,'seeds','Additional seed endpoints',(options.seeds??[]).join(', '),{help:'Optional comma-separated native scheme://host:port endpoints; at most 15 additional hosts. Keep TLS consistent. Leave empty for standalone and MongoDB SRV.'});
  if(template.transport==='redis')field(network,'sentinelMaster','Sentinel primary name',options.sentinelMaster??'',{help:'Required only for Sentinel. The main endpoint and seeds refer to Sentinel servers.'});
  for(const [key,label,value] of [['connectTimeoutMS','Connect timeout (ms)',10000],['socketTimeoutMS','Socket timeout (ms)',30000]])
    field(network,key,label,options[key]??value,{type:'number'});
  if(template.transport==='mongodb'){
    field(network,'replicaSet','Replica set name',options.replicaSet??'');
    field(network,'readPreference','Read preference',options.readPreference??'primary',{choices:['primary','primaryPreferred','secondary','secondaryPreferred','nearest']});
  }
  panel('Driver properties').append(el('p','Only verified native options are accepted. Arbitrary URI properties and JavaScript/shell expressions are not executed.'));
  const lifecycle=panel('Pool & lifecycle');
  field(lifecycle,'idleTimeoutMS','Idle client timeout (ms)',options.idleTimeoutMS??60000,{type:'number'});
  if(template.transport==='mongodb')field(lifecycle,'maximumPoolSize','Maximum connections',options.maximumPoolSize??2,{type:'number',help:'1–16 per native client; global DBA job admission still applies.'});
  lifecycle.append(el('p','Clients are lazy, revision-bound and shared. Redis operations borrow isolated connections; commands are never automatically replayed after disconnect.'));
}

export function nativeConnectionDraft({template,profile,get,shade}) {
  const result={templateId:template.id,transport:template.transport,name:get('name'),color:shade,url:get('url'),
    username:get('username'),readOnly:get('native-access')!=='Allow reviewed writes',nativeOptions:{}};
  if(profile?.id)result.connectionId=profile.id;
  for(const key of ['database','topology','authDatabase','authMechanism','replicaSet','readPreference','sentinelMaster'])
    if(get(key)!=='')result.nativeOptions[key]=get(key);
  if(get('seeds').trim())result.nativeOptions.seeds=get('seeds').split(',').map(value=>value.trim()).filter(Boolean);
  for(const key of ['connectTimeoutMS','socketTimeoutMS','maximumPoolSize','idleTimeoutMS'])
    if(get(key)!=='')result.nativeOptions[key]=Number(get(key));
  if(get('tls')!=='Endpoint default')result.nativeOptions.tls=get('tls')==='true';
  if(get('password-action')==='Remove')result.removePassword=true;
  else if(get('password')||get('password-action')==='Replace'&&profile)result.password=get('password');
  return result;
}
