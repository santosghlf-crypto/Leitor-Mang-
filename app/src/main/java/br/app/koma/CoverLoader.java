package br.app.koma;

import android.app.ActivityManager;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.LruCache;
import java.io.InputStream;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** All provider reads and image decoding run on a bounded worker pool. */
final class CoverLoader {
    interface Result { void accept(Bitmap bitmap); }
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor pool;
    private final LruCache<String,Bitmap> cache;
    private final int limit;
    private final boolean low;
    private final AtomicInteger generation=new AtomicInteger();
    private volatile boolean closed;
    CoverLoader(Context app){
        context=app;
        ActivityManager am=(ActivityManager)app.getSystemService(Context.ACTIVITY_SERVICE);
        low=am!=null&&am.isLowRamDevice();limit=PerfPolicy.coverBudgetBytes(am==null?128:am.getMemoryClass(),low);
        cache=new LruCache<String,Bitmap>(limit){
            @Override protected int sizeOf(String k,Bitmap b){return b.getAllocationByteCount();}
        };
        int threads=low?1:2;
        pool=new ThreadPoolExecutor(threads,threads,15,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{
            Thread t=new Thread(()->{Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);r.run();},"koma-cover");return t;
        });
        pool.allowCoreThreadTimeOut(true);
    }
    boolean lowRam(){return low;}
    int limitBytes(){return limit;}
    Bitmap get(String key){return cache.get(key);}
    Future<?> load(String uri,String key,int size,Result callback){
        if(closed)return null;
        int stamp=generation.get();
        try{
            return pool.submit(()->{
                if(Thread.currentThread().isInterrupted()||closed)return;
                Bitmap b=cache.get(key);if(b==null)b=decode(uri,size);
                if(Thread.currentThread().isInterrupted()||closed)return;
                final Bitmap result=b;
                main.post(()->{
                    if(closed)return;
                    if(result!=null&&stamp==generation.get())cache.put(key,result);
                    callback.accept(result);
                });
            });
        }catch(RejectedExecutionException e){return null;}
    }
    private Bitmap decode(String value,int size){
        try{
            Uri uri=Uri.parse(value);if(!"content".equals(uri.getScheme())&&!"file".equals(uri.getScheme()))return null;
            BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
            try(InputStream input=context.getContentResolver().openInputStream(uri)){if(input==null)return null;BitmapFactory.decodeStream(input,null,options);}
            if(options.outWidth<=0||options.outHeight<=0||Thread.currentThread().isInterrupted())return null;
            options.inSampleSize=PerfPolicy.sampleSize(options.outWidth,options.outHeight,size);
            options.inJustDecodeBounds=false;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
            Bitmap b;
            try(InputStream input=context.getContentResolver().openInputStream(uri)){if(input==null)return null;b=BitmapFactory.decodeStream(input,null,options);}
            if(b==null)return null;int largest=Math.max(b.getWidth(),b.getHeight());
            if(largest<=size)return b;
            float scale=(float)size/largest;Bitmap scaled=Bitmap.createScaledBitmap(b,Math.max(1,Math.round(b.getWidth()*scale)),Math.max(1,Math.round(b.getHeight()*scale)),true);
            if(scaled!=b)b.recycle();return scaled;
        }catch(Exception|OutOfMemoryError e){return null;}
    }
    void cancel(Future<?> task){if(task==null)return;task.cancel(true);if(task instanceof Runnable)pool.remove((Runnable)task);}
    void invalidate(String uri){
        if(uri==null||uri.isEmpty())return;generation.incrementAndGet();
        for(String key:cache.snapshot().keySet())if(key.startsWith(uri+"|"))cache.remove(key);
    }
    void trim(int level){
        if(level>=ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN||level==ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)clear();
        else if(level>=ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW){generation.incrementAndGet();cache.trimToSize(limit/2);}
    }
    void clear(){generation.incrementAndGet();cache.evictAll();}
    void close(){closed=true;pool.shutdownNow();main.removeCallbacksAndMessages(null);clear();}
}
