package com.life.fingerprint;

import android.os.*;
import android.provider.Settings;
import android.content.*;
import android.content.res.Configuration;
import android.app.ActivityManager;
import android.webkit.WebView;
import android.webkit.WebSettings;
import org.json.*;
import java.lang.reflect.*;
import java.util.*;
import java.io.File;

public final class BuildSystemCollector implements Collector {
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
        Commands.collect(s,"build","build/getprop","/system/bin/getprop");
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
        File[] cpus=new File("/sys/devices/system/cpu").listFiles();if(cpus!=null)for(File cpu:cpus)if(cpu.getName().matches("cpu[0-9]{1,4}"))for(String suffix:new String[]{"cpufreq/cpuinfo_max_freq","cpufreq/cpuinfo_min_freq","cpufreq/scaling_cur_freq","cpufreq/scaling_governor","topology/core_id","topology/physical_package_id"})s.file("kernel","kernel/cpu/"+cpu.getName()+"/"+suffix,new File(cpu,suffix).getPath());
    }
    private void storage(ItemSink s){
        for(String path:new String[]{"/","/system","/vendor","/product","/odm","/data",s.context.getFilesDir().getPath(),Environment.getExternalStorageDirectory().getPath()})s.read("storage","storage/statvfs"+path,path,"StatFs("+path+")",()->{StatFs f=new StatFs(path);return Json.obj("path",path,"blockSize",f.getBlockSizeLong(),"blocks",f.getBlockCountLong(),"availableBlocks",f.getAvailableBlocksLong(),"freeBlocks",f.getFreeBlocksLong(),"totalBytes",f.getTotalBytes(),"availableBytes",f.getAvailableBytes());});
        s.read("storage","storage/volumes","存储卷","StorageManager.getStorageVolumes",()->{JSONArray a=new JSONArray();android.os.storage.StorageManager m=s.context.getSystemService(android.os.storage.StorageManager.class);for(android.os.storage.StorageVolume v:m.getStorageVolumes()){JSONObject o=Json.obj("description",v.getDescription(s.context),"uuid",v.getUuid(),"state",v.getState(),"primary",v.isPrimary(),"removable",v.isRemovable(),"emulated",v.isEmulated());if(Build.VERSION.SDK_INT>=30)Json.put(o,"directory",v.getDirectory()==null?null:v.getDirectory().getPath());a.put(o);}return a;});
        s.file("storage","storage/partitions","/proc/partitions");s.file("storage","storage/diskstats","/proc/diskstats");
    }
    private void environment(ItemSink s){
        s.read("environment","environment/locales","语言与区域","Configuration / LocaleList / TimeZone",()->{Configuration c=s.context.getResources().getConfiguration();TimeZone z=TimeZone.getDefault();return Json.obj("locales",c.getLocales().toLanguageTags(),"timezoneId",z.getID(),"timezoneDisplayName",z.getDisplayName(),"rawOffset",z.getRawOffset(),"daylightSavings",z.getDSTSavings(),"configuration",c.toString(),"fontScale",c.fontScale,"densityDpi",c.densityDpi,"uiMode",c.uiMode,"keyboard",c.keyboard,"navigation",c.navigation,"touchscreen",c.touchscreen);});
        s.ok("environment","environment/clocks","单次时钟快照","android-api","SystemClock",Json.obj("elapsedRealtime",SystemClock.elapsedRealtime(),"uptimeMillis",SystemClock.uptimeMillis(),"wallClockMillis",System.currentTimeMillis()));
        s.read("environment","environment/risk-settings","系统风控相关设置摘要","Settings.Global / Settings.Secure",()->riskSettings(s.context));
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
    private static Boolean settingFlag(android.content.ContentResolver resolver,boolean global,String key){
        String value=global?Settings.Global.getString(resolver,key):Settings.Secure.getString(resolver,key);
        if(value==null)return null;
        if("1".equals(value)||"true".equalsIgnoreCase(value)||"on".equalsIgnoreCase(value))return true;
        if("0".equals(value)||"false".equalsIgnoreCase(value)||"off".equalsIgnoreCase(value))return false;
        return null;
    }
}
