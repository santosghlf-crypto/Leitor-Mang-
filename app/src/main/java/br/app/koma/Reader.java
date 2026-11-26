package br.app.koma;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.content.res.Configuration;
import android.content.pm.ActivityInfo;
import java.lang.ref.WeakReference;
import java.util.concurrent.*;
import android.text.*;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.io.InputStream;
import java.net.URI;
import java.util.*;
import org.json.*;
import org.mozilla.geckoview.*;

public class Reader extends Activity {
    int BG=0xff101316, PANEL=0xff191e23, RAISED=0xff252c33, LINE=0xff313940,
        WHITE=0xfff1f5f3, MUTED=0xffa3afb4, MINT=0xffa8edc1, INK=0xff12261b, WARN=0xffffc68b;
    static final int PICK_COVER=42;
    OfflineStore offline;
    final ExecutorService offlineWorker=Executors.newSingleThreadExecutor();
    int themeChoice=0;
    GeckoRuntime engine;
    GeckoSession session;
    GeckoView browser;
    WebExtension blocker, pages;
    SharedPreferences prefs;
    JSONArray sites, history;
    LinearLayout root, bar, shelfContent, bottom, filters;
    EditText search;
    TextView readerTitle, readerHost;
    ProgressBar progress;
    String source, pending, pageUrl, pickSite, query="";
    int tab=3;
    boolean ready, failed, full, canBack, dashboard, favoritesOnly;
    final Handler ui = new Handler(Looper.getMainLooper());
    CoverLoader covers;
    ListView shelfList;
    ShelfAdapter shelfAdapter;
    LinearLayout shelfSummary;
    Runnable searchRefresh;
    String positionSource, positionUrl, lastRecordedUrl;
    int positionY;
    boolean positionDirty, positionScheduled, foreground;
    long pageStartedAt, lastBlockedNotice;
    int blockedNavigation;
    WebExtension.Port readerPort;
    TextView autoButton, previousButton, nextButton, readingPercent;
    LinearLayout readingTools;
    String nextChapter, previousChapter;
    boolean autoRunning, chapterDetected;
    int readingProgress;
    ReadingPositions readingPositions;
    String extractToken,extractUrl,extractKind;
    final Runnable extractTimeout=()->{if(extractToken!=null){boolean work="work".equals(extractKind)&&Objects.equals(extractUrl,pageUrl);clearExtraction();if(work&&session!=null&&foreground)editWork(null,source,pageUrl);else toast("A página não respondeu. Recarregue e tente novamente.");}};
    MangaLibrary manga;
    String activeWork,pendingWork,pickWork,workQuery="",authorizedWorkUrl;
    int workFilter=0,workSort=0;
    boolean workFavorites=false;
    WorkAdapter workAdapter;
    LinearLayout workSummary;
    final Runnable workFlush=this::saveWorks;
    boolean worksDirty,worksScheduled;
    static final int PICK_WORK_COVER=43;
    static final String[] WORK_STATUS={"Quero ler","Lendo","Concluído","Pausado"};
    final Runnable positionFlush = this::flushPosition;

