package com.life.fingerprint;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import java.io.File;
import java.util.concurrent.*;

// 隔离所有设备调用：控制进程可终止本 App 工作进程，避免 Binder/JNI 阻塞拖死 UI。
public final class CollectorService extends Service {
    public static final int HELLO=1,COLLECT=2,DONE=3,FAILED=4;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private volatile boolean busy;
    private final Messenger messenger=new Messenger(new Handler(Looper.getMainLooper(),msg->{
        if(msg.what==HELLO){reply(msg.replyTo,HELLO,null,null);return true;}
        if(msg.what!=COLLECT||busy)return true;
        String module=msg.getData().getString("module"),session=msg.getData().getString("session");Messenger client=msg.replyTo;busy=true;
        executor.execute(()->{try{File file=ReportStore.part(this,session,module);ItemSink sink=new ItemSink(this,session);sink.checkpointFile=file;try{Collectors.get(module).collect(sink);}catch(Exception|LinkageError e){sink.failed(module,"collection/"+module+"/error","模块执行异常","collector",module,"error",e);}ReportStore.write(file,sink.items.toString());reply(client,DONE,module,null);}catch(Exception e){reply(client,FAILED,module,e.toString());}finally{busy=false;}});return true;
    }));
    private void reply(Messenger client,int what,String module,String error){if(client==null)return;try{Message out=Message.obtain(null,what);out.arg1=android.os.Process.myPid();Bundle b=new Bundle();b.putString("module",module);b.putString("error",error);out.setData(b);client.send(out);}catch(RemoteException ignored){}}
    @Override public IBinder onBind(Intent intent){return messenger.getBinder();}
    @Override public void onDestroy(){executor.shutdownNow();super.onDestroy();}
}
