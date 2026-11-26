const test=require("node:test"),assert=require("node:assert/strict");
const {createTimedCache}=require("../../app/src/main/assets/paginas/reader.js");
test("120 status reads over 90s trigger at most 18 metadata reads without changes",()=>{
 let reads=0,t=0;const cache=createTimedCache(()=>++reads,()=>t,5000);
 for(let i=0;i<120;i++){t=i*750;cache.get();}
 assert.equal(reads,18);
});
test("DOM changes invalidate cached metadata immediately",()=>{
 let reads=0,t=0;const cache=createTimedCache(()=>++reads,()=>t,5000);
 assert.equal(cache.get(),1);t=100;assert.equal(cache.get(),1);cache.invalidate();assert.equal(cache.get(),2);assert.equal(cache.get(true),3);
});
