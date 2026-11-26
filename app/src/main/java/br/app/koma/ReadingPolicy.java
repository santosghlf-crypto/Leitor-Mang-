package br.app.koma;
final class ReadingPolicy {
    static int speed(int value){return Math.max(30,Math.min(300,value));}
    static int brightness(int value){return Math.max(5,Math.min(100,value));}
    static int percent(int value){return Math.max(0,Math.min(100,value));}
    static String chapterLink(String source,String current,String target){
        String u=SecurityPolicy.normalize(target,false);
        if(u==null||!SecurityPolicy.sameSite(source,u)||current==null)return null;
        try{
            java.net.URI a=new java.net.URI(current),b=new java.net.URI(u);
            if(SecurityPolicy.sameSite(current,u)&&java.util.Objects.equals(a.getRawPath(),b.getRawPath())&&java.util.Objects.equals(a.getRawQuery(),b.getRawQuery()))return null;
        }catch(Exception e){return null;}
        return u;
    }
}
