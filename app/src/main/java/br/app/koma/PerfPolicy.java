package br.app.koma;

/** Pure policy: testable without Android or Gecko. */
final class PerfPolicy {
    private PerfPolicy(){}
    static int coverBudgetBytes(int memoryClassMiB,boolean lowRam){
        int mib=lowRam?4:Math.max(4,Math.min(12,memoryClassMiB/16));
        return mib*1024*1024;
    }
    static int coverBucket(int pixels){
        if(pixels<=256)return 256;
        if(pixels<=384)return 384;
        if(pixels<=512)return 512;
        return 640;
    }
    static int sampleSize(int width,int height,int target){
        if(width<=0||height<=0||target<=0)return 1;
        int sample=1;long max=Math.max(width,height);
        while(max/(2L*sample)>=target&&sample<(1<<29))sample*=2;
        return sample;
    }
    static int clampPosition(int y){return Math.max(0,Math.min(10000000,y));}
}
