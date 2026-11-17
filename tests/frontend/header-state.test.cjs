const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const root=path.resolve(__dirname,'../../novel-front/src/main/resources/static/javascript/site');
const jwt=(username,created=1)=>'header.'+Buffer.from(JSON.stringify({sub:JSON.stringify({id:'777',username}),created})).toString('base64url')+'.signature';
const response=data=>({ok:true,json:async()=>({code:200,data})});
const unauthenticated=()=>({ok:true,json:async()=>({code:1001})});
const failed=()=>({ok:false,status:500});
const flush=()=>new Promise(resolve=>setImmediate(resolve));
async function settled(){for(let i=0;i<12;i++)await flush();}
function element(){return{children:[],events:new Map(),attributes:{},textContent:'',append(...nodes){this.children.push(...nodes);},replaceChildren(...nodes){this.children=[...nodes];},setAttribute(key,value){this.attributes[key]=value;},addEventListener(key,callback){this.events.set(key,callback);}};}
async function surface({fetch,requiresLogin=false,initial='account-a'}={}){
  let cookie=initial?'Authorization='+encodeURIComponent(jwt(initial)):'';const store=new Map(),headers=[element(),element()],events=new Map(),inits=[];
  const document={documentElement:{dataset:{ready:'false'}},body:{dataset:{page:requiresLogin?'profile':'home'},hasAttribute:name=>name==='data-requires-login'&&requiresLogin},visibilityState:'visible',
    get cookie(){return cookie;},set cookie(value){cookie=value.includes('Max-Age=0')?'':value.split(';')[0];},querySelector:()=>null,
    querySelectorAll:selector=>selector==='[data-header-auth],[data-mobile-auth]'?headers:[],createElement:()=>element(),addEventListener(){},dispatchEvent(){}};
  const location={origin:'http://localhost',protocol:'http:',pathname:'/',search:'',hash:'',assign:value=>location.navigation=value};
  const context=vm.createContext({URL,URLSearchParams,AbortController,TextDecoder,Uint8Array,FormData,atob,setTimeout,clearTimeout,Math,Date,console,document,location,fetch,
    matchMedia:()=>({addEventListener(){}}),CustomEvent:class{constructor(type){this.type=type;}},localStorage:{getItem:key=>store.get(key)||null,setItem:(key,value)=>store.set(key,value),removeItem:key=>store.delete(key)},window:{addEventListener:(key,callback)=>events.set(key,callback)}});
  const modules=new Map();
  async function load(name){
    name=name.split('?')[0];if(modules.has(name))return modules.get(name);
    const exports={'./books.js':'initBooks','./account.js':'initAccount','./author.js':'initAuthor','../reader/global-player.js':'bootGlobalPlayer'};
    let mod;if(exports[name]){const exported=exports[name];mod=new vm.SyntheticModule([exported],function(){this.setExport(exported,async()=>inits.push(exported));},{context});}
    else mod=new vm.SourceTextModule(fs.readFileSync(path.join(root,name.replace(/^\.\//,'')),'utf8'),{context,identifier:name});
    modules.set(name,mod);await mod.link(specifier=>load(specifier));return mod;
  }
  const site=await load('./site.js');await site.evaluate();
  const api=modules.get('./api.js').namespace;
  return{api,context,document,headers,location,inits,events,labels:()=>headers.map(host=>host.children.map(node=>node.textContent).join('|'))};
}

test('real site header displays current user and exit after successful refresh',async()=>{
  const fresh=jwt('account-a',2);const tab=await surface({fetch:async()=>response({token:fresh,username:'account-a',nickName:'当前读者'})});await settled();
  assert.equal(tab.api.token(),fresh);assert.deepEqual(tab.labels(),['当前读者|退出','当前读者|退出']);assert.equal(tab.document.documentElement.dataset.authState,'verified');assert.equal(tab.document.documentElement.dataset.ready,'true');
});

test('refresh 500 preserves session, confirms identity through userInfo and exposes retry',async()=>{
  const calls=[];const tab=await surface({fetch:async url=>{calls.push(url.pathname);return url.pathname.endsWith('refreshToken')?failed():response({username:'account-a',nickName:'已验证读者'});}});await settled();
  assert.deepEqual(calls,['/user/refreshToken','/user/userInfo']);assert.equal(tab.api.token(),jwt('account-a'));
  assert.deepEqual(tab.labels(),['已验证读者|退出|登录续期暂时失败|重试','已验证读者|退出|登录续期暂时失败|重试']);assert.equal(tab.document.documentElement.dataset.authState,'refresh-error');
  tab.events.get('focus')();await settled();assert.equal(tab.document.documentElement.dataset.authState,'refresh-error');
  tab.context.fetch=async()=>response({token:jwt('account-a',3),username:'account-a'});tab.headers[0].children[3].events.get('click')();await settled();
  assert.equal(tab.document.documentElement.dataset.authState,'verified');assert.equal(tab.api.token(),jwt('account-a',3));
});

test('unverified server failure shows explicit state and prevents private page boot',async()=>{
  const tab=await surface({requiresLogin:true,fetch:async()=>failed()});await settled();
  assert.equal(tab.api.token(),jwt('account-a'));assert.equal(tab.location.navigation,undefined);assert.equal(tab.document.documentElement.dataset.ready,'error');assert.equal(tab.document.documentElement.dataset.authState,'error');assert.equal(tab.inits.length,0);
  assert.deepEqual(tab.labels(),['暂时无法确认登录|重试','暂时无法确认登录|重试']);
});

test('only a verified no-login result clears the cookie and redirects a private page',async()=>{
  const tab=await surface({requiresLogin:true,fetch:async()=>unauthenticated()});await settled();
  assert.equal(tab.api.token(),'');assert.match(tab.location.navigation,/^\/user\/login\.html/);assert.deepEqual(tab.labels(),['登录|注册','登录|注册']);assert.equal(tab.inits.length,0);
});

test('late refresh from account A cannot replace account B token or visible identity',async()=>{
  let release;const tab=await surface({fetch:url=>url.pathname.endsWith('refreshToken')?new Promise(resolve=>release=resolve):Promise.resolve(response({username:'account-b',nickName:'新读者'}))});
  tab.api.saveToken(jwt('account-b'),false,true);await settled();release(response({token:jwt('account-a',2),username:'account-a',nickName:'旧读者'}));await settled();
  assert.equal(tab.api.token(),jwt('account-b'));assert.deepEqual(tab.labels(),['新读者|退出','新读者|退出']);assert.equal(tab.document.documentElement.dataset.ready,'true');
});

test('late userInfo failure cannot clear the replacement account during fallback',async()=>{
  let release;let infoCalls=0;const tab=await surface({fetch:url=>url.pathname.endsWith('refreshToken')?Promise.resolve(failed()):++infoCalls===1?new Promise(resolve=>release=resolve):Promise.resolve(response({username:'account-b'}))});
  await settled();tab.api.saveToken(jwt('account-b'),false,true);await settled();release(unauthenticated());await settled();
  assert.equal(tab.api.token(),jwt('account-b'));assert.deepEqual(tab.labels(),['account-b|退出','account-b|退出']);assert.equal(tab.location.navigation,undefined);
});

test('invalid server response is shown and does not authenticate from token contents alone',async()=>{
  const tab=await surface({requiresLogin:true,fetch:async()=>response(null)});await settled();assert.equal(tab.document.documentElement.dataset.authState,'error');assert.equal(tab.inits.length,0);assert.equal(tab.api.token(),jwt('account-a'));
});

test('focus while initial refresh is pending cannot strand page initialization',async()=>{
  let release;const tab=await surface({requiresLogin:true,fetch:url=>url.pathname.endsWith('refreshToken')?new Promise(resolve=>release=resolve):Promise.resolve(response({username:'account-a'}))});
  tab.events.get('focus')();await settled();release(response({token:jwt('account-a',2),username:'account-a'}));await settled();
  assert.equal(tab.document.documentElement.dataset.ready,'true');assert.equal(tab.inits.length,3);assert.equal(tab.document.documentElement.dataset.authState,'verified');assert.equal(tab.api.token(),jwt('account-a',2));
});

test('successful focus identity check recovers initialization after transient private-page failure',async()=>{
  const tab=await surface({requiresLogin:true,fetch:async()=>failed()});await settled();assert.equal(tab.inits.length,0);
  tab.context.fetch=async()=>response({username:'account-a'});tab.events.get('focus')();await settled();
  assert.equal(tab.document.documentElement.dataset.ready,'true');assert.equal(tab.inits.length,3);
});
