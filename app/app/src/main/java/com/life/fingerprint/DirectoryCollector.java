package com.life.fingerprint;

import android.os.Environment;
import android.system.*;
import org.json.*;
import java.io.*;
import java.util.*;

public final class DirectoryCollector implements Collector {
    private static final int LIMIT=100000, DEPTH=6;
    private int count,chunk;
    private JSONArray entries=new JSONArray();
    private static final class Node {final File file;final int depth,maxDepth;Node(File f,int d,int max){file=f;depth=d;maxDepth=max;}}
    public void collect(ItemSink s)throws Exception {
        ArrayList<String> roots=new ArrayList<>(Arrays.asList("/","/system","/vendor","/product","/odm","/oem","/apex","/proc","/proc/"+android.os.Process.myPid(),"/sys","/sys/devices/system/cpu","/sys/class/power_supply","/dev",s.context.getFilesDir().getCanonicalPath(),Environment.getExternalStorageDirectory().getCanonicalPath()));
        File external=s.context.getExternalFilesDir(null);if(external!=null)roots.add(external.getCanonicalPath());
        s.ok("directories","directories/scope","目录扫描范围","collector","directory-profile@1",Json.obj("roots",roots,"maxDepth",DEPTH,"maxEntries",LIMIT,"followSymlinks",false,"contentsRead",false,"procSysDevPolicy","入口及固定白名单子树"));
        ArrayDeque<Node> queue=new ArrayDeque<>();Set<String> visited=new HashSet<>();for(String root:roots){int max=root.equals("/")||root.equals("/proc")||root.equals("/sys")||root.equals("/dev")?1:DEPTH;queue.add(new Node(new File(root),0,max));}
        boolean limited=false;while(!queue.isEmpty()&&!s.isFull()){
            if(count>=LIMIT){limited=true;break;}Node node=queue.remove();String path=node.file.getPath();if(!visited.add(path))continue;
            JSONObject stat=new JSONObject(NativeProbe.inspectPath(path));Json.put(stat,"path",path);Json.put(stat,"depth",node.depth);
            if(stat.has("linkBase64"))Json.put(stat,"linkTarget",Json.bytes(stat.getString("linkBase64")));
            count++;
            if(stat.optBoolean("ok")&&stat.optString("type").equals("directory")){
                if(node.depth>=node.maxDepth){Json.put(stat,"listingStatus","depth_limit");limited=true;}
                else{
                    int remaining=Math.max(1,LIMIT-count-queue.size());JSONObject listing=new JSONObject(NativeProbe.listDirectory(path,remaining));Json.put(stat,"listingErrno",listing.optInt("errno"));
                    Json.put(stat,"listingStatus",listing.optBoolean("ok")?(listing.optBoolean("truncated")?"entry_limit":"listed"):"unavailable");
                    if(listing.optBoolean("truncated"))limited=true;JSONArray names=listing.getJSONArray("names");Json.put(stat,"returnedChildren",names.length());
                    JSONArray undecodable=new JSONArray();for(int i=0;i<names.length();i++){Object decoded=Json.bytes(names.getString(i));if(!(decoded instanceof String)){undecodable.put(decoded);continue;}if(queue.size()+count>=LIMIT){limited=true;Json.put(stat,"listingStatus","entry_limit");break;}queue.add(new Node(new File(node.file,(String)decoded),node.depth+1,node.maxDepth));}
                    if(undecodable.length()>0){Json.put(stat,"undecodableNames",undecodable);Json.put(stat,"listingStatus","partial_invalid_utf8_names");limited=true;}
                }
            }
            entries.put(stat);if(entries.length()>=200)flush(s);
        }
        flush(s);s.add("directories","directories/summary","目录扫描完成范围","collector","directory-profile@1",limited||s.isFull()?"truncated":"ok",Json.obj("entries",count,"pendingNodes",queue.size(),"maxDepth",DEPTH,"maxEntries",LIMIT),null,limited?Json.obj("reason","depth_or_entry_limit","maxDepth",DEPTH,"maxEntries",LIMIT):null);
    }
    private void flush(ItemSink s){if(entries.length()==0)return;s.ok("directories",String.format(Locale.ROOT,"directories/chunk/%05d",chunk++),"目录元数据批次","jni","openat(O_NOFOLLOW) + fstatat(AT_SYMLINK_NOFOLLOW) + bounded readdir",entries);entries=new JSONArray();}
}
