(() => {
  "use strict";
  const SAVE_INTERVAL = 1500;
  const MAX_PIXELS = 3000000;
  const DEFAULT_CONTAINERS = ".reading-content .page-break,#readerarea,.wp-manga-chapter-img-wrap";
  const DEFAULT_SELECTOR = ".reading-content .page-break img:not([usemap]),#readerarea img:not([usemap]),.wp-manga-chapter-img-wrap img:not([usemap])";

  let CONTAINERS=DEFAULT_CONTAINERS,SELECTOR=DEFAULT_SELECTOR,activeAdapter={};
  function createPositionTracker({read,send,setTimer,clearTimer}) {
    let enabled=false,timer=null,lastSent=null,lastFlushed=null;
    function transmit(flush=false) {
      if(!enabled)return;
      const current=read();
      if(!current||!Number.isFinite(current.y))return;
      const y=Math.max(0,Math.min(10000000,Math.round(current.y)));
      const key=JSON.stringify([current.url,y]);
      if(flush ? key===lastFlushed&&key===lastSent : key===lastSent)return;
      lastSent=key;if(flush)lastFlushed=key;
      try {
        Promise.resolve(send({type:"position",url:current.url,y,flush})).catch(()=>{
          if(lastSent===key)lastSent=null;if(lastFlushed===key)lastFlushed=null;
        });
      } catch(e) {lastSent=null;lastFlushed=null;}
    }
    return {
      activate(){enabled=true;},
      scroll(){
        if(!enabled||timer!==null)return;
        timer=setTimer(()=>{timer=null;transmit(false);},SAVE_INTERVAL);
      },
      flush(){
        if(timer!==null){clearTimer(timer);timer=null;}
        transmit(true);
      },
      stop(){if(timer!==null)clearTimer(timer);timer=null;enabled=false;}
    };
  }
  function imageKey(img){return img.currentSrc||img.src||"";}
  function selectCandidate(images,viewport,prepared) {
    if(!Number.isFinite(viewport)||viewport<=0)return null;
    let chosen=null,best=Infinity,checked=0;
    for(const img of images){
      if(++checked>32)break;
      if(!img.isConnected||!img.complete||!img.naturalWidth||!img.naturalHeight)continue;
      if(img.naturalWidth*img.naturalHeight>MAX_PIXELS)continue;
      const key=imageKey(img);if(!key||prepared.get(img)===key)continue;
      const rect=img.getBoundingClientRect();
      if(rect.width<=0||rect.height<=0||rect.top<viewport-8||rect.top>viewport*1.8)continue;
      if(rect.top<best){best=rect.top;chosen=img;}
    }
    return chosen;
  }
  const clampSpeed=value=>Math.max(30,Math.min(300,Number.isFinite(Number(value))?Number(value):100));
  function chapterUrl(raw,current){
    if(!raw)return null;
    try{
      const a=new URL(current),b=new URL(raw,current),host=u=>u.hostname.toLowerCase().replace(/^www\./,"");
      if(a.protocol!=="https:"||b.protocol!=="https:"||b.username||b.password||b.port||host(a)!==host(b))return null;
      if(a.pathname===b.pathname&&a.search===b.search)return null;
      return b.href;
    }catch(e){return null;}
  }
  function progressPercent(y,max){return max>0?Math.max(0,Math.min(100,Math.round(y/max*100))):0;}
  function createTapTracker(){
    let last=null;
    return {
      reset(){last=null;},
      tap(x,y,t){const match=last&&t-last.t>0&&t-last.t<=320&&Math.hypot(x-last.x,y-last.y)<=32;
        last=match?null:{x,y,t};return !!match;}
    };
  }
  if(typeof module!=="undefined"&&module.exports)module.exports={createPositionTracker,selectCandidate,SAVE_INTERVAL,MAX_PIXELS,clampSpeed,chapterUrl,progressPercent,createTapTracker};
  if(typeof window==="undefined"||window!==window.top)return;

  const send=(message)=>browser.runtime.sendNativeMessage("koma",message);
  const tracker=createPositionTracker({
    read:()=>({url:location.href,y:window.scrollY}),send,
    setTimer:setTimeout,clearTimer:clearTimeout
  });
  let user=false,restoring=false,restoreTimer=null,restoreTarget=0,attempts=0,lastMax=-1;
  let lastScroll=-1000,warmTimer=null,idleWarm=null,idleScan=null,busy=false,finished=false;
  let observer=null,mutations=null,discovery=null,discoveryTimer=null,scanList=[],scanIndex=0,needsScan=false,needsPrune=false;
  const observed=new Set(),near=new Set(),prepared=new WeakMap(),pendingNodes=new Set();
  let warmEnabled=false;
  let configNow=null,port=null,statusTimer=null,auto=false,raf=null,autoWait=null,lastFrame=0,carry=0,stalledAt=null,stalledY=-1;
  let lastStatus="",pointer=null;const taps=createTapTracker();
  function hasChapter(){
    // Only recognized reader containers that actually contain images; no guesses on catalogs.
    return !!document.querySelector(SELECTOR);
  }
  function chapterLink(direction){
    if(!hasChapter())return null;
    const next=direction==="next";
    const configured=next?activeAdapter.next:activeAdapter.previous;
    if(configured){const target=KomaAdapter.link(KomaAdapter.first(document,configured),location.href);return chapterUrl(target,location.href);}
    const selectors=next?['a[rel~="next"]','a.next-chapter','a.chapter-next','.nav-next a','.next_page']:['a[rel~="prev"]','a.prev-chapter','a.chapter-prev','.nav-previous a','.prev_page'];
    for(const selector of selectors){
      const link=document.querySelector(selector);
      if(!link||!link.href)continue;
      const explicit=selector.includes("rel~")||selector.includes("chapter");
      const label=[link.textContent,link.getAttribute("title"),link.getAttribute("aria-label")].join(" ").toLowerCase();
      if(!explicit&&!/(cap[ií]tulo|chapter|chap\b|epis[oó]dio|episode)/i.test(label))continue;
      const valid=chapterUrl(link.href,location.href);if(valid)return valid;
    }
    return null;
  }
  function maxScroll(){const d=document.scrollingElement||document.documentElement;return Math.max(0,d.scrollHeight-window.innerHeight);}
  function post(type,extra={}){
    if(!port)return;try{port.postMessage({type,url:location.href,...extra});}catch(e){}
  }
  function publish(force=false){
    if(finished||!port||document.hidden)return;
    const chapter=hasChapter(),state={chapter,percent:progressPercent(window.scrollY,maxScroll()),running:auto,next:chapter?chapterLink("next"):null,previous:chapter?chapterLink("previous"):null,chapterLabel:chapter?KomaAdapter.text(document,activeAdapter.chapterLabel).slice(0,80):""};
    const signature=JSON.stringify(state);if(!force&&signature===lastStatus)return;
    lastStatus=signature;post("status",state);
  }
  function scheduleStatus(){
    if(!port||finished||document.hidden||statusTimer!==null)return;
    statusTimer=setTimeout(()=>{statusTimer=null;publish();},750);
  }
  function stopAuto(report=true){
    auto=false;if(raf!==null)cancelAnimationFrame(raf);if(autoWait!==null)clearTimeout(autoWait);
    raf=null;autoWait=null;lastFrame=0;carry=0;stalledAt=null;stalledY=-1;
    if(report){tracker.flush();publish(true);}
  }
  function autoFrame(now){
    raf=null;if(!auto||finished||document.hidden){stopAuto();return;}
    if(!lastFrame)lastFrame=now;
    const elapsed=Math.min(50,Math.max(0,now-lastFrame));lastFrame=now;
    carry+=clampSpeed(configNow.speed)*elapsed/1000;
    const pixels=Math.floor(carry);
    if(pixels>0){carry-=pixels;window.scrollBy({top:pixels,left:0,behavior:"instant"});}
    const y=window.scrollY;
    // Back off at the end or if the website refuses document scrolling.
    if(y===stalledY){if(stalledAt===null)stalledAt=now;}
    else{stalledY=y;stalledAt=null;}
    if(stalledAt!==null&&now-stalledAt>=500){
      const end=maxScroll();const atEnd=end>0&&y>=end-3;
      autoWait=setTimeout(()=>{
        autoWait=null;if(!auto||finished||document.hidden){stopAuto();return;}
        if(window.scrollY<maxScroll()-3&&atEnd){lastFrame=0;stalledAt=null;raf=requestAnimationFrame(autoFrame);return;}
        if(atEnd&&window.scrollY>=maxScroll()-3&&configNow.autoNext===true){
          publish(true);const next=chapterLink("next");if(next)post("autoEnd",{next});
        }
        stopAuto();
      },2500);return;
    }
    raf=requestAnimationFrame(autoFrame);
  }
  function startAuto(speed){
    if(!configNow||!hasChapter()||document.hidden||finished)return;
    stopAuto(false);if(restoring)stopRestore();tracker.activate();
    configNow.speed=clampSpeed(speed);auto=true;publish(true);raf=requestAnimationFrame(autoFrame);
  }
  function connectReader(){
    if(port||!configNow||finished)return;
    try{
      const connection=browser.runtime.connectNative("koma");port=connection;lastStatus="";
      connection.onDisconnect.addListener(()=>{if(port===connection){port=null;stopAuto(false);}});
      connection.onMessage.addListener(m=>{
        if(port!==connection||!m||m.url!==location.href||finished)return;
        if(m.type==="start")startAuto(m.speed);
        else if(m.type==="stop")stopAuto();
        else if(m.type==="status")publish(true);
        else if(m.type==="extract"&&["work","catalog","chapters"].includes(m.kind)&&typeof m.request==="string"&&m.request.length<=64){post("extraction",{request:m.request,kind:m.kind,...KomaAdapter.extract(document,activeAdapter,m.kind,location.href)});}
        else if(m.type==="settings"){
          stopAuto();configNow.doubleTap=m.doubleTap===true;configNow.autoNext=m.autoNext===true;configNow.speed=clampSpeed(m.speed);
          document.documentElement.classList.toggle("koma-doubletap",configNow.doubleTap);taps.reset();publish(true);
        }
      });
      publish(true);
    }catch(e){port=null;}
  }
  function disconnectReader(){
    if(statusTimer!==null)clearTimeout(statusTimer);statusTimer=null;stopAuto(false);
    const p=port;port=null;if(p)try{p.disconnect();}catch(e){}
  }
  function interactive(target){return !(target instanceof Element)||!!target.closest("a,button,input,textarea,select,label,[contenteditable],[role=button]");}
  document.addEventListener("pointerdown",event=>{
    if(!event.isTrusted)return;if(auto)stopAuto();
    if(!configNow?.doubleTap||!event.isPrimary||!["touch","pen"].includes(event.pointerType)||interactive(event.target)||!event.target.matches(SELECTOR)){pointer=null;taps.reset();return;}
    pointer={id:event.pointerId,x:event.clientX,y:event.clientY,t:performance.now(),target:event.target};
  },{passive:true,capture:true});
  document.addEventListener("pointerup",event=>{
    const p=pointer;pointer=null;
    if(!p||!event.isTrusted||event.pointerId!==p.id||event.target!==p.target||performance.now()-p.t>250||Math.hypot(event.clientX-p.x,event.clientY-p.y)>12){taps.reset();return;}
    if(taps.tap(event.clientX,event.clientY,performance.now()))send({type:"toggleControls",url:location.href}).catch(()=>{});
  },{passive:true,capture:true});
  document.addEventListener("pointercancel",()=>{pointer=null;taps.reset();},{passive:true});
  for(const event of ["wheel","keydown"])addEventListener(event,e=>{if(e.isTrusted&&auto)stopAuto();},{passive:true});


  function stopRestore(){
    if(restoreTimer!==null)clearTimeout(restoreTimer);
    restoreTimer=null;restoring=false;tracker.activate();
  }
  function restore(){
    restoreTimer=null;
    if(finished||!restoring)return;
    if(user){stopRestore();return;}
    if(document.hidden){restoreTimer=setTimeout(restore,500);return;}
    const doc=document.scrollingElement||document.documentElement;
    const max=Math.max(0,doc.scrollHeight-window.innerHeight);
    // Avoid repeated forced scroll calls while the chapter is still growing.
    if(max!==lastMax||attempts===0){
      lastMax=max;
      const desired=Math.min(restoreTarget,max);
      if(Math.abs(window.scrollY-desired)>2)window.scrollTo(0,desired);
    }
    attempts++;
    if(Math.abs(window.scrollY-restoreTarget)<3||attempts>=25){stopRestore();return;}
    restoreTimer=setTimeout(restore,300);
  }
  function markInteraction(){user=true;if(restoring)stopRestore();}
  for(const event of ["touchstart","wheel","pointerdown","keydown"])
    addEventListener(event,markInteraction,{passive:true,once:true});

  function idle(fn){return window.requestIdleCallback(fn);}
  function cancelWarm(){
    if(warmTimer!==null)clearTimeout(warmTimer);
    if(idleWarm!==null)window.cancelIdleCallback(idleWarm);
    warmTimer=null;idleWarm=null;
  }
  function scheduleWarm(){
    if(!warmEnabled||finished||document.hidden||busy||warmTimer!==null||idleWarm!==null)return;
    const remaining=Math.max(0,220-(performance.now()-lastScroll));
    if(remaining>0){warmTimer=setTimeout(()=>{warmTimer=null;scheduleWarm();},remaining);return;}
    idleWarm=idle(deadline=>{
      idleWarm=null;
      if(finished||document.hidden||busy)return;
      if(performance.now()-lastScroll<220){scheduleWarm();return;}
      if(deadline.timeRemaining()<4)return;
      const img=selectCandidate(near,window.innerHeight,prepared);
      if(!img||typeof img.decode!=="function")return;
      prepared.set(img,imageKey(img));busy=true;
      // No fetch(), no new Image(), no duplicate request, no retained pixel buffer.
      Promise.resolve().then(()=>img.decode()).catch(()=>{}).finally(()=>{busy=false;});
    });
  }
  function register(img){
    if(!img.isConnected||observed.has(img)||observed.size>=1200)return;
    if(activeAdapter.reader)img.classList.add("koma-adapter-image");
    if(!img.hasAttribute("decoding"))img.setAttribute("decoding","async");
    observed.add(img);
    if(observer)observer.observe(img);
  }
  function prune(){
    for(const img of observed)if(!img.isConnected){observed.delete(img);near.delete(img);if(observer)observer.unobserve(img);}
  }
  function scheduleScan(){
    if(finished||document.hidden||idleScan!==null)return;
    const run=deadline=>{
      idleScan=null;if(finished||document.hidden)return;
      if(needsPrune){needsPrune=false;prune();}
      if(needsScan){needsScan=false;pendingNodes.clear();scanList=Array.from(document.querySelectorAll(SELECTOR));scanIndex=0;}
      let count=0;
      while(count<48&&(count===0||deadline.timeRemaining()>2)){
        if(scanIndex<scanList.length){register(scanList[scanIndex++]);count++;continue;}
        scanList=[];scanIndex=0;
        const first=pendingNodes.values().next();
        if(first.done)break;
        const node=first.value;pendingNodes.delete(node);count++;
        if(!node.isConnected)continue;
        if(node.matches(SELECTOR))register(node);
        // Process only added subtrees, not the entire document on every scroll.
        scanList=Array.from(node.querySelectorAll(SELECTOR));scanIndex=0;
      }
      if(scanIndex<scanList.length||pendingNodes.size||needsScan)scheduleScan();else scheduleStatus();
    };
    if(typeof window.requestIdleCallback==="function")idleScan=idle(run);
    else idleScan=setTimeout(()=>run({timeRemaining:()=>5}),50);
  }
  function observeReaders(){
    const roots=Array.from(document.querySelectorAll(CONTAINERS));
    if(roots.length===0)return false;
    // A single observer only on reader containers and their immediate parents,
    // not the whole site for the lifetime of the document.
    mutations.disconnect();
    const targets=new Set();
    for(const root of roots){
      if(targets.size>=96)break;
      if(root.parentElement&&root.parentElement!==document.body&&root.parentElement!==document.documentElement)targets.add(root.parentElement);
      else targets.add(root);
    }
    for(const root of targets)mutations.observe(root,{childList:true,subtree:true});
    needsScan=true;scheduleScan();
    return true;
  }
  function stopDiscovery(){
    if(discovery)discovery.disconnect();discovery=null;
    if(discoveryTimer!==null)clearTimeout(discoveryTimer);discoveryTimer=null;
  }
  function beginDiscovery(){
    stopDiscovery();
    if(observeReaders()||!document.body)return;
    // Bounded discovery for readers inserted by site JavaScript. Non-reader
    // pages do not keep a permanent whole-document mutation observer.
    let check=null;
    discovery=new MutationObserver(()=>{
      if(check!==null||finished)return;
      check=setTimeout(()=>{check=null;if(!finished&&observeReaders())stopDiscovery();},350);
    });
    discovery.observe(document.body,{childList:true,subtree:true});
    discoveryTimer=setTimeout(()=>{if(check!==null)clearTimeout(check);stopDiscovery();},12000);
  }
  function startImages(predecode){
    warmEnabled=predecode&&typeof window.requestIdleCallback==="function"&&typeof IntersectionObserver==="function";
    if(warmEnabled)observer=new IntersectionObserver(entries=>{
      for(const entry of entries){if(entry.isIntersecting)near.add(entry.target);else near.delete(entry.target);}
      scheduleWarm();
    },{rootMargin:"0px 0px "+Math.round(window.innerHeight*.8)+"px 0px"});
    mutations=new MutationObserver(records=>{
      let changed=false;
      for(const record of records){
        for(const node of record.addedNodes){
          if(node.nodeType!==1)continue;
          if(pendingNodes.size>=64){needsScan=true;pendingNodes.clear();break;}
          pendingNodes.add(node);changed=true;
        }
        if(record.removedNodes.length){changed=true;needsPrune=true;}
      }
      if(changed||needsScan)scheduleScan();
    });
    beginDiscovery();
    if(warmEnabled)document.addEventListener("load",event=>{
      if(observed.has(event.target))scheduleWarm();
    },true);
  }
  addEventListener("scroll",()=>{
    lastScroll=performance.now();tracker.scroll();scheduleStatus();
    // One scheduled check; don't reset a timer or scan images for every scroll event.
    scheduleWarm();
  },{passive:true});
  document.addEventListener("visibilitychange",()=>{
    if(document.hidden){stopAuto();tracker.flush();cancelWarm();}
    else{publish(true);scheduleWarm();if(needsScan||pendingNodes.size||scanIndex<scanList.length)scheduleScan();}
  });
  addEventListener("pagehide",event=>{
    tracker.flush();cancelWarm();disconnectReader();
    if(event.persisted)return; // BFCache page: resume normally when shown again.
    finished=true;tracker.stop();
    if(restoreTimer!==null)clearTimeout(restoreTimer);
    if(idleScan!==null){if(typeof window.cancelIdleCallback==="function")window.cancelIdleCallback(idleScan);else clearTimeout(idleScan);}
    stopDiscovery();if(mutations)mutations.disconnect();if(observer)observer.disconnect();
    near.clear();observed.clear();pendingNodes.clear();scanList=[];
  });
  addEventListener("pageshow",()=>{if(!finished){connectReader();scheduleWarm();if(needsScan||pendingNodes.size||scanIndex<scanList.length)scheduleScan();}});
  send({type:"init",url:location.href}).then(config=>{
    if(finished)return;
    if(!config){tracker.activate();return;}
    configNow=config;activeAdapter=KomaAdapter.profile(config.adapter);
    if(activeAdapter.reader){CONTAINERS=activeAdapter.reader;SELECTOR=CONTAINERS+" img:not([usemap])";}
    document.documentElement.classList.toggle("koma-doubletap",config.doubleTap===true);connectReader();
    const mode=config.mode;
    if(mode==="fit"||mode==="continuous"){
      document.documentElement.classList.add("koma-"+mode);startImages(config.predecode===true);
    }
    restoreTarget=Math.max(0,Math.min(10000000,Number(config.y)||0));
    if(restoreTarget<=0||user){tracker.activate();return;}
    restoring=true;restore();
  }).catch(()=>tracker.activate());
})();
