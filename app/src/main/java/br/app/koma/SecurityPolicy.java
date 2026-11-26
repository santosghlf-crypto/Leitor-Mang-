package br.app.koma;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/** URL policy deliberately avoids guessing registrable domains or using string prefixes. */
final class SecurityPolicy {
    private SecurityPolicy(){}
    private static final Pattern HOST=Pattern.compile("(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?");
    static URI parse(String raw,boolean inferScheme){
        if(raw==null||raw.length()>4096)return null;
        try{
            String value=raw.trim();
            if(value.isEmpty()||value.indexOf('\\')>=0)return null;
            if(inferScheme&&!value.contains("://"))value="https://"+value;
            URI u=new URI(value);
            if(!"https".equalsIgnoreCase(u.getScheme())||u.getRawUserInfo()!=null||u.getHost()==null)return null;
            if(u.getPort()!=-1&&u.getPort()!=443)return null;
            String host=u.getHost().toLowerCase(Locale.ROOT);
            if(!HOST.matcher(host).matches()||host.equals("localhost")||host.endsWith(".localhost")||
                host.endsWith(".local")||host.endsWith(".internal")||host.endsWith(".home.arpa")||
                host.endsWith(".onion")||host.endsWith(".test")||host.endsWith(".invalid"))return null;
            return u.normalize();
        }catch(Exception e){return null;}
    }
    static String normalize(String raw,boolean inferScheme){URI u=parse(raw,inferScheme);return u==null?null:u.toASCIIString();}
    static String host(URI u){
        String h=u.getHost().toLowerCase(Locale.ROOT);
        return h.startsWith("www.")?h.substring(4):h;
    }
    static boolean sameSite(String a,String b){
        URI x=parse(a,false),y=parse(b,false);
        return x!=null&&y!=null&&host(x).equals(host(y));
    }
    static URI extension(String raw){
        try{
            URI u=new URI(raw);
            if(!"moz-extension".equals(u.getScheme())||u.getHost()==null||u.getRawUserInfo()!=null||u.getPort()!=-1)return null;
            return u;
        }catch(Exception e){return null;}
    }
    static boolean extensionPage(String base,String url){
        URI a=extension(base),b=extension(url);
        return a!=null&&b!=null&&a.getHost().equalsIgnoreCase(b.getHost());
    }
    static boolean blockerWarning(String base,String url){
        if(!extensionPage(base,url))return false;
        return "/document-blocked.html".equals(extension(url).getPath());
    }
}
