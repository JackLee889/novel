import test from 'node:test';
import assert from 'node:assert/strict';
import {applyChapterMetadata} from '../../main/resources/static/javascript/reader/seo.js';

function documentFixture() {
  const nodes=[];
  const doc={title:'旧章节',nodes,head:{append(node){nodes.push(node);}},createElement(tag){return {tag,attributes:{},setAttribute(k,v){this.attributes[k]=v;},getAttribute(k){return this.attributes[k];},remove(){const i=nodes.indexOf(this);if(i>=0)nodes.splice(i,1);}};},querySelector(selector){
    if(selector==='#seo-jsonld')return nodes.find(n=>n.id==='seo-jsonld')||null;
    const meta=selector.match(/^meta\[name="(.+)"\]$/);if(meta)return nodes.find(n=>n.tag==='meta'&&n.attributes.name===meta[1])||null;
    return nodes.find(n=>n.tag==='link'&&n.attributes.rel==='canonical')||null;
  }};return doc;
}

test('快速翻章同时更新 title 摘要 关键词 canonical robots 与结构化数据',()=>{
  const doc=documentFixture();
  applyChapterMetadata({title:'第二章 - 原创书',description:'公开第二章摘要',keywords:'原创书,第二章',canonical:'https://xs001.xyz/book/9007199254740993/9007199254741002.html',robots:'index,follow',jsonLd:JSON.stringify({'@context':'https://schema.org','@graph':[{'@type':'BreadcrumbList',name:'第二章'}]})},doc);
  assert.equal(doc.title,'第二章 - 原创书');assert.equal(doc.querySelector('meta[name="description"]').getAttribute('content'),'公开第二章摘要');
  assert.equal(doc.querySelector('meta[name="keywords"]').getAttribute('content'),'原创书,第二章');assert.equal(doc.querySelector('meta[name="robots"]').getAttribute('content'),'index,follow');
  assert.equal(doc.querySelector('link[rel="canonical"]').getAttribute('href'),'https://xs001.xyz/book/9007199254740993/9007199254741002.html');assert.match(doc.querySelector('#seo-jsonld').textContent,/第二章/);
});

test('从免费章进入 VIP 壳页不会保留前章可索引状态或摘要',()=>{
  const doc=documentFixture();applyChapterMetadata({title:'免费',description:'上一章正文摘要',keywords:'免费',canonical:'https://xs001.xyz/book/1/2.html',robots:'index,follow',jsonLd:'{"name":"上一章"}'},doc);
  applyChapterMetadata({title:'付费章',description:'书名，付费章。',keywords:'书名,付费章',canonical:'https://xs001.xyz/book/1/3.html',robots:'noindex,follow',jsonLd:''},doc);
  assert.equal(doc.querySelector('meta[name="robots"]').getAttribute('content'),'noindex,follow');assert.equal(doc.querySelector('meta[name="description"]').getAttribute('content'),'书名，付费章。');assert.equal(doc.querySelector('#seo-jsonld'),null);
});

test('无可信站点地址时删除旧 canonical，结构化 JSON 不解析为 HTML',()=>{
  const doc=documentFixture();applyChapterMetadata({title:'书',canonical:'https://xs001.xyz/book/1/2.html',robots:'index,follow',jsonLd:'{"name":"</script><script>bad</script>"}'},doc);
  assert.ok(!doc.querySelector('#seo-jsonld').textContent.includes('<'));assert.equal(JSON.parse(doc.querySelector('#seo-jsonld').textContent).name,'</script><script>bad</script>');
  applyChapterMetadata({canonical:'javascript:alert(1)',robots:'noindex,follow',jsonLd:'invalid'},doc);
  assert.equal(doc.querySelector('link[rel="canonical"]'),null);assert.equal(doc.querySelector('#seo-jsonld'),null);
});
