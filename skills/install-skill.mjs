#!/usr/bin/env node
import {lstat, mkdir, mkdtemp, readFile, readdir, realpath, rename, rm, writeFile} from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import {fileURLToPath} from 'node:url';

const directory=path.dirname(fileURLToPath(import.meta.url));
const locations=Object.fromEntries((await readFile(path.join(directory,'clients.properties'),'utf8'))
  .split(/\r?\n/).filter(line=>line.trim() && !line.startsWith('#')).map(line=>{
    const separator=line.indexOf('=');return [line.slice(0,separator),line.slice(separator+1)];
  }));
const names=locations.clients.split(',');
export const clients=Object.freeze(Object.fromEntries(names.map(name=>[name,locations[name+'.project']])));
const source=path.join(directory,'code-graph');

export function selectClients(value) {
  if(typeof value!=='string' || !value.trim()) throw new Error('Choose --client all, none, or comma-separated clients: '+names.join(','));
  const normalized=value.trim().toLowerCase();
  if(normalized==='all')return [...names];
  if(normalized==='none')return [];
  const selected=normalized.split(',').map(item=>item.trim());
  if(selected.some(item=>!names.includes(item)) || new Set(selected).size!==selected.length)
    throw new Error('Choose unique clients: '+names.join(',')+'; all and none must be used alone');
  return selected;
}

async function exists(location) {
  try { return await lstat(location); } catch (error) { if(error.code==='ENOENT') return null; throw error; }
}
async function inventory(directory, prefix='', budget={files:0,bytes:0}) {
  if((await lstat(directory)).isSymbolicLink())throw new Error('Skill directories must not contain symlinks');
  const files=[];
  for(const item of (await readdir(directory,{withFileTypes:true})).sort((a,b)=>a.name.localeCompare(b.name))) {
    const relative=path.join(prefix,item.name), absolute=path.join(directory,item.name);
    if(item.isSymbolicLink()) throw new Error('Skill directories must not contain symlinks');
    if(item.isDirectory()) files.push(...await inventory(absolute,relative,budget));
    else if(item.isFile()) {
      const size=(await lstat(absolute)).size;
      if(++budget.files>128 || size>2*1024*1024 || (budget.bytes+=size)>8*1024*1024)
        throw new Error('Skill inventory exceeds safe installation bounds');
      files.push([relative,await readFile(absolute)]);
    }
    else throw new Error('Unsupported file in skill directory');
  }
  return files;
}

async function safeDirectories(target) {
  const absolute=path.resolve(target);
  let current=path.parse(absolute).root;
  for(const part of absolute.slice(current.length).split(path.sep)) {
    current=path.join(current,part);
    const stat=await exists(current);
    if(stat && (stat.isSymbolicLink() || !stat.isDirectory() || await realpath(current)!==current))
      throw new Error('Refusing a linked or non-directory skill destination');
  }
}

export async function installSkill({client,project,global=false,dryRun=false,userHome=os.homedir(),environment=process.env}) {
  if(!Object.hasOwn(clients,client)) throw new Error('Choose codex, copilot, claude or windsurf');
  if(global && project)throw new Error('--global and --project are mutually exclusive');
  if(!global && !project) throw new Error('An explicit --project directory or --global is required');
  const root=await realpath(global?userHome:project);
  if(!(await lstat(root)).isDirectory()) throw new Error('Skill root must be a directory');
  const overrideName=locations[client+'.homeEnv'];
  const override=global && overrideName?environment[overrideName]:null;
  if(override && override.trim() && !path.isAbsolute(override))throw new Error('Client configuration directory must be absolute: '+client);
  const base=override && override.trim()?override:path.join(root,locations[client+(global?'.global':'.project')]);
  const parent=path.join(path.resolve(base),'skills');
  await safeDirectories(parent);
  const destination=path.join(parent,'code-graph'), current=await exists(destination);
  const original=await inventory(source);
  if(current) {
    if(current.isSymbolicLink() || !current.isDirectory()) throw new Error('Refusing to replace the existing destination');
    await safeDirectories(destination);
    const existing=await inventory(destination);
    if(original.length===existing.length && original.every(([name,bytes],i)=>name===existing[i][0] && bytes.equals(existing[i][1])))
      return {status:'already-installed',destination};
    throw new Error('Existing code-graph skill differs; preserve it and merge updates manually. Nothing was overwritten.');
  }
  if(dryRun) return {status:'would-install',destination};
  await mkdir(parent,{recursive:true});
  await safeDirectories(parent);
  const stage=await mkdtemp(path.join(parent,'.code-graph-install-'));
  try {
    for(const [name,bytes] of original) {
      const file=path.join(stage,'code-graph',name);
      await mkdir(path.dirname(file),{recursive:true});await writeFile(file,bytes,{flag:'wx'});
    }
    // No recursive writes into the final location, and never intentionally replace a user skill.
    if(await exists(destination)) throw new Error('Destination appeared during installation; nothing was replaced');
    await rename(path.join(stage,'code-graph'),destination);
  } finally { await rm(stage,{recursive:true,force:true}); }
  return {status:'installed',destination};
}

export async function installSkills(options) {
  const results=[];
  for(const client of selectClients(options.client)) {
    try { results.push({client,...await installSkill({...options,client})}); }
    catch(error) { results.push({client,status:'failed',error:error.message}); }
  }
  return results;
}
export const usage='Usage: node skills/install-skill.mjs --client all|none|codex,copilot,claude,windsurf (--global | --project PATH) [--dry-run]';
export function parseOptions(args) {
  const options={};
  for(let i=0;i<args.length;i++) {
    const flag=args[i], key=flag==='--dry-run'?'dryRun':flag.slice(2);
    if(Object.hasOwn(options,key))throw new Error('Duplicate option: '+flag);
    if(flag==='--dry-run'||flag==='--global'||flag==='--help')options[key]=true;
    else if(flag==='--client'||flag==='--project') {
      const value=args[++i];
      if(!value || value.startsWith('--'))throw new Error('Expected one value for '+flag);
      options[key]=value;
    } else throw new Error(usage);
  }
  if(!options.help) {
    selectClients(options.client);
    if(Boolean(options.global)===Boolean(options.project))throw new Error('Choose exactly one of --global or --project PATH');
  }
  return options;
}

if(process.argv[1] && path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  try {
    const options=parseOptions(process.argv.slice(2));
    if(options.help)console.log(usage+'\nOptional guidance only; no MCP setup, permissions, server startup, or network access. Existing differing skills are never overwritten.');
    else {
      const results=await installSkills(options);
      // Preserve the original single-client CLI response shape.
      console.log(JSON.stringify(selectClients(options.client).length===1?results[0]:results));
      if(results.some(result=>result.status==='failed'))process.exitCode=1;
    }
  } catch(error) { console.error(error.message); process.exitCode=1; }
}