    @Override public void onCreate(Bundle saved) {
        prefs=getSharedPreferences("shelf", MODE_PRIVATE);
        themeChoice=Math.max(0,Math.min(2,prefs.getInt("themeChoice",0)));
        setTheme(themeChoice==1?R.style.KomaAmoled:themeChoice==2?R.style.KomaMidnight:R.style.KomaTheme);
        super.onCreate(saved);applyPalette();
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        offline=new OfflineStore(getFilesDir());offlineWorker.execute(offline::cleanup);
        sites=array("sites"); history=array("history");
        readingPositions=new ReadingPositions(prefs.getString("chapterPositions","[]"));
        manga=new MangaLibrary(prefs.getString("mangaLibrary","[]"));if(sites.length()==0)tab=0;
        covers=new CoverLoader(getApplicationContext());
        if(saved!=null){pending=saved.getString("source");pickSite=saved.getString("pickSite");pendingWork=saved.getString("work");pickWork=saved.getString("pickWork");tab=saved.getInt("tab",0);}
        engine=GeckoRuntime.getDefault(this);
        engine.getSettings().setRemoteDebuggingEnabled(false);
        engine.getSettings().setAllowInsecureConnections(GeckoRuntimeSettings.HTTPS_ONLY);
        engine.getSettings().getContentBlocking().setSafeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT);
        prepare();
    }
    JSONArray array(String key) {
        try { return new JSONArray(prefs.getString(key,"[]")); } catch(Exception e){return new JSONArray();}
    }
    void prepare() {
        ready=false; failed=false; library();
        WebExtensionController c=engine.getWebExtensionController();
        c.ensureBuiltIn("resource://android/assets/ublock/","uBlock0@raymondhill.net")
            .then(ext->{blocker=ext; if(!ext.metaData.enabled)throw new IllegalStateException("uBlock desativado");
                return c.ensureBuiltIn("resource://android/assets/paginas/","pages@koma.local");})
            .accept(ext->{if(isDestroyed()||isFinishing())return;pages=ext;ready=true;library();
                if(pendingWork!=null&&manga.get(pendingWork)!=null){String id=pendingWork;pendingWork=null;pending=null;openWork(id);}
                else if(pending!=null){String s=pending;pending=null;open(s,null);}},
            e->{if(!isDestroyed()&&!isFinishing()){failed=true;library();}});
    }

    void applyPalette(){
        if(themeChoice==1){BG=0xff000000;PANEL=0xff101010;RAISED=0xff202020;LINE=0xff303030;MINT=0xffc3efb3;INK=0xff172411;}
        else if(themeChoice==2){BG=0xff0d1422;PANEL=0xff182337;RAISED=0xff25344d;LINE=0xff354761;MINT=0xffa5c8ff;INK=0xff11223e;MUTED=0xffb3c0d5;}
    }
    int dp(float n){return Math.round(getResources().getDisplayMetrics().density*n);}
    LinearLayout col(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);return l;}
    LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(0);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size*prefs.getFloat("fontScale",1f));t.setTextColor(color);t.setIncludeFontPadding(false);return t;}
    TextView bold(String s,int size,int color){TextView t=text(s,size,color);t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;}
    GradientDrawable bg(int c,int r){GradientDrawable d=new GradientDrawable();d.setColor(c);d.setCornerRadius(dp(r));return d;}
    GradientDrawable border(int c,int r,int stroke){GradientDrawable d=bg(c,r);d.setStroke(dp(1),stroke);return d;}
    void pad(View v,int x,int y){v.setPadding(dp(x),dp(y),dp(x),dp(y));}
    void space(LinearLayout l,int height){l.addView(new View(this),new LinearLayout.LayoutParams(1,dp(height)));}
    void fill(LinearLayout l,View v){l.addView(v,new LinearLayout.LayoutParams(-1,-2));}
    void weighted(LinearLayout l,View v,int height){l.addView(v,new LinearLayout.LayoutParams(0,height,1));}
    TextView button(String s,boolean primary,Runnable run){
        TextView t=bold(s,14,primary?INK:WHITE);t.setGravity(Gravity.CENTER);pad(t,12,10);t.setMinHeight(dp(48));
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x25ffffff),bg(primary?MINT:RAISED,13),null));
        t.setClickable(true);t.setFocusable(true);t.setOnClickListener(v->run.run());return t;
    }
    void line(LinearLayout l){View v=new View(this);v.setBackgroundColor(LINE);l.addView(v,new LinearLayout.LayoutParams(-1,dp(1)));}
    void root(boolean reading){
        if(searchRefresh!=null){ui.removeCallbacks(searchRefresh);searchRefresh=null;}
        shelfList=null;shelfAdapter=null;shelfSummary=null;search=null;workAdapter=null;workSummary=null;
        root=col();root.setBackgroundColor(reading?Color.BLACK:BG);
        root.setFitsSystemWindows(!full);setContentView(root);
    }
    String host(String s){try{return URI.create(s).getHost();}catch(Exception e){return "";}}
    String key(String k,String s){return k+"/"+s;}
    String name(String s){String h=host(s);return prefs.getString(key("name",s),h==null?s:h.replaceFirst("^www\\.",""));}
    boolean favorite(String s){return prefs.getBoolean(key("fav",s),false);}
    String mode(String s){return prefs.getString(key("mode",s),"fit");}
    boolean has(String s){for(int i=0;i<sites.length();i++)if(s.equals(sites.optString(i)))return true;return false;}
    String last(String s){return prefs.getString(key("last",s),s);}
    String modeLabel(String s){return "original".equals(s)?"Original":"continuous".equals(s)?"Webtoon":"Ajustar largura";}
    static String checked(String raw){return SecurityPolicy.normalize(raw,true);}
    String contextFor(String site){
        String k=key("context",site);String current=prefs.getString(k,"");
        if(current.isEmpty()){current="koma-"+UUID.randomUUID();prefs.edit().putString(k,current).apply();}
        return current;
    }
    void resetSiteSession(String site){
        new AlertDialog.Builder(this).setTitle("Limpar sessão desta fonte?")
            .setMessage("Os cookies e os dados de navegação desta fonte serão separados em uma nova sessão. Você pode precisar entrar novamente. A estante e o histórico continuam salvos.")
            .setNegativeButton("Cancelar",null).setPositiveButton("Limpar",(d,i)->{
                if(session!=null)closeReader();
                String old=prefs.getString(key("context",site),"");
                prefs.edit().putString(key("context",site),"koma-"+UUID.randomUUID()).apply();
                if(!old.isEmpty())engine.getStorageController().clearDataForSessionContext(old);
                toast("Nova sessão criada. Limpeza do contexto anterior solicitada.");
            }).show();
    }
    void noteBlocked(GeckoSession gs,String url,boolean gesture){
        if(gs!=session)return;
        blockedNavigation=Math.min(1000000,blockedNavigation+1);
        long now=SystemClock.elapsedRealtime();
        if(gesture&&now-lastBlockedNotice>1500){
            lastBlockedNotice=now;
            ui.post(()->{if(gs==session&&!isFinishing()){
                setFull(false);toast("Destino não autorizado. Adicione o site como outra fonte.");}});
        }
    }
    void saveSites(){prefs.edit().putString("sites",sites.toString()).apply();}

    void library(){
        full=false;dashboard=false;immersive();root(false);
        LinearLayout header=row();pad(header,20,15);
        TextView logo=bold("K",23,INK);logo.setTypeface(Typeface.create("sans-serif-black",Typeface.BOLD));logo.setGravity(Gravity.CENTER);logo.setBackground(bg(MINT,12));
        header.addView(logo,new LinearLayout.LayoutParams(dp(42),dp(42)));
        LinearLayout brand=col();brand.setPadding(dp(12),0,0,0);
        fill(brand,bold("KOMA",20,WHITE));space(brand,3);fill(brand,text("Seu leitor",11,MUTED));weighted(header,brand,-2);
        TextView protection=bold(ready?"uBlock ativo":failed?"Falha uBlock":"Iniciando",11,ready?MINT:WARN);
        pad(protection,10,9);protection.setBackground(bg(PANEL,20));protection.setOnClickListener(v->{tab=2;library();});
        header.addView(protection);fill(root,header);
        shelfContent=col();pad(shelfContent,20,9);
        if(tab==0||tab==3){
            shelfList=new ListView(this);shelfList.setDivider(null);shelfList.setDividerHeight(0);
            shelfList.setSelector(new ColorDrawable(Color.TRANSPARENT));shelfList.setCacheColorHint(Color.TRANSPARENT);
            shelfList.setItemsCanFocus(true);shelfList.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            shelfList.setClipToPadding(false);shelfList.setVerticalScrollBarEnabled(false);
            root.addView(shelfList,new LinearLayout.LayoutParams(-1,0,1));
            shelfList.addHeaderView(shelfContent,null,false);
            if(tab==3)buildWorks();else buildShelf();
        }else{
            ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);
            root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));scroll.addView(shelfContent);
            if(tab==1)buildHistory();else if(tab==4)buildDownloads();else buildSettings();
        }
        line(root);bottom=row();pad(bottom,12,7);
        String[] labels={"Estante","Fontes","Histórico","Ajustes"};int[] order={3,0,1,2};
        for(int i=0;i<order.length;i++){
            final int index=order[i];boolean selected=tab==index;LinearLayout b=col();b.setGravity(Gravity.CENTER);b.setMinimumHeight(dp(58));b.setBackground(bg(selected?PANEL:BG,14));
            NavIcon icon=new NavIcon(i<2?i:i+1,selected?MINT:MUTED);b.addView(icon,new LinearLayout.LayoutParams(dp(24),dp(24)));space(b,4);
            TextView title=bold(labels[i],10,selected?MINT:MUTED);title.setGravity(Gravity.CENTER);title.setSingleLine();fill(b,title);
            b.setContentDescription(labels[i]);b.setSelected(selected);b.setFocusable(true);b.setOnClickListener(v->{tab=index;query="";library();});weighted(bottom,b,-2);
        }
        fill(root,bottom);
    }

    void heading(LinearLayout l,String top,String sub){
        fill(l,bold(top,25,WHITE));space(l,5);fill(l,text(sub,13,MUTED));space(l,14);
    }
    int shelfColumns(){int custom=prefs.getInt("gridColumns",0);return custom==2||custom==3?custom:getResources().getDisplayMetrics().widthPixels/dp(1)>=650?3:2;}
    final class NavIcon extends View{
        final int type;final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        NavIcon(int type,int color){super(Reader.this);this.type=type;p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.7f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);}
        @Override protected void onDraw(Canvas c){super.onDraw(c);c.save();c.scale(getWidth()/24f,getHeight()/24f);
            if(type==0){c.drawRoundRect(3,4,9,20,1,1,p);c.drawRoundRect(11,4,15,20,1,1,p);c.save();c.rotate(-15,18,12);c.drawRoundRect(17,5,21,20,1,1,p);c.restore();}
            else if(type==1){c.drawCircle(12,12,9,p);c.drawOval(8,3,16,21,p);c.drawLine(3,12,21,12,p);}
            else if(type==2){c.drawLine(12,3,12,15,p);c.drawLine(7,10,12,15,p);c.drawLine(17,10,12,15,p);c.drawRoundRect(4,17,20,21,1,1,p);}
            else if(type==3){c.drawCircle(12,12,9,p);c.drawLine(12,7,12,12,p);c.drawLine(12,12,16,14,p);}
            else{c.drawCircle(12,12,4,p);c.drawCircle(12,12,8,p);for(int i=0;i<8;i++){c.save();c.rotate(i*45,12,12);c.drawLine(12,2,12,4,p);c.restore();}}
            c.restore();}
    }
    void buildShelf(){
        heading(shelfContent,"Minhas fontes",sites.length()==0?"Comece a montar sua coleção.":sites.length()+" fontes na sua coleção");
        fill(shelfContent,button("+  Adicionar fonte",true,()->editSite(null)));space(shelfContent,18);
        search=new EditText(this);search.setSingleLine();search.setTextSize(14);search.setTextColor(WHITE);search.setHintTextColor(MUTED);
        search.setHint("Buscar fontes");search.setText(query);search.setBackground(border(PANEL,13,LINE));pad(search,15,12);
        fill(shelfContent,search);space(shelfContent,12);filters=row();fill(shelfContent,filters);
        for(int i=0;i<2;i++){
            final boolean f=i==1;TextView chip=button(f?"★  Favoritos":"Todas as fontes",favoritesOnly==f,()->{
                if(favoritesOnly==f)return;favoritesOnly=f;
                if(searchRefresh!=null){ui.removeCallbacks(searchRefresh);searchRefresh=null;}refreshShelf();});
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(42),1);if(i==1)p.leftMargin=dp(8);filters.addView(chip,p);
        }
        space(shelfContent,15);shelfSummary=col();fill(shelfContent,shelfSummary);
        shelfAdapter=new ShelfAdapter(shelfColumns());shelfList.setAdapter(shelfAdapter);refreshShelf();
        final EditText field=search;
        search.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int a,int c,int z){}
            public void onTextChanged(CharSequence s,int a,int b,int c){
                if(field!=search)return;query=s.toString();
                if(searchRefresh!=null)ui.removeCallbacks(searchRefresh);
                searchRefresh=()->{searchRefresh=null;if(field==search&&shelfAdapter!=null)refreshShelf();};
                ui.postDelayed(searchRefresh,180);
            }
            public void afterTextChanged(Editable e){}
        });
        shelfContent.setFocusableInTouchMode(true);shelfContent.requestFocus();
    }
    void refreshShelf(){
        if(shelfAdapter==null||shelfSummary==null)return;
        for(int i=0;i<2;i++){TextView b=(TextView)filters.getChildAt(i);boolean selected=favoritesOnly==(i==1);
            b.setTextColor(selected?INK:WHITE);b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x25ffffff),bg(selected?MINT:RAISED,13),null));}
        ArrayList<String> shown=new ArrayList<>();String q=query.trim().toLowerCase(Locale.ROOT);
        for(int i=0;i<sites.length();i++){String site=sites.optString(i);if(checked(site)==null)continue;
            if((!favoritesOnly||favorite(site))&&(name(site)+" "+site).toLowerCase(Locale.ROOT).contains(q))shown.add(site);}
        shelfSummary.removeAllViews();
        if(shown.isEmpty()){
            LinearLayout empty=col();pad(empty,20,30);empty.setBackground(border(PANEL,20,LINE));
            TextView symbol=bold("▤",46,MINT);symbol.setGravity(Gravity.CENTER);fill(empty,symbol);space(empty,16);
            TextView title=bold(sites.length()==0?"Sua próxima história começa aqui":favoritesOnly?"Nenhuma fonte favorita":"Nenhuma fonte encontrada",19,WHITE);title.setGravity(Gravity.CENTER);fill(empty,title);space(empty,9);
            TextView desc=text(sites.length()==0?"Adicione um endereço HTTPS. Cada site vira uma fonte independente na estante.":"Altere a busca ou marque suas fontes favoritas.",14,MUTED);desc.setGravity(Gravity.CENTER);fill(empty,desc);
            if(sites.length()==0){space(empty,20);fill(empty,button("Adicionar meu primeiro site",true,()->editSite(null)));}fill(shelfSummary,empty);
        }else{
            if(!favoritesOnly&&q.isEmpty()&&history.length()>0){
                JSONObject h=history.optJSONObject(0);if(h!=null){String site=h.optString("source");if(has(site)){
                    LinearLayout resume=row();pad(resume,14,14);resume.setBackground(border(PANEL,16,LINE));
                    LinearLayout words=col();fill(words,bold("CONTINUAR LENDO",10,MINT));space(words,7);fill(words,bold(name(site),17,WHITE));space(words,4);fill(words,text("Retomar última página",12,MUTED));weighted(resume,words,-2);
                    resume.addView(button("Ler  ›",true,()->open(site,null)));fill(shelfSummary,resume);space(shelfSummary,18);}}}
            fill(shelfSummary,bold("Fontes  •  "+shown.size(),16,WHITE));space(shelfSummary,5);
        }
        shelfAdapter.replace(shown);
    }
    final class ShelfAdapter extends BaseAdapter {
        final int columns;
        List<String> items=Collections.emptyList();
        ShelfAdapter(int columns){this.columns=columns;}
        void replace(List<String> next){items=next;notifyDataSetChanged();}
        @Override public int getCount(){return (items.size()+columns-1)/columns;}
        @Override public Object getItem(int p){return items.get(p*columns);}
        @Override public long getItemId(int p){return p;}
        @Override public boolean isEnabled(int p){return false;}
        @Override public View getView(int p,View old,ViewGroup parent){
            ShelfRow r=old instanceof ShelfRow?(ShelfRow)old:new ShelfRow(columns);
            for(int j=0;j<columns;j++){int index=p*columns+j;r.tiles[j].bind(index<items.size()?items.get(index):null);}
            return r;
        }
    }
    final class ShelfRow extends LinearLayout {
        final Tile[] tiles;
        ShelfRow(int columns){super(Reader.this);setOrientation(HORIZONTAL);setGravity(Gravity.TOP);
            setPadding(dp(20),0,dp(20),dp(12));setLayoutParams(new AbsListView.LayoutParams(-1,-2));
            tiles=new Tile[columns];for(int i=0;i<columns;i++){
                Tile t=new Tile();tiles[i]=t;LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);
                if(i>0)p.leftMargin=dp(12);addView(t,p);
            }
        }
    }
    final class Tile extends LinearLayout {
        final Cover art;final TextView star,n,domain,more;
        String site;
        Tile(){super(Reader.this);setOrientation(VERTICAL);setBackground(border(PANEL,16,LINE));setClipToOutline(true);
            FrameLayout image=new FrameLayout(Reader.this);image.setBackground(bg(0xff273b34,16));addView(image,new LinearLayout.LayoutParams(-1,dp(135)));
            art=new Cover();image.addView(art,new FrameLayout.LayoutParams(-1,-1));
            star=bold("☆",21,MINT);star.setGravity(Gravity.CENTER);star.setBackground(bg(0xcc151a1d,20));
            FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(dp(38),dp(38),Gravity.TOP|Gravity.END);sp.setMargins(0,dp(8),dp(8),0);image.addView(star,sp);
            star.setOnClickListener(v->{String siteNow=site;if(siteNow==null)return;prefs.edit().putBoolean(key("fav",siteNow),!favorite(siteNow)).apply();
                if(favoritesOnly)refreshShelf();else updateStar();});
            image.setOnClickListener(v->{if(site!=null)open(site,null);});
            LinearLayout info=col();pad(info,12,12);n=bold("",16,WHITE);n.setSingleLine();n.setEllipsize(TextUtils.TruncateAt.END);fill(info,n);space(info,5);
            domain=text("",11,MUTED);domain.setSingleLine();domain.setEllipsize(TextUtils.TruncateAt.END);fill(info,domain);space(info,10);
            LinearLayout actions=row();weighted(actions,button("Ler  ›",true,()->{if(site!=null)open(site,null);}),dp(42));
            more=button("⋯",false,()->{if(site!=null)siteMenu(site);});LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(dp(42),dp(42));mp.leftMargin=dp(6);actions.addView(more,mp);
            fill(info,actions);fill(this,info);setOnLongClickListener(v->{if(site!=null)siteMenu(site);return true;});
        }
        void updateStar(){boolean f=site!=null&&favorite(site);star.setText(f?"★":"☆");star.setContentDescription(f?"Remover favorito":"Adicionar favorito");}
        void bind(String siteNow){site=siteNow;setVisibility(site==null?INVISIBLE:VISIBLE);art.bind(site);
            if(site==null)return;n.setText(name(site));domain.setText(host(site));more.setContentDescription("Opções de "+name(site));updateStar();}
    }
    final class Cover extends View{
        final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        final RectF target=new RectF();
        final Typeface face=Typeface.create("sans-serif-medium",Typeface.BOLD);
        final int[] palette={0xff23483e,0xff36425c,0xff4a3651,0xff514437,0xff244a52};
        String site,uri="",letter="K",requestedKey="";
        Bitmap image;Future<?> task;int token,base=palette[0],retryCount;boolean finishedRequest;
        final Runnable retry=()->requestCover();
        Cover(){super(Reader.this);}
        void bind(String next){bindArt(next,next==null?"":prefs.getString(key("cover",next),""),next==null?"":name(next));}
        void bindArt(String next,String nextUri,String title){
            title=title.trim();
            String nextLetter=title.isEmpty()?"K":title.substring(0,title.offsetByCodePoints(0,1)).toUpperCase(Locale.ROOT);
            if(Objects.equals(site,next)&&uri.equals(nextUri)&&letter.equals(nextLetter)){requestCover();return;}
            cancel();site=next;uri=nextUri;letter=nextLetter;image=null;requestedKey="";finishedRequest=false;
            base=palette[Math.floorMod(next==null?0:next.hashCode(),palette.length)];
            setContentDescription(next==null?null:"Capa de "+title);invalidate();requestCover();
        }
        void cancel(){token++;removeCallbacks(retry);covers.cancel(task);task=null;retryCount=0;}
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();requestCover();}
        @Override protected void onDetachedFromWindow(){cancel();image=null;requestedKey="";finishedRequest=false;super.onDetachedFromWindow();}
        @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);requestCover();}
        void requestCover(){
            if(site==null||uri.isEmpty()||!isAttachedToWindow()||getWidth()==0||getHeight()==0||isDestroyed())return;
            int size=PerfPolicy.coverBucket(Math.max(getWidth(),getHeight()));
            String cacheKey=uri+"|"+size;
            if(cacheKey.equals(requestedKey)&&(image!=null||task!=null||finishedRequest))return;
            if(!cacheKey.equals(requestedKey)){cancel();image=null;finishedRequest=false;requestedKey=cacheKey;}
            Bitmap hit=covers.get(cacheKey);if(hit!=null){image=hit;invalidate();return;}
            final int id=token;WeakReference<Cover> ref=new WeakReference<>(this);
            task=covers.load(uri,cacheKey,size,bitmap->{
                Cover v=ref.get();if(v==null||id!=v.token||!v.isAttachedToWindow()||!cacheKey.equals(v.requestedKey))return;
                v.task=null;v.finishedRequest=true;v.image=bitmap;v.invalidate();
            });
            if(task==null&&retryCount++<3){removeCallbacks(retry);postDelayed(retry,180);}
        }
        @Override protected void onDraw(Canvas c){super.onDraw(c);int w=getWidth(),h=getHeight();Bitmap b=image;
            if(b!=null){float scale=Math.max((float)w/b.getWidth(),(float)h/b.getHeight());float dw=b.getWidth()*scale,dh=b.getHeight()*scale;
                target.set((w-dw)/2,(h-dh)/2,(w+dw)/2,(h+dh)/2);c.drawBitmap(b,null,target,p);return;}
            c.drawColor(base);p.setColor(0x22ffffff);c.drawCircle(w*.90f,h*.1f,h*.80f,p);c.drawCircle(w*.10f,h*.95f,h*.55f,p);
            p.setColor(0x18ffffff);c.drawRoundRect(w*.25f,h*.15f,w*.75f,h*.85f,dp(16),dp(16),p);
            p.setColor(WHITE);p.setTypeface(face);p.setTextSize(dp(48));p.setTextAlign(Paint.Align.CENTER);c.drawText(letter,w/2f,h/2f-(p.ascent()+p.descent())/2,p);
        }
    }

    void saveWorks(){ui.removeCallbacks(workFlush);worksScheduled=false;if(worksDirty){prefs.edit().putString("mangaLibrary",manga.serialize()).apply();worksDirty=false;}}
    void dirtyWorks(boolean immediately){worksDirty=true;if(immediately){saveWorks();return;}if(!worksScheduled){worksScheduled=true;ui.postDelayed(workFlush,4000);}}
    void buildWorks(){
        heading(shelfContent,"Estante",manga.size()+" obras acompanhadas");
        fill(shelfContent,button(sites.length()==0?"Adicionar primeira fonte":"+ Adicionar obra",true,()->{if(sites.length()==0){tab=0;editSite(null);}else editWork(null,null,null);}));space(shelfContent,12);
        search=new EditText(this);search.setSingleLine();search.setTextSize(14);search.setTextColor(WHITE);search.setHintTextColor(MUTED);
        search.setHint("Buscar mangá, manhwa ou fonte");search.setText(workQuery);search.setBackground(border(PANEL,13,LINE));pad(search,15,12);fill(shelfContent,search);space(shelfContent,10);
        LinearLayout choices=row();
        weighted(choices,button("Filtrar",false,()->new AlertDialog.Builder(this).setTitle("Status da obra").setSingleChoiceItems(new String[]{"Todas","Quero ler","Lendo","Concluído","Pausado"},workFilter,(d,i)->{workFilter=i;d.dismiss();refreshWorks();}).show()),dp(46));
        TextView sort=button("Ordenar",false,()->new AlertDialog.Builder(this).setTitle("Ordenar biblioteca").setSingleChoiceItems(new String[]{"Leitura recente","Nome A–Z","Adicionadas recentemente"},workSort,(d,i)->{workSort=i;d.dismiss();refreshWorks();}).show());
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(0,dp(46),1);sp.leftMargin=dp(6);choices.addView(sort,sp);
        TextView fav=button(workFavorites?"★":"☆",workFavorites,()->{workFavorites=!workFavorites;library();});fav.setContentDescription("Filtrar favoritos");LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(dp(52),dp(46));fp.leftMargin=dp(6);choices.addView(fav,fp);fill(shelfContent,choices);space(shelfContent,12);
        workSummary=col();fill(shelfContent,workSummary);workAdapter=new WorkAdapter(shelfColumns());shelfList.setAdapter(workAdapter);refreshWorks();
        final EditText field=search;field.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int z){}public void onTextChanged(CharSequence s,int a,int b,int c){
            if(field!=search)return;workQuery=s.toString();if(searchRefresh!=null)ui.removeCallbacks(searchRefresh);
            searchRefresh=()->{searchRefresh=null;if(field==search&&workAdapter!=null)refreshWorks();};ui.postDelayed(searchRefresh,180);}
            public void afterTextChanged(Editable e){}});
        shelfContent.setFocusableInTouchMode(true);shelfContent.requestFocus();
    }
    void refreshWorks(){
        if(workAdapter==null||workSummary==null)return;
        List<MangaLibrary.Work> list=manga.filtered(workQuery,workFilter,workFavorites,workSort,w->name(w.source));
        workSummary.removeAllViews();MangaLibrary.Work recent=manga.recent();
        if(recent!=null&&has(recent.source)&&workFilter==0&&!workFavorites&&workQuery.trim().isEmpty()){
            LinearLayout resume=row();pad(resume,14,14);resume.setBackground(border(PANEL,16,LINE));
            LinearLayout words=col();fill(words,bold("CONTINUAR LENDO",10,MINT));space(words,6);TextView title=bold(recent.title,17,WHITE);title.setMaxLines(2);fill(words,title);space(words,4);
            fill(words,text(recent.chapter+" • "+recent.percent+"%",12,MUTED));weighted(resume,words,-2);resume.addView(button("Ler ›",true,()->openWork(recent.id)));fill(workSummary,resume);space(workSummary,16);
        }
        if(list.isEmpty()){
            LinearLayout empty=col();pad(empty,18,24);empty.setBackground(border(PANEL,18,LINE));
            fill(empty,bold(manga.size()==0?"Sua coleção começa aqui":"Nenhuma obra neste filtro",18,WHITE));space(empty,8);
            fill(empty,text(manga.size()==0?"Abra uma fonte, escolha um mangá e use Salvar na biblioteca. Ou adicione uma obra manualmente.":"Experimente mudar o filtro ou a busca.",14,MUTED));space(empty,12);
            if(manga.size()==0)fill(empty,button("Explorar minhas fontes",false,()->{tab=0;library();}));fill(workSummary,empty);
        }else{fill(workSummary,text((workFilter==0?"Todas":WORK_STATUS[workFilter-1])+" • "+list.size()+" obras",13,MUTED));space(workSummary,10);}
        workAdapter.items=list;workAdapter.notifyDataSetChanged();
    }
    final class WorkAdapter extends BaseAdapter{
        final int columns;List<MangaLibrary.Work> items=Collections.emptyList();
        WorkAdapter(int c){columns=c;}
        public int getCount(){return (items.size()+columns-1)/columns;}
        public Object getItem(int p){return items.get(p*columns);}
        public long getItemId(int p){return p;}
        public boolean isEnabled(int p){return false;}
        public View getView(int p,View old,ViewGroup parent){
            WorkRow row=old instanceof WorkRow?(WorkRow)old:new WorkRow(columns);
            for(int i=0;i<columns;i++){int index=p*columns+i;row.tiles[i].bind(index<items.size()?items.get(index):null);}return row;
        }
    }
    final class WorkRow extends LinearLayout{
        final WorkTile[] tiles;
        WorkRow(int count){super(Reader.this);setOrientation(HORIZONTAL);setGravity(Gravity.TOP);setPadding(dp(20),0,dp(20),dp(12));
            setLayoutParams(new AbsListView.LayoutParams(-1,-2));tiles=new WorkTile[count];
            for(int i=0;i<count;i++){tiles[i]=new WorkTile();LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);if(i>0)p.leftMargin=dp(10);addView(tiles[i],p);}}
    }
    final class WorkTile extends LinearLayout{
        final Cover cover;final TextView title,detail,chapter,star,more;final ProgressBar progress;String id;
        WorkTile(){super(Reader.this);setOrientation(VERTICAL);setBackground(border(PANEL,16,LINE));setClipToOutline(true);
            FrameLayout image=new FrameLayout(Reader.this);addView(image,new LinearLayout.LayoutParams(-1,dp(prefs.getBoolean("compactCards",false)?145:190)));cover=new Cover();image.addView(cover,new FrameLayout.LayoutParams(-1,-1));
            star=bold("☆",22,MINT);star.setGravity(Gravity.CENTER);star.setBackground(bg(0xcc151a1d,20));
            FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(dp(40),dp(40),Gravity.END|Gravity.TOP);sp.setMargins(0,dp(8),dp(8),0);image.addView(star,sp);
            star.setOnClickListener(v->{MangaLibrary.Work w=manga.get(id);if(w==null)return;w.favorite=!w.favorite;dirtyWorks(true);if(workFavorites)refreshWorks();else updateStar(w);});
            image.setOnClickListener(v->{if(id!=null)openWork(id);});
            LinearLayout info=col();pad(info,12,12);title=bold("",16,WHITE);title.setMaxLines(2);title.setMinHeight(dp(40));title.setEllipsize(TextUtils.TruncateAt.END);fill(info,title);
            detail=text("",11,MUTED);detail.setSingleLine();detail.setEllipsize(TextUtils.TruncateAt.END);fill(info,detail);space(info,6);
            chapter=text("",12,MINT);chapter.setSingleLine();chapter.setEllipsize(TextUtils.TruncateAt.END);fill(info,chapter);space(info,6);
            progress=new ProgressBar(Reader.this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);progress.setProgressTintList(ColorStateList.valueOf(MINT));info.addView(progress,new LinearLayout.LayoutParams(-1,dp(4)));space(info,10);
            LinearLayout actions=row();weighted(actions,button("Ler ›",true,()->{if(id!=null)openWork(id);}),dp(45));
            more=button("⋯",false,()->{if(id!=null)workMenu(id);});LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(dp(42),dp(45));mp.leftMargin=dp(6);actions.addView(more,mp);fill(info,actions);fill(this,info);
        }
        void updateStar(MangaLibrary.Work w){star.setText(w.favorite?"★":"☆");star.setContentDescription(w.favorite?"Remover favorito":"Favoritar obra");}
        void bind(MangaLibrary.Work w){id=w==null?null:w.id;setVisibility(w==null?INVISIBLE:VISIBLE);
            cover.bindArt(w==null?null:w.id,w==null?"":w.cover,w==null?"":w.title);
            if(w==null)return;title.setText(w.title);detail.setText(WORK_STATUS[w.status]+" • "+name(w.source));
            chapter.setText(w.chapter+" • "+w.percent+"%");progress.setProgress(w.percent);more.setContentDescription("Opções de "+w.title);updateStar(w);}
    }
    void editWork(String id,String suggestedSource,String suggestedUrl){editWork(id,suggestedSource,suggestedUrl,"","");}
    void editWork(String id,String suggestedSource,String suggestedUrl,String suggestedTitle,String suggestedChapter){
        pauseAuto();final String openedPage=pageUrl;MangaLibrary.Work existing=manga.get(id);
        ArrayList<String> options=new ArrayList<>();ArrayList<String> labels=new ArrayList<>();
        for(int i=0;i<sites.length();i++){String site=sites.optString(i);if(checked(site)!=null){options.add(site);labels.add(name(site));}}
        if(options.isEmpty()){toast("Adicione uma fonte primeiro.");return;}
        ScrollView scroll=new ScrollView(this);LinearLayout form=col();pad(form,22,12);scroll.addView(form);
        EditText title=new EditText(this);title.setSingleLine();title.setHint("Nome da obra");title.setFilters(new InputFilter[]{new InputFilter.LengthFilter(120)});title.setText(existing==null?suggestedTitle:existing.title);fill(form,text("Nome",13,MUTED));fill(form,title);space(form,12);
        Spinner sources=new Spinner(this);sources.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,labels));String chosen=existing!=null?existing.source:suggestedSource;int selected=options.indexOf(chosen);sources.setSelection(Math.max(0,selected));sources.setEnabled(existing==null&&suggestedSource==null);
        fill(form,text("Fonte",13,MUTED));fill(form,sources);space(form,12);
        EditText home=new EditText(this);home.setSingleLine();home.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);home.setHint("Endereço da obra ou capítulo inicial");home.setText(existing!=null?existing.home:suggestedUrl==null?"":suggestedUrl);
        fill(form,text("Endereço de entrada",13,MUTED));fill(form,home);space(form,12);
        EditText chapter=new EditText(this);chapter.setSingleLine();chapter.setHint("Ex.: Capítulo 12");chapter.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});chapter.setText(existing!=null?existing.chapter:suggestedChapter);fill(form,text("Capítulo / anotação",13,MUTED));fill(form,chapter);space(form,12);
        Spinner status=new Spinner(this);status.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,WORK_STATUS));status.setSelection(existing==null?0:existing.status);fill(form,text("Status",13,MUTED));fill(form,status);space(form,8);
        fill(form,text("O endereço deve pertencer à fonte. A anotação de capítulo pode ser editada manualmente.",12,MUTED));
        AlertDialog d=new AlertDialog.Builder(this).setTitle(existing==null?"Salvar obra":"Editar obra").setView(scroll).setNegativeButton("Cancelar",null).setPositiveButton("Salvar",null).create();d.show();
        d.getButton(-1).setOnClickListener(v->{
            String t=title.getText().toString().trim(),site=options.get(sources.getSelectedItemPosition()),url=checked(home.getText().toString());
            if(t.isEmpty()){title.setError("Informe o nome.");return;}
            if(!SecurityPolicy.sameSite(site,url)){home.setError("Endereço HTTPS da fonte selecionada.");return;}
            MangaLibrary.Work w=existing==null?manga.add(site,url,t,chapter.getText().toString(),status.getSelectedItemPosition()):manga.edit(id,url,t,chapter.getText().toString(),status.getSelectedItemPosition());
            if(w==null){home.setError("Obra duplicada, limite atingido ou endereço inválido.");return;}
            dirtyWorks(true);d.dismiss();
            if(session!=null&&!dashboard&&site.equals(source)&&SecurityPolicy.sameSite(site,pageUrl)){
                if(existing==null&&Objects.equals(suggestedSource,source)&&Objects.equals(openedPage,pageUrl)){activeWork=w.id;authorizedWorkUrl=pageUrl;readerTitle.setText(w.title);if(chapterDetected){String label=w.chapter;manga.update(w.id,source,pageUrl,readingProgress);w.chapter=label;}dirtyWorks(true);}
                toast("Obra salva na biblioteca.");
            }else{tab=3;library();}
        });
    }
    void workMenu(String id){
        MangaLibrary.Work w=manga.get(id);if(w==null)return;
        new AlertDialog.Builder(this).setTitle(w.title).setItems(new String[]{"Editar obra","Escolher capa","Remover capa","Abrir página da obra","Alterar status","Remover da biblioteca"},(d,i)->{
            MangaLibrary.Work current=manga.get(id);if(current==null)return;
            switch(i){case 0:editWork(id,null,null);break;
                case 1:pickWork=id;Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.setType("image/*");intent.addCategory(Intent.CATEGORY_OPENABLE);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(intent,PICK_WORK_COVER);break;
                case 2:replaceWorkCover(current,"");library();break;
                case 3:openWorkAt(id,current.home);break;
                case 4:new AlertDialog.Builder(this).setTitle("Status").setSingleChoiceItems(WORK_STATUS,current.status,(a,b)->{current.status=b;dirtyWorks(true);a.dismiss();refreshWorks();}).show();break;
                case 5:new AlertDialog.Builder(this).setTitle("Remover obra?").setMessage("A fonte e as outras obras permanecem salvas.").setNegativeButton("Cancelar",null).setPositiveButton("Remover",(a,b)->{replaceWorkCover(current,"");manga.remove(id);dirtyWorks(true);library();}).show();break;
            }
        }).show();
    }
    void replaceWorkCover(MangaLibrary.Work w,String uri){
        String old=w.cover;w.cover=uri;dirtyWorks(true);covers.invalidate(old);covers.invalidate(uri);if(!Objects.equals(old,uri))releaseCoverIfUnused(old);
    }
    void releaseCoverIfUnused(String uri){
        if(uri==null||uri.isEmpty())return;
        for(int i=0;i<sites.length();i++)if(uri.equals(prefs.getString(key("cover",sites.optString(i)),"")))return;
        if(manga.usesCover(uri))return;
        try{getContentResolver().releasePersistableUriPermission(Uri.parse(uri),Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
    }
    void openWork(String id){MangaLibrary.Work w=manga.get(id);if(w!=null)openWorkAt(id,w.resume);}
    void openWorkAt(String id,String url){
        MangaLibrary.Work w=manga.get(id);if(w==null||!has(w.source)){toast("A fonte desta obra não está disponível.");return;}
        if(!ready||session!=null){toast("O leitor ainda não está disponível.");return;}
        String safe=SecurityPolicy.normalize(url,false);if(!SecurityPolicy.sameSite(w.source,safe)){toast("Endereço da obra inválido.");return;}
        activeWork=id;authorizedWorkUrl=safe;tab=3;startReader(w.source,safe,false);
    }
    void editSite(String existing){
        LinearLayout form=col();pad(form,22,10);EditText title=new EditText(this),url=new EditText(this);
        title.setSingleLine();title.setHint("Nome da fonte (opcional)");url.setSingleLine();url.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);url.setHint("https://site.com");
        if(existing!=null){title.setText(name(existing));url.setText(existing);url.setEnabled(false);}
        fill(form,text("Nome",12,MUTED));fill(form,title);space(form,12);fill(form,text("Endereço",12,MUTED));fill(form,url);space(form,10);
        fill(form,text("O site abre dentro do Koma, com o bloqueador ativo.",12,MUTED));
        AlertDialog d=new AlertDialog.Builder(this).setTitle(existing==null?"Nova fonte":"Editar fonte").setView(form).setNegativeButton("Cancelar",null).setPositiveButton("Salvar",null).create();d.show();
        d.getButton(-1).setOnClickListener(v->{String s=existing==null?checked(url.getText().toString()):existing;
            if(s==null){url.setError("Informe um endereço HTTPS válido.");return;}if(existing==null&&has(s)){url.setError("Essa fonte já existe.");return;}
            String n=title.getText().toString().trim();if(n.length()>100)n=n.substring(0,100);if(n.isEmpty())n=host(s);prefs.edit().putString(key("name",s),n).apply();
            if(existing==null){sites.put(s);saveSites();}d.dismiss();tab=0;query="";favoritesOnly=false;library();});
    }
    void siteMenu(String s){
        String[] opts={"Editar nome","Escolher capa","Remover capa",favorite(s)?"Remover dos favoritos":"Adicionar aos favoritos","Configurações de leitura","Abrir página inicial","Limpar cookies e sessão","Remover fonte","Perfil da fonte (avançado)"};
        new AlertDialog.Builder(this).setTitle(name(s)).setItems(opts,(d,i)->{
            switch(i){case 0:editSite(s);break;case 1:pickSite=s;Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.setType("image/*");intent.addCategory(Intent.CATEGORY_OPENABLE);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(intent,PICK_COVER);break;
                case 2:forgetCover(s);library();break;case 3:prefs.edit().putBoolean(key("fav",s),!favorite(s)).apply();library();break;
                case 4:siteSettings(s);break;case 5:open(s,s);break;case 6:resetSiteSession(s);break;case 7:new AlertDialog.Builder(this).setTitle("Remover "+name(s)+"?").setMessage("A fonte só pode ser removida se não houver obras vinculadas.").setNegativeButton("Cancelar",null).setPositiveButton("Remover",(a,b)->remove(s)).show();break;case 8:extensionMenu(s);break;}
        }).show();
    }
    void forgetCover(String s){String u=prefs.getString(key("cover",s),"");prefs.edit().remove(key("cover",s)).apply();covers.invalidate(u);
        releaseCoverIfUnused(u);}
    @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);
        if(req==PICK_WORK_COVER){
            MangaLibrary.Work w=manga.get(pickWork);pickWork=null;
            if(w!=null&&result==RESULT_OK&&data!=null&&data.getData()!=null){
                Uri u=data.getData();try{getContentResolver().takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION);replaceWorkCover(w,u.toString());library();}catch(Exception e){toast("Não foi possível salvar essa capa.");}}
            return;
        }
        if(req!=PICK_COVER)return;String s=pickSite;pickSite=null;if(result!=RESULT_OK||data==null||data.getData()==null||s==null||!has(s))return;
        Uri u=data.getData();try{getContentResolver().takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if(!u.toString().equals(prefs.getString(key("cover",s),"")))forgetCover(s);
            prefs.edit().putString(key("cover",s),u.toString()).apply();covers.invalidate(u.toString());library();}
        catch(Exception e){toast("Não foi possível salvar essa capa.");}}
    void remove(String s){
        if(!manga.forSource(s).isEmpty()){toast("Remova ou preserve suas obras antes de excluir a fonte.");return;}
        String old=prefs.getString(key("context",s),"");
        if(!old.isEmpty())engine.getStorageController().clearDataForSessionContext(old);
        readingPositions.removeSource(s);
        prefs.edit().remove(key("context",s)).putString("chapterPositions",readingPositions.serialize()).apply();forgetCover(s);for(int i=0;i<sites.length();i++)if(s.equals(sites.optString(i))){sites.remove(i);break;}
        for(int i=history.length()-1;i>=0;i--){JSONObject h=history.optJSONObject(i);if(h!=null&&s.equals(h.optString("source")))history.remove(i);}
        SharedPreferences.Editor ed=prefs.edit();for(String k:new String[]{"name","last","fav","mode","fullscreen","position","positionUrl","brightness","rotation","keepAwake","doubleTap","autoNext","scrollSpeed","adapter"})ed.remove(key(k,s));
        ed.putString("history",history.toString()).putString("sites",sites.toString()).apply();library();}
    int scrollSpeed(String s){return ReadingPolicy.speed(prefs.getInt(key("scrollSpeed",s),100));}
    void siteSettings(String s){
        pauseAuto();
        ScrollView scroll=new ScrollView(this);LinearLayout box=col();pad(box,22,10);scroll.addView(box);
        fill(box,bold("Exibição",16,WHITE));space(box,8);
        RadioGroup modes=new RadioGroup(this);String[] values={"fit","continuous","original"},labels={"Ajustar largura","Webtoon • remover espaços","Original do site"};
        for(int i=0;i<3;i++){RadioButton radio=new RadioButton(this);radio.setId(100+i);radio.setText(labels[i]);radio.setButtonTintList(ColorStateList.valueOf(MINT));modes.addView(radio);if(values[i].equals(mode(s)))modes.check(100+i);}
        fill(box,modes);space(box,10);
        Switch fs=new Switch(this);fs.setText("Abrir em tela cheia");fs.setChecked(prefs.getBoolean(key("fullscreen",s),true));fill(box,fs);
        Switch awake=new Switch(this);awake.setText("Manter tela ligada durante a leitura");awake.setChecked(prefs.getBoolean(key("keepAwake",s),true));fill(box,awake);
        space(box,18);fill(box,bold("Brilho e rotação",16,WHITE));space(box,8);
        Switch system=new Switch(this);system.setText("Usar brilho do sistema");int oldBrightness=prefs.getInt(key("brightness",s),-1);system.setChecked(oldBrightness<0);fill(box,system);
        SeekBar light=new SeekBar(this);light.setMax(95);light.setProgress(ReadingPolicy.brightness(oldBrightness<0?60:oldBrightness)-5);light.setEnabled(!system.isChecked());fill(box,light);
        TextView lightLabel=text("Brilho: "+(light.getProgress()+5)+"%",12,MUTED);fill(box,lightLabel);
        system.setOnCheckedChangeListener((v,on)->light.setEnabled(!on));
        light.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean user){lightLabel.setText("Brilho: "+(p+5)+"%");}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
        RadioGroup rotation=new RadioGroup(this);String[] orientations={"Automática (sistema)","Retrato","Paisagem","Bloquear posição atual"};
        for(int i=0;i<orientations.length;i++){RadioButton b=new RadioButton(this);b.setId(200+i);b.setText(orientations[i]);b.setButtonTintList(ColorStateList.valueOf(MINT));rotation.addView(b);if(i==prefs.getInt(key("rotation",s),0))rotation.check(200+i);}fill(box,rotation);
        space(box,18);fill(box,bold("Gestos e rolagem",16,WHITE));space(box,8);
        fill(box,text("A rolagem automática para no final do capítulo. Use Voltar para mostrar os controles.",12,MUTED));space(box,8);
        SeekBar speed=new SeekBar(this);speed.setMax(27);speed.setProgress((scrollSpeed(s)-30)/10);
        TextView speedLabel=text("Velocidade: "+(30+speed.getProgress()*10)+" px/s",13,MUTED);fill(box,speedLabel);fill(box,speed);
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean user){speedLabel.setText("Velocidade: "+(30+p*10)+" px/s");}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
        space(box,12);
        new AlertDialog.Builder(this).setTitle("Leitura • "+name(s)).setView(scroll).setNegativeButton("Cancelar",null).setPositiveButton("Salvar",(d,i)->{
            int selected=modes.getCheckedRadioButtonId()-100;if(selected<0||selected>2)selected=0;
            int orientation=rotation.getCheckedRadioButtonId()-200;if(orientation<0||orientation>3)orientation=0;
            boolean modeChanged=!mode(s).equals(values[selected]);
            prefs.edit().putString(key("mode",s),values[selected]).putBoolean(key("fullscreen",s),fs.isChecked())
                .putBoolean(key("keepAwake",s),awake.isChecked()).putInt(key("brightness",s),system.isChecked()?-1:light.getProgress()+5)
                .putInt(key("rotation",s),orientation).remove(key("doubleTap",s))
                .remove(key("autoNext",s)).putInt(key("scrollSpeed",s),30+speed.getProgress()*10).apply();
            if(session!=null&&!dashboard&&s.equals(source)){
                applyReadingWindow(s);if(modeChanged){flushPosition();session.reload();}else sendReaderCommand("settings",readingConfig(s,pageUrl));
            }else library();
        }).show();
    }
    void applyReadingWindow(String s){
        WindowManager.LayoutParams p=getWindow().getAttributes();
        int light=prefs.getInt(key("brightness",s),-1);
        p.screenBrightness=light<0?-1f:ReadingPolicy.brightness(light)/100f;getWindow().setAttributes(p);
        if(prefs.getBoolean(key("keepAwake",s),true))getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        int rotation=prefs.getInt(key("rotation",s),0);
        int wanted=rotation==1?ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:rotation==2?ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:rotation==3?ActivityInfo.SCREEN_ORIENTATION_LOCKED:ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        if(getRequestedOrientation()!=wanted)setRequestedOrientation(wanted);
    }
    void resetReadingWindow(){
        WindowManager.LayoutParams p=getWindow().getAttributes();p.screenBrightness=-1f;getWindow().setAttributes(p);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if(getRequestedOrientation()!=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }
    JSONObject readingConfig(String s,String url){
        JSONObject result=new JSONObject();try{
            result.put("mode",mode(s));result.put("y",savedPosition(s,url));result.put("predecode",false);
            
            result.put("speed",scrollSpeed(s));result.put("adapter",adapterFor(s).json());
        }catch(Exception ignored){}return result;
    }
    void record(String s,String url){
        if(!has(s)||url==null||url.length()>4096||url.equals(lastRecordedUrl))return;
        lastRecordedUrl=url;
        for(int i=history.length()-1;i>=0;i--){JSONObject h=history.optJSONObject(i);if(h!=null&&s.equals(h.optString("source"))&&url.equals(h.optString("url")))history.remove(i);}
        JSONObject item=new JSONObject();try{item.put("source",s);item.put("url",url);item.put("time",System.currentTimeMillis());}catch(Exception ignored){}
        JSONArray next=new JSONArray();next.put(item);for(int i=0;i<history.length()&&i<59;i++)next.put(history.optJSONObject(i));history=next;
        prefs.edit().putString(key("last",s),url).putString("history",history.toString()).apply();
    }
    void buildHistory(){
        heading(shelfContent,"Histórico","Volte às suas páginas recentes.");
        if(history.length()==0){fill(shelfContent,text("Nenhuma página visitada por enquanto.",15,MUTED));return;}
        fill(shelfContent,button("Limpar histórico",false,()->new AlertDialog.Builder(this).setTitle("Limpar histórico?").setMessage("A última página e as posições de leitura serão apagadas. As obras permanecerão salvas, mas seu progresso será reiniciado.").setNegativeButton("Cancelar",null).setPositiveButton("Limpar",(d,i)->{history=new JSONArray();readingPositions.clear();manga.clearProgress();dirtyWorks(true);SharedPreferences.Editor e=prefs.edit().remove("history").remove("chapterPositions");for(int j=0;j<sites.length();j++){String s=sites.optString(j);for(String k:new String[]{"last","position","positionUrl"})e.remove(key(k,s));}e.apply();library();}).show()));space(shelfContent,14);
        for(int i=0;i<history.length();i++){JSONObject h=history.optJSONObject(i);if(h==null)continue;String s=h.optString("source"),u=h.optString("url");if(!has(s)||checked(u)==null)continue;
            LinearLayout item=col();pad(item,15,14);item.setBackground(border(PANEL,14,LINE));fill(item,bold(name(s)+"  ›",16,WHITE));space(item,6);
            TextView address=text(u,12,MUTED);address.setMaxLines(2);address.setEllipsize(TextUtils.TruncateAt.END);fill(item,address);space(item,6);
            fill(item,text(android.text.format.DateUtils.getRelativeTimeSpanString(h.optLong("time")).toString(),11,MINT));item.setOnClickListener(v->open(s,u));fill(shelfContent,item);space(shelfContent,8);}
    }
    void buildSettings(){
        heading(shelfContent,"Ajustes","Controle sua experiência de leitura.");
        buildAppearance();space(shelfContent,16);
        LinearLayout panel=col();pad(panel,16,16);panel.setBackground(border(PANEL,16,LINE));
        fill(panel,bold("Proteção de navegação",18,WHITE));space(panel,7);fill(panel,text(ready?"● uBlock Origin habilitado":failed?"Falha ao carregar extensão":"Iniciando extensão",14,ready?MINT:WARN));space(panel,8);
        fill(panel,text("O painel oficial permite ajustar filtros e consultar os números do bloqueador. Extensão habilitada não garante que todos os anúncios serão bloqueados.",13,MUTED));space(panel,14);
        fill(panel,button("Abrir painel do uBlock",true,this::openDashboard));if(failed){space(panel,8);fill(panel,button("Tentar novamente",false,this::prepare));}
        fill(shelfContent,panel);space(shelfContent,16);
        LinearLayout privacy=col();pad(privacy,16,16);privacy.setBackground(border(PANEL,16,LINE));
        fill(privacy,bold("Privacidade e segurança",18,WHITE));space(privacy,9);
        fill(privacy,text("● HTTPS obrigatório e proteção contra sites perigosos\n● Cookies separados por fonte\n● Câmera, microfone e localização negados\n● Pop-ups automáticos e navegação para outros domínios bloqueados\n● Depuração remota desativada",14,MUTED));space(privacy,12);
        fill(privacy,text("Bloqueios de navegação nesta execução: "+blockedNavigation+" (não são contagem de anúncios do uBlock).",12,MUTED));
        space(privacy,8);fill(privacy,text("Para abrir outro domínio, adicione uma nova fonte. Para apagar a sessão de um site, use as opções da fonte.",13,MUTED));fill(shelfContent,privacy);space(shelfContent,16);
        fill(shelfContent,button("Downloads antigos",false,()->{tab=4;library();}));space(shelfContent,16);
        LinearLayout reading=col();pad(reading,16,16);reading.setBackground(border(PANEL,16,LINE));fill(reading,bold("Como usar",18,WHITE));space(reading,9);
        fill(reading,text("• Voltar mostra os controles durante a tela cheia.\n• Use os controles inferiores para rolagem automática e capítulos reconhecidos.\n• Ajuste brilho e rotação nas preferências da fonte.\n• O Koma guarda as últimas posições por página.",14,MUTED));fill(shelfContent,reading);space(shelfContent,22);
        fill(shelfContent,text("KOMA 7.2  ·  GeckoView 161  ·  ARM64",12,MUTED));
    }
    void buildAppearance(){
        LinearLayout panel=col();pad(panel,16,16);panel.setBackground(border(PANEL,18,LINE));
        fill(panel,bold("Aparência",19,WHITE));space(panel,7);
        fill(panel,text("Escolha a identidade visual do Koma. As imagens dos capítulos não são recoloridas.",13,MUTED));space(panel,12);
        String[] names={"Grafite e menta","Preto AMOLED","Azul noturno"};
        fill(panel,button("Tema: "+names[themeChoice],false,()->new AlertDialog.Builder(this).setTitle("Tema do aplicativo").setSingleChoiceItems(names,themeChoice,(d,i)->{d.dismiss();if(i!=themeChoice){prefs.edit().putInt("themeChoice",i).apply();recreate();}}).show()));space(panel,8);
        String[] sizes={"Automático","2 colunas","3 colunas"};int current=prefs.getInt("gridColumns",0);
        fill(panel,button("Grade: "+sizes[current==2?1:current==3?2:0],false,()->new AlertDialog.Builder(this).setTitle("Colunas da estante").setSingleChoiceItems(sizes,current==2?1:current==3?2:0,(d,i)->{prefs.edit().putInt("gridColumns",i==0?0:i+1).apply();d.dismiss();library();}).show()));space(panel,8);
        Switch compact=new Switch(this);compact.setText("Capas compactas");compact.setChecked(prefs.getBoolean("compactCards",false));compact.setOnCheckedChangeListener((v,on)->prefs.edit().putBoolean("compactCards",on).apply());fill(panel,compact);
        fill(panel,button("Tamanho dos textos",false,()->new AlertDialog.Builder(this).setTitle("Tamanho dos textos").setItems(new String[]{"Pequeno","Padrão","Grande"},(d,i)->{prefs.edit().putFloat("fontScale",i==0?.9f:i==2?1.12f:1f).apply();library();}).show()));
        fill(shelfContent,panel);
    }
    void buildDownloads(){
        heading(shelfContent,"Downloads antigos","Acesso aos capítulos já salvos");
        final LinearLayout target=col();fill(shelfContent,target);fill(target,text("Consultando armazenamento…",14,MUTED));
        offlineWorker.execute(()->{List<OfflineStore.Entry> entries=offline.list();long bytes=offline.used();ui.post(()->{
            if(isDestroyed()||tab!=4||session!=null||target.getParent()!=shelfContent)return;
            target.removeAllViews();LinearLayout storage=col();pad(storage,16,14);storage.setBackground(border(PANEL,18,LINE));
            fill(storage,bold("Armazenamento",17,WHITE));space(storage,7);
            fill(storage,text(String.format(Locale.getDefault(),"%.1f / 500 MiB • %d capítulos",bytes/1048576d,entries.size()),13,MUTED));
            ProgressBar capacity=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);capacity.setMax(500);capacity.setProgress((int)Math.min(500,bytes/1048576));capacity.setProgressTintList(ColorStateList.valueOf(MINT));storage.addView(capacity,new LinearLayout.LayoutParams(-1,dp(5)));fill(target,storage);space(target,16);
            if(entries.isEmpty()){fill(target,text("Nenhum download antigo encontrado. Novos downloads foram desativados nesta versão.",14,MUTED));return;}
            for(OfflineStore.Entry e:entries){LinearLayout item=col();pad(item,14,14);item.setBackground(border(PANEL,16,LINE));
                TextView title=bold(e.title,16,WHITE);title.setMaxLines(2);fill(item,title);space(item,6);
                fill(item,text(e.pages.size()+" imagens • "+String.format(Locale.getDefault(),"%.1f MiB",e.bytes/1048576d),12,MUTED));space(item,10);
                LinearLayout actions=row();weighted(actions,button("Ler offline",true,()->startActivity(new Intent(this,OfflineActivity.class).putExtra("id",e.id))),dp(48));
                TextView del=button("Excluir",false,()->new AlertDialog.Builder(this).setTitle("Excluir download?").setMessage("A obra na biblioteca permanece salva.").setNegativeButton("Cancelar",null).setPositiveButton("Excluir",(d,i)->offlineWorker.execute(()->{offline.delete(e.id);getSharedPreferences("offlinePositions",MODE_PRIVATE).edit().remove(e.id+"/page").remove(e.id+"/offset").apply();ui.post(()->{if(!isDestroyed()&&tab==4&&session==null)library();});})).show());
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(80),dp(48));p.leftMargin=dp(8);actions.addView(del,p);fill(item,actions);fill(target,item);space(target,10);
            }
        });});
    }
    void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    void openDashboard(){
        if(!ready||blocker==null){toast("O bloqueador ainda não está pronto.");return;}
        String base=blocker.metaData.baseUrl;
        String u=blocker.metaData.optionsPageUrl;
        if(!SecurityPolicy.extensionPage(base,u))u=base==null?null:base+(base.endsWith("/")?"":"/")+"dashboard.html";
        if(!SecurityPolicy.extensionPage(base,u)){toast("O painel não está disponível nesta sessão.");return;}
        if(session!=null)closeReader();activeWork=null;tab=2;startReader(null,u,true);
    }
    void open(String s,String explicit){
        activeWork=null;
        if(!ready){toast("O leitor ainda está iniciando.");return;}if(session!=null||!has(s))return;
        String u=explicit==null?last(s):explicit;String valid=checked(u);if(!SecurityPolicy.sameSite(s,valid))valid=checked(s);
        if(valid==null){toast("Endereço antigo não atende à política HTTPS. Adicione uma fonte válida.");return;}startReader(s,valid,false);
    }
    void startReader(String s,String url,boolean isDashboard){
        flushPosition();positionSource=null;positionUrl=null;lastRecordedUrl=null;pageStartedAt=0;
        clearReaderPort();resetChapterControls();
        source=s;dashboard=isDashboard;pageUrl=url;canBack=false;full=!isDashboard&&prefs.getBoolean(key("fullscreen",s),true);root(true);
        if(!isDashboard)applyReadingWindow(s);
        bar=col();bar.setBackgroundColor(PANEL);LinearLayout top=row();pad(top,10,8);
        TextView exit=button("‹",false,this::closeReader);exit.setContentDescription("Voltar à estante");top.addView(exit,new LinearLayout.LayoutParams(dp(46),dp(46)));
        LinearLayout words=col();words.setPadding(dp(12),0,dp(8),0);readerTitle=bold(isDashboard?"uBlock Origin":manga.get(activeWork)!=null?manga.get(activeWork).title:name(s),16,WHITE);readerTitle.setSingleLine();readerTitle.setEllipsize(TextUtils.TruncateAt.END);fill(words,readerTitle);space(words,4);
        readerHost=text(isDashboard?"Painel oficial":host(url),11,MUTED);readerHost.setSingleLine();readerHost.setEllipsize(TextUtils.TruncateAt.END);fill(words,readerHost);weighted(top,words,-2);
        TextView menu=button("⋯",false,this::readerMenu);menu.setContentDescription("Menu de leitura");top.addView(menu,new LinearLayout.LayoutParams(dp(46),dp(46)));fill(bar,top);
        if(!isDashboard){LinearLayout controls=row();pad(controls,10,5);String[] names={"Voltar","Recarregar","Tela cheia"};Runnable[] actions={()->{if(session!=null&&canBack)session.goBack();},()->{if(session!=null)session.reload();},()->setFull(true)};
            for(int i=0;i<3;i++){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(42),1);if(i>0)p.leftMargin=dp(6);controls.addView(button(names[i],i==2,actions[i]),p);}fill(bar,controls);}
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);progress.setProgressTintList(ColorStateList.valueOf(MINT));bar.addView(progress,new LinearLayout.LayoutParams(-1,dp(2)));
        browser=new GeckoView(this);browser.setBackgroundColor(Color.BLACK);
        if(isDashboard){fill(root,bar);root.addView(browser,new LinearLayout.LayoutParams(-1,0,1));}
        else{
            fill(root,bar);
            root.addView(browser,new LinearLayout.LayoutParams(-1,0,1));
            readingTools=col();pad(readingTools,10,9);readingTools.setBackgroundColor(PANEL);
            LinearLayout info=row();readingPercent=text("Posição na página: —",12,MUTED);weighted(info,readingPercent,-2);
            info.addView(button("Ajustes",false,()->siteSettings(source)));fill(readingTools,info);space(readingTools,6);
            LinearLayout controls=row();previousButton=button("‹ Anterior",false,()->navigateChapter(previousChapter));autoButton=button("▶ Auto",true,this::toggleAuto);nextButton=button("Próximo ›",false,()->navigateChapter(nextChapter));
            for(TextView b:new TextView[]{previousButton,autoButton,nextButton}){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(48),1);if(b!=previousButton)p.leftMargin=dp(6);controls.addView(b,p);}
            fill(readingTools,controls);fill(root,readingTools);
        }
        GeckoSessionSettings settings=new GeckoSessionSettings.Builder().contextId(isDashboard?"koma-ublock-panel":contextFor(s)).build();
        session=new GeckoSession(settings);
        session.setPermissionDelegate(new GeckoSession.PermissionDelegate(){
            @Override public void onAndroidPermissionsRequest(GeckoSession gs,String[] permissions,Callback cb){cb.reject();}
            @Override public void onMediaPermissionRequest(GeckoSession gs,String uri,MediaSource[] video,MediaSource[] audio,MediaCallback cb){cb.reject();}
            @Override public GeckoResult<Integer> onContentPermissionRequest(GeckoSession gs,ContentPermission permission){
                return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
            }
        });
        final String blockerBase=blocker==null?null:blocker.metaData.baseUrl;
        session.setNavigationDelegate(new GeckoSession.NavigationDelegate(){
            @Override public void onCanGoBack(GeckoSession gs,boolean b){if(gs==session)canBack=b;}
            @Override public void onLocationChange(GeckoSession gs,String loc,List<GeckoSession.PermissionDelegate.ContentPermission> permissions,Boolean gesture){
                if(gs!=session||loc==null)return;
                if(!loc.equals(pageUrl)){
                    if(activeWork!=null&&Objects.equals(authorizedWorkUrl,pageUrl)&&(Objects.equals(loc,nextChapter)||Objects.equals(loc,previousChapter)))authorizedWorkUrl=loc;
                    flushPosition();saveWorks();positionSource=null;positionUrl=null;
                    if(!SecurityPolicy.sameDocument(loc,pageUrl)){clearReaderPort();resetChapterControls();updateChapterControls();}
                    else {clearExtraction();if(Objects.equals(authorizedWorkUrl,pageUrl))authorizedWorkUrl=loc;}
                }
                pageUrl=loc;if(!dashboard){String domain=host(loc);if(!TextUtils.equals(readerHost.getText(),domain))readerHost.setText(domain);if(sameSite(loc)){record(source,loc);}}}
            @Override public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession gs,LoadRequest r){
                if(gs!=session||r.uri==null)return GeckoResult.deny();
                boolean trusted=isDashboard?SecurityPolicy.extensionPage(blockerBase,r.uri):SecurityPolicy.blockerWarning(blockerBase,r.uri);
                boolean allowed=trusted||(!isDashboard&&SecurityPolicy.sameSite(s,r.uri));
                if(!allowed){noteBlocked(gs,r.uri,r.hasUserGesture);return GeckoResult.deny();}
                if(r.target==TARGET_WINDOW_NEW){
                    if(r.hasUserGesture&&!isDashboard&&SecurityPolicy.sameSite(s,r.uri)){
                        final String destination=r.uri;ui.post(()->{if(gs==session)gs.loadUri(destination);});
                    }else noteBlocked(gs,r.uri,r.hasUserGesture);
                    return GeckoResult.deny();
                }
                return GeckoResult.allow();}
        });
        session.setProgressDelegate(new GeckoSession.ProgressDelegate(){
            @Override public void onPageStart(GeckoSession gs,String url){if(gs==session){pauseAuto();pageStartedAt=SystemClock.elapsedRealtime();if(!full){progress.setProgress(0);progress.setVisibility(View.VISIBLE);}}}
            @Override public void onProgressChange(GeckoSession gs,int value){if(gs==session&&!full){
                if(value==100||value-progress.getProgress()>=5||value<progress.getProgress())progress.setProgress(value);
                int visibility=value<100?View.VISIBLE:View.INVISIBLE;if(progress.getVisibility()!=visibility)progress.setVisibility(visibility);}}
            @Override public void onPageStop(GeckoSession gs,boolean ok){if(gs==session){progress.setVisibility(View.INVISIBLE);
                if(pageStartedAt>0)android.util.Log.d("KomaPerf","page_stop_ms="+(SystemClock.elapsedRealtime()-pageStartedAt)+" success="+ok);
                pageStartedAt=0;if(!ok){setFull(false);toast("Não foi possível carregar. Tente recarregar.");}}}
        });
        if(!isDashboard&&pages!=null)session.getWebExtensionController().setMessageDelegate(pages,new WebExtension.MessageDelegate(){
            @Override public GeckoResult<Object> onMessage(String app,Object message,WebExtension.MessageSender sender){
                if(!"koma".equals(app)||sender.session!=session||!sender.isTopLevel()||dashboard||source==null||!(message instanceof JSONObject))return null;
                JSONObject m=(JSONObject)message;String u=m.optString("url");
                if(!sameSite(u)||!SecurityPolicy.sameDocument(u,sender.url)||!u.equals(pageUrl)||u.length()>4096)return null;
                
                if("init".equals(m.optString("type")))return GeckoResult.fromValue((Object)readingConfig(source,u));
                if("position".equals(m.optString("type"))&&u.equals(pageUrl)){int y=PerfPolicy.clampPosition(m.optInt("y",0));queuePosition(source,u,y,m.optBoolean("flush",false));}
                return null;}
            @Override public void onConnect(WebExtension.Port port){
                if(!validPort(port)){port.setDelegate(new WebExtension.PortDelegate(){});port.disconnect();return;}
                clearReaderPort();readerPort=port;
                port.setDelegate(new WebExtension.PortDelegate(){
                    @Override public void onPortMessage(Object message,WebExtension.Port p){handleReaderPort(message,p);}
                    @Override public void onDisconnect(WebExtension.Port p){if(readerPort==p){readerPort=null;resetChapterControls();updateChapterControls();}}
                });
                sendReaderCommand("status",null);
            }
        },"koma");
        session.open(engine);browser.setSession(session);setFull(full);session.loadUri(url);
        if(!isDashboard&&!prefs.getBoolean("hint2",false)){toast("Use Voltar para mostrar os controles.");prefs.edit().putBoolean("hint2",true).apply();}
    }
    boolean sameSite(String u){return SecurityPolicy.sameSite(source,u);}
    boolean validPort(WebExtension.Port p){
        return p!=null&&"koma".equals(p.name)&&p.sender.session==session&&p.sender.isTopLevel()&&!dashboard&&source!=null
            &&SecurityPolicy.sameDocument(pageUrl,p.sender.url)&&sameSite(p.sender.url);
    }
    void clearReaderPort(){
        clearExtraction();
        WebExtension.Port old=readerPort;readerPort=null;autoRunning=false;
        if(old!=null){try{old.disconnect();}catch(RuntimeException ignored){}}
    }
    void resetChapterControls(){autoRunning=false;chapterDetected=false;nextChapter=null;previousChapter=null;readingProgress=0;}
    void updateChapterControls(){
        if(readingTools==null||dashboard||session==null)return;
        if(previousButton!=null){previousButton.setEnabled(previousChapter!=null);previousButton.setAlpha(previousChapter==null?.45f:1f);}
        if(nextButton!=null){nextButton.setEnabled(nextChapter!=null);nextButton.setAlpha(nextChapter==null?.45f:1f);}
        if(autoButton!=null){boolean enabled=readerPort!=null&&chapterDetected;autoButton.setEnabled(enabled);autoButton.setAlpha(enabled?1f:.45f);String label=autoRunning?"Ⅱ Pausar":"▶ Auto";if(!TextUtils.equals(autoButton.getText(),label))autoButton.setText(label);}
        if(readingPercent!=null){String label=chapterDetected?"Posição: "+readingProgress+"%":"Navegação manual";if(!TextUtils.equals(readingPercent.getText(),label))readingPercent.setText(label);}
    }
    void sendReaderCommand(String type,JSONObject data){
        WebExtension.Port p=readerPort;if(!validPort(p))return;
        try{JSONObject m=data==null?new JSONObject():data;m.put("type",type);m.put("url",pageUrl);p.postMessage(m);}catch(Exception e){clearReaderPort();resetChapterControls();updateChapterControls();}
    }
    void handleReaderPort(Object value,WebExtension.Port p){
        if(readerPort!=p||!validPort(p)||!(value instanceof JSONObject))return;
        JSONObject m=(JSONObject)value;if(!Objects.equals(pageUrl,m.optString("url")))return;
        String type=m.optString("type");
        if("extraction".equals(type)){handleExtraction(m);return;}
        if("status".equals(type)){
            chapterDetected=m.optBoolean("chapter",false);readingProgress=ReadingPolicy.percent(m.optInt("percent",0));
            nextChapter=chapterDetected?ReadingPolicy.chapterLink(source,pageUrl,m.optString("next")):null;
            previousChapter=chapterDetected?ReadingPolicy.chapterLink(source,pageUrl,m.optString("previous")):null;
            autoRunning=foreground&&chapterDetected&&m.optBoolean("running",false);updateChapterControls();
            // Only update the explicitly selected work, and only known reader pages.
            if(chapterDetected&&m.optBoolean("progressReady",false)&&foreground&&Objects.equals(authorizedWorkUrl,pageUrl)){
                boolean changed=manga.update(activeWork,source,pageUrl,readingProgress);
                MangaLibrary.Work w=manga.get(activeWork);String label=MangaLibrary.clean(m.optString("chapterLabel"),80,"");
                if(w!=null&&!label.isEmpty()&&!label.equals(w.chapter)){w.chapter=label;changed=true;}
                if(changed)dirtyWorks(false);
            }
        }
    }
    void navigateChapter(String target){
        if(session==null||dashboard)return;String u=ReadingPolicy.chapterLink(source,pageUrl,target);
        if(u==null)return;if(activeWork!=null&&Objects.equals(authorizedWorkUrl,pageUrl))authorizedWorkUrl=u;pauseAuto();flushPosition();session.loadUri(u);
    }
    void toggleAuto(){
        if(!chapterDetected||readerPort==null){toast("Leitor de capítulo não reconhecido neste site.");return;}
        if(autoRunning){pauseAuto();return;}
        JSONObject data=new JSONObject();try{data.put("speed",scrollSpeed(source));}catch(Exception ignored){}
        sendReaderCommand("start",data);setFull(true);
    }
    void pauseAuto(){
        if(readerPort!=null)sendReaderCommand("stop",null);autoRunning=false;updateChapterControls();
    }
    void readerMenu(){
        pauseAuto();if(dashboard){new AlertDialog.Builder(this).setTitle("uBlock Origin").setItems(new String[]{"Recarregar painel","Voltar aos ajustes"},(d,i)->{if(i==0)session.reload();else closeReader();}).show();return;}
        ArrayList<String> labels=new ArrayList<>();ArrayList<Runnable> actions=new ArrayList<>();
        labels.add("Salvar obra");actions.add(()->{if(validPort(readerPort))requestExtraction("work");else editWork(null,source,pageUrl);});
        if(!manga.forSource(source).isEmpty()){labels.add("Registrar capítulo em obra");actions.add(this::editActiveProgress);}
        AdapterProfile p=adapterFor(source);
        if(validPort(readerPort)&&!p.value("chapters").isEmpty()){labels.add("Lista de capítulos");actions.add(()->requestExtraction("chapters"));}
        if(validPort(readerPort)&&!p.value("catalog").isEmpty()){labels.add("Catálogo");actions.add(()->requestExtraction("catalog"));}
        labels.add("Página inicial da fonte");actions.add(()->{activeWork=null;authorizedWorkUrl=null;readerTitle.setText(name(source));session.loadUri(source);});
        labels.add("Copiar endereço");actions.add(()->{((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Página",pageUrl));toast("Endereço copiado.");});
        labels.add("Opções da fonte");actions.add(()->readerSourceOptions(source));
        new AlertDialog.Builder(this).setTitle("Leitura").setItems(labels.toArray(new String[0]),(d,i)->{if(session!=null)actions.get(i).run();}).show();
    }
    void readerSourceOptions(String site){
        new AlertDialog.Builder(this).setTitle(name(site)).setItems(new String[]{"Configurar leitura","Perfil da fonte (avançado)","Painel do uBlock","Limpar sessão"},(d,i)->{
            if(i==0)siteSettings(site);else if(i==1)extensionMenu(site);else if(i==2)openDashboard();else resetSiteSession(site);
        }).show();
    }
    AdapterProfile adapterFor(String site){
        try{return AdapterProfile.parse(prefs.getString(key("adapter",site),"{}"));}
        catch(IllegalArgumentException e){return AdapterProfile.preset(0);}
    }
    void saveAdapter(String site,AdapterProfile profile){
        prefs.edit().putString(key("adapter",site),profile.json().toString()).apply();
        if(session!=null&&!dashboard&&site.equals(source)){pauseAuto();flushPosition();clearReaderPort();session.reload();}
        else library();
        toast("Perfil salvo nesta fonte.");
    }
    void extensionMenu(String site){
        if(!has(site))return;pauseAuto();
        AdapterProfile current=adapterFor(site);
        new AlertDialog.Builder(this).setTitle("Extensão • "+name(site))
          .setItems(new String[]{"Perfil atual: "+current.name(),"Escolher modelo","Editar seletores","Importar configuração JSON","Copiar configuração JSON","Restaurar padrão"},(d,i)->{
            switch(i){case 0:new AlertDialog.Builder(this).setTitle(current.name()).setMessage("Os perfis interpretam apenas elementos da página atual. Não executam scripts importados. Para testar, abra uma página e use Explorar catálogo ou Listar capítulos.").setPositiveButton("OK",null).show();break;
            case 1:new AlertDialog.Builder(this).setTitle("Escolher modelo").setItems(new String[]{"Genérico (padrão)","Madara (WordPress)","MangaReader (modelo comum)"},(a,b)->new AlertDialog.Builder(this).setTitle("Aplicar perfil?").setMessage("O perfil é um modelo inicial. Você pode editar os seletores depois.").setNegativeButton("Cancelar",null).setPositiveButton("Aplicar",(x,y)->saveAdapter(site,AdapterProfile.preset(b))).show()).show();break;
            case 2:adapterEditor(site,current);break;
            case 3:importAdapter(site);break;
            case 4:((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Koma extensão",current.json().toString()));toast("Configuração copiada.");break;
            case 5:new AlertDialog.Builder(this).setTitle("Restaurar padrão?").setMessage("Somente os seletores desta fonte serão alterados.").setNegativeButton("Cancelar",null).setPositiveButton("Restaurar",(a,b)->saveAdapter(site,AdapterProfile.preset(0))).show();break;}
          }).setNegativeButton("Fechar",null).show();
    }
    void adapterEditor(String site,AdapterProfile profile){
        ScrollView scroll=new ScrollView(this);LinearLayout form=col();pad(form,20,10);scroll.addView(form);
        fill(form,text("Seletores são nomes de elementos da página, por exemplo .reading-content ou #readerarea. Campos vazios usam o comportamento genérico quando disponível.",13,MUTED));space(form,12);
        String[] keys=AdapterProfile.KEYS;String[] labels={"Nome do perfil","Área de imagens do leitor","Link de próximo capítulo","Link de capítulo anterior","Título da obra","Título do capítulo","Link da página da obra","Links do catálogo","Links da lista de capítulos"};
        EditText[] fields=new EditText[keys.length];
        for(int i=0;i<keys.length;i++){fill(form,text(labels[i],13,MUTED));EditText e=new EditText(this);e.setSingleLine();e.setTextSize(14);e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(i==0?60:120)});e.setText(profile.value(keys[i]));fill(form,e);fields[i]=e;space(form,8);}
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Configurar extensão").setView(scroll).setNegativeButton("Cancelar",null).setPositiveButton("Salvar",null).create();d.show();
        d.getButton(-1).setOnClickListener(v->{try{
            JSONObject o=new JSONObject();o.put("version",1);for(int i=0;i<keys.length;i++)o.put(keys[i],fields[i].getText().toString());
            AdapterProfile p=AdapterProfile.parse(o.toString());d.dismiss();saveAdapter(site,p);
        }catch(Exception e){toast("Seletores inválidos. Use tags, classes, IDs e relações simples de descendência.");}});
    }
    void importAdapter(String site){
        EditText input=new EditText(this);input.setGravity(Gravity.TOP);input.setMinLines(6);input.setMaxLines(12);input.setTextSize(13);input.setHint("Cole o JSON de configuração");input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4096)});
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Importar perfil (JSON)").setView(input).setNegativeButton("Cancelar",null).setPositiveButton("Revisar",null).create();d.show();
        d.getButton(-1).setOnClickListener(v->{try{AdapterProfile p=AdapterProfile.parse(input.getText().toString());d.dismiss();adapterEditor(site,p);}catch(Exception e){input.setError("JSON inválido ou campos não permitidos.");}});
    }
    void clearExtraction(){ui.removeCallbacks(extractTimeout);extractToken=null;extractUrl=null;extractKind=null;}
    void requestExtraction(String kind){
        if(!validPort(readerPort)){toast("Aguarde a página carregar ou recarregue.");return;}
        pauseAuto();clearExtraction();extractToken=UUID.randomUUID().toString();extractUrl=pageUrl;extractKind=kind;
        try{JSONObject m=new JSONObject();m.put("request",extractToken);m.put("kind",kind);ui.postDelayed(extractTimeout,4000);sendReaderCommand("extract",m);}
        catch(Exception e){clearExtraction();toast("Não foi possível consultar a página.");}
    }
    void handleExtraction(JSONObject m){
        if(extractToken==null||!extractToken.equals(m.optString("request"))||!Objects.equals(extractUrl,pageUrl)||!Objects.equals(extractKind,m.optString("kind")))return;
        final String kind=extractKind,site=source,current=pageUrl;final GeckoSession expected=session;clearExtraction();
        if(!foreground||!sameSite(current))return;
        if("work".equals(kind)){
            String title=MangaLibrary.clean(m.optString("title"),120,"");
            String chapter=MangaLibrary.clean(m.optString("chapterLabel"),80,"");
            String candidate=SecurityPolicy.normalize(m.optString("workUrl"),false);
            String url=SecurityPolicy.sameSite(site,candidate)?candidate:current;
            editWork(null,site,url,title,chapter);return;
        }
        JSONArray a=m.optJSONArray("items");ArrayList<String> titles=new ArrayList<>(),urls=new ArrayList<>();HashSet<String> seen=new HashSet<>();
        if(a!=null)for(int i=0;i<Math.min(100,a.length());i++){
            JSONObject o=a.optJSONObject(i);if(o==null)continue;String u=SecurityPolicy.normalize(o.optString("url"),false);
            if(!SecurityPolicy.sameSite(site,u)||!seen.add(u))continue;
            String label=MangaLibrary.clean(o.optString("title"),120,"Abrir página");titles.add(label);urls.add(u);
        }
        if(urls.isEmpty()){toast("Nenhum link reconhecido. Configure os seletores da extensão.");return;}
        new AlertDialog.Builder(this).setTitle(("catalog".equals(kind)?"Obras":"Capítulos")+" • "+urls.size())
            .setItems(titles.toArray(new String[0]),(d,i)->{
                if(session!=expected||!Objects.equals(pageUrl,current)||!Objects.equals(source,site))return;
                String u=urls.get(i);
                if("chapters".equals(kind)&&activeWork!=null&&Objects.equals(authorizedWorkUrl,current))authorizedWorkUrl=u;
                else if("catalog".equals(kind)){activeWork=null;authorizedWorkUrl=null;readerTitle.setText(name(site));}
                pauseAuto();flushPosition();session.loadUri(u);
            }).setNegativeButton("Fechar",null).show();
    }
    void editActiveProgress(){
        if(!SecurityPolicy.sameSite(source,pageUrl)){toast("Página fora da fonte.");return;}
        List<MangaLibrary.Work> candidates=manga.forSource(source);
        if(candidates.isEmpty()){toast("Salve uma obra na biblioteca primeiro.");return;}
        String[] labels=new String[candidates.size()];int current=-1;
        for(int i=0;i<candidates.size();i++){labels[i]=candidates.get(i).title;if(candidates.get(i).id.equals(activeWork))current=i;}
        new AlertDialog.Builder(this).setTitle("Registrar capítulo em qual obra?").setSingleChoiceItems(labels,current,(d,i)->{
            d.dismiss();confirmWorkProgress(candidates.get(i));
        }).setNegativeButton("Cancelar",null).show();
    }
    void confirmWorkProgress(MangaLibrary.Work w){
        if(!SecurityPolicy.sameSite(w.source,pageUrl)){toast("Página fora da fonte da obra.");return;}
        EditText field=new EditText(this);field.setSingleLine();field.setText(w.chapter);field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        new AlertDialog.Builder(this).setTitle("Capítulo atual • "+w.title).setView(field).setNegativeButton("Cancelar",null).setPositiveButton("Salvar",(d,i)->{
            manga.update(w.id,source,pageUrl,chapterDetected?readingProgress:0);w.chapter=MangaLibrary.clean(field.getText().toString(),80,"Capítulo");activeWork=w.id;authorizedWorkUrl=pageUrl;readerTitle.setText(w.title);dirtyWorks(true);}).show();
    }
    int savedPosition(String s,String url){
        if(Objects.equals(s,positionSource)&&Objects.equals(url,positionUrl))return positionY;
        return savedPositionFromDisk(s,url);
    }
    void queuePosition(String s,String url,int y,boolean urgent){
        if(!Objects.equals(s,positionSource)||!Objects.equals(url,positionUrl)){
            flushPosition();positionSource=s;positionUrl=url;positionY=savedPositionFromDisk(s,url);
        }
        if(y!=positionY||!readingPositions.contains(s,url)){positionY=y;positionDirty=true;}
        if(urgent||!foreground){flushPosition();return;}
        if(positionDirty&&!positionScheduled){positionScheduled=true;ui.postDelayed(positionFlush,4000);}
    }
    int savedPositionFromDisk(String s,String url){return readingPositions.get(s,url,url.equals(prefs.getString(key("positionUrl",s),""))?prefs.getInt(key("position",s),0):0);}
    void flushPosition(){
        ui.removeCallbacks(positionFlush);positionScheduled=false;
        if(positionDirty&&positionSource!=null&&positionUrl!=null){
            readingPositions.put(positionSource,positionUrl,positionY);
            prefs.edit().putString(key("positionUrl",positionSource),positionUrl).putInt(key("position",positionSource),positionY).putString("chapterPositions",readingPositions.serialize()).apply();
            positionDirty=false;
        }
    }
    void immersive(){getWindow().getDecorView().setSystemUiVisibility(full?View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE:0);}
    void setFull(boolean v){full=v;bar.setVisibility(v?View.GONE:View.VISIBLE);
        if(readingTools!=null&&!dashboard){readingTools.setVisibility(v?View.GONE:View.VISIBLE);updateChapterControls();if(!v)sendReaderCommand("status",null);}
        root.setFitsSystemWindows(!v);root.requestApplyInsets();immersive();}
    void release(){pauseAuto();flushPosition();positionSource=null;positionUrl=null;clearReaderPort();if(session!=null){GeckoSession old=session;session=null;browser.releaseSession();old.close();browser=null;}readingTools=null;autoButton=null;previousButton=null;nextButton=null;readingPercent=null;resetReadingWindow();}
    void closeReader(){release();saveWorks();activeWork=null;authorizedWorkUrl=null;source=null;pageUrl=null;library();}
    @Override public void onBackPressed(){if(session==null){if(tab!=3){tab=3;library();}else super.onBackPressed();}else if(full){pauseAuto();setFull(false);}else if(canBack){pauseAuto();session.goBack();}else closeReader();}
    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(focus&&full)immersive();}
    @Override protected void onPause(){foreground=false;pauseAuto();flushPosition();saveWorks();if(session!=null)session.setActive(false);super.onPause();}
    @Override protected void onResume(){super.onResume();foreground=true;if(session!=null)session.setActive(true);}
    @Override public void onTrimMemory(int level){super.onTrimMemory(level);if(covers!=null)covers.trim(level);}
    @Override public void onLowMemory(){super.onLowMemory();if(covers!=null)covers.clear();}
    @Override public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);if(session==null&&(tab==0||tab==3))library();}
    @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putString("source",source!=null?source:pending);b.putString("pickSite",pickSite);b.putString("work",activeWork!=null?activeWork:pendingWork);b.putString("pickWork",pickWork);b.putInt("tab",tab);}
    @Override protected void onDestroy(){release();offlineWorker.shutdown();saveWorks();if(searchRefresh!=null)ui.removeCallbacks(searchRefresh);if(covers!=null)covers.close();super.onDestroy();}
}
