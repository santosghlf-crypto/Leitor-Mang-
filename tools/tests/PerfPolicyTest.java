package br.app.koma;
public final class PerfPolicyTest {
    static void eq(int expected,int actual){if(expected!=actual)throw new AssertionError("Expected "+expected+" got "+actual);}
    public static void main(String[] args){
        eq(4*1048576,PerfPolicy.coverBudgetBytes(512,true));
        eq(4*1048576,PerfPolicy.coverBudgetBytes(32,false));
        eq(8*1048576,PerfPolicy.coverBudgetBytes(128,false));
        eq(12*1048576,PerfPolicy.coverBudgetBytes(512,false));
        eq(256,PerfPolicy.coverBucket(100));eq(384,PerfPolicy.coverBucket(300));
        eq(512,PerfPolicy.coverBucket(500));eq(640,PerfPolicy.coverBucket(2000));
        eq(1,PerfPolicy.sampleSize(641,300,640));eq(8,PerfPolicy.sampleSize(6000,4000,640));
        eq(1,PerfPolicy.sampleSize(0,100,640));eq(1,PerfPolicy.sampleSize(600,400,640));
        eq(0,PerfPolicy.clampPosition(-1));eq(0,PerfPolicy.clampPosition(0));
        eq(120,PerfPolicy.clampPosition(120));eq(10000000,PerfPolicy.clampPosition(Integer.MAX_VALUE));
        System.out.println("16 verificações de política de desempenho aprovadas.");
    }
}
