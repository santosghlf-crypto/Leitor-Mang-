package br.app.koma;
public final class OfflinePolicyTest{
    static void check(boolean v){if(!v)throw new AssertionError();}
    public static void main(String[] args){
        long m=OfflinePolicy.MIB;
        check(OfflinePolicy.validId("550e8400-e29b-41d4-a716-446655440000"));check(!OfflinePolicy.validId("../private"));
        check(OfflinePolicy.allowedImage(1080,20000,"image/jpeg"));check(!OfflinePolicy.allowedImage(16000,100000,"image/png"));check(!OfflinePolicy.allowedImage(100,100,"image/svg+xml"));
        check(OfflinePolicy.fits(0,0,0,49152,101*m));check(!OfflinePolicy.fits(500*m,0,0,1,200*m));check(!OfflinePolicy.fits(0,100*m,0,1,200*m));
        check(!OfflinePolicy.fits(0,0,16*m,1,200*m));check(!OfflinePolicy.fits(0,0,0,49153,200*m));check(!OfflinePolicy.fits(0,0,0,1,100*m));
        check(OfflinePolicy.sample(4000,1000)==4);check(OfflinePolicy.sample(500,1000)==1);
        check(OfflinePolicy.sample(3199,1600)==2);check(OfflinePolicy.sample(16000,1600)==8);check(OfflinePolicy.sample(2048,1600)==1);
        System.out.println("16 verificações de limites offline aprovadas.");
    }
}
