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
import java.lang.ref.WeakReference;
import java.util.concurrent.Future;
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
    static final int BG=0xff101316, PANEL=0xff191e23, RAISED=0xff252c33, LINE=0xff313940,
        WHITE=0xfff1f5f3, MUTED=0xffa3afb4, MINT=0xffa8edc1, INK=0xff12261b, WARN=0xffffc68b;
    static final int PICK_COVER=42;
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
    int tab=0;
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
    boolean isolatedSession;
    final Runnable positionFlush = this::flushPosition;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        prefs=getSharedPreferences("shelf", MODE_PRIVATE);
        sites=array("sites"); history=array("history");
        covers=new CoverLoader(getApplicationContext());
        if(saved!=null){pending=saved.getString("source");pickSite=saved.getString("pickSite");}
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
                if(pending!=null){String s=pending;pending=null;open(s,null);}},
            e->{if(!isDestroyed()&&!isFinishing()){failed=true;library();}});
    }

    int dp(float n){return Math.round(getResources().getDisplayMetrics().density*n);}
    LinearLayout col(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);return l;}
    LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(0);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);return t;}
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
        shelfList=null;shelfAdapter=null;shelfSummary=null;search=null;
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
        TextView logo=bold("K",22,INK);logo.setGravity(Gravity.CENTER);logo.setBackground(bg(MINT,12));
        header.addView(logo,new LinearLayout.LayoutParams(dp(42),dp(42)));
        LinearLayout brand=col();brand.setPadding(dp(12),0,0,0);
        fill(brand,bold("KOMA",19,WHITE));space(brand,3);fill(brand,text("Seu universo de leitura",11,MUTED));weighted(header,brand,-2);
        TextView protection=bold(ready?"● Protegido":failed?"● Erro":"● Iniciando",11,ready?MINT:WARN);
        pad(protection,10,9);protection.setBackground(bg(PANEL,20));protection.setOnClickListener(v->{tab=2;library();});
        header.addView(protection);fill(root,header);
        shelfContent=col();pad(shelfContent,20,9);
        if(tab==0){
            shelfList=new ListView(this);shelfList.setDivider(null);shelfList.setDividerHeight(0);
            shelfList.setSelector(new ColorDrawable(Color.TRANSPARENT));shelfList.setCacheColorHint(Color.TRANSPARENT);
            shelfList.setItemsCanFocus(true);shelfList.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            shelfList.setClipToPadding(false);shelfList.setVerticalScrollBarEnabled(false);
            root.addView(shelfList,new LinearLayout.LayoutParams(-1,0,1));
            shelfList.addHeaderView(shelfContent,null,false);
            buildShelf();
        }else{
            ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);
            root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));scroll.addView(shelfContent);
            if(tab==1)buildHistory();else buildSettings();
        }
        line(root);bottom=row();pad(bottom,12,7);
        String[] icons={"▦","◷","⚙"}, labels={"Estante","Histórico","Ajustes"};
        for(int i=0;i<3;i++){final int index=i;TextView b=bold(icons[i]+"\n"+labels[i],13,tab==i?MINT:MUTED);b.setGravity(Gravity.CENTER);b.setMinHeight(dp(53));b.setBackground(bg(tab==i?PANEL:BG,12));b.setOnClickListener(v->{tab=index;query="";library();});weighted(bottom,b,-2);}
        fill(root,bottom);
    }

    void heading(LinearLayout l,String top,String sub){
        fill(l,bold(top,29,WHITE));space(l,7);fill(l,text(sub,14,MUTED));space(l,19);
    }
    int shelfColumns(){return getResources().getDisplayMetrics().widthPixels/dp(1)>=650?3:2;}
    void buildShelf(){
        heading(shelfContent,"Minha estante",sites.length()==0?"Comece a montar sua coleção.":sites.length()+" fontes na sua coleção");
        fill(shelfContent,button("+  Adicionar fonte",true,()->editSite(null)));space(shelfContent,18);
        search=new EditText(this);search.setSingleLine();search.setTextSize(14);search.setTextColor(WHITE);search.setHintTextColor(MUTED);
        search.setHint("Buscar na estante");search.setText(query);search.setBackground(border(PANEL,13,LINE));pad(search,15,12);
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
        void bind(String next){
            String nextUri=next==null?"":prefs.getString(key("cover",next),"");
            String title=next==null?"":name(next).trim();
            String nextLetter=title.isEmpty()?"K":title.substring(0,title.offsetByCodePoints(0,1)).toUpperCase(Locale.ROOT);
            if(Objects.equals(site,next)&&uri.equals(nextUri)&&letter.equals(nextLetter)){requestCover();return;}
            cancel();site=next;uri=nextUri;letter=nextLetter;image=null;requestedKey="";finishedRequest=false;
            base=palette[Math.floorMod(next==null?0:next.hashCode(),palette.length)];
            setContentDescription(next==null?null:"Capa de "+name(next));invalidate();requestCover();
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
        String[] opts={"Editar nome","Escolher capa","Remover capa",favorite(s)?"Remover dos favoritos":"Adicionar aos favoritos","Configurações de leitura","Abrir página inicial","Limpar cookies e sessão","Remover fonte"};
        new AlertDialog.Builder(this).setTitle(name(s)).setItems(opts,(d,i)->{
            switch(i){case 0:editSite(s);break;case 1:pickSite=s;Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.setType("image/*");intent.addCategory(Intent.CATEGORY_OPENABLE);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(intent,PICK_COVER);break;
                case 2:forgetCover(s);library();break;case 3:prefs.edit().putBoolean(key("fav",s),!favorite(s)).apply();library();break;
                case 4:siteSettings(s);break;case 5:open(s,s);break;case 6:resetSiteSession(s);break;case 7:new AlertDialog.Builder(this).setTitle("Remover "+name(s)+"?").setMessage("A fonte e suas preferências serão removidas.").setNegativeButton("Cancelar",null).setPositiveButton("Remover",(a,b)->remove(s)).show();break;}
        }).show();
    }
    void forgetCover(String s){String u=prefs.getString(key("cover",s),"");prefs.edit().remove(key("cover",s)).apply();covers.invalidate(u);
        if(!u.isEmpty()){boolean used=false;for(int i=0;i<sites.length();i++)if(u.equals(prefs.getString(key("cover",sites.optString(i)),"")))used=true;
            if(!used)try{getContentResolver().releasePersistableUriPermission(Uri.parse(u),Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}}}
    @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);
        if(req!=PICK_COVER)return;String s=pickSite;pickSite=null;if(result!=RESULT_OK||data==null||data.getData()==null||s==null||!has(s))return;
        Uri u=data.getData();try{getContentResolver().takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if(!u.toString().equals(prefs.getString(key("cover",s),"")))forgetCover(s);
            prefs.edit().putString(key("cover",s),u.toString()).apply();covers.invalidate(u.toString());library();}
        catch(Exception e){toast("Não foi possível salvar essa capa.");}}
    void remove(String s){
        String old=prefs.getString(key("context",s),"");
        if(!old.isEmpty())engine.getStorageController().clearDataForSessionContext(old);
        prefs.edit().remove(key("context",s)).apply();forgetCover(s);for(int i=0;i<sites.length();i++)if(s.equals(sites.optString(i))){sites.remove(i);break;}
        for(int i=history.length()-1;i>=0;i--){JSONObject h=history.optJSONObject(i);if(h!=null&&s.equals(h.optString("source")))history.remove(i);}
        SharedPreferences.Editor ed=prefs.edit();for(String k:new String[]{"name","last","fav","mode","fullscreen","position","positionUrl"})ed.remove(key(k,s));
        ed.putString("history",history.toString()).putString("sites",sites.toString()).apply();library();}
    void siteSettings(String s){
        LinearLayout box=col();pad(box,22,10);fill(box,text("Modo de exibição",13,MUTED));space(box,8);
        RadioGroup modes=new RadioGroup(this);String[] values={"fit","continuous","original"},labels={"Ajustar largura","Webtoon (sem espaços em leitores conhecidos)","Original do site"};
        for(int i=0;i<3;i++){RadioButton radio=new RadioButton(this);radio.setId(100+i);radio.setText(labels[i]);radio.setButtonTintList(ColorStateList.valueOf(MINT));modes.addView(radio);if(values[i].equals(mode(s)))modes.check(100+i);}fill(box,modes);space(box,8);
        Switch fs=new Switch(this);fs.setText("Abrir em tela cheia");fs.setChecked(prefs.getBoolean(key("fullscreen",s),true));fill(box,fs);
        new AlertDialog.Builder(this).setTitle("Leitura • "+name(s)).setView(box).setNegativeButton("Cancelar",null).setPositiveButton("Salvar",(d,i)->{
            int selected=modes.getCheckedRadioButtonId()-100;if(selected<0||selected>2)selected=0;
            prefs.edit().putString(key("mode",s),values[selected]).putBoolean(key("fullscreen",s),fs.isChecked()).apply();
            if(session!=null&&!dashboard)session.reload();else library();
        }).show();
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
        fill(shelfContent,button("Limpar histórico",false,()->new AlertDialog.Builder(this).setTitle("Limpar histórico?").setMessage("A última página e a posição de cada fonte também serão apagadas.").setNegativeButton("Cancelar",null).setPositiveButton("Limpar",(d,i)->{history=new JSONArray();SharedPreferences.Editor e=prefs.edit().remove("history");for(int j=0;j<sites.length();j++){String s=sites.optString(j);for(String k:new String[]{"last","position","positionUrl"})e.remove(key(k,s));}e.apply();library();}).show()));space(shelfContent,14);
        for(int i=0;i<history.length();i++){JSONObject h=history.optJSONObject(i);if(h==null)continue;String s=h.optString("source"),u=h.optString("url");if(!has(s)||checked(u)==null)continue;
            LinearLayout item=col();pad(item,15,14);item.setBackground(border(PANEL,14,LINE));fill(item,bold(name(s)+"  ›",16,WHITE));space(item,6);
            TextView address=text(u,12,MUTED);address.setMaxLines(2);address.setEllipsize(TextUtils.TruncateAt.END);fill(item,address);space(item,6);
            fill(item,text(android.text.format.DateUtils.getRelativeTimeSpanString(h.optLong("time")).toString(),11,MINT));item.setOnClickListener(v->open(s,u));fill(shelfContent,item);space(shelfContent,8);}
    }
    void buildSettings(){
        heading(shelfContent,"Ajustes","Controle sua experiência de leitura.");
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
        LinearLayout perf=col();pad(perf,16,16);perf.setBackground(border(PANEL,16,LINE));
        fill(perf,bold("Desempenho",18,WHITE));space(perf,8);
        Switch warm=new Switch(this);warm.setText("Preparar próxima imagem");warm.setChecked(!covers.lowRam()&&prefs.getBoolean("predecode",true));warm.setEnabled(!covers.lowRam());
        warm.setOnCheckedChangeListener((v,on)->prefs.edit().putBoolean("predecode",on).apply());fill(perf,warm);
        space(perf,6);fill(perf,text(covers.lowRam()?"Modo leve automático: preparação extra desativada neste aparelho.":"Prepara somente uma imagem próxima, já baixada, quando o leitor está ocioso. Não reduz a qualidade.",13,MUTED));
        space(perf,8);fill(perf,text("Cache de capas: até "+(covers.limitBytes()/1048576)+" MiB. Imagens de capítulos continuam sob controle do Gecko.",12,MUTED));fill(shelfContent,perf);space(shelfContent,16);
        LinearLayout reading=col();pad(reading,16,16);reading.setBackground(border(PANEL,16,LINE));fill(reading,bold("Como usar",18,WHITE));space(reading,9);
        fill(reading,text("• Voltar mostra os controles durante a tela cheia.\n• Ajuste o modo e a capa em cada fonte.\n• O Koma salva a página e tenta recuperar a posição em leitores compatíveis.",14,MUTED));fill(shelfContent,reading);space(shelfContent,22);
        fill(shelfContent,text("KOMA 3.2  ·  GeckoView 161  ·  ARM64",12,MUTED));
    }
    void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    void openDashboard(){
        if(!ready||blocker==null){toast("O bloqueador ainda não está pronto.");return;}
        String base=blocker.metaData.baseUrl;
        String u=blocker.metaData.optionsPageUrl;
        if(!SecurityPolicy.extensionPage(base,u))u=base==null?null:base+(base.endsWith("/")?"":"/")+"dashboard.html";
        if(!SecurityPolicy.extensionPage(base,u)){toast("O painel não está disponível nesta sessão.");return;}
        if(session!=null)closeReader();tab=2;startReader(null,u,true);
    }
    void open(String s,String explicit){
        if(!ready){toast("O leitor ainda está iniciando.");return;}if(session!=null||!has(s))return;
        String u=explicit==null?last(s):explicit;String valid=checked(u);if(!SecurityPolicy.sameSite(s,valid))valid=checked(s);
        if(valid==null){toast("Endereço antigo não atende à política HTTPS. Adicione uma fonte válida.");return;}startReader(s,valid,false);
    }
    void startReader(String s,String url,boolean isDashboard){
        flushPosition();positionSource=null;positionUrl=null;lastRecordedUrl=null;pageStartedAt=0;
        source=s;dashboard=isDashboard;pageUrl=url;canBack=false;full=!isDashboard&&prefs.getBoolean(key("fullscreen",s),true);root(true);
        bar=col();bar.setBackgroundColor(PANEL);LinearLayout top=row();pad(top,10,8);
        TextView exit=button("‹",false,this::closeReader);exit.setContentDescription("Voltar à estante");top.addView(exit,new LinearLayout.LayoutParams(dp(46),dp(46)));
        LinearLayout words=col();words.setPadding(dp(12),0,dp(8),0);readerTitle=bold(isDashboard?"uBlock Origin":name(s),16,WHITE);readerTitle.setSingleLine();readerTitle.setEllipsize(TextUtils.TruncateAt.END);fill(words,readerTitle);space(words,4);
        readerHost=text(isDashboard?"Painel oficial":host(url),11,MUTED);readerHost.setSingleLine();readerHost.setEllipsize(TextUtils.TruncateAt.END);fill(words,readerHost);weighted(top,words,-2);
        TextView menu=button("⋯",false,this::readerMenu);menu.setContentDescription("Menu de leitura");top.addView(menu,new LinearLayout.LayoutParams(dp(46),dp(46)));fill(bar,top);
        if(!isDashboard){LinearLayout controls=row();pad(controls,10,5);String[] names={"Voltar","Recarregar","Tela cheia"};Runnable[] actions={()->{if(session!=null&&canBack)session.goBack();},()->{if(session!=null)session.reload();},()->setFull(true)};
            for(int i=0;i<3;i++){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(42),1);if(i>0)p.leftMargin=dp(6);controls.addView(button(names[i],i==2,actions[i]),p);}fill(bar,controls);}
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);progress.setProgressTintList(ColorStateList.valueOf(MINT));bar.addView(progress,new LinearLayout.LayoutParams(-1,dp(2)));fill(root,bar);
        browser=new GeckoView(this);browser.setBackgroundColor(Color.BLACK);root.addView(browser,new LinearLayout.LayoutParams(-1,0,1));
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
                if(!loc.equals(pageUrl)){flushPosition();positionSource=null;positionUrl=null;}
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
            @Override public void onPageStart(GeckoSession gs,String url){if(gs==session){pageStartedAt=SystemClock.elapsedRealtime();if(!full){progress.setProgress(0);progress.setVisibility(View.VISIBLE);}}}
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
                if(!sameSite(u)||!u.equals(sender.url)||!u.equals(pageUrl)||u.length()>4096)return null;
                if("init".equals(m.optString("type"))){JSONObject result=new JSONObject();try{
                    result.put("mode",mode(source));result.put("y",savedPosition(source,u));
                    result.put("predecode",!covers.lowRam()&&prefs.getBoolean("predecode",true));}catch(Exception ignored){}return GeckoResult.fromValue((Object)result);}
                if("position".equals(m.optString("type"))&&u.equals(pageUrl)){int y=PerfPolicy.clampPosition(m.optInt("y",0));queuePosition(source,u,y,m.optBoolean("flush",false));}
                return null;}
        },"koma");
        session.open(engine);browser.setSession(session);setFull(full);session.loadUri(url);
        if(!isDashboard&&!prefs.getBoolean("hint2",false)){toast("Use Voltar para mostrar os controles.");prefs.edit().putBoolean("hint2",true).apply();}
    }
    boolean sameSite(String u){return SecurityPolicy.sameSite(source,u);}
    void readerMenu(){
        if(dashboard){new AlertDialog.Builder(this).setTitle("uBlock Origin").setItems(new String[]{"Recarregar painel","Voltar aos ajustes"},(d,i)->{if(i==0)session.reload();else closeReader();}).show();return;}
        new AlertDialog.Builder(this).setTitle(name(source)).setItems(new String[]{"Página inicial","Configurar leitura","Copiar endereço","Painel do uBlock","Limpar cookies e sessão","Voltar à estante"},(d,i)->{
            switch(i){case 0:session.loadUri(source);break;case 1:siteSettings(source);break;case 2:((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Página",pageUrl));toast("Endereço copiado.");break;case 3:openDashboard();break;case 4:resetSiteSession(source);break;case 5:closeReader();break;}}).show();
    }
    int savedPosition(String s,String url){
        if(Objects.equals(s,positionSource)&&Objects.equals(url,positionUrl))return positionY;
        return url.equals(prefs.getString(key("positionUrl",s),""))?prefs.getInt(key("position",s),0):0;
    }
    void queuePosition(String s,String url,int y,boolean urgent){
        if(!Objects.equals(s,positionSource)||!Objects.equals(url,positionUrl)){
            flushPosition();positionSource=s;positionUrl=url;positionY=savedPositionFromDisk(s,url);
        }
        if(y!=positionY||!url.equals(prefs.getString(key("positionUrl",s),""))){positionY=y;positionDirty=true;}
        if(urgent||!foreground){flushPosition();return;}
        if(positionDirty&&!positionScheduled){positionScheduled=true;ui.postDelayed(positionFlush,4000);}
    }
    int savedPositionFromDisk(String s,String url){return url.equals(prefs.getString(key("positionUrl",s),""))?prefs.getInt(key("position",s),0):0;}
    void flushPosition(){
        ui.removeCallbacks(positionFlush);positionScheduled=false;
        if(positionDirty&&positionSource!=null&&positionUrl!=null){
            prefs.edit().putString(key("positionUrl",positionSource),positionUrl).putInt(key("position",positionSource),positionY).apply();
            positionDirty=false;
        }
    }
    void immersive(){getWindow().getDecorView().setSystemUiVisibility(full?View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE:0);}
    void setFull(boolean v){full=v;bar.setVisibility(v?View.GONE:View.VISIBLE);root.setFitsSystemWindows(!v);root.requestApplyInsets();immersive();}
    void release(){flushPosition();positionSource=null;positionUrl=null;if(session!=null){GeckoSession old=session;session=null;browser.releaseSession();old.close();browser=null;}}
    void closeReader(){release();source=null;pageUrl=null;library();}
    @Override public void onBackPressed(){if(session==null){if(tab!=0){tab=0;library();}else super.onBackPressed();}else if(full)setFull(false);else if(canBack)session.goBack();else closeReader();}
    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(focus&&full)immersive();}
    @Override protected void onPause(){foreground=false;flushPosition();if(session!=null)session.setActive(false);super.onPause();}
    @Override protected void onResume(){super.onResume();foreground=true;if(session!=null)session.setActive(true);}
    @Override public void onTrimMemory(int level){super.onTrimMemory(level);if(covers!=null)covers.trim(level);}
    @Override public void onLowMemory(){super.onLowMemory();if(covers!=null)covers.clear();}
    @Override public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);if(session==null&&tab==0)library();}
    @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putString("source",source!=null?source:pending);b.putString("pickSite",pickSite);}
    @Override protected void onDestroy(){release();if(searchRefresh!=null)ui.removeCallbacks(searchRefresh);if(covers!=null)covers.close();super.onDestroy();}
}
