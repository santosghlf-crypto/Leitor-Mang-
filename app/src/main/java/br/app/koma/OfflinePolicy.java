package br.app.koma;
import java.util.UUID;
final class OfflinePolicy {
    static final long MIB=1048576L, MAX_IMAGE=16*MIB, MAX_CHAPTER=100*MIB, MAX_TOTAL=500*MIB, RESERVE=100*MIB;
    static final int MAX_PAGES=120, MAX_CHUNK=48*1024;
    static boolean validId(String s){try{return s!=null&&UUID.fromString(s).toString().equals(s);}catch(Exception e){return false;}}
    static boolean allowedImage(int w,int h,String mime){return w>0&&h>0&&w<=16000&&h<=100000&&(long)w*h<=100000000L&&("image/jpeg".equals(mime)||"image/png".equals(mime)||"image/webp".equals(mime));}
    static boolean fits(long total,long chapter,long image,int chunk,long free){return chunk>0&&chunk<=MAX_CHUNK&&total>=0&&chapter>=0&&image>=0&&total+chapter+chunk<=MAX_TOTAL&&chapter+chunk<=MAX_CHAPTER&&image+chunk<=MAX_IMAGE&&free>=RESERVE+chunk;}
    static int sample(int width,int display){int s=1;while(width/(2L*s)>=Math.min(1600,Math.max(1,display)))s*=2;while((width+(long)s-1)/s>2048)s*=2;return s;}
}
