package br.app.koma;
import org.junit.Test;
import static org.junit.Assert.*;
public class AdapterProfileTest {
    @Test public void presetsValid(){for(int i=0;i<3;i++){AdapterProfile p=AdapterProfile.preset(i);assertNotNull(p.name());assertEquals(p.name(),AdapterProfile.parse(p.json().toString()).name());}}
    @Test public void selectorLimits(){assertTrue(AdapterProfile.validSelector(".reader > div.page"));assertTrue(AdapterProfile.validSelector("#readerarea"));for(String s:new String[]{"*",":has(a)","a[href]","a,b","a b c d e","a;script"})assertFalse(s,AdapterProfile.validSelector(s));}
    @Test public void invalidJsonRejected(){for(String s:new String[]{"{\"script\":\"alert(1)\"}","{\"version\":2}","{\"reader\":\"*\"}","{\"title\":3}","bad"})try{AdapterProfile.parse(s);fail(s);}catch(IllegalArgumentException expected){}}
    @Test public void roundTrip(){AdapterProfile p=AdapterProfile.parse("{\"name\":\"Meu perfil\",\"catalog\":\".card a\"}");assertEquals(".card a",p.value("catalog"));assertEquals("Meu perfil",p.name());assertEquals("",p.value("reader"));}
}
