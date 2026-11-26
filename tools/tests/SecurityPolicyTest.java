package br.app.koma;
public final class SecurityPolicyTest {
    static int checks;
    static void yes(boolean v){checks++;if(!v)throw new AssertionError("check "+checks);}
    static void no(boolean v){yes(!v);}
    public static void main(String[] args){
        yes(SecurityPolicy.normalize("manga.example.com/chapter",true).equals("https://manga.example.com/chapter"));
        yes(SecurityPolicy.normalize("https://example.org:443/",false)!=null);
        for(String s:new String[]{"http://example.org","javascript:alert(1)","file:///sdcard/x","intent://example.org","content://other/x","data:text/html,x","https://example.org@evil.com","https://user:pass@example.org","https://127.0.0.1","https://2130706433","https://[::1]","https://localhost","https://router.local","https://private.home.arpa","https://example.org:8443","https://a.example.org\\@evil.org","https://example.org.","https://example.org%2e.evil.org","https://example.org:bad","https://example.org\\evil.org"})yes(SecurityPolicy.normalize(s,false)==null);
        yes(SecurityPolicy.sameSite("https://www.example.org","https://example.org/chapter"));
        yes(SecurityPolicy.sameSite("https://example.org","https://example.org:443/x"));
        no(SecurityPolicy.sameSite("https://example.org","https://example.org.evil.com"));
        no(SecurityPolicy.sameSite("https://example.org","https://another.example.org"));
        no(SecurityPolicy.sameSite("https://example.org","http://example.org"));
        no(SecurityPolicy.sameSite("https://example.org","https://example.org@evil.org"));
        String base="moz-extension://abc-123/";
        yes(SecurityPolicy.extensionPage(base,base+"dashboard.html#settings"));
        yes(SecurityPolicy.blockerWarning(base,base+"document-blocked.html?details=123"));
        no(SecurityPolicy.blockerWarning(base,base+"dashboard.html"));
        no(SecurityPolicy.extensionPage(base,"moz-extension://abc-123.evil/dashboard.html"));
        no(SecurityPolicy.extensionPage(base,"https://abc-123/dashboard.html"));
        no(SecurityPolicy.extensionPage(base,"moz-extension://abc-123@evil/dashboard.html"));
        no(SecurityPolicy.extensionPage(null,base));
        yes(SecurityPolicy.sameDocument("https://example.org/a#x","https://example.org/a#y"));
        no(SecurityPolicy.sameDocument("https://example.org/a","https://example.org/b"));
        no(SecurityPolicy.sameDocument("https://example.org/a?q=1","https://example.org/a?q=2"));
        no(SecurityPolicy.sameDocument("https://www.example.org/a","https://example.org/a"));
        no(SecurityPolicy.sameDocument("https://example.org/a","https://evil.org/a"));
        System.out.println(checks+" verificações de URL aprovadas.");
    }
}
