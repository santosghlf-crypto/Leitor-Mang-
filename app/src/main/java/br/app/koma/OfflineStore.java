package br.app.koma;
import android.graphics.BitmapFactory;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Worker-thread-only storage. A chapter becomes visible only after all images validate. */
final class OfflineStore {
    final File root;
    OfflineStore(File files){root=new File(files,"offline-v1");}
    static final class Page{final int w,h;Page(int w,int h){this.w=w;this.h=h;}}
    static final class Entry{
        final String id,title,source,url;final long bytes;final List<Page> pages;
        Entry(String id,String title,String source,String url,long bytes,List<Page> pages){this.id=id;this.title=title;this.source=source;this.url=url;this.bytes=bytes;this.pages=pages;}
    }
    File dir(String id){if(!OfflinePolicy.validId(id))throw new IllegalArgumentException("id");return new File(root,id);}
    File image(String id,int n){return new File(dir(id),n+".img");}
    static void erase(File f){File[] children=f.listFiles();if(children!=null)for(File c:children)erase(c);f.delete();}
    void cleanup(){root.mkdirs();File[] files=root.listFiles();if(files!=null)for(File f:files)if(f.getName().endsWith(".part"))erase(f);}
    List<Entry> list(){
        List<Entry> result=new ArrayList<>();File[] files=root.listFiles();if(files!=null)for(File f:files){if(!OfflinePolicy.validId(f.getName()))continue;Entry e=read(f.getName());if(e!=null)result.add(e);}
        result.sort(Comparator.comparing(e->e.title,String.CASE_INSENSITIVE_ORDER));return result;
    }
    Entry read(String id){
        try{
            File d=dir(id),meta=new File(d,"meta.json");if(!meta.isFile()||meta.length()>65536)return null;
            byte[] raw=new byte[(int)meta.length()];try(DataInputStream in=new DataInputStream(new FileInputStream(meta))){in.readFully(raw);}
            JSONObject o=new JSONObject(new String(raw,StandardCharsets.UTF_8));JSONArray a=o.getJSONArray("pages");if(a.length()<1||a.length()>OfflinePolicy.MAX_PAGES)return null;
            String source=o.getString("source"),url=o.getString("url");if(!SecurityPolicy.sameSite(source,url))return null;
            long bytes=0;List<Page> pages=new ArrayList<>();for(int i=0;i<a.length();i++){
                JSONObject p=a.getJSONObject(i);File image=image(id,i);int w=p.getInt("w"),h=p.getInt("h");
                if(!OfflinePolicy.allowedImage(w,h,p.getString("mime"))||!image.isFile()||image.length()<=0||image.length()>OfflinePolicy.MAX_IMAGE)return null;
                if(p.has("bytes")&&p.optLong("bytes",-1)!=image.length())return null;
                bytes+=image.length();pages.add(new Page(w,h));
            }
            if(bytes>OfflinePolicy.MAX_CHAPTER)return null;
            return new Entry(id,MangaLibrary.clean(o.getString("title"),120,"Capítulo"),source,url,bytes,pages);
        }catch(Exception e){return null;}
    }
    long used(){long total=0;File[] dirs=root.listFiles();if(dirs!=null)for(File d:dirs){if(!OfflinePolicy.validId(d.getName()))continue;File[] fs=d.listFiles();if(fs!=null)for(File f:fs)if(f.isFile())total+=f.length();}return total;}
    void delete(String id){erase(dir(id));}
    final class Transfer{
        final String id,source,url,title;final File part;final int expected;final JSONArray pages=new JSONArray();
        final long existing;long bytes,imageBytes;int index;boolean done;OutputStream stream;
        Transfer(String id,String source,String url,String title,int count)throws IOException{
            if(!OfflinePolicy.validId(id)||!SecurityPolicy.sameSite(source,url)||count<1||count>OfflinePolicy.MAX_PAGES)throw new IOException("Dados inválidos");
            this.id=id;this.source=source;this.url=url;this.title=MangaLibrary.clean(title,120,"Capítulo");expected=count;
            root.mkdirs();existing=used();if(existing>=OfflinePolicy.MAX_TOTAL||root.getUsableSpace()<OfflinePolicy.RESERVE)throw new IOException("Sem espaço");
            part=new File(root,id+".part");if(!part.mkdir())throw new IOException("Não foi possível criar download");
        }
        void chunk(int n,String encoded)throws IOException{
            if(done||n!=index||index>=expected||encoded==null||encoded.length()>65536)throw new IOException("Transferência inválida");
            byte[] b;try{b=Base64.decode(encoded,Base64.NO_WRAP);}catch(Exception e){throw new IOException("Dados inválidos");}
            if(!OfflinePolicy.fits(existing,bytes,imageBytes,b.length,root.getUsableSpace()))throw new IOException("Limite de espaço ou imagem");
            if(stream==null)stream=new BufferedOutputStream(new FileOutputStream(new File(part,index+".img")),65536);
            stream.write(b);
            bytes+=b.length;imageBytes+=b.length;
        }
        void end(int n)throws Exception{
            if(done||n!=index||index>=expected||imageBytes<=0)throw new IOException("Imagem incompleta");
            closeStream();BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;
            BitmapFactory.decodeFile(new File(part,index+".img").getPath(),o);
            if(!OfflinePolicy.allowedImage(o.outWidth,o.outHeight,o.outMimeType))throw new IOException("Formato ou tamanho não suportado");
            pages.put(new JSONObject().put("w",o.outWidth).put("h",o.outHeight).put("mime",o.outMimeType).put("bytes",imageBytes));index++;imageBytes=0;
        }
        void finish()throws Exception{
            if(done||index!=expected||imageBytes!=0)throw new IOException("Capítulo incompleto");
            JSONObject o=new JSONObject().put("title",title).put("source",source).put("url",url).put("pages",pages);
            try(FileOutputStream out=new FileOutputStream(new File(part,"meta.json"))){out.write(o.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
            if(!part.renameTo(dir(id)))throw new IOException("Falha ao concluir");
            done=true;
        }
        void closeStream()throws IOException{OutputStream out=stream;stream=null;if(out!=null)out.close();}
        void abort(){try{closeStream();}catch(IOException ignored){}if(!done)erase(part);done=true;}
    }
}
