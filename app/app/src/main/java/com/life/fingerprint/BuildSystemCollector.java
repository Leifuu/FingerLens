package com.life.fingerprint;

import android.os.*;
import android.provider.Settings;
import android.content.*;
import android.content.res.Configuration;
import android.content.pm.*;
import android.app.ActivityManager;
import android.app.usage.UsageStatsManager;
import android.location.LocationManager;
import android.webkit.WebView;
import android.webkit.WebSettings;
import android.media.RingtoneManager;
import android.net.Uri;
import org.json.*;
import java.lang.reflect.*;
import java.util.*;
import java.io.File;

public final class BuildSystemCollector implements Collector {
    private static final String[] SYSTEM_ENVIRONMENT_KEYS={"screen_brightness","screen_brightness_mode","screen_off_timeout","accelerometer_rotation","user_rotation","window_animation_scale","transition_animation_scale","animator_duration_scale","alarm_alert","date_format","end_button_behavior","font_scale","text_auto_replace","text_auto_punctuate","time_12_24"};
    private static final String[] RISK_PROPERTY_KEYS={
            "ro.boot.verifiedbootstate","ro.boot.flash.locked","ro.boot.vbmeta.device_state",
            "ro.boot.veritymode","ro.boot.warranty_bit","ro.boot.vbmeta.digest","ro.boot.slot_suffix",
            "ro.boot.dynamic_partitions","ro.boot.selinux","ro.boot.hardware","ro.product.hardware.sku",
            "ro.product.first_api_level","ro.product.cpu.abilist","ro.debuggable","ro.secure",
            "ro.adb.secure","ro.build.tags","ro.build.type","ro.build.version.security_patch"};
    private static final Set<String> RISK_PROPERTY_SET=new HashSet<>(Arrays.asList(RISK_PROPERTY_KEYS));
    private static final String[] MAP_MARKERS={"frida","gadget","xposed","substrate","riru","zygisk","magisk"};
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
        s.read("build","build/radio-version","无线电固件版本","Build.getRadioVersion",()->radioVersionValue(Build.getRadioVersion()));
        s.ok("build","build/os-summary","Android 系统版本摘要","android-api","Build.VERSION",osSummary());
        s.ok("build","build/fingerprinted-partitions","系统分区构建指纹","android-api","Build.getFingerprintedPartitions / Build.Partition",fingerprintedPartitions());
        s.read("build","build/sdk-extensions","SDK 扩展版本摘要","SdkExtensions.getAllExtensionVersions / getExtensionVersion",BuildSystemCollector::sdkExtensions);
        s.read("build","build/module-summary","Mainline 系统模块摘要","PackageManager.getInstalledModules",()->moduleSummary(s.context));
        s.read("build","build/modules","Mainline 系统模块清单","PackageManager.getInstalledModules / getPackageInfo(MATCH_APEX)",()->moduleList(s.context));
        s.read("build","build/apex-packages","当前 App 可见的已安装 APEX 包","PackageManager.getInstalledPackages(MATCH_APEX) / PackageInfo.isApex",()->apexPackages(s.context));
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
    // 业务块：独立保留公开无线电固件 getter 的空值语义；不把版本字符串当作硬件唯一标识。
    static JSONObject radioVersionValue(String version){
        if(version!=null&&version.length()>2048)throw new IllegalArgumentException("无线电固件版本过长");
        return Json.obj("available",true,"status",version==null||version.isEmpty()?"empty":"ok","version",version,"length",version==null?null:version.length());
    }
    // 业务块：整理公开系统版本与补丁环境；Build.VERSION 原始字段继续保留。
    private static JSONObject osSummary(){
        JSONObject value=Json.obj("available",true,"sdkInt",Build.VERSION.SDK_INT,"release",Build.VERSION.RELEASE,"codename",Build.VERSION.CODENAME,"incremental",Build.VERSION.INCREMENTAL,"securityPatch",Build.VERSION.SDK_INT>=23?Build.VERSION.SECURITY_PATCH:null,"baseOs",Build.VERSION.SDK_INT>=23?Build.VERSION.BASE_OS:null,"previewSdkInt",Build.VERSION.SDK_INT>=23?Build.VERSION.PREVIEW_SDK_INT:null,"firstApiLevel",Build.VERSION.SDK_INT>=24?firstApiLevel():null,"mediaPerformanceClass",Build.VERSION.SDK_INT>=31?Build.VERSION.MEDIA_PERFORMANCE_CLASS:null);
        JSONObject full=sdkFullVersion(Build.VERSION.SDK_INT,()->Build.VERSION.SDK_INT_FULL);Json.put(value,"sdkIntFull",full.opt("sdkIntFull"));Json.put(value,"sdkIntFullMajor",full.opt("sdkIntFullMajor"));Json.put(value,"sdkIntFullMinor",full.opt("sdkIntFullMinor"));Json.put(value,"sdkIntFullStatus",full.optString("status"));if(full.has("errorClass"))Json.put(value,"sdkIntFullErrorClass",full.opt("errorClass"));return value;
    }
    // 业务块：API36+保留公开 major/minor SDK 编码；旧系统不访问新字段，异常不伪造版本值。
    static JSONObject sdkFullVersion(int sdk,java.util.function.IntSupplier read){
        if(sdk<36)return Json.obj("status","unsupported","sdkIntFull",null,"sdkIntFullMajor",null,"sdkIntFullMinor",null);
        try{int full=Objects.requireNonNull(read,"missing full SDK query").getAsInt();if(full<0)throw new IllegalArgumentException("完整SDK版本不能为负数");return Json.obj("status","ok","sdkIntFull",full,"sdkIntFullMajor",Build.getMajorSdkVersion(full),"sdkIntFullMinor",Build.getMinorSdkVersion(full));}
        catch(UnsupportedOperationException|NoSuchMethodError e){return Json.obj("status","unsupported","sdkIntFull",null,"sdkIntFullMajor",null,"sdkIntFullMinor",null,"errorClass",e.getClass().getName());}
        catch(Exception|LinkageError e){return Json.obj("status","error","sdkIntFull",null,"sdkIntFullMajor",null,"sdkIntFullMinor",null,"errorClass",e.getClass().getName());}
    }
    // 业务块：API29+保留公开系统分区指纹与构建时间；不读取分区文件、不把单分区差异解释为篡改结论。
    private static JSONObject fingerprintedPartitions(){
        if(Build.VERSION.SDK_INT<29)return fingerprintedPartitionsUnavailable("unsupported",null);
        try{
            List<Build.Partition> partitions=Build.getFingerprintedPartitions();
            if(partitions==null)return fingerprintedPartitionsUnavailable("error","java.lang.NullPointerException");
            String[] names=new String[partitions.size()],fingerprints=new String[partitions.size()];long[] buildTimes=new long[partitions.size()];
            for(int i=0;i<partitions.size();i++){
                Build.Partition partition=partitions.get(i);if(partition==null)throw new IllegalArgumentException("分区列表含 null 项");
                names[i]=partition.getName();fingerprints[i]=partition.getFingerprint();buildTimes[i]=partition.getBuildTimeMillis();
            }
            return fingerprintedPartitionValues(names,fingerprints,buildTimes);
        }catch(SecurityException e){return fingerprintedPartitionsUnavailable("denied",e.getClass().getName());}
        catch(UnsupportedOperationException|NoSuchMethodError e){return fingerprintedPartitionsUnavailable("unsupported",e.getClass().getName());}
        catch(Exception|LinkageError e){return fingerprintedPartitionsUnavailable("error",e.getClass().getName());}
    }
    static JSONObject fingerprintedPartitionValues(String[] names,String[] fingerprints,long[] buildTimes)throws Exception{
        if(names==null||fingerprints==null||buildTimes==null||names.length!=fingerprints.length||names.length!=buildTimes.length)throw new IllegalArgumentException("分区字段长度不一致");
        if(names.length>4096)throw new IllegalArgumentException("分区数量超过4096项");
        JSONArray all=new JSONArray();
        for(int i=0;i<names.length;i++){
            if(names[i]!=null&&names[i].length()>2048)throw new IllegalArgumentException("分区名称过长");
            if(fingerprints[i]!=null&&fingerprints[i].length()>2048)throw new IllegalArgumentException("分区指纹过长");
            all.put(Json.obj("name",names[i],"fingerprint",fingerprints[i],"buildTimeMillis",buildTimes[i]));
        }
        int limit=Math.min(128,all.length());JSONArray entries=new JSONArray();for(int i=0;i<limit;i++)entries.put(all.opt(i));
        return Json.obj("available",true,"status",all.length()>128?"partial":"ok","scope","public-build-partition-fingerprints","partitionCount",all.length(),"returnedCount",entries.length(),"partitionLimit",128,"partitionsTruncated",all.length()>128,"partitionSetSha256",PackageInputCollector.sha(all.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),"partitions",entries);
    }
    static JSONObject fingerprintedPartitionsUnavailable(String status,String errorClass){
        return Json.obj("available",false,"status",status,"scope","public-build-partition-fingerprints","partitionCount",null,"returnedCount",0,"partitionLimit",128,"partitionsTruncated",null,"partitionSetSha256",null,"partitions",new JSONArray(),"errorClass",errorClass);
    }
    // 业务块：API 30 仅查询 R；API 31+ 读取公开扩展集合，不探测任意 ID，也不把版本 0 当作缺失。
    private static JSONObject sdkExtensions(){
        if(Build.VERSION.SDK_INT<30)return sdkExtensionsUnavailable("unsupported",null);
        try{
            boolean all=Build.VERSION.SDK_INT>=31;
            Map<Integer,Integer> versions=all?android.os.ext.SdkExtensions.getAllExtensionVersions():Collections.singletonMap(30,android.os.ext.SdkExtensions.getExtensionVersion(30));
            return sdkExtensionValues(versions,all);
        }catch(SecurityException e){return sdkExtensionsUnavailable("denied",e.getClass().getName());}
        catch(Exception|LinkageError e){return sdkExtensionsUnavailable("error",e.getClass().getName());}
    }
    static JSONObject sdkExtensionValues(Map<Integer,Integer> versions,boolean all)throws Exception{
        TreeMap<Integer,Integer> sorted=new TreeMap<>(versions);JSONArray entries=new JSONArray();StringBuilder canonical=new StringBuilder();
        for(Map.Entry<Integer,Integer> entry:sorted.entrySet()){
            if(entry.getKey()<30||entry.getValue()==null||entry.getValue()<0)throw new IllegalArgumentException("非法 SDK 扩展版本");
            canonical.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
            if(entries.length()<32)entries.put(Json.obj("extensionId",entry.getKey(),"version",entry.getValue()));
        }
        return Json.obj("available",true,"status",sorted.size()>32?"partial":"ok","enumerationMode",all?"all-reported-extensions":"android-r-only",
                "entryCount",sorted.size(),"returnedCount",entries.length(),"entryLimit",32,"entriesTruncated",sorted.size()>32,
                "extensionSetSha256",PackageInputCollector.sha(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),"entries",entries);
    }
    private static JSONObject sdkExtensionsUnavailable(String status,String error){
        return Json.obj("available",false,"status",status,"enumerationMode",null,"entryCount",null,"returnedCount",0,"entryLimit",32,"entriesTruncated",null,"extensionSetSha256",null,"entries",new JSONArray(),"errorClass",error);
    }
    // 业务块：汇总公开 Mainline 模块名称和包名，限制展示规模并用集合哈希保留全量差异线索。
    private static JSONObject moduleSummary(Context context)throws Exception{
        if(Build.VERSION.SDK_INT<29)return Json.obj("available",false,"status","unsupported","count",null,"hiddenCount",null,"namedCount",null,"packageNameCount",null,"moduleNames",new JSONArray(),"packageNames",new JSONArray(),"moduleNamesTruncated",null,"packageNamesTruncated",null,"moduleSetSha256",null);
        try{
            List<ModuleInfo> modules=installedModules(context);TreeSet<String> names=new TreeSet<>(),packages=new TreeSet<>();ArrayList<String> canonical=new ArrayList<>();int hidden=0;
            for(ModuleInfo module:modules){if(module==null)continue;String name=module.getName()==null?"":module.getName().toString(),packageName=module.getPackageName()==null?"":module.getPackageName();if(module.isHidden())hidden++;if(!name.isEmpty())names.add(name);if(!packageName.isEmpty())packages.add(packageName);canonical.add(name+"\u0000"+packageName+"\u0000"+module.isHidden());}
            Collections.sort(canonical);StringBuilder joined=new StringBuilder();for(String entry:canonical)joined.append(entry).append('\n');
            return Json.obj("available",true,"status","ok","count",modules.size(),"hiddenCount",hidden,"namedCount",names.size(),"packageNameCount",packages.size(),"moduleNames",boundedStrings(names,256),"packageNames",boundedStrings(packages,256),"moduleNamesTruncated",names.size()>256,"packageNamesTruncated",packages.size()>256,"moduleSetSha256",PackageInputCollector.sha(joined.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }catch(SecurityException e){return Json.obj("available",false,"status","denied","count",null,"hiddenCount",null,"namedCount",null,"packageNameCount",null,"moduleNames",new JSONArray(),"packageNames",new JSONArray(),"moduleNamesTruncated",null,"packageNamesTruncated",null,"moduleSetSha256",null);}catch(RuntimeException e){return Json.obj("available",false,"status","error","count",null,"hiddenCount",null,"namedCount",null,"packageNameCount",null,"moduleNames",new JSONArray(),"packageNames",new JSONArray(),"moduleNamesTruncated",null,"packageNamesTruncated",null,"moduleSetSha256",null,"error",e.getClass().getName());}
    }
    private static JSONObject moduleList(Context context)throws Exception{
        List<ModuleInfo> modules=installedModules(context);PackageManager manager=context.getPackageManager();Map<String,JSONObject> versions=new HashMap<>();JSONArray values=new JSONArray();int limit=Math.min(512,modules.size());
        for(int i=0;i<limit;i++){ModuleInfo module=modules.get(i);if(module==null)continue;values.put(Json.obj("name",module.getName()==null?null:module.getName().toString(),"packageName",module.getPackageName(),"hidden",module.isHidden(),"packageVersion",versions.computeIfAbsent(module.getPackageName(),name->modulePackageVersion(manager,name))));}
        return Json.obj("count",modules.size(),"returnedCount",values.length(),"truncated",modules.size()>limit,"modules",values);
    }
    // 业务块：仅查询模块清单关联包，包含当前已安装 APEX；单包失败不抹去模块元数据或猜测版本。
    static JSONObject modulePackageVersion(PackageManager manager,String packageName){
        JSONObject out=Json.obj("status","unavailable","versionName",null,"versionCode",null,"isApex",null,"errorClass",null);
        if(Build.VERSION.SDK_INT<29){Json.put(out,"status","unsupported");return out;}
        if(packageName==null||packageName.isEmpty()){Json.put(out,"status","missing-package-name");return out;}
        if(manager==null)return out;
        try{
            PackageInfo info=manager.getPackageInfo(packageName,PackageManager.MATCH_APEX);if(info==null)return out;
            Json.put(out,"status","ok");Json.put(out,"versionName",info.versionName);Json.put(out,"versionCode",info.getLongVersionCode());Json.put(out,"isApex",info.isApex);
        }catch(PackageManager.NameNotFoundException e){Json.put(out,"status","not-found-or-not-visible");Json.put(out,"errorClass",e.getClass().getName());}
        catch(SecurityException e){Json.put(out,"status","denied");Json.put(out,"errorClass",e.getClass().getName());}
        catch(RuntimeException|LinkageError e){Json.put(out,"status","error");Json.put(out,"errorClass",e.getClass().getName());}
        return out;
    }
    // 业务块：独立枚举公开安装包中的 APEX，不把 ModuleInfo 配置包名猜成实际 APEX 包名。
    private static JSONObject apexPackages(Context context)throws Exception{
        if(Build.VERSION.SDK_INT<29)throw new UnsupportedOperationException("requires API 29");
        PackageManager manager=context.getPackageManager();if(manager==null)throw new IllegalStateException("PackageManager unavailable");
        return apexPackageValues(manager.getInstalledPackages(PackageManager.MATCH_APEX));
    }
    static JSONObject apexPackageValues(List<PackageInfo> packages)throws Exception{
        if(packages==null||packages.size()>20000)throw new IllegalArgumentException("包清单缺失或超过 20000 项");
        ArrayList<JSONObject> values=new ArrayList<>();
        for(PackageInfo info:packages){
            if(info==null)throw new IllegalArgumentException("包清单含 null 项");
            if(!info.isApex)continue;
            values.add(Json.obj("packageName",info.packageName,"versionName",info.versionName,"versionCode",info.getLongVersionCode()));
        }
        values.sort(Comparator.comparing(JSONObject::toString));JSONArray all=new JSONArray(values),entries=new JSONArray(values.subList(0,Math.min(256,values.size())));
        return Json.obj("available",true,"status",values.size()>256?"partial":"ok","scope","current-app-visible-installed-apex",
                "packageCount",values.size(),"returnedCount",entries.length(),"packageLimit",256,"truncated",values.size()>256,
                "packageSetSha256",PackageInputCollector.sha(all.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),"packages",entries);
    }
    private static List<ModuleInfo> installedModules(Context context){if(Build.VERSION.SDK_INT<29)throw new UnsupportedOperationException("requires API 29");PackageManager manager=context.getPackageManager();if(manager==null)throw new IllegalStateException("PackageManager unavailable");return manager.getInstalledModules(PackageManager.MATCH_ALL);}
    private static JSONArray boundedStrings(Collection<String> values,int limit){JSONArray out=new JSONArray();int count=0;for(String value:values){if(count++>=limit)break;out.put(value);}return out;}
    private static Integer firstApiLevel(){
        for(String name:new String[]{"DEVICE_INITIAL_SDK_INT","FIRST_API_LEVEL"})try{return Build.VERSION.class.getField(name).getInt(null);}catch(Exception ignored){}
        return null;
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
        s.read("kernel","kernel/sysconf-summary","内存页与时钟节拍摘要","Os.sysconf(_SC_PAGESIZE / _SC_CLK_TCK)",BuildSystemCollector::sysconfSummary);
        for(String p:new String[]{"/proc/version","/proc/cpuinfo","/proc/meminfo","/proc/self/status","/proc/self/maps","/proc/self/mountinfo","/proc/self/mounts","/proc/self/cgroup","/proc/self/limits","/proc/self/sched","/proc/self/attr/current","/proc/1/cgroup","/proc/uptime","/proc/sys/kernel/random/boot_id","/proc/sys/kernel/osrelease","/sys/devices/system/cpu/present","/sys/devices/system/cpu/online"})s.file("kernel","kernel/file"+p,p);
        s.read("kernel","kernel/process-status-summary","当前进程状态摘要","/proc/self/status",BuildSystemCollector::processStatusSummary);
        s.read("kernel","kernel/maps-summary","当前进程内存映射摘要","/proc/self/maps",BuildSystemCollector::mapsSummary);
        s.read("kernel","kernel/mount-summary","挂载拓扑摘要","/proc/self/mountinfo",BuildSystemCollector::mountSummary);
        s.read("kernel","kernel/cgroup-summary","Cgroup 环境摘要","/proc/self/cgroup",BuildSystemCollector::cgroupSummary);
        s.read("kernel","kernel/cpu-summary","CPU 拓扑与频率摘要","/sys/devices/system/cpu",BuildSystemCollector::cpuSummary);
        File[] cpus=new File("/sys/devices/system/cpu").listFiles();if(cpus!=null)for(File cpu:cpus)if(cpu.getName().matches("cpu[0-9]{1,4}"))for(String suffix:new String[]{"cpufreq/cpuinfo_max_freq","cpufreq/cpuinfo_min_freq","cpufreq/scaling_cur_freq","cpufreq/scaling_governor","topology/core_id","topology/physical_package_id"})s.file("kernel","kernel/cpu/"+cpu.getName()+"/"+suffix,new File(cpu,suffix).getPath());
    }
    // 业务块：只查询两个要求正数的运行参数，保留原始返回值与逐项状态，不推断 APK 页大小兼容性。
    private static JSONObject sysconfSummary(){
        String[] fields={"pageSizeBytes","clockTicksPerSecond"},names={"_SC_PAGESIZE","_SC_CLK_TCK"};
        int[] selectors={android.system.OsConstants._SC_PAGESIZE,android.system.OsConstants._SC_CLK_TCK};
        JSONObject summary=Json.obj("scope","current-app-runtime","queryCount",selectors.length);JSONArray entries=new JSONArray();int known=0;
        for(int i=0;i<selectors.length;i++){
            Long raw=null,value=null;String status="ok",error=null;
            try{raw=android.system.Os.sysconf(selectors[i]);if(raw>0){value=raw;known++;}else status="unavailable";}
            catch(SecurityException e){status="denied";error=e.getClass().getName();}
            catch(Exception e){status="error";error=e.getClass().getName();}
            Json.put(summary,fields[i],value);entries.put(Json.obj("name",names[i],"status",status,"rawValue",raw,"errorClass",error));
        }
        Json.put(summary,"available",known>0);Json.put(summary,"status",known==selectors.length?"ok":known>0?"partial":"unavailable");Json.put(summary,"knownCount",known);Json.put(summary,"unknownCount",selectors.length-known);Json.put(summary,"entries",entries);return summary;
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
    // 业务块：从公开 maps 元数据提取映射权限、来源类别和受控 marker；不把路径复制进摘要，也不据此判定具体工具。
    private static JSONObject mapsSummary()throws Exception{
        JSONObject raw=new JSONObject(NativeProbe.readFile("/proc/self/maps",262144));boolean truncated=raw.optBoolean("truncated");
        if(!raw.optBoolean("ok")){int errno=raw.optInt("errno",0);return mapsUnavailable(errno==1||errno==13?"denied":"error",errno,truncated);}
        Object decoded=raw.has("base64")?Json.bytes(raw.getString("base64")):null;
        if(!(decoded instanceof String))return mapsUnavailable("unavailable",0,truncated);
        int mapCount=0,invalidLineCount=0,executable=0,writable=0,privateCount=0,sharedCount=0,anonymous=0,fileBacked=0,dev=0,memfd=0,ashmem=0,apk=0,apex=0,deleted=0,markerMatches=0;
        Map<String,Integer> permissions=new TreeMap<>();JSONObject markers=emptyMapMarkerPresence();
        for(String line:((String)decoded).split("\\n")){
            String normalized=line.trim();if(normalized.isEmpty())continue;String[] columns=normalized.split("\\s+",6);
            if(columns.length<5||columns[1].length()<4){invalidLineCount++;continue;}
            String perms=columns[1];mapCount++;permissions.put(perms,permissions.containsKey(perms)?permissions.get(perms)+1:1);
            if(perms.indexOf('x')>=0)executable++;if(perms.indexOf('w')>=0)writable++;if(perms.charAt(3)=='p')privateCount++;if(perms.charAt(3)=='s')sharedCount++;
            String path=columns.length>=6?columns[5].trim():"";String lower=path.toLowerCase(Locale.ROOT);boolean pseudo=path.isEmpty()||path.startsWith("[");if(pseudo)anonymous++;else fileBacked++;
            if(lower.startsWith("/dev/"))dev++;if(lower.contains("memfd:"))memfd++;if(lower.contains("ashmem"))ashmem++;if(lower.contains(".apk"))apk++;if(lower.contains(".apex")||lower.contains("/apex/"))apex++;if(lower.endsWith("(deleted)"))deleted++;
            boolean matched=false;for(String marker:MAP_MARKERS)if(lower.contains(marker)){Json.put(markers,marker,true);matched=true;}if(matched)markerMatches++;
        }
        JSONObject permissionCounts=new JSONObject();for(Map.Entry<String,Integer> entry:permissions.entrySet())Json.put(permissionCounts,entry.getKey(),entry.getValue());
        return Json.obj("available",true,"status","ok","mapCount",mapCount,"invalidLineCount",invalidLineCount,"executableMappingCount",executable,"writableMappingCount",writable,"privateMappingCount",privateCount,"sharedMappingCount",sharedCount,"anonymousMappingCount",anonymous,"fileBackedMappingCount",fileBacked,"devMappingCount",dev,"memfdMappingCount",memfd,"ashmemMappingCount",ashmem,"apkMappingCount",apk,"apexMappingCount",apex,"deletedMappingCount",deleted,"markerMatchCount",markerMatches,"markerPresence",markers,"permissionCounts",permissionCounts,"truncated",truncated);
    }
    private static JSONObject mapsUnavailable(String status,int errno,boolean truncated){return Json.obj("available",false,"status",status,"mapCount",null,"invalidLineCount",null,"executableMappingCount",null,"writableMappingCount",null,"privateMappingCount",null,"sharedMappingCount",null,"anonymousMappingCount",null,"fileBackedMappingCount",null,"devMappingCount",null,"memfdMappingCount",null,"ashmemMappingCount",null,"apkMappingCount",null,"apexMappingCount",null,"deletedMappingCount",null,"markerMatchCount",null,"markerPresence",emptyMapMarkerPresence(),"permissionCounts",new JSONObject(),"truncated",truncated,"errno",errno==0?null:errno);}
    private static JSONObject emptyMapMarkerPresence(){JSONObject out=new JSONObject();for(String marker:MAP_MARKERS)Json.put(out,marker,false);return out;}
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
        s.read("storage","storage/capacity-summary","存储容量摘要","StatFs fixed paths",()->storageCapacitySummary(s.context));
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
    // 业务块：固定标签汇总容量，避免在摘要重复导出路径；storage/statvfs* 原始条目继续保留。
    private static JSONObject storageCapacitySummary(Context context){
        String[][] paths={{"root","/"},{"system","/system"},{"vendor","/vendor"},{"product","/product"},{"odm","/odm"},{"data","/data"},{"appFiles",context.getFilesDir().getPath()},{"externalFiles",Environment.getExternalStorageDirectory().getPath()}};
        JSONArray entries=new JSONArray();int errors=0;
        for(String[] entry:paths){try{StatFs stat=new StatFs(entry[1]);entries.put(Json.obj("label",entry[0],"available",true,"blockSizeBytes",stat.getBlockSizeLong(),"totalBytes",stat.getTotalBytes(),"availableBytes",stat.getAvailableBytes()));}catch(Exception e){errors++;entries.put(Json.obj("label",entry[0],"available",false,"blockSizeBytes",null,"totalBytes",null,"availableBytes",null,"error",e.getClass().getName()));}}
        return Json.obj("available",true,"status",errors==0?"ok":"partial","entryCount",entries.length(),"errorCount",errors,"entries",entries);
    }
    private void environment(ItemSink s){
        s.read("environment","environment/locales","语言与区域","Configuration / LocaleList / TimeZone",()->{Configuration c=s.context.getResources().getConfiguration();TimeZone z=TimeZone.getDefault();return Json.obj("locales",c.getLocales().toLanguageTags(),"timezoneId",z.getID(),"timezoneDisplayName",z.getDisplayName(),"rawOffset",z.getRawOffset(),"daylightSavings",z.getDSTSavings(),"configuration",c.toString(),"fontScale",c.fontScale,"densityDpi",c.densityDpi,"uiMode",c.uiMode,"keyboard",c.keyboard,"navigation",c.navigation,"touchscreen",c.touchscreen);});
        s.read("environment","environment/locale-summary","语言与时区摘要","Configuration / DateFormat / LocaleManager",()->localeSummary(s.context));
        // 业务块：固定读取平台国际化版本；每项独立记录异常，不规范化或猜测缺失版本。
        s.read("environment","environment/icu-version","ICU 运行库版本","android.icu.util.VersionInfo.ICU_VERSION",()->android.icu.util.VersionInfo.ICU_VERSION.toString());
        s.read("environment","environment/unicode-version","Unicode 数据版本","android.icu.lang.UCharacter.getUnicodeVersion",()->android.icu.lang.UCharacter.getUnicodeVersion().toString());
        s.read("environment","environment/tzdata-version","ICU 时区数据版本","android.icu.util.TimeZone.getTZDataVersion",android.icu.util.TimeZone::getTZDataVersion);
        s.read("environment","environment/cldr-version","CLDR 区域数据版本","android.icu.util.LocaleData.getCLDRVersion",()->{if(Build.VERSION.SDK_INT<28)throw new UnsupportedOperationException("requires API 28");return android.icu.util.LocaleData.getCLDRVersion().toString();});
        s.read("environment","environment/resource-locales","资源语言集合摘要","AssetManager.getLocales / Resources.getSystem",()->resourceLocaleValues(s.context.getAssets().getLocales(),android.content.res.Resources.getSystem().getAssets().getLocales()));
        s.read("environment","environment/system-fonts","系统字体集合摘要","SystemFonts.getAvailableFonts / Font metadata",BuildSystemCollector::systemFonts);
        s.read("environment","environment/ringtone-source","铃声列表首项来源","RingtoneManager.getRingtoneUri(0)",()->ringtoneSource(s.context));
        s.read("environment","environment/gsf-id-summary","Google Services Framework ID 摘要","ContentResolver.query(content://com.google.android.gsf.gservices)",()->gsfIdSummary(s.context));
        s.read("environment","environment/android-id-summary","Android ID 摘要","Settings.Secure.ANDROID_ID",()->androidIdSummary(s.context));
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
        String[] globals={"adb_enabled","development_settings_enabled","airplane_mode_on","auto_time","auto_time_zone","http_proxy","private_dns_mode","private_dns_specifier","wifi_on","bluetooth_on","data_roaming"};
        for(String k:globals)s.read("environment","environment/global/"+k,k,"Settings.Global.getString",()->Settings.Global.getString(s.context.getContentResolver(),k));
        for(String k:new String[]{"android_id","enabled_accessibility_services","accessibility_enabled","default_input_method","enabled_input_methods","mock_location","install_non_market_apps","rtt_calling_mode","touch_exploration_enabled"})s.read("environment","environment/secure/"+k,k,"Settings.Secure.getString",()->Settings.Secure.getString(s.context.getContentResolver(),k));
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
        s.read("environment","environment/webview-package-summary","WebView 提供方安装环境摘要","WebView.getCurrentWebViewPackage / PackageManager.getPackageInfo / getInstallSourceInfo",()->webViewPackageSummary(s.context));
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
        s.read("environment","environment/thermal-headroom-thresholds","热余量阈值映射","PowerManager.getThermalHeadroomThresholds",()->{
            if(Build.VERSION.SDK_INT<35)return thermalHeadroomThresholds(false,Build.VERSION.SDK_INT,null);
            PowerManager power=s.context.getSystemService(PowerManager.class);
            return thermalHeadroomThresholds(power!=null,Build.VERSION.SDK_INT,()->power.getThermalHeadroomThresholds());
        });
        s.read("environment","environment/battery-discharge-prediction","电池预计剩余时间","PowerManager.getBatteryDischargePrediction",()->{
            PowerManager power=s.context.getSystemService(PowerManager.class);
            return batteryDischargePrediction(Build.VERSION.SDK_INT,power!=null,
                    power==null?null:()->power.getBatteryDischargePrediction(),
                    power==null?null:()->power.isBatteryDischargePredictionPersonalized());
        });
        s.read("environment","environment/memory-summary","内存环境摘要","ActivityManager.MemoryInfo / Runtime",()->memorySummary(s.context));
        ActivityManager.MemoryInfo memory=new ActivityManager.MemoryInfo();((ActivityManager)s.context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(memory);
        s.ok("environment","environment/memory","内存快照","android-api","ActivityManager.getMemoryInfo",Json.obj("totalMem",memory.totalMem,"availMem",memory.availMem,"threshold",memory.threshold,"lowMemory",memory.lowMemory,"runtimeProcessors",Runtime.getRuntime().availableProcessors()));
    }
    // 业务块：只保存 Android ID 的作用域、存在性、长度和 SHA-256，不在摘要重复导出原始标识。
    private static JSONObject androidIdSummary(Context context)throws Exception{
        String value=Settings.Secure.getString(context.getContentResolver(),Settings.Secure.ANDROID_ID);
        String scope=Build.VERSION.SDK_INT>=26?"app_signing_key_user_device":"user_device";
        if(value==null||value.isEmpty())return Json.obj("available",true,"status","empty","scope",scope,"androidIdPresent",false,"androidIdLength",null,"androidIdSha256",null);
        return Json.obj("available",true,"status","ok","scope",scope,"androidIdPresent",true,"androidIdLength",value.length(),"androidIdSha256",PackageInputCollector.sha(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    // 业务块：仅观察公开字体元数据，不读取字体正文或导出完整路径；排序消除 Set 枚举顺序影响。
    private static JSONObject systemFonts()throws Exception{
        if(Build.VERSION.SDK_INT<29)throw new UnsupportedOperationException("requires API 29");
        return systemFontValues(android.graphics.fonts.SystemFonts.getAvailableFonts());
    }
    static JSONObject systemFontValues(Collection<android.graphics.fonts.Font> fonts)throws Exception{
        ArrayList<String> records=new ArrayList<>();int fileBacked=0,italic=0,variable=0;
        for(android.graphics.fonts.Font font:fonts){
            File file=font.getFile();android.graphics.fonts.FontStyle style=font.getStyle();
            android.graphics.fonts.FontVariationAxis[] axes=font.getAxes();String variations=null;
            if(axes!=null){axes=axes.clone();Arrays.sort(axes,Comparator.comparing(android.graphics.fonts.FontVariationAxis::getTag).thenComparingDouble(android.graphics.fonts.FontVariationAxis::getStyleValue));variations=android.graphics.fonts.FontVariationAxis.toFontVariationSettings(axes);}
            if(file!=null)fileBacked++;if(style.getSlant()==android.graphics.fonts.FontStyle.FONT_SLANT_ITALIC)italic++;if(axes!=null&&axes.length>0)variable++;
            records.add(new JSONArray().put(file==null?JSONObject.NULL:file.getName()).put(font.getTtcIndex()).put(style.getWeight()).put(style.getSlant()).put(font.getLocaleList().toLanguageTags()).put(variations==null?JSONObject.NULL:variations).toString());
        }
        Collections.sort(records);StringBuilder canonical=new StringBuilder();JSONArray entries=new JSONArray();
        for(String record:records){
            canonical.append(record).append('\n');if(entries.length()>=128)continue;
            JSONArray tuple=new JSONArray(record);entries.put(Json.obj("fileName",tuple.get(0),"ttcIndex",tuple.get(1),"weight",tuple.get(2),"slant",tuple.get(3),"localeTags",tuple.get(4),"variationSettings",tuple.get(5)));
        }
        return Json.obj("available",true,"status",records.size()>128?"partial":"ok","scope","public-system-font-metadata",
                "fontCount",records.size(),"fileBackedCount",fileBacked,"noFileCount",records.size()-fileBacked,"italicCount",italic,"variationConfiguredCount",variable,
                "returnedCount",entries.length(),"entryLimit",128,"entriesTruncated",records.size()>128,
                "metadataSetSha256",PackageInputCollector.sha(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),"entries",entries);
    }
    // 业务块：区分 App 已加载资源与系统资源的语言集合，不把资源语言当作用户首选语言。
    static JSONObject resourceLocaleValues(String[] app,String[] system)throws Exception{
        JSONObject out=Json.obj("available",true,"scope","current-app-assets-and-system-resources","entryLimit",256);boolean truncated=false;
        String[][] sources={app,system};String[] prefixes={"appAsset","systemAsset"};
        for(int i=0;i<sources.length;i++){
            if(sources[i]==null)throw new IllegalArgumentException("资源语言集合不可用");
            String[] sorted=sources[i].clone();Arrays.sort(sorted,Comparator.nullsFirst(Comparator.naturalOrder()));int nulls=0;for(String tag:sorted)if(tag==null)nulls++;
            JSONArray full=new JSONArray(Arrays.asList(sorted)),shown=new JSONArray(Arrays.asList(Arrays.copyOf(sorted,Math.min(256,sorted.length))));String prefix=prefixes[i];
            Json.put(out,prefix+"LocaleCount",sorted.length);Json.put(out,prefix+"ReturnedCount",shown.length());Json.put(out,prefix+"NullCount",nulls);
            Json.put(out,prefix+"LocalesTruncated",sorted.length>256);Json.put(out,prefix+"LocalesSha256",PackageInputCollector.sha(full.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));Json.put(out,prefix+"LocaleTags",shown);
            truncated|=sorted.length>256;
        }
        Json.put(out,"status",truncated?"partial":"ok");return out;
    }
    // 业务块：复现公开 FingerprintJS 的铃声来源信号，只保存 URI 文本和受控摘要，不读取音频内容。
    static JSONObject ringtoneSource(Context context)throws Exception{
        if(context==null)throw new IllegalArgumentException("Context unavailable");
        RingtoneManager manager=new RingtoneManager(context);
        Uri uri=manager.getRingtoneUri(0);
        String raw=uri==null?null:uri.toString();
        if(raw!=null&&raw.length()>2048)throw new IllegalArgumentException("ringtone URI exceeds 2048 characters");
        boolean present=raw!=null&&!raw.isEmpty();
        return Json.obj("available",true,"status",present?"ok":"empty","uri",raw,"uriPresent",present,
                "uriLength",raw==null?null:raw.length(),"uriSha256",raw==null?null:PackageInputCollector.sha(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "scheme",uri==null?null:uri.getScheme(),"authority",uri==null?null:uri.getAuthority());
    }
    // 业务块：复现公开 FingerprintJS GSF ID 查询，只输出规范化值的摘要，不把供应商标识原文写入报告。
    static JSONObject gsfIdSummary(Context context)throws Exception{
        if(context==null)throw new IllegalArgumentException("Context unavailable");
        android.content.ContentResolver resolver=context.getContentResolver();
        if(resolver==null)return Json.obj("available",false,"status","unavailable","providerPackage","com.google.android.gsf","idPresent",false,"idLength",null,"idSha256",null,"normalization","signed-decimal-to-lowercase-hex");
        android.net.Uri uri=android.net.Uri.parse("content://com.google.android.gsf.gservices");
        android.database.Cursor cursor=resolver.query(uri,null,null,new String[]{"android_id"},null);
        if(cursor==null)return Json.obj("available",false,"status","unavailable","providerPackage","com.google.android.gsf","idPresent",false,"idLength",null,"idSha256",null,"normalization","signed-decimal-to-lowercase-hex");
        try{
            if(cursor.getColumnCount()<2||!cursor.moveToFirst())return Json.obj("available",true,"status","empty","providerPackage","com.google.android.gsf","idPresent",false,"idLength",null,"idSha256",null,"normalization","signed-decimal-to-lowercase-hex");
            String decimal=cursor.getString(1);
            if(decimal==null||decimal.isEmpty())return Json.obj("available",true,"status","empty","providerPackage","com.google.android.gsf","idPresent",false,"idLength",null,"idSha256",null,"normalization","signed-decimal-to-lowercase-hex");
            if(decimal.length()>64)throw new IllegalArgumentException("GSF android_id exceeds 64 characters");
            final String normalized;
            try{normalized=Long.toHexString(Long.parseLong(decimal));}
            catch(NumberFormatException e){throw new IllegalArgumentException("GSF android_id is not a signed decimal");}
            return Json.obj("available",true,"status","ok","providerPackage","com.google.android.gsf","idPresent",true,"idLength",normalized.length(),"idSha256",PackageInputCollector.sha(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8)),"normalization","signed-decimal-to-lowercase-hex");
        }finally{cursor.close();}
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
    // 业务块：区分设备内存容量、应用堆上限和采集瞬时值；environment/memory 原始快照继续保留。
    private static JSONObject memorySummary(Context context){
        ActivityManager manager=context.getSystemService(ActivityManager.class);Runtime runtime=Runtime.getRuntime();
        JSONObject out=Json.obj("available",true,"status","ok","activityManagerAvailable",manager!=null,"totalMemBytes",null,"availMemBytes",null,"thresholdBytes",null,"lowMemory",null,"lowRamDevice",null,"memoryClassMb",null,"largeMemoryClassMb",null,"runtimeMaxMemoryBytes",runtime.maxMemory(),"runtimeTotalMemoryBytes",runtime.totalMemory(),"runtimeFreeMemoryBytes",runtime.freeMemory(),"heapUsedBytes",Math.max(0L,runtime.totalMemory()-runtime.freeMemory()),"runtimeProcessorCount",runtime.availableProcessors());
        if(manager==null){Json.put(out,"status","activity-manager-unavailable");return out;}
        try{ActivityManager.MemoryInfo info=new ActivityManager.MemoryInfo();manager.getMemoryInfo(info);Json.put(out,"totalMemBytes",info.totalMem);Json.put(out,"availMemBytes",info.availMem);Json.put(out,"thresholdBytes",info.threshold);Json.put(out,"lowMemory",info.lowMemory);Json.put(out,"lowRamDevice",manager.isLowRamDevice());Json.put(out,"memoryClassMb",manager.getMemoryClass());Json.put(out,"largeMemoryClassMb",manager.getLargeMemoryClass());}
        catch(SecurityException e){Json.put(out,"status","denied");}
        catch(RuntimeException e){Json.put(out,"status","error");Json.put(out,"error",e.getClass().getName());}
        return out;
    }
    // 业务块：只记录当前设备初始化和当前用户运行状态，不读取用户身份或位置。
    private static JSONObject deviceState(Context context){
        android.content.ContentResolver resolver=context.getContentResolver();
        ActivityManager activity=context.getSystemService(ActivityManager.class);
        UserManager users=context.getSystemService(UserManager.class);
        PackageManager packages=context.getPackageManager();
        JSONObject upgrading=deviceUpgradingState(Build.VERSION.SDK_INT,packages!=null,packages==null?null:packages::isDeviceUpgrading);
        JSONObject out=Json.obj(
                "deviceProvisioned",settingFlag(resolver,true,"device_provisioned"),
                "userSetupComplete",settingFlag(resolver,false,"user_setup_complete"),
                "bootCount",Settings.Global.getString(resolver,"boot_count"),
                "deviceUpgrading",upgrading.opt("value"),
                "deviceUpgradingQueryStatus",upgrading.optString("status"),
                "lowRamDevice",activity==null?null:activity.isLowRamDevice(),
                "demoUser",Build.VERSION.SDK_INT>=23&&users!=null?users.isDemoUser():null,
                "systemUser",Build.VERSION.SDK_INT>=23&&users!=null?users.isSystemUser():null,
                "headlessSystemUserMode",Build.VERSION.SDK_INT>=31?android.os.UserManager.isHeadlessSystemUserMode():null,
                "userUnlocked",Build.VERSION.SDK_INT<24||users==null||users.isUserUnlocked(),
                "userRunning",Build.VERSION.SDK_INT<17||users==null||users.isUserRunning(android.os.Process.myUserHandle()),
                "userMonkey",ActivityManager.isUserAMonkey());
        if(Build.VERSION.SDK_INT>=29)Json.put(out,"testHarness",ActivityManager.isRunningInUserTestHarness());
        if(Build.VERSION.SDK_INT>=28)Json.put(out,"backgroundRestricted",activity!=null&&activity.isBackgroundRestricted());
        if(upgrading.has("errorClass"))Json.put(out,"deviceUpgradingErrorClass",upgrading.opt("errorClass"));
        return out;
    }
    // 业务块：API29+只读取 PackageManager 的设备升级瞬时状态；不触发升级或修改安装状态。
    static JSONObject deviceUpgradingState(int sdk,boolean serviceAvailable,java.util.function.BooleanSupplier query){
        if(sdk<29)return Json.obj("status","unsupported","value",null);
        if(!serviceAvailable||query==null)return Json.obj("status","unavailable","value",null);
        try{return Json.obj("status","ok","value",query.getAsBoolean());}
        catch(SecurityException e){return Json.obj("status","denied","value",null,"errorClass",e.getClass().getName());}
        catch(UnsupportedOperationException|NoSuchMethodError e){return Json.obj("status","unsupported","value",null,"errorClass",e.getClass().getName());}
        catch(RuntimeException|LinkageError e){return Json.obj("status","error","value",null,"errorClass",e.getClass().getName());}
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
    // 业务块：只读一次公开热阈值快照；旧API/无服务/禁用功能/异常与有效空映射分开。
    static JSONObject thermalHeadroomThresholds(boolean available,int sdk,java.util.function.Supplier<Map<Integer,Float>> query){
        JSONObject missing=Json.obj("managerAvailable",sdk<35?null:available,"status",sdk<35?"unsupported":"unavailable","entryCount",null,"entryLimit",64,"entries",null);
        if(sdk<35||!available)return missing;
        try{return thermalHeadroomThresholdValues(Objects.requireNonNull(query,"missing thermal threshold query").get());}
        catch(Exception|LinkageError e){Json.put(missing,"status",e instanceof SecurityException?"denied":e instanceof UnsupportedOperationException||e instanceof NoSuchMethodError?"unsupported":"error");Json.put(missing,"errorClass",e.getClass().getName());return missing;}
    }
    // 业务块：最多64项且全部保留，按原始整数键排序；不钳制阈值，不补齐缺失状态或滤掉未知/非有限返回值。
    static JSONObject thermalHeadroomThresholdValues(Map<Integer,Float> thresholds){
        if(thresholds==null||thresholds.size()>64)throw new IllegalArgumentException("热阈值映射缺失或超过64项");
        TreeMap<Integer,Float> snapshot=new TreeMap<>();int count=0;
        for(Map.Entry<Integer,Float> entry:thresholds.entrySet()){
            if(++count>64||entry.getKey()==null)throw new IllegalArgumentException("热阈值键缺失或超过64项");
            snapshot.put(entry.getKey(),entry.getValue());
        }
        JSONArray entries=new JSONArray();for(Map.Entry<Integer,Float> entry:snapshot.entrySet())entries.put(Json.obj("thermalStatus",entry.getKey(),"headroom",entry.getValue()));
        return Json.obj("managerAvailable",true,"status","ok","entryCount",entries.length(),"entryLimit",64,"entries",entries);
    }
    // 业务块：保留 PowerManager 的预计剩余时间与个性化标志，null 预测不按零或未知时长伪造。
    static JSONObject batteryDischargePrediction(int sdk,boolean managerAvailable,java.util.function.Supplier<java.time.Duration> prediction,java.util.function.BooleanSupplier personalized){
        JSONObject missing=Json.obj("available",sdk>=31&&managerAvailable,"status",sdk<31?"unsupported":"unavailable","predictionPresent",null,"predictionSeconds",null,"predictionNanos",null,"predictionIso",null,"predictionStatus",sdk<31?"unsupported":"unavailable","personalized",null,"personalizedStatus",sdk<31?"unsupported":"unavailable");
        if(sdk<31||!managerAvailable)return missing;
        if(prediction==null||personalized==null)throw new IllegalArgumentException("battery prediction query unavailable");
        String predictionStatus="error",personalizedStatus="error",predictionError=null,personalizedError=null;Boolean personalizedValue=null;java.time.Duration duration=null;
        try{duration=prediction.get();predictionStatus=duration==null?"empty":"ok";}catch(SecurityException e){predictionStatus="denied";predictionError=e.getClass().getName();}catch(RuntimeException e){predictionError=e.getClass().getName();}
        try{personalizedValue=personalized.getAsBoolean();personalizedStatus="ok";}catch(SecurityException e){personalizedStatus="denied";personalizedError=e.getClass().getName();}catch(RuntimeException e){personalizedError=e.getClass().getName();}
        JSONObject out=Json.obj("available",true,"status",predictionStatus.equals("error")||predictionStatus.equals("denied")||personalizedStatus.equals("error")||personalizedStatus.equals("denied")?"partial":"ok","predictionPresent",duration!=null,"predictionSeconds",duration==null?null:duration.getSeconds(),"predictionNanos",duration==null?null:duration.getNano(),"predictionIso",duration==null?null:duration.toString(),"predictionStatus",predictionStatus,"personalized",personalizedValue,"personalizedStatus",personalizedStatus);
        if(predictionError!=null)Json.put(out,"predictionErrorClass",predictionError);if(personalizedError!=null)Json.put(out,"personalizedErrorClass",personalizedError);return out;
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
    // 业务块：只查询选中的 WebView 包，不复制安装路径或 DER；保留查询受限和更新竞态证据。
    private static JSONObject webViewPackageSummary(Context context){
        if(Build.VERSION.SDK_INT<26)return Json.obj("available",false,"status","unsupported");
        PackageInfo selected=WebView.getCurrentWebViewPackage();
        if(selected==null)return Json.obj("available",false,"status","no-provider");
        PackageManager pm=context.getPackageManager();
        long selectedVersion=Build.VERSION.SDK_INT>=28?selected.getLongVersionCode():(long)selected.versionCode;
        JSONObject out=Json.obj("available",true,"status","ok","packageName",selected.packageName,"selectedVersionName",selected.versionName,"selectedVersionCode",selectedVersion,
                "packageQueryStatus","unavailable","selectionVersionMatches",null,"versionName",null,"versionCode",null,
                "applicationInfoAvailable",false,"enabled",null,"systemApp",null,"updatedSystemApp",null,"targetSdkVersion",null,"minSdkVersion",null,
                "firstInstallTime",null,"lastUpdateTime",null,"signatureCount",null,"signatureLimit",16,"signaturesTruncated",null,"signingCertificateSha256",new JSONArray(),"installer",null,"installerQueryStatus","unavailable");
        putWebViewSigningHistory(out,null);
        try{
            PackageInfo observed=pm.getPackageInfo(selected.packageName,Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES);
            long version=Build.VERSION.SDK_INT>=28?observed.getLongVersionCode():(long)observed.versionCode;
            Json.put(out,"packageQueryStatus","allowed");Json.put(out,"selectionVersionMatches",selected.packageName.equals(observed.packageName)&&selectedVersion==version);
            Json.put(out,"versionName",observed.versionName);Json.put(out,"versionCode",version);Json.put(out,"firstInstallTime",observed.firstInstallTime);Json.put(out,"lastUpdateTime",observed.lastUpdateTime);
            ApplicationInfo app=observed.applicationInfo;
            if(app!=null){Json.put(out,"applicationInfoAvailable",true);Json.put(out,"enabled",app.enabled);Json.put(out,"systemApp",(app.flags&ApplicationInfo.FLAG_SYSTEM)!=0);Json.put(out,"updatedSystemApp",(app.flags&ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)!=0);Json.put(out,"targetSdkVersion",app.targetSdkVersion);Json.put(out,"minSdkVersion",app.minSdkVersion);}
            android.content.pm.Signature[] signatures=Build.VERSION.SDK_INT>=28&&observed.signingInfo!=null?observed.signingInfo.getApkContentsSigners():observed.signatures;
            if(signatures!=null){Json.put(out,"signingCertificateSha256",webViewSignerHashes(signatures));Json.put(out,"signatureCount",signatures.length);Json.put(out,"signaturesTruncated",signatures.length>16);}
            putWebViewSigningHistory(out,observed);
        }catch(PackageManager.NameNotFoundException e){Json.put(out,"packageQueryStatus","not-found-or-not-visible");}
        catch(SecurityException e){Json.put(out,"packageQueryStatus","denied");}
        catch(Exception e){Json.put(out,"packageQueryStatus","error");}
        try{String installer=Build.VERSION.SDK_INT>=30?pm.getInstallSourceInfo(selected.packageName).getInstallingPackageName():pm.getInstallerPackageName(selected.packageName);Json.put(out,"installer",installer);Json.put(out,"installerQueryStatus","allowed");}
        catch(PackageManager.NameNotFoundException|IllegalArgumentException e){Json.put(out,"installerQueryStatus","not-found-or-not-visible");}
        catch(SecurityException e){Json.put(out,"installerQueryStatus","denied");}
        catch(Exception e){Json.put(out,"installerQueryStatus","error");}
        if(Build.VERSION.SDK_INT>=28&&!out.optString("packageQueryStatus").equals("allowed"))Json.put(out,"signingHistoryStatus",out.optString("packageQueryStatus"));
        return out;
    }
    // 业务块：保留平台验证的签名轮换顺序；只导出证书哈希，多签名不冒充空历史。
    static void putWebViewSigningHistory(JSONObject out,PackageInfo observed){
        Json.put(out,"signingHistoryStatus",Build.VERSION.SDK_INT<28?"unsupported":"unavailable");
        Json.put(out,"hasMultipleSigners",null);Json.put(out,"hasPastSigningCertificates",null);
        Json.put(out,"signingHistoryCount",null);Json.put(out,"signingHistoryLimit",16);Json.put(out,"signingHistoryTruncated",null);
        Json.put(out,"signingHistorySha256",null);Json.put(out,"signingCertificateHistorySha256",new JSONArray());
        if(Build.VERSION.SDK_INT<28||observed==null||observed.signingInfo==null)return;
        try{
            boolean multiple=observed.signingInfo.hasMultipleSigners();
            Json.put(out,"hasMultipleSigners",multiple);Json.put(out,"hasPastSigningCertificates",observed.signingInfo.hasPastSigningCertificates());
            if(multiple){Json.put(out,"signingHistoryStatus","multiple-signers");return;}
            android.content.pm.Signature[] history=observed.signingInfo.getSigningCertificateHistory();
            if(history==null)return;
            JSONArray hashes=new JSONArray();StringBuilder canonical=new StringBuilder();
            for(int i=0;i<history.length;i++){
                if(history[i]==null)throw new IllegalArgumentException("null signing certificate");
                String hash=PackageInputCollector.sha(history[i].toByteArray());canonical.append(hash).append('\n');
                if(i<16)hashes.put(hash);
            }
            String chainHash=PackageInputCollector.sha(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Json.put(out,"signingHistoryStatus",history.length>16?"partial":"ok");Json.put(out,"signingHistoryCount",history.length);
            Json.put(out,"signingHistoryTruncated",history.length>16);Json.put(out,"signingHistorySha256",chainHash);Json.put(out,"signingCertificateHistorySha256",hashes);
        }catch(SecurityException e){Json.put(out,"signingHistoryStatus","denied");}
        catch(Exception e){Json.put(out,"signingHistoryStatus","error");}
    }
    static JSONArray webViewSignerHashes(android.content.pm.Signature[] signatures)throws Exception{
        JSONArray hashes=new JSONArray();for(int i=0;i<Math.min(16,signatures.length);i++)hashes.put(PackageInputCollector.sha(signatures[i].toByteArray()));return hashes;
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
