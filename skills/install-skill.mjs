#!/usr/bin/env node
import {cp, lstat, mkdir, mkdtemp, readFile, readdir, realpath, rename, rm} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

export const clients = Object.freeze({codex:'.agents', copilot:'.github', claude:'.claude', windsurf:'.windsurf'});
const source = path.join(path.dirname(fileURLToPath(import.meta.url)), 'code-graph');

async function exists(location) {
  try { return await lstat(location); } catch (error) { if(error.code==='ENOENT') return null; throw error; }
}
async function inventory(directory, prefix='') {
  const files=[];
  for(const item of (await readdir(directory,{withFileTypes:true})).sort((a,b)=>a.name.localeCompare(b.name))) {
    const relative=path.join(prefix,item.name), absolute=path.join(directory,item.name);
    if(item.isSymbolicLink()) throw new Error('Skill directories must not contain symlinks');
    if(item.isDirectory()) files.push(...await inventory(absolute,relative));
    else if(item.isFile()) files.push([relative,(await readFile(absolute)).toString('base64')]);
    else throw new Error('Unsupported file in skill directory');
  }
  return files;
}

export async function installSkill({client,project,dryRun=false}) {
  if(!Object.hasOwn(clients,client)) throw new Error('Choose codex, copilot, claude or windsurf');
  if(!project) throw new Error('An explicit --project directory is required');
  const root=await realpath(project);
  if(!(await lstat(root)).isDirectory()) throw new Error('Project must be a directory');
  const parts=[clients[client],'skills'];
  let parent=root;
  for(const part of parts) {
    parent=path.join(parent,part);
    const stat=await exists(parent);
    if(stat && (stat.isSymbolicLink() || !stat.isDirectory())) throw new Error('Refusing a linked or non-directory skill destination');
  }
  const destination=path.join(parent,'code-graph'), current=await exists(destination);
  const original=await inventory(source);
  if(current) {
    if(current.isSymbolicLink() || !current.isDirectory()) throw new Error('Refusing to replace the existing destination');
    if(JSON.stringify(original)===JSON.stringify(await inventory(destination))) return {status:'already-installed',destination};
    throw new Error('Existing code-graph skill differs; preserve it and merge updates manually. Nothing was overwritten.');
  }
  if(dryRun) return {status:'would-install',destination};
  await mkdir(parent,{recursive:true});
  if(await realpath(parent)!==parent) throw new Error('Destination changed to a linked directory');
  const stage=await mkdtemp(path.join(parent,'.code-graph-install-'));
  try {
    await cp(source,path.join(stage,'code-graph'),{recursive:true,errorOnExist:true,force:false});
    // No recursive writes into the final location, and never intentionally replace a user skill.
    if(await exists(destination)) throw new Error('Destination appeared during installation; nothing was replaced');
    await rename(path.join(stage,'code-graph'),destination);
  } finally { await rm(stage,{recursive:true,force:true}); }
  return {status:'installed',destination};
}

if(process.argv[1] && path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  try {
    const options={};
    for(let i=2;i<process.argv.length;i++) {
      const flag=process.argv[i];
      if(flag==='--dry-run') options.dryRun=true;
      else if(flag==='--client'||flag==='--project') {
        const key=flag.slice(2), value=process.argv[++i];
        if(!value || value.startsWith('--') || Object.hasOwn(options,key)) throw new Error('Expected one value for '+flag);
        options[key]=value;
      } else throw new Error('Usage: node skills/install-skill.mjs --client codex|copilot|claude|windsurf --project PATH [--dry-run]');
    }
    console.log(JSON.stringify(await installSkill(options)));
  } catch(error) { console.error(error.message); process.exitCode=1; }
}
