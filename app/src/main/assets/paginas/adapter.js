/* Declarative selectors only. No fetch, script evaluation, HTML insertion or clicks. */
(function(root){
  "use strict";
  const ATOM="(?:[a-zA-Z][a-zA-Z0-9_-]*|[.#][a-zA-Z_][a-zA-Z0-9_-]*)";
  const PART=ATOM+"{1,5}", SAFE=new RegExp("^"+PART+"(?:(?: +| *> *)"+PART+"){0,3}$");
  const valid=s=>typeof s==="string"&&s.length<=120&&(s===""||SAFE.test(s));
  const clean=(s,n)=>typeof s==="string"?s.trim().replace(/\s+/g," ").slice(0,n):"";
  const profile=p=>{const out={};for(const k of ["reader","next","previous","title","chapterLabel","workLink","catalog","chapters"])out[k]=valid(p?.[k])?p[k]:"";return out;};
  function url(raw,current){
    try{
      if(typeof raw!=="string"||!raw.trim()||raw.length>4096)return null;
      const a=new URL(current),b=new URL(raw,current),host=u=>u.hostname.toLowerCase().replace(/^www\./,"");
      if(a.protocol!=="https:"||b.protocol!=="https:"||b.username||b.password||b.port||host(a)!==host(b))return null;
      b.hash="";return b.href.length<=4096?b.href:null;
    }catch(e){return null;}
  }
  function first(doc,selector){if(!selector)return null;try{return doc.querySelector(selector);}catch(e){return null;}}
  function text(doc,selector){return clean(first(doc,selector)?.textContent,120);}
  function link(node,current){
    if(!node)return null;
    const a=node.matches?.("a[href]")?node:node.querySelector?.("a[href]");
    return a?url(a.getAttribute("href"),current):null;
  }
  function extract(doc,raw,kind,current){
    const p=profile(raw);
    if(kind==="work"){
      return {title:text(doc,p.title)||clean(doc.title,120),chapterLabel:text(doc,p.chapterLabel).slice(0,80),workUrl:link(first(doc,p.workLink),current)};
    }
    const selector=kind==="catalog"?p.catalog:kind==="chapters"?p.chapters:"";
    const items=[],seen=new Set();if(!selector)return {items};
    let nodes;try{nodes=doc.querySelectorAll(selector);}catch(e){return {items};}
    for(let i=0;i<Math.min(nodes.length,300)&&items.length<100;i++){
      const node=nodes[i],a=node.matches?.("a[href]")?node:node.querySelector?.("a[href]");
      const dest=link(a,current);if(!dest||seen.has(dest))continue;
      const title=clean(a.textContent,120)||clean(a.getAttribute("title"),120)||clean(a.getAttribute("aria-label"),120);
      if(!title)continue;
      seen.add(dest);items.push({title,url:dest});
    }
    return {items};
  }
  const api={valid,profile,url,first,text,link,extract};
  if(typeof module!=="undefined"&&module.exports)module.exports=api;
  else root.KomaAdapter=api;
})(globalThis);
