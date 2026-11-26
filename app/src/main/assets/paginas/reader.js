(() => {
  if (window !== window.top) return;
  const send = (type, extra={}) => browser.runtime.sendNativeMessage("koma", {type,url:location.href,...extra});
  let active = false, timer, retry, user = false;
  const save = () => { if(active) send("position",{y:Math.round(window.scrollY)}).catch(()=>{}); };
  const cancelRestore = () => {user=true;clearInterval(retry);active=true;};
  for(const event of ["touchstart","wheel","pointerdown","keydown"]) addEventListener(event,cancelRestore,{passive:true,once:true});
  addEventListener("scroll",()=>{if(active){clearTimeout(timer);timer=setTimeout(save,500);}}, {passive:true});
  addEventListener("pagehide",save);
  send("init").then(config=>{
    if(!config){active=true;return;}
    const mode=config.mode;
    if(mode==="fit"||mode==="continuous")document.documentElement.classList.add("koma-"+mode);
    let y=Number(config.y)||0;
    if(y<=0||user){active=true;return;}
    let count=0;
    const restore=()=>{
      if(user){clearInterval(retry);active=true;return;}
      window.scrollTo(0,y);count++;
      if(Math.abs(window.scrollY-y)<3||count>=25){clearInterval(retry);active=true;}
    };
    retry=setInterval(restore,300);restore();
  }).catch(()=>{active=true;});
})();