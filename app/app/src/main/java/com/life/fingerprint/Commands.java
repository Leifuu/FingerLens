package com.life.fingerprint;

import org.json.*;
import java.io.*;
import java.util.concurrent.TimeUnit;
import android.util.Base64;

public final class Commands {
    private Commands() {}
    private static final class Output implements Runnable {
        final InputStream input; final ByteArrayOutputStream kept = new ByteArrayOutputStream(); long total; IOException error;
        Output(InputStream stream) { input=stream; }
        public void run() { try(InputStream in=input){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){int keep=Math.min(n,Math.max(0,262144-kept.size()));kept.write(b,0,keep);total+=n;}}catch(IOException e){error=e;} }
        Object raw() { return Json.bytes(Base64.encodeToString(kept.toByteArray(), Base64.NO_WRAP)); }
    }
    // 命令表由代码固定，绝不把导入指纹或 UI 文本交给 shell。
    public static void collect(ItemSink sink,String category,String key,String... argv) {
        String command=String.join(" ",argv); Process p=null;
        try {
            p=new ProcessBuilder(argv).start();Output stdout=new Output(p.getInputStream()),stderr=new Output(p.getErrorStream());
            Thread a=new Thread(stdout,"stdout"),b=new Thread(stderr,"stderr");a.setDaemon(true);b.setDaemon(true);a.start();b.start();
            boolean ended;
            if(android.os.Build.VERSION.SDK_INT>=26)ended=p.waitFor(5,TimeUnit.SECONDS);
            else{long until=android.os.SystemClock.elapsedRealtime()+5000;ended=false;while(android.os.SystemClock.elapsedRealtime()<until){try{p.exitValue();ended=true;break;}catch(IllegalThreadStateException stillRunning){Thread.sleep(25);}}}
            if(!ended){if(android.os.Build.VERSION.SDK_INT>=26)p.destroyForcibly();else p.destroy();}a.join(1000);b.join(1000);
            boolean incomplete=a.isAlive()||b.isAlive();boolean truncated=stdout.total>262144||stderr.total>262144;
            JSONObject v=Json.obj("argv",argv,"stdout",stdout.raw(),"stderr",stderr.raw(),"exitCode",ended?p.exitValue():null);
            String status=!ended?"timeout":incomplete?"error":truncated?"truncated":p.exitValue()==0?"ok":"error";
            sink.add(category,key,command,"command",command,status,v,incomplete?Json.obj("message","输出管道未正常结束"):null,truncated?Json.obj("streamLimitBytes",262144,"stdoutBytes",String.valueOf(stdout.total),"stderrBytes",String.valueOf(stderr.total)):null);
        } catch(Exception e){sink.failed(category,key,command,"command",command,"error",e);}
        finally {if(p!=null)p.destroy();}
    }
}
