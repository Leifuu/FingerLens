package com.life.fingerprint;

import android.Manifest;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public final class CollectionController {
    private static CollectionController instance;
    public static synchronized CollectionController get(Context c){if(instance==null)instance=new CollectionController(c.getApplicationContext());return instance;}
    private final Context context;
    private final Handler control;
    private JSONObject data;
    private JSONArray items;
    private int index,workerPid,generation;
    private long deadline,totalBytes;
    private String session,stopReason,bridgeId;
    private ServiceConnection connection;
    private boolean completedWithLimits;
    public volatile boolean running;
    public volatile String text="准备就绪\n所有信息来自普通 App 身份，拒绝权限仍可继续。";
    public volatile int progress;
    public volatile File reportFile;
    CollectionController(Context c){context=c;HandlerThread thread=new HandlerThread("collection-control");thread.start();control=new Handler(thread.getLooper());File saved=new File(c.getFilesDir(),"latest-path.txt");try{if(saved.isFile()){File f=new File(ReportStore.read(saved));if(f.isFile()){reportFile=f;JSONObject old=new JSONObject(ReportStore.read(f));if(old.getJSONObject("data").optString("state").equals("running")){Json.put(old.getJSONObject("data"),"state","interrupted");Json.put(old.getJSONObject("data"),"finishedAt",now());Json.put(old,"code",1);Json.put(old,"message","上次采集进程中断，保留已完成模块");ReportStore.write(f,old.toString());text="上次采集已中断，可导出已有结果或重新采集。";}else text="已有指纹，可导出或重新采集。";}}}catch(Exception e){text="上次指纹读取失败："+e.getMessage();}}
    public static String now(){SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX",Locale.ROOT);return f.format(new Date());}
    public static String[] requestedPermissions(){ArrayList<String> p=new ArrayList<>(Arrays.asList(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.READ_PHONE_STATE,Manifest.permission.READ_PHONE_NUMBERS));if(Build.VERSION.SDK_INT<=32)p.add(Manifest.permission.READ_EXTERNAL_STORAGE);if(Build.VERSION.SDK_INT<=29)p.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);if(Build.VERSION.SDK_INT>=31)p.add(Manifest.permission.BLUETOOTH_CONNECT);if(Build.VERSION.SDK_INT>=33)p.add(Manifest.permission.NEARBY_WIFI_DEVICES);return p.toArray(new String[0]);}
    public static JSONObject permissions(Context c)throws Exception{JSONObject o=new JSONObject();PackageInfo p=c.getPackageManager().getPackageInfo(c.getPackageName(),PackageManager.GET_PERMISSIONS);if(p.requestedPermissions!=null)for(String permission:p.requestedPermissions)Json.put(o,permission,c.checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED);Json.put(o,"special/allFilesAccess",Build.VERSION.SDK_INT>=30?Environment.isExternalStorageManager():null);return o;}
    public void start(){start(null);}
    public synchronized boolean start(String requestId){
        if(requestId!=null&&!requestId.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}"))throw new IllegalArgumentException("非法采集任务标识");
        if(running)return false;running=true;text="正在准备采集…";progress=0;control.post(()->{try{
        bridgeId=requestId;session=requestId==null?UUID.randomUUID().toString():requestId;stopReason=null;index=0;totalBytes=0;completedWithLimits=false;items=new JSONArray();deadline=SystemClock.elapsedRealtime()+180000;
        PackageInfo info=context.getPackageManager().getPackageInfo(context.getPackageName(),Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES);
        data=Json.obj("schemaVersion","1.0","collectorVersion","1.1.0","sessionId",session,"startedAt",now(),"finishedAt",null,"state","running",
            "config",Json.obj("profile","app-full-snapshot@1","sampling","single","modules",Collectors.MODULES,"directoryMaxDepth",6,"directoryMaxEntries",100000,"rawFileLimitBytes",262144,"jsonLimitBytes",ReportStore.MAX_BYTES,"regularTimeoutMs",15000,"attestationTimeoutMs",30000,"directoryTimeoutMs",60000,"totalTimeoutMs",180000),
            "permissions",permissions(context),"app",Json.obj("packageName",context.getPackageName(),"versionName",info.versionName,"versionCode",info.versionCode,"signatures",PackageInputCollector.signatures(info),"uid",android.os.Process.myUid(),"targetSdk",context.getApplicationInfo().targetSdkVersion,"minSdk",context.getApplicationInfo().minSdkVersion,"androidSdk",Build.VERSION.SDK_INT,"supportedAbis",Build.SUPPORTED_ABIS,"debuggable",(context.getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0),"items",items);
        reportFile=new File(ReportStore.exports(context),"fingerprint-"+session+".json");ReportStore.write(new File(context.getFilesDir(),"latest-path.txt"),reportFile.getAbsolutePath());persist();next();
    }catch(Exception e){abort(e);}});return true;}
    public void cancel(){control.post(()->{if(!running)return;stopReason="cancelled";detach(true);finish("cancelled");});}
    public void cancel(String requestId){control.post(()->{if(!running||!requestId.equals(bridgeId))return;stopReason="cancelled";detach(true);finish("cancelled");});}
    private void next(){
        if(!running)return;if(stopReason!=null){finish(stopReason);return;}if(index>=Collectors.MODULES.length){finish(completedWithLimits?"partial":"complete");return;}
        if(SystemClock.elapsedRealtime()>=deadline){stopReason="partial";for(int i=index;i<Collectors.MODULES.length;i++)marker(Collectors.MODULES[i],"skipped","整轮采集达到180秒上限");finish("partial");return;}
        final int token=++generation;final String module=Collectors.MODULES[index];workerPid=0;progress=index*100/Collectors.MODULES.length;text="采集中 "+(index+1)+" / "+Collectors.MODULES.length+"\n"+Collectors.NAMES[index]+"\n"+summary();
        try{publishBridge();}catch(Exception e){abort(e);return;}
        Messenger replies=new Messenger(new Handler(control.getLooper(),msg->{if(token!=generation||!running)return true;
            if(msg.what==CollectorService.HELLO){workerPid=msg.arg1;Message work=Message.obtain(null,CollectorService.COLLECT);Bundle args=new Bundle();args.putString("module",module);args.putString("session",session);work.setData(args);work.replyTo=replyMessenger;try{remote.send(work);}catch(Exception e){complete(token,module,"error",e.toString(),true);}return true;}
            if(msg.what==CollectorService.DONE||msg.what==CollectorService.FAILED)complete(token,module,msg.what==CollectorService.DONE?null:"error",msg.getData().getString("error"),false);return true;}));
        replyMessenger=replies;
        connection=new ServiceConnection(){public void onServiceConnected(ComponentName name,IBinder binder){control.post(()->{if(token!=generation)return;remote=new Messenger(binder);Message hello=Message.obtain(null,CollectorService.HELLO);hello.replyTo=replies;try{remote.send(hello);}catch(Exception e){complete(token,module,"error",e.toString(),true);}});}public void onServiceDisconnected(ComponentName name){control.post(()->complete(token,module,"error","采集进程意外结束",false));}public void onBindingDied(ComponentName name){control.post(()->complete(token,module,"error","采集绑定失效",true));}public void onNullBinding(ComponentName name){control.post(()->complete(token,module,"error","采集服务无绑定",false));}};
        try{if(!context.bindService(new Intent(context,CollectorService.class),connection,Context.BIND_AUTO_CREATE)){complete(token,module,"error","无法启动采集进程",false);return;}}catch(Exception e){complete(token,module,"error",e.toString(),false);return;}
        long wait=Math.min(Collectors.timeout(module),Math.max(1,deadline-SystemClock.elapsedRealtime()));control.postDelayed(()->complete(token,module,"timeout","模块达到时间上限",true),wait);
    }
    private Messenger remote,replyMessenger;
    private void complete(int token,String module,String status,String error,boolean kill){
        if(token!=generation||!running)return;detach(kill);
        try{File part=ReportStore.part(context,session,module);if(part.isFile()){JSONArray got=new JSONArray(ReportStore.read(part));for(int i=0;i<got.length();i++){JSONObject item=got.getJSONObject(i);int bytes=item.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;if(totalBytes+bytes>ReportStore.MAX_BYTES-1024*1024){marker(module,"truncated","整份指纹达到64MiB预算");stopReason="partial";break;}totalBytes+=bytes;items.put(item);String state=item.optString("status");if(!state.equals("ok")&&!state.equals("unsupported")&&!state.equals("skipped"))completedWithLimits=true;}}
            if(status!=null)marker(module,status,error);index++;persist();next();
        }catch(Exception e){abort(e);}
    }
    private void marker(String module,String status,String message){completedWithLimits=true;items.put(Json.obj("key","collection/"+module+"/"+items.length(),"category","collection","name",module+" 采集状态","source",Json.obj("kind","collector","api",module,"uid",android.os.Process.myUid(),"pid",android.os.Process.myPid(),"selinux","控制进程，未读取"),"dataType","null","status",status,"value",null,"error",Json.obj("message",message)));}
    private void detach(boolean kill){generation++;if(kill&&workerPid>0&&workerPid!=android.os.Process.myPid())android.os.Process.killProcess(workerPid);workerPid=0;if(connection!=null){try{context.unbindService(connection);}catch(Exception ignored){}connection=null;}remote=null;}
    private void finish(String state){try{
        if(!state.equals("complete")&&index<Collectors.MODULES.length){File part=ReportStore.part(context,session,Collectors.MODULES[index]);if(part.isFile()){JSONArray got=new JSONArray(ReportStore.read(part));for(int i=0;i<got.length();i++){JSONObject item=got.getJSONObject(i);long n=item.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;if(totalBytes+n>ReportStore.MAX_BYTES-1024*1024)break;items.put(item);totalBytes+=n;}}}
        Json.put(data,"state",state);Json.put(data,"finishedAt",now());progress=state.equals("complete")||index>=Collectors.MODULES.length?100:progress;text=(state.equals("complete")?"采集完成":state.equals("cancelled")?"已取消，保留已有结果":"采集完成，部分信息受限或截断")+"\n"+summary()+"\n"+reportFile.getAbsolutePath();persist();running=false;
    }catch(Exception e){abort(e);}}
    private void persist()throws Exception {String state=data.optString("state");JSONObject out=Json.obj("data",data,"message",state.equals("complete")?"采集完成":state.equals("running")?"采集中":"非完整结果，详见逐项状态","code",state.equals("complete")?0:state.equals("running")?2:1);ReportStore.write(reportFile,out.toString());publishBridge();}
    // 进度独立于原始指纹；完成时一次 gzip，后端轮询不再反复拉取整份 JSON。
    private void publishBridge()throws Exception {
        if(bridgeId==null||data==null)return;
        String state=data.optString("state");boolean done=!state.equals("running");
        JSONObject status=Json.obj("requestId",bridgeId,"sessionId",session,"state",state,"progress",progress,"text",text,"uid",android.os.Process.myUid());
        if(done&&reportFile!=null&&reportFile.isFile()){
            File transfer=reportFile;
            try{File compressed=ReportStore.gzip(reportFile);if(compressed.length()<=16*1024*1024)transfer=compressed;}catch(IOException e){Json.put(status,"compressionError",e.toString());}
            Json.put(status,"fileName",transfer.getName());Json.put(status,"bytes",transfer.length());Json.put(status,"jsonBytes",reportFile.length());Json.put(status,"sha256",ReportStore.sha256(reportFile));
        }
        ReportStore.write(new File(ReportStore.exports(context),"bridge-"+bridgeId+".json"),Json.obj("data",status,"message","采集传输状态，非设备指纹","code",0).toString());
    }
    private String summary(){int ok=0,denied=0,error=0;for(int i=0;i<items.length();i++){String state=items.optJSONObject(i).optString("status");if(state.equals("ok"))ok++;else if(state.equals("denied")||state.equals("unsupported")||state.equals("skipped"))denied++;else error++;}return "成功 "+ok+" · 受限/未执行 "+denied+" · 异常/截断 "+error;}
    private void abort(Exception e){detach(true);running=false;text="采集异常，已保存模块可导出："+e.getMessage();try{if(data!=null){Json.put(data,"state","interrupted");Json.put(data,"finishedAt",now());persist();}}catch(Exception ignored){}}
}
