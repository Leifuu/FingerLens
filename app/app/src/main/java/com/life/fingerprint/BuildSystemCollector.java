package com.life.fingerprint;

import android.os.*;
import android.provider.Settings;
import android.content.*;
import android.content.res.Configuration;
import android.app.ActivityManager;
import android.app.usage.UsageStatsManager;
import android.location.LocationManager;
import android.webkit.WebView;
import android.webkit.WebSettings;
import org.json.*;
import java.lang.reflect.*;
import java.util.*;
import java.io.File;

public final class BuildSystemCollector implements Collector {
    private static final String[] SYSTEM_ENVIRONMENT_KEYS={"screen_brightness","screen_brightness_mode","screen_off_timeout","accelerometer_rotation","user_rotation","window_animation_scale","transition_animation_scale","animator_duration_scale"};
    private static final String[] RISK_PROPERTY_KEYS={
            "ro.boot.verifiedbootstate","ro.boot.flash.locked","ro.boot.vbmeta.device_state",
            "ro.boot.veritymode","ro.boot.warranty_bit","ro.boot.vbmeta.digest","ro.boot.slot_suffix",
            "ro.boot.dynamic_partitions","ro.boot.selinux","ro.boot.hardware","ro.product.hardware.sku",
            "ro.product.first_api_level","ro.product.cpu.abilist","ro.debuggable","ro.secure",
            "ro.adb.secure","ro.build.tags","ro.build.type","ro.build.version.security_patch"};
    private static final Set<String> RISK_PROPERTY_SET=new HashSet<>(Arrays.asList(RISK_PROPERTY_KEYS));
    private final String module;
    public BuildSystemCollector(String name){module=name;}
    public void collect(ItemSink s)throws Exception {
        switch(module){
            case "build": build(s);break;
            case "kernel": kernel(s);break;
            case "storage": storage(s);break;
            case "environment": environment(s);break;
            case "binder": Commands.collect(s,"binder","binder/service-list","/system/bin/service","list");break;
            default: throw new IllegalArgumentException(module);
        }
    }
    private void build(ItemSink s)throws Exception {
        for(Class<?> cls:new Class<?>[]{Build.class,Build.VERSION.class})for(Field f:cls.getFields()){
            if(!Modifier.isStatic(f.getModifiers()))continue;
            s.read("build","build/"+cls.getSimpleName()+"/"+f.getName(),f.getName(),cls.getName()+"."+f.getName(),()->f.get(null));
        }
        s.read("build","build/java-properties","Java 运行时属性","System.getProperties",()->{JSONObject o=new JSONObject();Properties props=System.getProperties();for(String k:props.stringPropertyNames())Json.put(o,k,props.getProperty(k));return o;});
        if(Build.VERSION.SDK_INT>=26){
            JSONArray props=new JSONArray(NativeProbe.properties());
            for(int i=0;i<props.length();i++){JSONObject p=props.getJSONObject(i);Object name=Json.bytes(p.getString("nameBase64"));Object value=Json.bytes(p.getString("valueBase64"));s.ok("build","build/native-property/"+String.valueOf(name),String.valueOf(name),"jni","__system_property_foreach + __system_property_read_callback",value);}
            s.ok("build","build/risk-properties-summary","风控相关原生属性摘要","jni","__system_property_foreach + __system_property_read_callback",riskPropertiesSummary(props));
        }else s.add("build","build/native-properties","原生属性枚举","jni","__system_property_read_callback","unsupported",null,Json.obj("minSdk",26),null);
        s.ok("build","build/abi-summary","ABI 与运行时架构摘要","android-api","Build.SUPPORTED_ABIS / Process.is64Bit",abiSummary());
        s.ok("build","build/hardware-summary","硬件型号与 SoC 摘要","android-api","Build public hardware fields",hardwareSummary());
        Commands.collect(s,"build","build/getprop","/system/bin/getprop");
    }
    // 业务块：把公开 ABI 列表压缩为架构族和位数计数；Build 原始字段继续保留。
    private static JSONObject abiSummary(){
        String[] all=Build.SUPPORTED_ABIS==null?new String[0]:Build.SUPPORTED_ABIS;
        String[] bit32=Build.SUPPORTED_32_BIT_ABIS==null?new String[0]:Build.SUPPORTED_32_BIT_ABIS;
        String[] bit64=Build.SUPPORTED_64_BIT_ABIS==null?new String[0]:Build.SUPPORTED_64_BIT_ABIS;
        int arm=0,x86=0,riscv=0,other=0;
        for(String abi:all){String value=abi==null?"":abi.toLowerCase(Locale.ROOT);if(value.startsWith("arm")||value.startsWith("aarch"))arm++;else if(value.startsWith("x86"))x86++;else if(value.startsWith("riscv"))riscv++;else other++;}
        return Json.obj("available",true,"supportedAbiCount",all.length,"supported32BitAbiCount",bit32.length,"supported64BitAbiCount",bit64.length,"primaryAbi",all.length==0?null:all[0],"armAbiCount",arm,"x86AbiCount",x86,"riscvAbiCount",riscv,"otherAbiCount",other,"runtime64Bit",Build.VERSION.SDK_INT>=23?android.os.Process.is64Bit():null);
    }
    // 业务块：整理公开硬件型号和 SoC 字段；构建指纹只输出长度与 SHA-256，原始 Build 字段继续保留。
    private static JSONObject hardwareSummary()throws Exception{
        String fingerprint=Build.FINGERPRINT;
        return Json.obj("available",true,"manufacturer",Build.MANUFACTURER,"brand",Build.BRAND,"model",Build.MODEL,"device",Build.DEVICE,"product",Build.PRODUCT,"hardware",Build.HARDWARE,"board",Build.BOARD,"fingerprintPresent",fingerprint!=null&&!fingerprint.isEmpty(),"fingerprintLength",fingerprint==null?null:fingerprint.length(),"fingerprintSha256",fingerprint==null?null:PackageInputCollector.sha(fingerprint.getBytes(java.nio.charset.StandardCharsets.UTF_8)),"socManufacturer",Build.VERSION.SDK_INT>=31?Build.SOC_MANUFACTURER:null,"socModel",Build.VERSION.SDK_INT>=31?Build.SOC_MODEL:null);
    }
    // 业务块：从完整原生属性中提取稳定的风控字段；原始属性条目继续完整保留。
    private static JSONObject riskPropertiesSummary(JSONArray props)throws JSONException{
        Map<String,String> observed=new HashMap<>();
        for(int i=0;i<props.length();i++){
            JSONObject property=props.getJSONObject(i);
            Object name=Json.bytes(property.getString("nameBase64"));
            if(!(name instanceof String)||!RISK_PROPERTY_SET.contains((String)name))continue;
            Object value=Json.bytes(property.getString("valueBase64"));
            if(value instanceof String)observed.put((String)name,(String)value);
        }
        JSONObject values=new JSONObject();JSONArray observedProperties=new JSONArray(),missingProperties=new JSONArray();int observedCount=0;
        for(String key:RISK_PROPERTY_KEYS){String value=observed.get(key);Json.put(values,key,value);if(value!=null){observedProperties.put(key);observedCount++;}else missingProperties.put(key);}
        return Json.obj("observedCount",observedCount,"knownCount",RISK_PROPERTY_KEYS.length,"observedProperties",observedProperties,"missingProperties",missingProperties,"properties",values);
    }
    private void kernel(ItemSink s)throws Exception {
        s.ok("kernel","kernel/uname","内核 uname","jni","uname",new JSONObject(NativeProbe.kernel()));
        for(String p:new String[]{"/proc/version","/proc/cpuinfo","/proc/meminfo","/proc/self/status","/proc/self/maps","/proc/self/mountinfo","/proc/self/mounts","/proc/self/cgroup","/proc/self/limits","/proc/self/sched","/proc/self/attr/current","/proc/1/cgroup","/proc/uptime","/proc/sys/kernel/random/boot_id","/proc/sys/kernel/osrelease","/sys/devices/system/cpu/present","/sys/devices/system/cpu/online"})s.file("kernel","kernel/file"+p,p);
        s.read("kernel","kernel/process-status-summary","当前进程状态摘要","/proc/self/status",BuildSystemCollector::processStatusSummary);
        s.read("kernel","kernel/mount-summary","挂载拓扑摘要","/proc/self/mountinfo",BuildSystemCollector::mountSummary);
        s.read("kernel","kernel/cgroup-summary","Cgroup 环境摘要","/proc/self/cgroup",BuildSystemCollector::cgroupSummary);
        s.read("kernel","kernel/cpu-summary","CPU 拓扑与频率摘要","/sys/devices/system/cpu",BuildSystemCollector::cpuSummary);
        File[] cpus=new File("/sys/devices/system/cpu").listFiles();if(cpus!=null)for(File cpu:cpus)if(cpu.getName().matches("cpu[0-9]{1,4}"))for(String suffix:new String[]{"cpufreq/cpuinfo_max_freq","cpufreq/cpuinfo_min_freq","cpufreq/scaling_cur_freq","cpufreq/scaling_governor","topology/core_id","topology/physical_package_id"})s.file("kernel","kernel/cpu/"+cpu.getName()+"/"+suffix,new File(cpu,suffix).getPath());
    }
    // 业务块：从公开 proc 状态中提取有限的调试/隔离线索，不复制进程状态原文。
    private static JSONObject processStatusSummary()throws Exception{
        JSONObject raw=new JSONObject(NativeProbe.readFile("/proc/self/status",65536));
        if(!raw.optBoolean("ok")){int errno=raw.optInt("errno",0);return Json.obj("available",false,"status",errno==1||errno==13?"denied":"error","traced",null,"tracerPid",null,"state",null,"threadCount",null,"noNewPrivs",null,"seccompMode",null,"capEffPresent",null,"capBndPresent",null,"fieldsObserved",0,"truncated",false,"errno",errno);}
        Object decoded=raw.has("base64")?Json.bytes(raw.getString("base64")):null;
        if(!(decoded instanceof String))return Json.obj("available",false,"status","unavailable","traced",null,"tracerPid",null,"state",null,"threadCount",null,"noNewPrivs",null,"seccompMode",null,"capEffPresent",null,"capBndPresent",null,"fieldsObserved",0,"truncated",raw.optBoolean("truncated"));
        Map<String,String> fields=new HashMap<>();for(String line:((String)decoded).split("\\n")){int colon=line.indexOf(':');if(colon>0)fields.put(line.substring(0,colon),line.substring(colon+1).trim());}
        Integer tracerPid=integer(fields.get("TracerPid"));return Json.obj("available",true,"status","ok","traced",tracerPid!=null&&tracerPid!=0,"tracerPid",tracerPid,"state",fields.get("State"),"threadCount",integer(fields.get("Threads")),"noNewPrivs",integer(fields.get("NoNewPrivs")),"seccompMode",integer(fields.get("Seccomp")),"capEffPresent",fields.containsKey("CapEff"),"capBndPresent",fields.containsKey("CapBnd"),"fieldsObserved",fields.size(),"truncated",raw.optBoolean("truncated"));
    }
    private static Integer integer(String value){if(value==null)return null;try{return Integer.valueOf(value);}catch(NumberFormatException e){return null;}}
    // 业务块：压缩 mountinfo 的文件系统和挂载属性计数，不导出挂载路径、源设备或原始行。
    private static JSONObject mountSummary()throws Exception{
        JSONObject raw=new JSONObject(NativeProbe.readFile("/proc/self/mountinfo",262144));
        if(!raw.optBoolean("ok")){int errno=raw.optInt("errno",0);return Json.obj("available",false,"status",errno==1||errno==13?"denied":"error","entryCount",null,"readOnlyCount",null,"sharedMountCount",null,"masterMountCount",null,"optionalFieldCount",null,"overlayCount",null,"tmpfsCount",null,"procCount",null,"sysfsCount",null,"ext4Count",null,"rootFsType",null,"fsTypeCounts",new JSONObject(),"fsTypeCount",null,"fsTypesTruncated",null,"truncated",false,"errno",errno);}
        Object decoded=raw.has("base64")?Json.bytes(raw.getString("base64")):null;
        if(!(decoded instanceof String))return Json.obj("available",false,"status","unavailable","entryCount",null,"readOnlyCount",null,"sharedMountCount",null,"masterMountCount",null,"optionalFieldCount",null,"overlayCount",null,"tmpfsCount",null,"procCount",null,"sysfsCount",null,"ext4Count",null,"rootFsType",null,"fsTypeCounts",new JSONObject(),"fsTypeCount",null,"fsTypesTruncated",null,"truncated",raw.optBoolean("truncated"));
        Map<String,Integer> counts=new HashMap<>();int entries=0,readOnly=0,shared=0,master=0,optional=0,overlay=0,tmpfs=0,proc=0,sysfs=0,ext4=0;String rootFsType=null;boolean typesTruncated=false;
        for(String line:((String)decoded).split("\\n")){if(line.isEmpty())continue;String[] halves=line.split(" - ",2);if(halves.length!=2)continue;String[] left=halves[0].trim().split(" ");String[] right=halves[1].trim().split(" ");if(left.length<6||right.length<1)continue;entries++;if(hasOption(left[5],"ro"))readOnly++;for(int i=6;i<left.length;i++){optional++;if(left[i].startsWith("shared:"))shared++;if(left[i].startsWith("master:"))master++;}String fs=right[0];if("/".equals(left[4]))rootFsType=fs;if("overlay".equals(fs))overlay++;if("tmpfs".equals(fs))tmpfs++;if("proc".equals(fs))proc++;if("sysfs".equals(fs))sysfs++;if("ext4".equals(fs))ext4++;if(counts.containsKey(fs)||counts.size()<64)counts.put(fs,counts.containsKey(fs)?counts.get(fs)+1:1);else typesTruncated=true;}
        JSONObject fsTypeCounts=new JSONObject();ArrayList<String> names=new ArrayList<>(counts.keySet());Collections.sort(names);for(String name:names)Json.put(fsTypeCounts,name,counts.get(name));
        return Json.obj("available",true,"status","ok","entryCount",entries,"readOnlyCount",readOnly,"sharedMountCount",shared,"masterMountCount",master,"optionalFieldCount",optional,"overlayCount",overlay,"tmpfsCount",tmpfs,"procCount",proc,"sysfsCount",sysfs,"ext4Count",ext4,"rootFsType",rootFsType,"fsTypeCounts",fsTypeCounts,"fsTypeCount",counts.size(),"fsTypesTruncated",typesTruncated,"truncated",raw.optBoolean("truncated"));
    }
    private static boolean hasOption(String options,String wanted){for(String option:options.split(","))if(wanted.equals(option))return true;return false;}
    // 业务块：压缩 cgroup 版本、控制器和有限环境标记，不导出 cgroup 路径或 UID 目录。
    private static JSONObject cgroupSummary()throws Exception{
        JSONObject raw=new JSONObject(NativeProbe.readFile("/proc/self/cgroup",65536));
        if(!raw.optBoolean("ok")){int errno=raw.optInt("errno",0);return Json.obj("available",false,"status",errno==1||errno==13?"denied":"error","cgroupVersion","unknown","entryCount",null,"v1EntryCount",null,"v2EntryCount",null,"controllerCount",null,"controllerNames",new JSONArray(),"controllerNamesTruncated",null,"maxPathDepth",null,"dockerMarker",null,"kubernetesMarker",null,"lxcMarker",null,"sandboxMarker",null,"truncated",false,"errno",errno);}
        Object decoded=raw.has("base64")?Json.bytes(raw.getString("base64")):null;
        if(!(decoded instanceof String))return Json.obj("available",false,"status","unavailable","cgroupVersion","unknown","entryCount",null,"v1EntryCount",null,"v2EntryCount",null,"controllerCount",null,"controllerNames",new JSONArray(),"controllerNamesTruncated",null,"maxPathDepth",null,"dockerMarker",null,"kubernetesMarker",null,"lxcMarker",null,"sandboxMarker",null,"truncated",raw.optBoolean("truncated"));
        Map<String,Integer> controllers=new HashMap<>();int entries=0,v1=0,v2=0,maxDepth=0;boolean docker=false,kubernetes=false,lxc=false,sandbox=false,namesTruncated=false;
        for(String line:((String)decoded).split("\\n")){if(line.isEmpty())continue;String[] parts=line.split(":",3);if(parts.length!=3)continue;entries++;String controllerText=parts[1],path=parts[2];if("0".equals(parts[0])&&controllerText.isEmpty())v2++;else v1++;int depth=0;for(String segment:path.split("/"))if(!segment.isEmpty())depth++;if(depth>maxDepth)maxDepth=depth;String lower=path.toLowerCase(Locale.ROOT);docker|=lower.contains("docker");kubernetes|=lower.contains("kubepods")||lower.contains("kube.slice");lxc|=lower.contains("lxc");sandbox|=lower.contains("sandbox")||lower.contains("containerd")||lower.contains("libpod");if(!controllerText.isEmpty())for(String name:controllerText.split(",")){if(name.isEmpty())continue;if(controllers.containsKey(name)||controllers.size()<64)controllers.put(name,controllers.containsKey(name)?controllers.get(name)+1:1);else namesTruncated=true;}}
        JSONArray controllerNames=new JSONArray();ArrayList<String> names=new ArrayList<>(controllers.keySet());Collections.sort(names);for(String name:names)controllerNames.put(name);String version=v1>0&&v2>0?"mixed":v2>0?"v2":v1>0?"v1":"unknown";
        return Json.obj("available",true,"status","ok","cgroupVersion",version,"entryCount",entries,"v1EntryCount",v1,"v2EntryCount",v2,"controllerCount",controllers.size(),"controllerNames",controllerNames,"controllerNamesTruncated",namesTruncated,"maxPathDepth",maxDepth,"dockerMarker",docker,"kubernetesMarker",kubernetes,"lxcMarker",lxc,"sandboxMarker",sandbox,"truncated",raw.optBoolean("truncated"));
    }
    // 业务块：汇总公开 CPU 拓扑和 cpufreq 能力，限制 CPU 数量与拓扑 ID 数量，不改变 kernel/cpu/* 原始条目。
    private static JSONObject cpuSummary()throws Exception{
        File root=new File("/sys/devices/system/cpu");File[] files=root.listFiles();if(files==null)return Json.obj("available",false,"status","unavailable","logicalProcessorCount",null,"runtimeProcessorCount",Runtime.getRuntime().availableProcessors(),"presentCount",null,"onlineCount",null,"offlineCount",null,"frequencyValueCount",0,"minFrequencyKHz",null,"maxFrequencyKHz",null,"governorCounts",new JSONObject(),"coreIdCount",0,"physicalPackageIdCount",0,"coreIds",new JSONArray(),"physicalPackageIds",new JSONArray(),"topologyValuesTruncated",false,"cpuDirectoriesTruncated",false);
        ArrayList<File> cpus=new ArrayList<>();for(File file:files)if(file.isDirectory()&&file.getName().matches("cpu[0-9]{1,4}"))cpus.add(file);cpus.sort(Comparator.comparing(File::getName));boolean cpuLimit=cpus.size()>256;if(cpuLimit)cpus=new ArrayList<>(cpus.subList(0,256));
        Integer present=rangeCount(textFile("/sys/devices/system/cpu/present",1024)),online=rangeCount(textFile("/sys/devices/system/cpu/online",1024));Long minFrequency=null,maxFrequency=null;int frequencyValues=0;Map<String,Integer> governors=new HashMap<>();Set<Integer> coreIds=new HashSet<>(),packages=new HashSet<>();boolean topologyLimit=false;
        for(File cpu:cpus){Long min=longNumber(textFile(new File(cpu,"cpufreq/cpuinfo_min_freq").getPath(),128)),max=longNumber(textFile(new File(cpu,"cpufreq/cpuinfo_max_freq").getPath(),128));if(min!=null){frequencyValues++;minFrequency=minFrequency==null?min:Math.min(minFrequency,min);}if(max!=null){frequencyValues++;maxFrequency=maxFrequency==null?max:Math.max(maxFrequency,max);}String governor=textFile(new File(cpu,"cpufreq/scaling_governor").getPath(),128);if(governor!=null&&!governor.trim().isEmpty()){String name=governor.trim();governors.put(name,governors.containsKey(name)?governors.get(name)+1:1);}Integer core=integer(textFile(new File(cpu,"topology/core_id").getPath(),128));if(core!=null){if(coreIds.size()<64||coreIds.contains(core))coreIds.add(core);else topologyLimit=true;}Integer packageId=integer(textFile(new File(cpu,"topology/physical_package_id").getPath(),128));if(packageId!=null){if(packages.size()<64||packages.contains(packageId))packages.add(packageId);else topologyLimit=true;}}
        JSONObject governorCounts=new JSONObject();ArrayList<String> governorNames=new ArrayList<>(governors.keySet());Collections.sort(governorNames);for(String name:governorNames)Json.put(governorCounts,name,governors.get(name));JSONArray coreValues=sortedIntegers(coreIds),packageValues=sortedIntegers(packages);return Json.obj("available",true,"status","ok","logicalProcessorCount",cpus.size(),"runtimeProcessorCount",Runtime.getRuntime().availableProcessors(),"presentCount",present,"onlineCount",online,"offlineCount",present!=null&&online!=null?Math.max(0,present-online):null,"frequencyValueCount",frequencyValues,"minFrequencyKHz",minFrequency,"maxFrequencyKHz",maxFrequency,"governorCounts",governorCounts,"coreIdCount",coreIds.size(),"physicalPackageIdCount",packages.size(),"coreIds",coreValues,"physicalPackageIds",packageValues,"topologyValuesTruncated",topologyLimit,"cpuDirectoriesTruncated",cpuLimit);
    }
    private static String textFile(String path,int limit)throws Exception{JSONObject raw=new JSONObject(NativeProbe.readFile(path,limit));if(!raw.optBoolean("ok"))return null;Object decoded=raw.has("base64")?Json.bytes(raw.getString("base64")):null;return decoded instanceof String?(String)decoded:null;}
    private static Long longNumber(String value){if(value==null)return null;try{return Long.valueOf(value.trim());}catch(NumberFormatException e){return null;}}
    private static Integer rangeCount(String value){if(value==null||value.trim().isEmpty())return null;int total=0;for(String token:value.trim().split(",")){String[] ends=token.trim().split("-",2);try{int start=Integer.parseInt(ends[0]),end=ends.length==1?start:Integer.parseInt(ends[1]);if(start<0||end<start||end-start>4096)return null;total+=end-start+1;if(total>4096)return null;}catch(NumberFormatException e){return null;}}return total;}
    private static JSONArray sortedIntegers(Set<Integer> values){JSONArray out=new JSONArray();ArrayList<Integer> sorted=new ArrayList<>(values);Collections.sort(sorted);for(Integer value:sorted)out.put(value);return out;}
    private void storage(ItemSink s){
        s.read("storage","storage/summary","存储卷环境摘要","StorageManager.getStorageVolumes",()->storageSummary(s.context));
        for(String path:new String[]{"/","/system","/vendor","/product","/odm","/data",s.context.getFilesDir().getPath(),Environment.getExternalStorageDirectory().getPath()})s.read("storage","storage/statvfs"+path,path,"StatFs("+path+")",()->{StatFs f=new StatFs(path);return Json.obj("path",path,"blockSize",f.getBlockSizeLong(),"blocks",f.getBlockCountLong(),"availableBlocks",f.getAvailableBlocksLong(),"freeBlocks",f.getFreeBlocksLong(),"totalBytes",f.getTotalBytes(),"availableBytes",f.getAvailableBytes());});
        s.read("storage","storage/volumes","存储卷","StorageManager.getStorageVolumes",()->{JSONArray a=new JSONArray();android.os.storage.StorageManager m=s.context.getSystemService(android.os.storage.StorageManager.class);for(android.os.storage.StorageVolume v:m.getStorageVolumes()){JSONObject o=Json.obj("description",v.getDescription(s.context),"uuid",v.getUuid(),"state",v.getState(),"primary",v.isPrimary(),"removable",v.isRemovable(),"emulated",v.isEmulated());if(Build.VERSION.SDK_INT>=30)Json.put(o,"directory",v.getDirectory()==null?null:v.getDirectory().getPath());a.put(o);}return a;});
        s.file("storage","storage/partitions","/proc/partitions");s.file("storage","storage/diskstats","/proc/diskstats");
    }
    // 业务块：汇总公开存储卷拓扑，不把路径或 UUID 重复展开；storage/* 原始条目继续保留。
    private static JSONObject storageSummary(Context context)throws Exception{
        android.os.storage.StorageManager manager=context.getSystemService(android.os.storage.StorageManager.class);if(manager==null)return Json.obj("available",false,"volumeCount",0,"stateCounts",new JSONObject());
        List<android.os.storage.StorageVolume> volumes=manager.getStorageVolumes();JSONObject stateCounts=new JSONObject();int mountedCount=0,primaryCount=0,removableCount=0,emulatedCount=0,uuidPresentCount=0;for(android.os.storage.StorageVolume volume:volumes){String state=volume.getState();if(state!=null){Json.put(stateCounts,state,stateCounts.optInt(state,0)+1);if(Environment.MEDIA_MOUNTED.equals(state)||Environment.MEDIA_MOUNTED_READ_ONLY.equals(state))mountedCount++;}if(volume.isPrimary())primaryCount++;if(volume.isRemovable())removableCount++;if(volume.isEmulated())emulatedCount++;if(volume.getUuid()!=null)uuidPresentCount++;}
        return Json.obj("available",true,"volumeCount",volumes.size(),"mountedCount",mountedCount,"primaryCount",primaryCount,"removableCount",removableCount,"emulatedCount",emulatedCount,"uuidPresentCount",uuidPresentCount,"stateCounts",stateCounts);
    }
    private void environment(ItemSink s){
        s.read("environment","environment/locales","语言与区域","Configuration / LocaleList / TimeZone",()->{Configuration c=s.context.getResources().getConfiguration();TimeZone z=TimeZone.getDefault();return Json.obj("locales",c.getLocales().toLanguageTags(),"timezoneId",z.getID(),"timezoneDisplayName",z.getDisplayName(),"rawOffset",z.getRawOffset(),"daylightSavings",z.getDSTSavings(),"configuration",c.toString(),"fontScale",c.fontScale,"densityDpi",c.densityDpi,"uiMode",c.uiMode,"keyboard",c.keyboard,"navigation",c.navigation,"touchscreen",c.touchscreen);});
        s.read("environment","environment/locale-summary","语言与时区摘要","Configuration / DateFormat / LocaleManager",()->localeSummary(s.context));
        s.ok("environment","environment/clocks","单次时钟快照","android-api","SystemClock",Json.obj("elapsedRealtime",SystemClock.elapsedRealtime(),"uptimeMillis",SystemClock.uptimeMillis(),"wallClockMillis",System.currentTimeMillis()));
        s.ok("environment","environment/clock-summary","系统时钟摘要","android-api","SystemClock",clockSummary());
        s.read("environment","environment/risk-settings","系统风控相关设置摘要","Settings.Global / Settings.Secure",()->riskSettings(s.context));
        s.read("environment","environment/system-settings","显示与动画设置摘要","Settings.System",()->systemSettings(s.context));
        for(String key:SYSTEM_ENVIRONMENT_KEYS)s.read("environment","environment/system/"+key,key,"Settings.System.getString",()->Settings.System.getString(s.context.getContentResolver(),key));
        s.read("environment","environment/device-state","设备初始化与运行状态","ActivityManager / UserManager / Settings",()->deviceState(s.context));
        s.read("environment","environment/user-profiles","用户与工作资料摘要","UserManager.getUserProfiles",()->userProfileSummary(s.context));
        s.read("environment","environment/app-standby","当前 App 待机分桶","UsageStatsManager.getAppStandbyBucket",()->appStandby(s.context));
        s.read("environment","environment/process-exits","当前 App 进程退出历史","ActivityManager.getHistoricalProcessExitReasons",()->processExitEnvironment(s.context));
        s.read("environment","environment/low-power-standby","低功耗待机与重启能力","PowerManager",()->lowPowerStandby(s.context));
        s.read("environment","environment/location","定位与 GNSS 能力","LocationManager public state",()->locationEnvironment(s.context));
        String[] globals={"adb_enabled","development_settings_enabled","airplane_mode_on","auto_time","auto_time_zone","http_proxy","private_dns_mode","private_dns_specifier","wifi_on","bluetooth_on"};
        for(String k:globals)s.read("environment","environment/global/"+k,k,"Settings.Global.getString",()->Settings.Global.getString(s.context.getContentResolver(),k));
        for(String k:new String[]{"android_id","enabled_accessibility_services","accessibility_enabled","default_input_method","enabled_input_methods","mock_location","install_non_market_apps"})s.read("environment","environment/secure/"+k,k,"Settings.Secure.getString",()->Settings.Secure.getString(s.context.getContentResolver(),k));
        s.read("environment","environment/webview-provider","WebView 提供方环境","WebView.getCurrentWebViewPackage",()->{
            if(Build.VERSION.SDK_INT<26)throw new UnsupportedOperationException("requires API 26");
            android.content.pm.PackageInfo info=WebView.getCurrentWebViewPackage();
            if(info==null)return null;
            return Json.obj("packageName",info.packageName,"versionName",info.versionName,
                    "versionCode",Build.VERSION.SDK_INT>=28?info.getLongVersionCode():(long)info.versionCode,
                     "signatures",PackageInputCollector.signatures(info));
        });
        s.read("environment","environment/webview-user-agent","WebView 默认 User-Agent","WebSettings.getDefaultUserAgent",()->{
            String userAgent=WebSettings.getDefaultUserAgent(s.context);
            if(userAgent==null)return null;
            return Json.obj("userAgent",userAgent,"length",userAgent.length());
        });
        // 业务块：只记录 PowerManager 公开的当前电源/热状态，不控制电源策略。
        s.read("environment","environment/power-thermal","电源与热状态","PowerManager",()->{
            PowerManager power=s.context.getSystemService(PowerManager.class);
            if(power==null)return null;
            JSONObject value=Json.obj("powerSaveMode",power.isPowerSaveMode(),"interactive",power.isInteractive());
            if(Build.VERSION.SDK_INT>=23){Boolean ignored=null;String queryStatus="allowed";try{ignored=power.isIgnoringBatteryOptimizations(s.context.getPackageName());}catch(SecurityException e){queryStatus="denied";}Json.put(value,"ignoresBatteryOptimizations",ignored);Json.put(value,"batteryOptimizationQueryStatus",queryStatus);}else{Json.put(value,"ignoresBatteryOptimizations",null);Json.put(value,"batteryOptimizationQueryStatus","unsupported");}
            if(Build.VERSION.SDK_INT>=23)Json.put(value,"deviceIdleMode",power.isDeviceIdleMode());
            if(Build.VERSION.SDK_INT>=33)Json.put(value,"lightDeviceIdleMode",power.isDeviceLightIdleMode());
            if(Build.VERSION.SDK_INT>=28)Json.put(value,"locationPowerSaveMode",power.getLocationPowerSaveMode());
            if(Build.VERSION.SDK_INT>=29)Json.put(value,"thermalStatus",power.getCurrentThermalStatus());
            if(Build.VERSION.SDK_INT>=30)Json.put(value,"thermalHeadroom",power.getThermalHeadroom(0));
            return value;
        });
        ActivityManager.MemoryInfo memory=new ActivityManager.MemoryInfo();((ActivityManager)s.context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(memory);
        s.ok("environment","environment/memory","内存快照","android-api","ActivityManager.getMemoryInfo",Json.obj("totalMem",memory.totalMem,"availMem",memory.availMem,"threshold",memory.threshold,"lowMemory",memory.lowMemory,"runtimeProcessors",Runtime.getRuntime().availableProcessors()));
    }
    private static JSONObject clockSummary(){
        long elapsedRealtime=SystemClock.elapsedRealtime();long uptimeMillis=SystemClock.uptimeMillis();long wallClockMillis=System.currentTimeMillis();
        return Json.obj("elapsedRealtimeMillis",elapsedRealtime,"uptimeMillis",uptimeMillis,"wallClockMillis",wallClockMillis,"bootTimeMillis",wallClockMillis-elapsedRealtime,"sleepMillis",Math.max(0L,elapsedRealtime-uptimeMillis));
    }
    // 业务块：整理可比较的语言、时区与显示区域设置；完整 Configuration 仍保留在 environment/locales。
    private static JSONObject localeSummary(Context context){
        if(context==null||context.getResources()==null)return Json.obj("available",false,"localeTags",null,"localeCount",null,"defaultLocale",null,"timezoneId",null,"rawOffsetMillis",null,"usesDaylightTime",null,"inDaylightTime",null,"is24HourFormat",null,"layoutDirection",null,"fontScale",null,"densityDpi",null,"uiMode",null,"applicationLocaleAccess","unavailable","applicationLocaleTags",null);
        Configuration configuration=context.getResources().getConfiguration();android.os.LocaleList locales=configuration.getLocales();TimeZone zone=TimeZone.getDefault();
        JSONObject out=Json.obj("available",true,"localeTags",locales==null?null:locales.toLanguageTags(),"localeCount",locales==null?null:locales.size(),"defaultLocale",java.util.Locale.getDefault()==null?null:java.util.Locale.getDefault().toLanguageTag(),"timezoneId",zone.getID(),"rawOffsetMillis",zone.getRawOffset(),"usesDaylightTime",zone.useDaylightTime(),"inDaylightTime",zone.inDaylightTime(new Date()),"is24HourFormat",android.text.format.DateFormat.is24HourFormat(context),"layoutDirection",configuration.getLayoutDirection(),"fontScale",configuration.fontScale,"densityDpi",configuration.densityDpi,"uiMode",configuration.uiMode,"applicationLocaleAccess",Build.VERSION.SDK_INT>=33?"unknown":"unsupported","applicationLocaleTags",null);
        if(Build.VERSION.SDK_INT>=33){android.app.LocaleManager manager=context.getSystemService(android.app.LocaleManager.class);if(manager==null){Json.put(out,"applicationLocaleAccess","unavailable");}else try{android.os.LocaleList appLocales=manager.getApplicationLocales();Json.put(out,"applicationLocaleAccess","allowed");Json.put(out,"applicationLocaleTags",appLocales==null?null:appLocales.toLanguageTags());}catch(SecurityException e){Json.put(out,"applicationLocaleAccess","denied");}catch(RuntimeException e){Json.put(out,"applicationLocaleAccess","error");Json.put(out,"error",e.getClass().getName());}}
        return out;
    }
    // 业务块：把公开系统设置整理成可读摘要；原始 Settings 条目仍单独保留供复核。
    private static JSONObject riskSettings(Context context){
        android.content.ContentResolver resolver=context.getContentResolver();
        return Json.obj("adbEnabled",settingFlag(resolver,true,"adb_enabled"),
                "developmentSettingsEnabled",settingFlag(resolver,true,"development_settings_enabled"),
                "airplaneModeOn",settingFlag(resolver,true,"airplane_mode_on"),
                "autoTime",settingFlag(resolver,true,"auto_time"),
                "autoTimeZone",settingFlag(resolver,true,"auto_time_zone"),
                "privateDnsMode",Settings.Global.getString(resolver,"private_dns_mode"),
                "privateDnsSpecifier",Settings.Global.getString(resolver,"private_dns_specifier"),
                "wifiOn",settingFlag(resolver,true,"wifi_on"),
                "bluetoothOn",settingFlag(resolver,true,"bluetooth_on"),
                "accessibilityEnabled",settingFlag(resolver,false,"accessibility_enabled"),
                "mockLocation",settingFlag(resolver,false,"mock_location"),
                "installNonMarketApps",settingFlag(resolver,false,"install_non_market_apps"));
    }
    // 业务块：整理固定的显示/动画设置；原始键值继续逐项保留，读取失败不伪造结果。
    private static JSONObject systemSettings(Context context){
        JSONObject values=new JSONObject();String status="allowed";android.content.ContentResolver resolver=context.getContentResolver();
        for(String key:SYSTEM_ENVIRONMENT_KEYS)try{Json.put(values,key,Settings.System.getString(resolver,key));}catch(SecurityException e){Json.put(values,key,null);status="denied";}
        return Json.obj("available",true,"status",status,"settings",values);
    }
    // 业务块：只记录当前设备初始化和当前用户运行状态，不读取用户身份或位置。
    private static JSONObject deviceState(Context context){
        android.content.ContentResolver resolver=context.getContentResolver();
        ActivityManager activity=context.getSystemService(ActivityManager.class);
        UserManager users=context.getSystemService(UserManager.class);
        JSONObject out=Json.obj(
                "deviceProvisioned",settingFlag(resolver,true,"device_provisioned"),
                "userSetupComplete",settingFlag(resolver,false,"user_setup_complete"),
                "bootCount",Settings.Global.getString(resolver,"boot_count"),
                "lowRamDevice",activity==null?null:activity.isLowRamDevice(),
                "demoUser",Build.VERSION.SDK_INT>=23&&users!=null?users.isDemoUser():null,
                "userUnlocked",Build.VERSION.SDK_INT<24||users==null||users.isUserUnlocked(),
                "userRunning",Build.VERSION.SDK_INT<17||users==null||users.isUserRunning(android.os.Process.myUserHandle()),
                "userMonkey",ActivityManager.isUserAMonkey());
        if(Build.VERSION.SDK_INT>=29)Json.put(out,"testHarness",ActivityManager.isRunningInUserTestHarness());
        if(Build.VERSION.SDK_INT>=28)Json.put(out,"backgroundRestricted",activity!=null&&activity.isBackgroundRestricted());
        return out;
    }
    // 业务块：只记录当前用户可见的资料数量和托管资料状态，不导出用户句柄、账号或个人内容。
    private static JSONObject userProfileSummary(Context context){
        if(Build.VERSION.SDK_INT<21)return Json.obj("available",false,"status","unsupported","profileCount",null,"managedProfile",null);
        UserManager users=context.getSystemService(UserManager.class);
        if(users==null)return Json.obj("available",false,"status","unavailable","profileCount",null,"managedProfile",null);
        try{
            List<android.os.UserHandle> profiles=users.getUserProfiles();
            return Json.obj("available",true,"status","ok","profileCount",profiles==null?0:profiles.size(),"managedProfile",Build.VERSION.SDK_INT>=24?users.isManagedProfile():null);
        }catch(SecurityException e){
            return Json.obj("available",false,"status","denied","profileCount",null,"managedProfile",null);
        }catch(RuntimeException e){
            return Json.obj("available",false,"status","error","profileCount",null,"managedProfile",null,"error",e.getClass().getName());
        }
    }
    // 业务块：只读取当前 App 的系统待机分桶，不查询其它 App 的使用记录。
    private static JSONObject appStandby(Context context){
        if(Build.VERSION.SDK_INT<28)return Json.obj("available",false,"status","unsupported","bucket",null,"bucketName",null);
        UsageStatsManager usage=context.getSystemService(UsageStatsManager.class);
        if(usage==null)return Json.obj("available",false,"status","unavailable","bucket",null,"bucketName",null);
        try{
            int bucket=usage.getAppStandbyBucket();
            return Json.obj("available",true,"status","ok","bucket",bucket,"bucketName",standbyBucketName(bucket));
        }catch(SecurityException e){
            return Json.obj("available",false,"status","denied","bucket",null,"bucketName",null);
        }catch(RuntimeException e){
            return Json.obj("available",false,"status","error","bucket",null,"bucketName",null,"error",e.getClass().getName());
        }
    }
    private static String standbyBucketName(int bucket){
        if(bucket==UsageStatsManager.STANDBY_BUCKET_ACTIVE)return "active";
        if(bucket==UsageStatsManager.STANDBY_BUCKET_WORKING_SET)return "working_set";
        if(bucket==UsageStatsManager.STANDBY_BUCKET_FREQUENT)return "frequent";
        if(bucket==UsageStatsManager.STANDBY_BUCKET_RARE)return "rare";
        if(bucket==UsageStatsManager.STANDBY_BUCKET_RESTRICTED)return "restricted";
        return "unknown";
    }
    // 业务块：只保留当前 App 最近的进程退出元数据，限制条数且不读取 trace/描述文本。
    private static JSONObject processExitEnvironment(Context context){
        if(Build.VERSION.SDK_INT<30)return Json.obj("available",false,"status","unsupported","count",null,"entries",new JSONArray());
        ActivityManager activity=context.getSystemService(ActivityManager.class);
        if(activity==null)return Json.obj("available",false,"status","unavailable","count",null,"entries",new JSONArray());
        try{
            List<android.app.ApplicationExitInfo> history=activity.getHistoricalProcessExitReasons(context.getPackageName(),0,32);
            if(history==null)return Json.obj("available",false,"status","unavailable","count",null,"entries",new JSONArray());
            JSONObject reasons=new JSONObject(),statuses=new JSONObject();JSONArray entries=new JSONArray();
            for(android.app.ApplicationExitInfo info:history){
                if(info==null)continue;
                String reason=exitReasonName(info.getReason());Json.put(reasons,reason,reasons.optInt(reason,0)+1);
                String status=String.valueOf(info.getStatus());Json.put(statuses,status,statuses.optInt(status,0)+1);
                byte[] state=info.getProcessStateSummary();
                entries.put(Json.obj("processName",info.getProcessName(),"pid",info.getPid(),"reason",info.getReason(),"reasonName",reason,"status",info.getStatus(),"importance",info.getImportance(),"timestamp",info.getTimestamp(),"pssBytes",info.getPss(),"rssBytes",info.getRss(),"processStateSummaryLength",state==null?0:state.length,"processStateSummarySha256",state==null?null:PackageInputCollector.sha(state)));
            }
            return Json.obj("available",true,"status","ok","count",entries.length(),"limit",32,"reasons",reasons,"statuses",statuses,"entries",entries);
        }catch(SecurityException e){
            return Json.obj("available",false,"status","denied","count",null,"entries",new JSONArray());
        }catch(Exception e){
            return Json.obj("available",false,"status","error","count",null,"entries",new JSONArray(),"error",e.getClass().getName());
        }
    }
    private static String exitReasonName(int reason){
        if(reason==android.app.ApplicationExitInfo.REASON_ANR)return "anr";
        if(reason==android.app.ApplicationExitInfo.REASON_CRASH)return "crash";
        if(reason==android.app.ApplicationExitInfo.REASON_CRASH_NATIVE)return "crash_native";
        if(reason==android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED)return "dependency_died";
        if(reason==android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE)return "excessive_resource_usage";
        if(reason==android.app.ApplicationExitInfo.REASON_EXIT_SELF)return "exit_self";
        if(reason==android.app.ApplicationExitInfo.REASON_FREEZER)return "freezer";
        if(reason==android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE)return "initialization_failure";
        if(reason==android.app.ApplicationExitInfo.REASON_LOW_MEMORY)return "low_memory";
        if(reason==android.app.ApplicationExitInfo.REASON_OTHER)return "other";
        if(reason==android.app.ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE)return "package_state_change";
        if(reason==android.app.ApplicationExitInfo.REASON_PACKAGE_UPDATED)return "package_updated";
        if(reason==android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE)return "permission_change";
        if(reason==android.app.ApplicationExitInfo.REASON_SIGNALED)return "signaled";
        if(reason==android.app.ApplicationExitInfo.REASON_UNKNOWN)return "unknown";
        if(reason==android.app.ApplicationExitInfo.REASON_USER_REQUESTED)return "user_requested";
        if(reason==android.app.ApplicationExitInfo.REASON_USER_STOPPED)return "user_stopped";
        return "code_"+reason;
    }
    // 业务块：读取公开的低功耗待机和用户空间重启能力，不改变电源策略。
    private static JSONObject lowPowerStandby(Context context){
        if(Build.VERSION.SDK_INT<30)return Json.obj("available",false,"status","unsupported","lowPowerStandbyEnabled",null,"lowPowerStandbyExempt",null,"rebootingUserspaceSupported",null);
        PowerManager power=context.getSystemService(PowerManager.class);
        if(power==null)return Json.obj("available",false,"status","unavailable","lowPowerStandbyEnabled",null,"lowPowerStandbyExempt",null,"rebootingUserspaceSupported",null);
        try{
            return Json.obj("available",true,"status","ok",
                    "lowPowerStandbyEnabled",Build.VERSION.SDK_INT>=33?power.isLowPowerStandbyEnabled():null,
                    "lowPowerStandbyExempt",Build.VERSION.SDK_INT>=34?power.isExemptFromLowPowerStandby():null,
                    "rebootingUserspaceSupported",power.isRebootingUserspaceSupported());
        }catch(SecurityException e){
            return Json.obj("available",false,"status","denied","lowPowerStandbyEnabled",null,"lowPowerStandbyExempt",null,"rebootingUserspaceSupported",null);
        }catch(RuntimeException e){
            return Json.obj("available",false,"status","error","lowPowerStandbyEnabled",null,"lowPowerStandbyExempt",null,"rebootingUserspaceSupported",null,"error",e.getClass().getName());
        }
    }
    private static Boolean settingFlag(android.content.ContentResolver resolver,boolean global,String key){
        String value=global?Settings.Global.getString(resolver,key):Settings.Secure.getString(resolver,key);
        if(value==null)return null;
        if("1".equals(value)||"true".equalsIgnoreCase(value)||"on".equalsIgnoreCase(value))return true;
        if("0".equals(value)||"false".equalsIgnoreCase(value)||"off".equalsIgnoreCase(value))return false;
        return null;
    }
    // 业务块：只读取定位开关、provider 元数据与 GNSS 能力，不获取位置样本或坐标。
    private static JSONObject locationEnvironment(Context context)throws JSONException{
        LocationManager location=context.getSystemService(LocationManager.class);
        if(location==null)return Json.obj("available",false,"providerCount",0,"providers",new JSONArray());
        ArrayList<String> names=new ArrayList<>();List<String> all=location.getAllProviders();if(all!=null)names.addAll(all);Collections.sort(names,String.CASE_INSENSITIVE_ORDER);
        JSONArray providers=new JSONArray();
        for(String name:names)providers.put(Json.obj("name",name,"enabled",location.isProviderEnabled(name)));
        JSONObject out=Json.obj("available",true,"providerCount",providers.length(),"providers",providers,
                "hasGpsProvider",null,"hasNetworkProvider",null,"hasFusedProvider",null);
        if(Build.VERSION.SDK_INT>=31){
            Json.put(out,"hasGpsProvider",location.hasProvider(LocationManager.GPS_PROVIDER));Json.put(out,"hasNetworkProvider",location.hasProvider(LocationManager.NETWORK_PROVIDER));Json.put(out,"hasFusedProvider",location.hasProvider(LocationManager.FUSED_PROVIDER));
            List<android.location.GnssAntennaInfo> antennas=location.getGnssAntennaInfos();int antennaCount=antennas==null?0:antennas.size(),limit=Math.min(64,antennaCount);JSONArray frequencies=new JSONArray();for(int i=0;i<limit;i++)frequencies.put(antennas.get(i).getCarrierFrequencyMHz());
            Json.put(out,"gnssAntennaCount",antennaCount);Json.put(out,"gnssAntennaInfoTruncated",antennaCount>limit);Json.put(out,"gnssAntennaCarrierFrequenciesMHz",frequencies);
        }
        if(Build.VERSION.SDK_INT>=28){Json.put(out,"locationEnabled",location.isLocationEnabled());Json.put(out,"gnssHardwareModelName",location.getGnssHardwareModelName());Json.put(out,"gnssYearOfHardware",location.getGnssYearOfHardware());}
        if(Build.VERSION.SDK_INT>=31)Json.put(out,"gnssCapabilities",String.valueOf(location.getGnssCapabilities()));
        return out;
    }
}
