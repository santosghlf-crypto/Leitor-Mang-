package br.app.koma;
import org.junit.Test;
import static org.junit.Assert.*;
public class MangaLibraryTest {
    static final String S="https://manga.example.org";
    @Test public void separateWorksOnOneSource(){
        MangaLibrary lib=new MangaLibrary("[]");
        MangaLibrary.Work a=lib.add(S,S+"/a","A","Capítulo 1",0),b=lib.add(S,S+"/b","B","Capítulo 2",0);
        assertNotNull(a);assertNotNull(b);assertTrue(lib.update(a.id,S,S+"/a/1",52));
        assertEquals(52,a.percent);assertEquals(0,b.percent);assertEquals(1,a.status);assertEquals(0,b.status);
        assertEquals(a.id,lib.recent().id);
    }
    @Test public void roundTripAndFilters(){
        MangaLibrary l=new MangaLibrary("[]");MangaLibrary.Work a=l.add(S,S+"/a","Zeta","Capítulo 2",1);a.favorite=true;
        l.add(S,S+"/b","Alfa","Capítulo",3);l.update(a.id,S,S+"/a/2",81);a.chapter="Capítulo 2";
        MangaLibrary copy=new MangaLibrary(l.serialize());
        assertEquals(2,copy.size());assertEquals(81,copy.get(a.id).percent);assertEquals("Capítulo 2",copy.get(a.id).chapter);
        assertEquals(1,copy.filtered("zeta",0,true,0,w->"Fonte").size());
        assertEquals("Alfa",copy.filtered("",0,false,1,w->"Fonte").get(0).title);
        assertEquals(1,copy.filtered("",4,false,0,w->"Fonte").size());
    }
    @Test public void rejectUnsafeAndDuplicate(){
        MangaLibrary l=new MangaLibrary("[]");MangaLibrary.Work a=l.add(S,S+"/a","A","",0);
        assertNull(l.add(S,S+"/a","Duplicate","",0));assertNull(l.add(S,"https://elsewhere.org/a","X","",0));
        assertFalse(l.update(a.id,S,"https://elsewhere.org/a",90));assertNull(l.edit(a.id,"http://manga.example.org/a","A","",0));
    }
    @Test public void removeSourceAndClearProgress(){
        MangaLibrary l=new MangaLibrary("[]");MangaLibrary.Work a=l.add(S,S+"/a","A","",0);
        MangaLibrary.Work b=l.add("https://other.example.org","https://other.example.org/b","B","",0);
        l.update(a.id,S,S+"/a/2",75);l.clearProgress();assertEquals(a.home,a.resume);assertEquals(0,a.percent);
        l.removeSource(S);assertNull(l.get(a.id));assertNotNull(l.get(b.id));
    }
    @Test public void boundedAndCorrupt(){
        assertEquals(0,new MangaLibrary("not JSON").size());
        MangaLibrary l=new MangaLibrary("[]");
        for(int i=0;i<MangaLibrary.LIMIT;i++)assertNotNull(l.add(S,S+"/work/"+i,"Work "+i,"",99));
        assertNull(l.add(S,S+"/overflow","Overflow","",0));assertEquals(MangaLibrary.LIMIT,l.size());
        assertEquals(3,l.filtered("",4,false,0,w->"").get(0).status);
    }
}
