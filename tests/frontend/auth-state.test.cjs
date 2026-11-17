const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const root=path.resolve(__dirname,'../../novel-front/src/main/resources/static/javascript/site');
const jwt=(username,created=1)=>'header.'+Buffer.from(JSON.stringify({sub:JSON.stringify({id:'2107666245426266112',username}),created})).toString('base64url')+'.signature';
function environment({broadcast=true}={}){
  const store=new Map(),tabs=[],messages=[];let cookie='';
  async function tab(){
    const events=new Map(),modules=new Map();
    const context=vm.createContext({URL,URLSearchParams,AbortController,TextDecoder,Uint8Array,FormData,atob,setTimeout,clearTimeout,Math,Date,console});
    const surface={context,events};tabs.push(surface);
    const emit=(name,data)=>{for(const listener of events.get(name)||[])listener(data);};
    context.window={addEventListener:(name,fn)=>{if(!events.has(name))events.set(name,[]);events.get(name).push(fn);}};
    context.document={get cookie(){return cookie;},set cookie(value){cookie=value.includes('Max-Age=0')?'':value.split(';')[0];}};
    context.location={origin:'http://localhost',protocol:'http:',assign:value=>surface.navigation=value};
    context.localStorage={getItem:key=>store.get(key)||null,removeItem:key=>store.delete(key),setItem(key,value){store.set(key,value);for(const target of tabs)if(target!==surface)queueMicrotask(()=>{for(const fn of target.events.get('storage')||[])fn({key,newValue:value});});}};
    const channels=[];
    if(broadcast)context.BroadcastChannel=class{constructor(){surface.channel=this;}addEventListener(name,fn){channels.push(fn);}postMessage(data){messages.push(data);for(const target of tabs)if(target!==surface&&target.channel)queueMicrotask(()=>target.channel.receive(data));}receive(data){for(const fn of channels)fn({data});}};
    context.fetch=async()=>({ok:true,json:async()=>({code:200,data:{}})});
    async function load(name){name=name.split('?')[0];if(modules.has(name))return modules.get(name);const mod=new vm.SourceTextModule(fs.readFileSync(path.join(root,name),'utf8'),{context,identifier:name});modules.set(name,mod);await mod.link(specifier=>load(specifier.replace(/^\.\//,'')));await mod.evaluate();return mod;}
    const api=await load('api.js');const auth=modules.get('auth-state.js');
    return {api:api.namespace,auth:auth.namespace,context,emit,get navigation(){return surface.navigation;}};
  }
  return {tab,messages};
}
const flush=()=>new Promise(resolve=>setImmediate(resolve));

test('login and logout synchronize once across tabs without putting credentials in notifications',async()=>{
  const env=environment(),a=await env.tab(),b=await env.tab();let changes=0;b.auth.onAuthChange(()=>changes++);
  const value=jwt('19910710411');a.api.saveToken(value,false,true);await flush();assert.equal(changes,1);assert.equal(b.api.token(),value);
  assert.equal(JSON.stringify(env.messages).includes(value),false);assert.equal(JSON.stringify(env.messages).includes('19910710411'),false);
  a.api.clearToken();await flush();assert.equal(changes,2);assert.equal(b.api.token(),'');
});

test('same-account token renewal preserves in-flight identity and sends no account-switch event',async()=>{
  const env=environment(),a=await env.tab(),b=await env.tab();a.api.saveToken(jwt('19910710411'),false,true);await flush();
  let changes=0;b.auth.onAuthChange(()=>changes++);const current=b.auth.authSnapshot();
  a.api.saveToken(jwt('19910710411',2),false);await flush();assert.equal(changes,0);assert.equal(b.auth.isCurrentAuth(current),true);
});

test('an old history response is discarded after another tab changes the account',async()=>{
  const env=environment(),a=await env.tab(),b=await env.tab();a.api.saveToken(jwt('19910710411'),false,true);await flush();
  let release;b.context.fetch=()=>new Promise(resolve=>release=resolve);
  const pending=b.api.api('/user/listReadHistoryByPage');a.api.saveToken(jwt('19910710412'),false,true);await flush();
  release({ok:true,json:async()=>({code:200,data:{list:['old-private-history']}})});
  await assert.rejects(pending,error=>error.code==='auth-changed');
});

test('an old expired-session response cannot redirect or clear the new account',async()=>{
  const env=environment(),a=await env.tab(),b=await env.tab();a.api.saveToken(jwt('19910710411'),false,true);await flush();
  let release;b.context.fetch=()=>new Promise(resolve=>release=resolve);
  const pending=b.api.api('/user/refreshToken',{}, {method:'POST',auth:false});a.api.saveToken(jwt('19910710412'),false,true);await flush();
  release({ok:true,json:async()=>({code:1001})});await assert.rejects(pending,error=>error.code==='auth-changed');
  assert.equal(b.api.token(),jwt('19910710412'));assert.equal(b.navigation,undefined);
});

test('storage fallback synchronizes account changes when BroadcastChannel is unavailable',async()=>{
  const env=environment({broadcast:false}),a=await env.tab(),b=await env.tab();let changes=0;b.auth.onAuthChange(()=>changes++);
  a.api.saveToken(jwt('19910710411'),false,true);await flush();assert.equal(changes,1);assert.equal(b.auth.authSnapshot().identity,'user:19910710411');
});

test('focus check detects changed cookies even without a login/logout notification',async()=>{
  const env=environment(),a=await env.tab();let changes=0;a.auth.onAuthChange(()=>changes++);
  a.api.saveToken(jwt('19910710412'));assert.equal(a.auth.checkAuthState(),true);assert.equal(changes,1);
  assert.equal(a.auth.checkAuthState(),false);
});

test('an old refresh success cannot overwrite a newly signed-in account',async()=>{
  const env=environment(),a=await env.tab(),b=await env.tab();a.api.saveToken(jwt('account-a'),false,true);await flush();
  let release;b.context.fetch=()=>new Promise(resolve=>release=resolve);
  const pending=b.api.api('/user/refreshToken',{}, {method:'POST',auth:false});a.api.saveToken(jwt('account-b'),false,true);await flush();
  release({ok:true,json:async()=>({code:200,data:{token:jwt('account-a',2),username:'account-a'}})});
  await assert.rejects(pending,error=>error.code==='auth-changed');assert.equal(b.api.token(),jwt('account-b'));
});

test('API errors distinguish HTTP, malformed response, timeout, cancellation and network failure',async()=>{
  const tab=await environment().tab();
  tab.context.fetch=async()=>({ok:false,status:500});await assert.rejects(tab.api.api('/public'),error=>error.code===500&&error.kind==='http');
  tab.context.fetch=async()=>({ok:true,json:async()=>{throw new SyntaxError('HTML instead of JSON');}});
  await assert.rejects(tab.api.api('/public'),error=>error.code==='format'&&error.kind==='format');
  for(const data of [null,[],{}, {code:null}]){tab.context.fetch=async()=>({ok:true,json:async()=>data});await assert.rejects(tab.api.api('/public'),error=>error.code==='format');}
  tab.context.fetch=async()=>{throw new TypeError('connection lost');};await assert.rejects(tab.api.api('/public'),error=>error.code==='network');
  tab.context.fetch=(url,{signal})=>new Promise((resolve,reject)=>signal.addEventListener('abort',()=>reject(Object.assign(new Error('aborted'),{name:'AbortError'})),{once:true}));
  const external=new AbortController();await assert.rejects(tab.api.api('/public',{}, {signal:external.signal,timeout:5}),error=>error.code==='timeout');
  const canceled=new AbortController(),pending=tab.api.api('/public',{}, {signal:canceled.signal});canceled.abort();await assert.rejects(pending,error=>error.code==='cancelled');
});

test('a late network failure after switching accounts is classified as stale identity',async()=>{
  const tab=await environment().tab();tab.api.saveToken(jwt('account-a'),false,true);let fail;
  tab.context.fetch=()=>new Promise((resolve,reject)=>fail=reject);const pending=tab.api.api('/user/userInfo',{}, {auth:false});
  tab.api.saveToken(jwt('account-b'),false,true);fail(new TypeError('old connection failed'));
  await assert.rejects(pending,error=>error.code==='auth-changed');assert.equal(tab.api.token(),jwt('account-b'));
});
