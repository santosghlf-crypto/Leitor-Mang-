(() => {
  "use strict";
  const SAVE_INTERVAL = 1500;
  const MAX_PIXELS = 3000000;
  const CONTAINERS = ".reading-content .page-break,#readerarea,.wp-manga-chapter-img-wrap";
  const SELECTOR = ".reading-content .page-break img:not([usemap]),#readerarea img:not([usemap]),.wp-manga-chapter-img-wrap img:not([usemap])";

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
  if(typeof module!=="undefined"&&module.exports)module.exports={createPositionTracker,selectCandidate,SAVE_INTERVAL,MAX_PIXELS};
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
      if(scanIndex<scanList.length||pendingNodes.size||needsScan)scheduleScan();
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
    lastScroll=performance.now();tracker.scroll();
    // One scheduled check; don't reset a timer or scan images for every scroll event.
    scheduleWarm();
  },{passive:true});
  document.addEventListener("visibilitychange",()=>{
    if(document.hidden){tracker.flush();cancelWarm();}
    else{scheduleWarm();if(needsScan||pendingNodes.size||scanIndex<scanList.length)scheduleScan();}
  });
  addEventListener("pagehide",event=>{
    tracker.flush();cancelWarm();
    if(event.persisted)return; // BFCache page: resume normally when shown again.
    finished=true;tracker.stop();
    if(restoreTimer!==null)clearTimeout(restoreTimer);
    if(idleScan!==null){if(typeof window.cancelIdleCallback==="function")window.cancelIdleCallback(idleScan);else clearTimeout(idleScan);}
    stopDiscovery();if(mutations)mutations.disconnect();if(observer)observer.disconnect();
    near.clear();observed.clear();pendingNodes.clear();scanList=[];
  });
  addEventListener("pageshow",()=>{if(!finished){scheduleWarm();if(needsScan||pendingNodes.size||scanIndex<scanList.length)scheduleScan();}});
  send({type:"init",url:location.href}).then(config=>{
    if(finished)return;
    if(!config){tracker.activate();return;}
    const mode=config.mode;
    if(mode==="fit"||mode==="continuous"){
      document.documentElement.classList.add("koma-"+mode);startImages(config.predecode===true);
    }
    restoreTarget=Math.max(0,Math.min(10000000,Number(config.y)||0));
    if(restoreTarget<=0||user){tracker.activate();return;}
    restoring=true;restore();
  }).catch(()=>tracker.activate());
})();
