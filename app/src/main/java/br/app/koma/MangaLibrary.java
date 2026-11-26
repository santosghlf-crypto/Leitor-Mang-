package br.app.koma;
import org.json.*;
import java.util.*;
import java.util.function.Function;

/** Local, bounded metadata. A source is a browser context; a work is a user bookmark. */
final class MangaLibrary {
    static final int LIMIT=1000;
    static final class Work {
        String id,source,home,resume,title,chapter,cover="";
        int status,percent; boolean favorite; long created,read;
    }
    private final LinkedHashMap<String,Work> works=new LinkedHashMap<>();
    MangaLibrary(String raw){
        try{JSONArray a=new JSONArray(raw);for(int i=0;i<a.length()&&works.size()<LIMIT;i++){
            JSONObject o=a.optJSONObject(i);if(o==null)continue;Work w=new Work();
            w.id=o.optString("id");w.source=o.optString("source");w.home=o.optString("home");w.resume=o.optString("resume",w.home);
            if(w.id.length()>64||w.id.isEmpty()||!SecurityPolicy.sameSite(w.source,w.home)||!SecurityPolicy.sameSite(w.source,w.resume))continue;
            w.title=clean(o.optString("title"),120,"Sem título");w.chapter=clean(o.optString("chapter"),80,"Capítulo");w.status=status(o.optInt("status"));
            w.percent=ReadingPolicy.percent(o.optInt("percent"));w.favorite=o.optBoolean("favorite");w.created=Math.max(0,o.optLong("created"));w.read=Math.max(0,o.optLong("read"));
            String cover=o.optString("cover");w.cover=cover.startsWith("content://")&&cover.length()<=4096?cover:"";
            if(!works.containsKey(w.id)&&!duplicate(w.source,w.home,w.id))works.put(w.id,w);
        }}catch(Exception ignored){}
    }
    static String clean(String value,int max,String fallback){String s=value==null?"":value.trim();return s.isEmpty()?fallback:s.substring(0,Math.min(max,s.length()));}
    static int status(int v){return Math.max(0,Math.min(3,v));}
    int size(){return works.size();}
    Work get(String id){return id==null?null:works.get(id);}
    boolean duplicate(String source,String home,String ignore){for(Work w:works.values())if(!w.id.equals(ignore)&&w.source.equals(source)&&w.home.equals(home))return true;return false;}
    Work add(String source,String url,String title,String chapter,int state){
        if(works.size()>=LIMIT||!SecurityPolicy.sameSite(source,url)||duplicate(source,url,null))return null;
        Work w=new Work();w.id=UUID.randomUUID().toString();w.source=source;w.home=url;w.resume=url;w.title=clean(title,120,"Sem título");w.chapter=clean(chapter,80,"Capítulo");w.status=status(state);w.created=System.currentTimeMillis();works.put(w.id,w);return w;
    }
    Work edit(String id,String home,String title,String chapter,int state){
        Work w=get(id);if(w==null||!SecurityPolicy.sameSite(w.source,home)||duplicate(w.source,home,id))return null;
        if(!w.home.equals(home)){w.home=home;w.resume=home;w.percent=0;w.read=0;}
        w.title=clean(title,120,"Sem título");w.chapter=clean(chapter,80,"Capítulo");w.status=status(state);return w;
    }
    boolean update(String id,String source,String url,int percent){
        Work w=get(id);if(w==null||!w.source.equals(source)||!SecurityPolicy.sameSite(source,url))return false;
        int p=ReadingPolicy.percent(percent);
        if(w.resume.equals(url)&&w.percent==p&&w.read>0)return false;
        if(!w.resume.equals(url))w.chapter="Capítulo"; // no guessed chapter number
        w.resume=url;w.percent=p;w.read=System.currentTimeMillis();if(w.status==0)w.status=1;return true;
    }
    Work recent(){Work best=null;for(Work w:works.values())if(w.status==1&&w.read>0&&(best==null||w.read>best.read))best=w;return best;}
    List<Work> filtered(String query,int filter,boolean favorites,int sort,Function<Work,String> sourceName){
        String q=query==null?"":query.trim().toLowerCase(Locale.ROOT);List<Work> result=new ArrayList<>();
        for(Work w:works.values())if((filter==0||w.status==filter-1)&&(!favorites||w.favorite)&&(w.title+" "+sourceName.apply(w)).toLowerCase(Locale.ROOT).contains(q))result.add(w);
        Comparator<Work> order=sort==1?Comparator.comparing(w->w.title,String.CASE_INSENSITIVE_ORDER):sort==2?Comparator.comparingLong((Work w)->w.created).reversed():Comparator.comparingLong((Work w)->w.read).reversed().thenComparing(Comparator.comparingLong((Work w)->w.created).reversed());
        result.sort(order);return result;
    }
    List<Work> forSource(String source){List<Work> r=new ArrayList<>();for(Work w:works.values())if(w.source.equals(source))r.add(w);return r;}
    boolean usesCover(String uri){for(Work w:works.values())if(uri.equals(w.cover))return true;return false;}
    void clearProgress(){for(Work w:works.values()){w.resume=w.home;w.percent=0;w.read=0;w.chapter="Capítulo";}}
    void remove(String id){works.remove(id);}
    void removeSource(String s){works.values().removeIf(w->w.source.equals(s));}
    String serialize(){JSONArray a=new JSONArray();for(Work w:works.values())try{
        JSONObject o=new JSONObject();o.put("id",w.id).put("source",w.source).put("home",w.home).put("resume",w.resume).put("title",w.title).put("chapter",w.chapter).put("cover",w.cover).put("status",w.status).put("percent",w.percent).put("favorite",w.favorite).put("created",w.created).put("read",w.read);a.put(o);
    }catch(JSONException ignored){}return a.toString();}
}
