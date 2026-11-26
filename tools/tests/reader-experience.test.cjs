const test=require("node:test"),assert=require("node:assert/strict");
const {clampSpeed,chapterUrl,progressPercent,createTapTracker}=require("../../app/src/main/assets/paginas/reader.js");
const current="https://example.org/manga/chapter-1";
test("speed bounded",()=>{assert.equal(clampSpeed(-1),30);assert.equal(clampSpeed(999),300);assert.equal(clampSpeed(120),120);assert.equal(clampSpeed(NaN),100);});
test("relative and www aliases allowed",()=>{assert.equal(chapterUrl("chapter-2",current),"https://example.org/manga/chapter-2");assert.equal(chapterUrl("https://www.example.org/chapter-2",current),"https://www.example.org/chapter-2");});
test("external, non-https, credential, port and fragment-only destinations rejected",()=>{for(const url of ["https://other.org/c2","https://example.org.evil.com/c2","http://example.org/c2","javascript:alert(1)","#next","https://user@example.org/c2","https://example.org:444/c2",null])assert.equal(chapterUrl(url,current),null);});
test("progress clamped",()=>{assert.equal(progressPercent(0,0),0);assert.equal(progressPercent(50,100),50);assert.equal(progressPercent(200,100),100);assert.equal(progressPercent(-10,100),0);});
test("double tap requires time and distance",()=>{let t=createTapTracker();assert.equal(t.tap(10,10,100),false);assert.equal(t.tap(15,15,350),true);assert.equal(t.tap(10,10,400),false);assert.equal(t.tap(10,10,800),false);assert.equal(t.tap(100,100,900),false);t.reset();assert.equal(t.tap(100,100,950),false);});
