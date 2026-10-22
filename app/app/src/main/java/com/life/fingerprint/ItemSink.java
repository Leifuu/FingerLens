package com.life.fingerprint;

import android.content.Context;
import android.os.Process;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

// 单模块结果：状态与原值分离；每个源失败只影响对应项目。
public final class ItemSink {
    public interface Read { Object get() throws Exception; }
    public final Context context;
    public final String session;
    public final JSONArray items = new JSONArray();
    private final Set<String> keys = new HashSet<>();
    private long bytes;
    private boolean full;
    private final String selinux;
    public java.io.File checkpointFile;
    private long lastCheckpoint;
    public ItemSink(Context c, String s) {
        context = c; session = s;
        String domain = "unknown";
        try { JSONObject r = new JSONObject(NativeProbe.readFile("/proc/self/attr/current", 4096)); if (r.optBoolean("ok")) domain = String.valueOf(Json.bytes(r.getString("base64"))); } catch (Exception ignored) {}
        selinux = domain;
    }
    public void add(String category, String key, String name, String kind, String api, String status, Object value, JSONObject error, JSONObject truncation) {
        if (full || !keys.add(key)) return;
        String type = Json.type(value);
        Object encoded = value instanceof Long ? String.valueOf(value) : value;
        JSONObject item = Json.obj("key", key, "category", category, "name", name,
            "source", Json.obj("kind", kind, "api", api, "uid", Process.myUid(), "pid", Process.myPid(), "selinux", selinux),
            "dataType", type, "status", status, "value", encoded);
        if (error != null) Json.put(item, "error", error);
        if (truncation != null) Json.put(item, "truncation", truncation);
        long size = item.toString().getBytes(StandardCharsets.UTF_8).length;
        if (bytes + size > 48L * 1024 * 1024) {
            full = true;
            items.put(Json.obj("key", "collection/module_limit", "category", "collection", "name", "模块输出达到上限", "source", item.optJSONObject("source"), "dataType", "null", "status", "truncated", "value", null, "truncation", Json.obj("limitBytes", 48 * 1024 * 1024)));
            return;
        }
        bytes += size; items.put(item);
        if(checkpointFile!=null&&android.os.SystemClock.elapsedRealtime()-lastCheckpoint>1000){try{ReportStore.write(checkpointFile,items.toString());lastCheckpoint=android.os.SystemClock.elapsedRealtime();}catch(java.io.IOException e){throw new IllegalStateException("保存采集进度失败",e);}}
    }
    public void ok(String c, String k, String n, String kind, String api, Object v) { add(c,k,n,kind,api,"ok",v,null,null); }
    public void read(String c, String k, String n, String api, Read read) {
        try { ok(c,k,n,"android-api",api,read.get()); }
        catch (SecurityException e) { failed(c,k,n,"android-api",api,"denied",e); }
        catch (UnsupportedOperationException | NoSuchMethodError e) { failed(c,k,n,"android-api",api,"unsupported",e); }
        catch (Exception | LinkageError e) { failed(c,k,n,"android-api",api,"error",e); }
    }
    public void failed(String c,String k,String n,String kind,String api,String status,Throwable e) { add(c,k,n,kind,api,status,null,Json.obj("class",e.getClass().getName(),"message",e.getMessage()),null); }
    public void file(String c, String key, String path) {
        try {
            JSONObject r = new JSONObject(NativeProbe.readFile(path, 262144));
            String status = r.optBoolean("ok") ? (r.optBoolean("truncated") ? "truncated" : "ok") : (r.optInt("errno") == 13 || r.optInt("errno") == 1 ? "denied" : "error");
            Object content = r.has("base64") ? Json.bytes(r.getString("base64")) : null;
            boolean binary = content instanceof JSONObject;
            if (binary) content = ((JSONObject)content).optString("value");
            add(c,key,path,"file",path,status,content,r.optBoolean("ok")?null:Json.obj("errno",r.optInt("errno")),r.optBoolean("truncated")?Json.obj("limitBytes",262144,"bytesRead",r.optInt("bytesRead")):null);
            if (binary && items.length() > 0) Json.put(items.optJSONObject(items.length()-1),"dataType","base64");
        } catch (Exception e) { failed(c,key,path,"file",path,"error",e); }
    }
    public boolean isFull() { return full; }
}
