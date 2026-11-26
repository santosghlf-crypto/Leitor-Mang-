package br.app.koma;
import org.json.*;
import java.util.*;
/** Bounded, local-only per-document progress; never persist image pixels. */
final class ReadingPositions{
    static final int LIMIT=120;
    private final LinkedHashMap<String,Entry> entries=new LinkedHashMap<>();
    static final class Entry{final String source,url;final int y;Entry(String s,String u,int y){source=s;url=u;this.y=y;}}
    ReadingPositions(String raw){try{JSONArray a=new JSONArray(raw);for(int i=Math.max(0,a.length()-LIMIT);i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null)put(o.optString("s"),o.optString("u"),o.optInt("y"));}}catch(Exception ignored){}}
    String key(String s,String u){return s+"\n"+u;}
    boolean contains(String s,String u){return entries.containsKey(key(s,u));}
    int get(String s,String u,int fallback){Entry e=entries.get(key(s,u));return e==null?fallback:e.y;}
    void put(String s,String u,int y){if(!SecurityPolicy.sameSite(s,u))return;String k=key(s,u);entries.remove(k);entries.put(k,new Entry(s,u,PerfPolicy.clampPosition(y)));while(entries.size()>LIMIT)entries.remove(entries.keySet().iterator().next());}
    void clear(){entries.clear();}
    void removeSource(String s){entries.values().removeIf(e->e.source.equals(s));}
    String serialize(){JSONArray a=new JSONArray();for(Entry e:entries.values()){try{JSONObject o=new JSONObject();o.put("s",e.source);o.put("u",e.url);o.put("y",e.y);a.put(o);}catch(JSONException ignored){}}return a.toString();}
}
