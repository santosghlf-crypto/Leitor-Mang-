package br.app.koma;
import android.app.Activity;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

/** Offline reader: local image regions, no browser, no network or JavaScript. */
public class OfflineActivity extends Activity{
    OfflineStore store;OfflineStore.Entry entry;
    final Handler main=new Handler(Looper.getMainLooper());
    final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,15,TimeUnit.SECONDS,new ArrayBlockingQueue<>(12));
    ListView list;LinearLayout root,bar;TextView label;boolean full=true,closed;int width;boolean initial=true;
    static final int TILE=768;
    final ArrayList<Tile> tiles=new ArrayList<>();
    static final class Tile{int page,sample,top,bottom,w,h;Tile(int p,int s,int t,int b,int w,int h){page=p;sample=s;top=t;bottom=b;this.w=w;this.h=h;}}
    int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().setStatusBarColor(Color.BLACK);getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);store=new OfflineStore(getFilesDir());
        String id=getIntent().getStringExtra("id");if(!OfflinePolicy.validId(id)){finish();return;}
        root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(Color.BLACK);setContentView(root);
        bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(8),dp(4),dp(8),dp(4));bar.setBackgroundColor(0xff191e23);
        Button back=new Button(this);back.setText("‹");back.setContentDescription("Voltar");back.setOnClickListener(v->finish());bar.addView(back,new LinearLayout.LayoutParams(dp(54),dp(48)));
        label=new TextView(this);label.setTextColor(Color.WHITE);label.setMaxLines(2);bar.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        Button hide=new Button(this);hide.setText("Tela cheia");hide.setOnClickListener(v->immersive(true));bar.addView(hide);root.addView(bar);
        list=new ListView(this);list.setDivider(null);list.setDividerHeight(0);list.setCacheColorHint(Color.TRANSPARENT);list.setSelector(android.R.color.transparent);list.setBackgroundColor(Color.BLACK);root.addView(list,new LinearLayout.LayoutParams(-1,0,1));immersive(true);
        worker.execute(()->{OfflineStore.Entry e=store.read(id);main.post(()->{if(closed)return;if(e==null){Toast.makeText(this,"Capítulo indisponível",Toast.LENGTH_LONG).show();finish();return;}entry=e;label.setText(e.title);list.post(()->{if(!closed)rebuild();});});});
    }
    void rebuild(){
        if(entry==null)return;int now=list.getWidth();if(now<=0)now=getResources().getDisplayMetrics().widthPixels;
        int first=list.getFirstVisiblePosition();int page=tiles.isEmpty()?0:tiles.get(Math.min(first,tiles.size()-1)).page;int offset=tiles.isEmpty()?0:tiles.get(Math.min(first,tiles.size()-1)).top;
        if(initial){page=getSharedPreferences("offlinePositions",MODE_PRIVATE).getInt(entry.id+"/page",0);offset=getSharedPreferences("offlinePositions",MODE_PRIVATE).getInt(entry.id+"/offset",0);initial=false;}
        width=now;tiles.clear();
        for(int i=0;i<entry.pages.size();i++){OfflineStore.Page p=entry.pages.get(i);int sample=OfflinePolicy.sample(p.w,width);
            int segment=TILE*sample;for(int top=0;top<p.h;top+=segment){int bottom=Math.min(p.h,top+segment);tiles.add(new Tile(i,sample,top,bottom,p.w,Math.max(1,(int)Math.round((double)(bottom-top)*Math.min(width,p.w*2)/p.w))));}}
        list.setAdapter(new BaseAdapter(){
            public int getCount(){return tiles.size();}public Object getItem(int i){return tiles.get(i);}public long getItemId(int i){return i;}
            public View getView(int i,View old,android.view.ViewGroup parent){Region v=old instanceof Region?(Region)old:new Region();v.bind(tiles.get(i));return v;}
        });
        int selection=0;for(int i=0;i<tiles.size();i++){Tile t=tiles.get(i);if(t.page<page||(t.page==page&&t.top<=offset))selection=i;else break;}list.setSelection(selection);
    }
    final class Region extends ImageView{
        Tile tile;Future<?> future;int generation;
        Region(){super(OfflineActivity.this);setScaleType(ScaleType.FIT_CENTER);setBackgroundColor(Color.BLACK);}
        void cancel(){generation++;if(future!=null){future.cancel(true);if(future instanceof Runnable)worker.remove((Runnable)future);future=null;}}
        void bind(Tile t){cancel();tile=t;setImageDrawable(null);setLayoutParams(new AbsListView.LayoutParams(-1,t.h));if(isAttachedToWindow())load();}
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();if(tile!=null)load();}
        @Override protected void onDetachedFromWindow(){cancel();setImageDrawable(null);super.onDetachedFromWindow();}
        void load(){
            if(closed||future!=null)return;final int token=generation;final Tile t=tile;final String id=entry.id;
            try{future=worker.submit(()->{
                Bitmap b=null;BitmapRegionDecoder decoder=null;
                try{decoder=BitmapRegionDecoder.newInstance(store.image(id,t.page).getPath(),false);BitmapFactory.Options opt=new BitmapFactory.Options();opt.inSampleSize=t.sample;
                    b=decoder.decodeRegion(new Rect(0,t.top,t.w,t.bottom),opt);
                }catch(Exception|OutOfMemoryError ignored){}finally{if(decoder!=null)decoder.recycle();}
                final Bitmap result=b;main.post(()->{if(closed||token!=generation||!isAttachedToWindow()){if(result!=null)result.recycle();return;}future=null;if(result!=null)setImageBitmap(result);else setContentDescription("Imagem indisponível");});
            });}catch(RejectedExecutionException ignored){postDelayed(()->{if(token==generation&&isAttachedToWindow())load();},160);}
        }
    }
    void immersive(boolean on){full=on;bar.setVisibility(on?View.GONE:View.VISIBLE);getWindow().getDecorView().setSystemUiVisibility(on?5894:0);}
    @Override public void onBackPressed(){if(full)immersive(false);else finish();}
    @Override public void onConfigurationChanged(android.content.res.Configuration c){super.onConfigurationChanged(c);list.post(()->{if(!closed)rebuild();});}
    @Override protected void onPause(){if(entry!=null&&!tiles.isEmpty()){Tile t=tiles.get(Math.min(list.getFirstVisiblePosition(),tiles.size()-1));getSharedPreferences("offlinePositions",MODE_PRIVATE).edit().putInt(entry.id+"/page",t.page).putInt(entry.id+"/offset",t.top).apply();}super.onPause();}
    @Override protected void onDestroy(){closed=true;worker.shutdownNow();super.onDestroy();}
}
