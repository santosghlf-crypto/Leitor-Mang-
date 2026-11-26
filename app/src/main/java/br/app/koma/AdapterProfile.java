package br.app.koma;
import org.json.*;
import java.util.*;
import java.util.regex.Pattern;

/** Only declarative, bounded selectors. No URLs, script, HTML or network permissions. */
final class AdapterProfile {
    static final String[] KEYS={"name","reader","next","previous","title","chapterLabel","workLink","catalog","chapters"};
    private static final Set<String> ALLOWED=new HashSet<>(Arrays.asList(KEYS));
    private static final String TAG="[a-zA-Z][a-zA-Z0-9_-]*";
    private static final String QUAL="[.#][a-zA-Z_][a-zA-Z0-9_-]*";
    private static final String PART="(?:"+TAG+"(?:"+QUAL+"){0,4}|(?:"+QUAL+"){1,5})";
    private static final Pattern SELECTOR=Pattern.compile(PART+"(?:(?: +| *> *)"+PART+"){0,3}");
    private final JSONObject data;
    private AdapterProfile(JSONObject d){data=d;}
    static boolean validSelector(String s){return s.isEmpty()||(s.length()<=120&&SELECTOR.matcher(s).matches());}
    static AdapterProfile parse(String raw){
        if(raw==null||raw.length()>4096)throw new IllegalArgumentException("size");
        try{
            JSONObject input=new JSONObject(raw);
            Iterator<String> keys=input.keys();
            while(keys.hasNext()){String k=keys.next();if(!"version".equals(k)&&!ALLOWED.contains(k))throw new IllegalArgumentException("unknown key");}
            if(input.has("version")&&(!(input.get("version") instanceof Number)||input.getDouble("version")!=1))throw new IllegalArgumentException("version");
            JSONObject out=new JSONObject();out.put("version",1);
            for(String k:KEYS){
                Object v=input.opt(k);if(v!=null&&!(v instanceof String))throw new IllegalArgumentException("type");
                String value=v==null?"":((String)v).trim();
                if("name".equals(k)){if(value.length()>60)throw new IllegalArgumentException("name");if(value.isEmpty())value="Genérico";}
                else if(!validSelector(value))throw new IllegalArgumentException("selector");
                out.put(k,value);
            }
            return new AdapterProfile(out);
        }catch(JSONException e){throw new IllegalArgumentException("JSON",e);}
    }
    String name(){return value("name");}
    String value(String k){return data.optString(k);}
    JSONObject json(){try{return new JSONObject(data.toString());}catch(JSONException e){throw new IllegalStateException(e);}}
    static AdapterProfile preset(int n){
        if(n==1)return parse("{\"name\":\"Madara\",\"reader\":\".reading-content\",\"next\":\"a.next_page\",\"previous\":\"a.prev_page\",\"title\":\".post-title h1\",\"chapterLabel\":\".chapter-heading\",\"catalog\":\".post-title a\",\"chapters\":\".wp-manga-chapter a\"}");
        if(n==2)return parse("{\"name\":\"MangaReader\",\"reader\":\"#readerarea\",\"next\":\"a.ch-next-btn\",\"previous\":\"a.ch-prev-btn\",\"title\":\".entry-title\",\"chapterLabel\":\".chaptertitle\",\"catalog\":\".bsx a\",\"chapters\":\".eph-num a\"}");
        return parse("{}");
    }
}
