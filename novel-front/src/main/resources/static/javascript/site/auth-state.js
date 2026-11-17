// This decoded identity is only a UI change detector; the server authenticates every request.
const storageKey='novel.site.auth.revision';
const listeners=new Set();
let fallbackRevision='',channel;
export function authToken(){
  try{const item=document.cookie.split(';').map(v=>v.trim()).find(v=>v.startsWith('Authorization='));return item?decodeURIComponent(item.slice(14)):'';}catch{return '';}
}
function identity(){
  const value=authToken();if(!value)return '';
  try{const part=value.split('.')[1].replace(/-/g,'+').replace(/_/g,'/');const bytes=Uint8Array.from(atob(part),c=>c.charCodeAt(0));const payload=JSON.parse(new TextDecoder().decode(bytes));const subject=JSON.parse(payload.sub);if(subject.username)return 'user:'+String(subject.username);}catch{}
  return 'opaque:'+value;
}
function revision(){try{return localStorage.getItem(storageKey)||fallbackRevision;}catch{return fallbackRevision;}}
export function authSnapshot(){return {identity:identity(),revision:revision()};}
export function isCurrentAuth(snapshot){const current=authSnapshot();return snapshot.identity===current.identity&&snapshot.revision===current.revision;}
let observed=authSnapshot();
function notify(reason){
  const current=authSnapshot();if(current.identity===observed.identity&&current.revision===observed.revision)return false;
  observed=current;for(const listener of listeners)listener({reason});return true;
}
export function onAuthChange(listener){listeners.add(listener);return ()=>listeners.delete(listener);}
export function checkAuthState(){return notify('check');}
export function announceAuthChange(){
  const nonce=Date.now().toString(36)+'.'+Math.random().toString(36).slice(2);
  fallbackRevision=nonce;try{localStorage.setItem(storageKey,nonce);}catch{}
  // Notifications carry a revision only, never credentials or user data.
  try{channel?.postMessage({revision:nonce});}catch{}
  notify('local');
}
try{channel=new BroadcastChannel('novel.site.auth');channel.addEventListener('message',event=>{if(typeof event.data?.revision!=='string')return;fallbackRevision=event.data.revision;notify('channel');});}catch{}
window.addEventListener('storage',event=>{if(event.key===storageKey){fallbackRevision=event.newValue||'';notify('storage');}});
