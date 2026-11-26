package br.app.koma;
public final class ReadingPolicyTest{
 static int checks;
 static void yes(boolean ok){checks++;if(!ok)throw new AssertionError("check "+checks);}
 public static void main(String[] args){
  yes(ReadingPolicy.speed(1)==30);yes(ReadingPolicy.speed(500)==300);yes(ReadingPolicy.speed(110)==110);
  yes(ReadingPolicy.brightness(0)==5);yes(ReadingPolicy.brightness(200)==100);yes(ReadingPolicy.brightness(75)==75);
  yes(ReadingPolicy.percent(-1)==0);yes(ReadingPolicy.percent(200)==100);
  String source="https://example.org", current=source+"/chapter1";
  yes(ReadingPolicy.chapterLink(source,current,source+"/chapter2")!=null);
  yes(ReadingPolicy.chapterLink(source,current,current+"#next")==null);
  yes(ReadingPolicy.chapterLink(source,current,"https://www.example.org/chapter1#next")==null);
  yes(ReadingPolicy.chapterLink(source,current,"https://other.org/chapter2")==null);
  yes(ReadingPolicy.chapterLink(source,current,"http://example.org/chapter2")==null);
  yes(ReadingPolicy.chapterLink(source,current,null)==null);
  System.out.println(checks+" verificações de leitura aprovadas.");
 }
}
