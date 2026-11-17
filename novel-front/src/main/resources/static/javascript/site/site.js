import{$,$$,api,ApiError,token,saveToken,clearToken,safeOrigin,toast,login,plainText}from'./api.js?v=20261020.1';
import{authSnapshot,isCurrentAuth,onAuthChange,checkAuthState}from'./auth-state.js?v=20261008.1';
import{initBooks}from'./books.js?v=20261020.1';import{initAccount}from'./account.js?v=20261020.1';import{initAuthor}from'./author.js?v=20261020.1';
import{bootGlobalPlayer}from'../reader/global-player.js?v=20261020.1';
function theme(){const button=$('[data-theme-toggle]');const update=()=>{const dark=document.documentElement.dataset.theme==='dark';button?.setAttribute('aria-pressed',String(dark));button?.setAttribute('aria-label',dark?'切换日间模式':'切换夜间模式');};update();button?.addEventListener('click',()=>{const next=document.documentElement.dataset.theme==='dark'?'light':'dark';document.documentElement.dataset.theme=next;try{localStorage.setItem('novel.site.theme',next);}catch{}update();});matchMedia('(prefers-color-scheme: dark)').addEventListener('change',event=>{try{if(localStorage.getItem('novel.site.theme'))return;}catch{}document.documentElement.dataset.theme=event.matches?'dark':'light';update();});}
let leaving=false;
function logout(){leaving=true;clearToken();location.assign('/');}
function anonymousHeader(){document.documentElement.dataset.authState='anonymous';for(const host of $$('[data-header-auth],[data-mobile-auth]')){host.replaceChildren();for(const [text,url]of [['登录','/user/login.html'],['注册','/user/register.html']]){const link=document.createElement('a');link.textContent=text;link.href=url;host.append(link);}}}
let headerVersion=0,refreshFailure;
function headerMessage(host,message,retry){const status=document.createElement('span');status.textContent=message;status.setAttribute('role','status');status.setAttribute('data-auth-status','');host.append(status);if(retry){const button=document.createElement('button');button.type='button';button.textContent='重试';button.setAttribute('data-auth-retry','');button.addEventListener('click',retry);host.append(button);}}
function userHeader(data,message='',retry){document.documentElement.dataset.authState=message?'refresh-error':'verified';for(const host of $$('[data-header-auth],[data-mobile-auth]')){host.replaceChildren();const user=document.createElement('a');user.href='/user/userinfo.html';user.textContent=data.nickName||data.username||'个人中心';const quit=document.createElement('button');quit.type='button';quit.textContent='退出';quit.addEventListener('click',logout);host.append(user,quit);if(message)headerMessage(host,message,retry);}}
async function header(refresh=true){
  const version=++headerVersion,identity=authSnapshot();const current=()=>version===headerVersion&&isCurrentAuth(identity);const retry=()=>{if(current())syncIdentity().catch(bootError);};const t=token();if(!t){refreshFailure=null;anonymousHeader();if(document.body.hasAttribute('data-requires-login')){login();return false;}return true;}
  document.documentElement.dataset.authState='checking';for(const host of $$('[data-header-auth],[data-mobile-auth]')){host.replaceChildren();headerMessage(host,'正在确认登录…');}
  const validate=data=>{if(!data||typeof data.username!=='string'||!data.username)throw new ApiError('服务器返回格式异常，请稍后重试。','format','format');return data;};
  const expired=()=>{clearToken();anonymousHeader();if(document.body.hasAttribute('data-requires-login')){login();return false;}return true;};
  try{const data=validate(await api(refresh?'/user/refreshToken':'/user/userInfo',{}, {method:refresh?'POST':'GET',auth:false}));if(!current())return false;if(refresh){if(typeof data.token!=='string'||!data.token)throw new ApiError('服务器返回格式异常，请稍后重试。','format','format');let remember=false;try{remember=localStorage.getItem('autoLogin')==='1';}catch{}saveToken(data.token,remember);refreshFailure=null;}const failedRefresh=refreshFailure&&isCurrentAuth(refreshFailure);userHeader(data,failedRefresh?'登录续期暂时失败':'',failedRefresh?retry:undefined);return true;}
  catch(error){if(error.code==='auth-changed'||!current())return false;if(Number(error.code)===1001)return expired();
    if(refresh){refreshFailure=identity;try{const data=validate(await api('/user/userInfo',{}, {auth:false}));if(!current())return false;userHeader(data,'登录续期暂时失败',retry);return true;}catch(fallback){if(fallback.code==='auth-changed'||!current())return false;if(Number(fallback.code)===1001)return expired();error=fallback;}}
    document.documentElement.dataset.authState='error';for(const host of $$('[data-header-auth],[data-mobile-auth]')){host.replaceChildren();headerMessage(host,'暂时无法确认登录',retry);}toast(error.message);if(document.body.hasAttribute('data-requires-login')){document.documentElement.dataset.ready='error';return false;}return true;
  }
}
function common(){
  document.addEventListener('submit',event=>{if(document.documentElement.dataset.ready!=='true'&&!event.target.classList.contains('site-search')&&!event.target.closest('#site-dialog')){event.preventDefault();toast('页面正在准备，请稍后再提交。');}});
  theme();$$('[data-logout]').forEach(b=>b.addEventListener('click',logout));
  const menu=$('[data-menu-toggle]');menu?.addEventListener('click',()=>{const open=menu.getAttribute('aria-expanded')!=='true';menu.setAttribute('aria-expanded',String(open));menu.setAttribute('aria-label',open?'关闭导航':'打开导航');$('#primary-nav').dataset.open=String(open);});document.addEventListener('keydown',event=>{if(event.key==='Escape'&&menu?.getAttribute('aria-expanded')==='true')menu.click();});
  $('[data-dialog-close]')?.addEventListener('click',()=>$('#site-dialog').close('cancel'));
  const search=$('#searchKey');if(search)search.value=new URLSearchParams(location.search).get('k')||'';
  $$('[data-rich-description]').forEach(el=>{el.textContent=plainText(el.textContent);});
  $$('[data-password-toggle]').forEach(b=>b.addEventListener('click',()=>{const input=document.getElementById(b.dataset.passwordToggle);const visible=input.type==='password';input.type=visible?'text':'password';b.textContent=visible?'隐藏':'显示';b.setAttribute('aria-label',visible?'隐藏密码':'显示密码');b.setAttribute('aria-pressed',String(visible));}));
  const failImage=img=>{if(img.closest('.cover'))img.hidden=true;else if(img.classList.contains('avatar')){img.src='/images/man.png';}};
  document.addEventListener('error',event=>{if(event.target instanceof HTMLImageElement)failImage(event.target);},true);$$('.cover img').forEach(img=>{if(img.complete&&!img.naturalWidth)failImage(img);});
  // Keep escaped rich-text descriptions as plain text; never inject API content as markup.
}
common();
if(document.querySelector('meta[name="reader-tts-enabled"]')?.content==='true')bootGlobalPlayer();
let initialized=false,initPromise,identityVersion=0;
async function initPage(){if(!initialized){initialized=true;const page=document.body.dataset.page;initPromise=Promise.all([initBooks(page),initAccount(page),initAuthor(page)]);}await initPromise;}
function bootError(error){if(error.code==='auth-changed')return;toast(error.message||'页面暂时无法加载，请重试。');document.documentElement.dataset.ready='error';}
async function syncIdentity(change,refresh=!change){
  if(leaving)return;const version=++identityVersion,identity=authSnapshot(),page=document.body.dataset.page;
  if(change){anonymousHeader();document.documentElement.dataset.ready='false';document.dispatchEvent(new CustomEvent('novel:auth-change'));
    if(['login','register'].includes(page)&&token()&&change.reason!=='local'){location.replace(safeOrigin());return;}
    if(initialized&&document.body.hasAttribute('data-requires-login')&&!['history','shelf','profile'].includes(page)&&token()){location.reload();return;}
  }
  if(!await header(refresh))return;if(version!==identityVersion||!isCurrentAuth(identity))return;
  await initPage();if(version===identityVersion&&isCurrentAuth(identity))document.documentElement.dataset.ready='true';
}
onAuthChange(change=>{syncIdentity(change).catch(bootError);});
let lastFocus=0;
function recheckIdentity(){if(checkAuthState())return;if(document.documentElement.dataset.authState==='checking'||Date.now()-lastFocus<1000)return;lastFocus=Date.now();syncIdentity(undefined,false).catch(bootError);}
window.addEventListener('focus',recheckIdentity);
window.addEventListener('pageshow',event=>{if(event.persisted)recheckIdentity();});
document.addEventListener('visibilitychange',()=>{if(document.visibilityState==='visible')recheckIdentity();});
syncIdentity().catch(bootError);
