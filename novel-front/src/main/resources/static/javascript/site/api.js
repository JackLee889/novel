import{authToken,authSnapshot,isCurrentAuth,announceAuthChange}from'./auth-state.js?v=20261008.1';
import{assertRequestAllowed,rateLimitResponse,RateLimitError,bindRateLimitRetry}from'./rate-limit.js?v=20261020.1';
export const $=(selector,root=document)=>root.querySelector(selector);
export const $$=(selector,root=document)=>[...root.querySelectorAll(selector)];
export const esc=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
export const id=value=>String(value??''); // Never coerce book/chapter/user IDs to Number.
export function plainText(value){const doc=new DOMParser().parseFromString(String(value??'').replace(/<br\s*\/?\s*>/gi,'\n').replace(/<\/p>/gi,'\n'),'text/html');doc.querySelectorAll('script,style,iframe').forEach(el=>el.remove());return doc.body.textContent?.trim()??'';}
export function safeImage(value){if(!value)return '';try{const u=new URL(value,location.origin);return ['https:','http:'].includes(u.protocol)?u.href:'';}catch{return '';}}
export function number(value,unit=''){const n=Number(value||0);return Number.isFinite(n)?(n>=10000?(n/10000).toFixed(n<100000?1:0)+'万':n.toLocaleString('zh-CN'))+unit:'—';}
export function token(){return authToken();}
export function saveToken(value,remember=false,identityChange=false){const expires=remember?'; Max-Age=604800':'';document.cookie=`Authorization=${encodeURIComponent(value)}; Path=/; SameSite=Lax${expires}${location.protocol==='https:'?'; Secure':''}`;try{localStorage.setItem('autoLogin',remember?'1':'0');}catch{}if(identityChange)announceAuthChange();}
export function clearToken(){document.cookie='Authorization=; Max-Age=0; Path=/; SameSite=Lax';try{localStorage.removeItem('autoLogin');}catch{}announceAuthChange();}
export function login(){const origin=location.pathname+location.search+location.hash;location.assign('/user/login.html?originUrl='+encodeURIComponent(origin));}
export function safeOrigin(){try{const url=new URL(new URLSearchParams(location.search).get('originUrl')||'/',location.origin);return url.origin===location.origin&&url.pathname!='/user/login.html'&&url.pathname!='/user/register.html'?url.pathname+url.search+url.hash:'/';}catch{return '/';}}
export class ApiError extends Error{constructor(message,code,kind='business'){super(message);this.code=code;this.kind=kind;}}
export async function api(path,params={},options={}){
  assertRequestAllowed(path);
  const method=options.method||'GET';const ctrl=new AbortController();let timedOut=false;const timer=setTimeout(()=>{timedOut=true;ctrl.abort();},options.timeout||25000);
  const cancel=()=>ctrl.abort();options.signal?.addEventListener('abort',cancel,{once:true});if(options.signal?.aborted)cancel();
  const url=new URL(path,location.origin);const identity=authSnapshot();const guarded=url.pathname.startsWith('/user/')&&!['/user/login','/user/register'].includes(url.pathname);const ensureCurrent=()=>{if(guarded&&!isCurrentAuth(identity))throw new ApiError('登录账号已变化，请重新加载。','auth-changed','auth');};const settings={method,credentials:'same-origin',signal:ctrl.signal,headers:{Accept:'application/json'}};
  if(method==='GET'){for(const[k,v]of Object.entries(params))if(v!==''&&v!=null)url.searchParams.set(k,String(v));}
  else if(params instanceof FormData)settings.body=params;
  else{settings.body=new URLSearchParams();for(const[k,v]of Object.entries(params))if(v!=null)settings.body.append(k,String(v));settings.headers['Content-Type']='application/x-www-form-urlencoded;charset=UTF-8';}
  try{
    const response=await fetch(url,settings);ensureCurrent();const limited=rateLimitResponse(response,url);if(limited)throw limited;if(!response.ok)throw new ApiError(response.status===403?'当前操作不可用，请稍后重试。':`请求未完成（${response.status}），请重试。`,response.status,'http');
    let result;try{result=await response.json();}catch{ensureCurrent();throw new ApiError('服务器返回格式异常，请稍后重试。','format','format');}ensureCurrent();
    if(!result||typeof result!=='object'||Array.isArray(result)||!['string','number'].includes(typeof result.code)||!/^\d+$/.test(String(result.code)))throw new ApiError('服务器返回格式异常，请稍后重试。','format','format');
    if(Number(result.code)!==200&&!(options.legacy&&Number(result.code)===1)){
      if(Number(result.code)===1001&&options.auth!==false){login();throw new ApiError('请先登录',1001);}
      throw new ApiError(result.msg||'操作未完成，请稍后重试。',result.code);
    }
    return result.data;
  }catch(error){ensureCurrent();if(error.name==='AbortError')throw new ApiError(timedOut?'请求超时，请检查网络后重试。':'请求已取消。',timedOut?'timeout':'cancelled',timedOut?'timeout':'cancelled');if(error instanceof ApiError||error instanceof RateLimitError)throw error;throw new ApiError('网络连接失败，请稍后重试。','network','network');}
  finally{clearTimeout(timer);options.signal?.removeEventListener('abort',cancel);}
}
let toastTimer;
export function toast(message){const box=$('#site-toast');if(!box)return;box.textContent=message;box.hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>box.hidden=true,4500);}
export function state(host,title,description='',retry){if(!host)return;host.innerHTML=`<div class="state-panel"><h3>${esc(title)}</h3><p>${esc(description)}</p>${retry?'<button class="button" data-retry>重新加载</button>':''}</div>`;if(retry)$('[data-retry]',host).addEventListener('click',retry);}
export function failure(host,error,retry){state(host,error.code===429?'访问过于频繁':'暂时没能加载',error.message,retry);if(error.code===429)bindRateLimitRetry($('[data-retry]',host));}
export function loading(host){if(host){host.innerHTML='<p class="loading-line">正在加载…</p>';host.setAttribute('aria-busy','true');}}
export function loaded(host){host?.removeAttribute('aria-busy');}
export function paginate(host,data,callback){
  if(!host)return;const curr=Number(data.pageNum)||1,total=Number(data.total)||0,size=Number(data.pageSize)||12,pages=Math.max(1,Math.ceil(total/size));host.innerHTML='';if(pages<=1)return;
  const button=(label,page,current=false)=>{const b=document.createElement('button');b.type='button';b.textContent=label;b.disabled=page<1||page>pages;if(current)b.setAttribute('aria-current','page');b.addEventListener('click',()=>callback(page));host.append(b);};
  button('上一页',curr-1);for(let p=Math.max(1,curr-1);p<=Math.min(pages,curr+1);p++)button(String(p),p,p===curr);const span=document.createElement('span');span.className='page-extra';span.textContent=`共 ${pages} 页`;host.append(span);button('下一页',curr+1);
}
export async function dialog(title,message,{input='',inputLabel='输入内容',confirm='确认',cancel='取消',showInput=false}={}){
  const el=$('#site-dialog');if(!el)return null;if(el.open)el.close('cancel');$('#dialog-title').textContent=title;$('#dialog-content').textContent=message;const field=$('#dialog-input');field.value=input;field.hidden=!showInput;field.setAttribute('aria-label',inputLabel);const buttons=$$('button[value]',el);buttons[0].textContent=cancel;buttons[1].textContent=confirm;
  const focus=document.activeElement;el.returnValue='';el.showModal();if(showInput){field.focus();field.select();}
  return new Promise(resolve=>{el.addEventListener('close',()=>{focus?.focus();resolve(el.returnValue==='confirm'?(showInput?field.value:true):null);},{once:true});});
}
export function bindForm(form,handler){
  if(!form)return;form.addEventListener('submit',async event=>{event.preventDefault();if(form.dataset.busy==='true'||!form.reportValidity())return;const status=$('[data-form-status],#LabErr',form);if(status){status.textContent='';status.classList.remove('success');}const button=$('[type=submit]',form);const label=button?.textContent;form.dataset.busy='true';if(button){button.disabled=true;button.textContent='正在提交…';}
    try{await handler(new FormData(form),form);}
    catch(error){if(status){status.textContent=error.message;status.classList.remove('success');}else toast(error.message);}
    finally{form.dataset.busy='false';if(button){button.disabled=false;button.textContent=label;}}
  });
}
export function formSuccess(message){const status=$('#LabErr')||$('[data-form-status]');if(status){status.textContent=message;status.classList.add('success');}toast(message);}
export async function upload(file){if(!file)return '';if(!['image/jpeg','image/png','image/gif','image/webp'].includes(file.type))throw new ApiError('请选择 JPG、PNG、GIF 或 WebP 图片。','file');const data=new FormData();data.append('file',file);return api('/file/picUpload',data,{method:'POST',timeout:60000});}
export function coverHtml(src,name,extra=''){const url=safeImage(src);return `<span class="cover"><span class="cover-fallback" aria-hidden="true">${esc(name)}</span>${url?`<img src="${esc(url)}" alt="" width="150" height="210" loading="lazy" ${extra}>`:''}</span>`;}
