const test=require("node:test");
const assert=require("node:assert/strict");
const {createPositionTracker,selectCandidate,SAVE_INTERVAL,MAX_PIXELS}=require("../../app/src/main/assets/paginas/reader.js");
function fake(){
  let time=0,id=0,y=0,url="https://example.org/chapter/1";const tasks=new Map(),messages=[];
  const tracker=createPositionTracker({
    read:()=>({url,y}),send:m=>messages.push(m),
    setTimer:(fn,delay)=>{tasks.set(++id,{fn,at:time+delay});return id;},clearTimer:n=>tasks.delete(n)
  });
  function tick(ms){const end=time+ms;for(;;){const next=[...tasks].sort((a,b)=>a[1].at-b[1].at)[0];if(!next||next[1].at>end)break;time=next[1].at;tasks.delete(next[0]);next[1].fn();}time=end;}
  return {tracker,messages,tasks,tick,position:n=>{y=n;},url:u=>{url=u;}};
}
test("continuous scrolling queues one timer and sends at most once per interval",()=>{
  const f=fake();f.tracker.activate();
  for(let i=0;i<120;i++){f.position(i);f.tracker.scroll();f.tick(10);}
  assert.equal(f.tasks.size,1);assert.equal(f.messages.length,0);
  f.tick(SAVE_INTERVAL-1200);assert.equal(f.messages.length,1);assert.equal(f.messages[0].y,119);
  for(let i=0;i<120;i++){f.position(120+i);f.tracker.scroll();f.tick(10);}
  assert.equal(f.messages.length,1);f.tick(300);assert.equal(f.messages.length,2);
});
test("hide flushes latest position immediately, cancels pending timer, deduplicates",()=>{
  const f=fake();f.tracker.activate();f.position(712);f.tracker.scroll();f.tracker.flush();f.tracker.flush();f.tick(5000);
  assert.equal(f.messages.length,1);assert.deepEqual(f.messages[0],{type:"position",url:"https://example.org/chapter/1",y:712,flush:true});
  assert.equal(f.tasks.size,0);
});
test("restore is silent until activated and zero position is preserved",()=>{
  const f=fake();f.position(0);f.tracker.scroll();f.tracker.flush();f.tick(3000);assert.equal(f.messages.length,0);
  f.tracker.activate();f.tracker.flush();assert.equal(f.messages[0].y,0);
});
test("new chapter can save same numeric position; stopping cancels work",()=>{
  const f=fake();f.tracker.activate();f.position(50);f.tracker.flush();f.url("https://example.org/chapter/2");f.tracker.flush();
  assert.equal(f.messages.length,2);f.position(70);f.tracker.scroll();f.tracker.stop();f.tick(4000);assert.equal(f.messages.length,2);
});
function image(top,opts={}){
  return {isConnected:true,complete:true,naturalWidth:1000,naturalHeight:2000,currentSrc:"https://example.org/page.jpg",
    getBoundingClientRect:()=>({top,width:800,height:1600}),...opts};
}
test("predecode only chooses the nearest already loaded image just below viewport",()=>{
  const visible=image(30),far=image(2100),next=image(1300),nearer=image(1050),notLoaded=image(1000,{complete:false});
  assert.equal(selectCandidate([visible,far,next,nearer,notLoaded],1000,new WeakMap()),nearer);
});
test("skips huge, failed, detached and previously prepared images",()=>{
  const huge=image(1020,{naturalWidth:MAX_PIXELS,naturalHeight:2}),bad=image(1020,{naturalWidth:0}),detached=image(1020,{isConnected:false});
  const already=image(1020),other=image(1120),prepared=new WeakMap([[already,already.currentSrc]]);
  assert.equal(selectCandidate([huge,bad,detached,already,other],1000,prepared),other);
  assert.equal(selectCandidate([huge,bad,detached,already],1000,prepared),null);
});
test("changed image source can be prepared again",()=>{
  const img=image(1100),prepared=new WeakMap([[img,img.currentSrc]]);img.currentSrc="https://example.org/new.jpg";
  assert.equal(selectCandidate([img],1000,prepared),img);
});

test("flush must resend a position revisited after another normal update",()=>{
  const f=fake();f.tracker.activate();f.position(100);f.tracker.flush();
  f.position(200);f.tracker.scroll();f.tick(SAVE_INTERVAL);
  f.position(100);f.tracker.flush();assert.equal(f.messages.length,3);assert.equal(f.messages[2].y,100);
});
