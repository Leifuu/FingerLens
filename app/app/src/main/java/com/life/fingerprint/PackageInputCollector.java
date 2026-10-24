package com.life.fingerprint;

import android.content.*;
import android.content.pm.*;
import android.hardware.display.DisplayManager;
import android.view.*;
import android.os.Build;
import android.util.DisplayMetrics;
import org.json.*;
import java.security.MessageDigest;
import java.util.*;

public final class PackageInputCollector implements Collector {
    private final String module;
    public PackageInputCollector(String module){this.module=module;}
    public static String sha(byte[] bytes)throws Exception{byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder b=new StringBuilder();for(byte v:digest)b.append(String.format(java.util.Locale.ROOT,"%02x",v&255));return b.toString();}
    static JSONArray signatures(PackageInfo p)throws Exception {
        JSONArray a=new JSONArray();android.content.pm.Signature[] signatures=Build.VERSION.SDK_INT>=28&&p.signingInfo!=null?p.signingInfo.getApkContentsSigners():p.signatures;
        if(signatures!=null)for(android.content.pm.Signature sig:signatures)a.put(Json.obj("sha256",sha(sig.toByteArray()),"derBase64",android.util.Base64.encodeToString(sig.toByteArray(),android.util.Base64.NO_WRAP)));return a;
    }
    private static JSONArray components(ComponentInfo[] values){JSONArray a=new JSONArray();if(values!=null)for(ComponentInfo c:values){JSONObject o=Json.obj("name",c.name,"packageName",c.packageName,"processName",c.processName,"enabled",c.enabled,"exported",c.exported,"directBootAware",c.directBootAware);if(c instanceof ActivityInfo){ActivityInfo i=(ActivityInfo)c;Json.put(o,"permission",i.permission);Json.put(o,"flags",i.flags);Json.put(o,"launchMode",i.launchMode);Json.put(o,"taskAffinity",i.taskAffinity);}if(c instanceof ServiceInfo){Json.put(o,"permission",((ServiceInfo)c).permission);Json.put(o,"flags",((ServiceInfo)c).flags);}if(c instanceof ProviderInfo){ProviderInfo i=(ProviderInfo)c;Json.put(o,"authority",i.authority);Json.put(o,"readPermission",i.readPermission);Json.put(o,"writePermission",i.writePermission);Json.put(o,"grantUriPermissions",i.grantUriPermissions);}a.put(o);}return a;}
    public void collect(ItemSink s)throws Exception {
        if(module.equals("packages")){packages(s);return;}
        if(module.equals("input")){input(s);return;}
        displays(s);
    }
    private void packages(ItemSink s)throws Exception {
        PackageManager pm=s.context.getPackageManager();int flags=PackageManager.GET_ACTIVITIES|PackageManager.GET_RECEIVERS|PackageManager.GET_SERVICES|PackageManager.GET_PROVIDERS|PackageManager.GET_PERMISSIONS|PackageManager.GET_CONFIGURATIONS|PackageManager.GET_SIGNING_CERTIFICATES;
        if(Build.VERSION.SDK_INT<28)flags=(flags&~PackageManager.GET_SIGNING_CERTIFICATES)|PackageManager.GET_SIGNATURES;
        java.util.List<PackageInfo> list=pm.getInstalledPackages(flags);s.ok("packages","packages/enumeration","应用枚举范围","android-api","PackageManager.getInstalledPackages",Json.obj("flags",flags,"returnedCount",list.size(),"visibility","当前用户与当前 App 实际可见包，不承诺系统全量"));s.ok("packages","packages/summary","应用清单摘要","android-api","PackageManager.getInstalledPackages",packageSummary(list));
        int count=0;for(PackageInfo p:list){if(s.isFull())break;if(count++>=20000){s.add("packages","packages/limit","包数量上限","collector","getInstalledPackages","truncated",null,null,Json.obj("limit",20000));break;}
            String key="packages/"+p.packageName;try{ApplicationInfo a=p.applicationInfo;JSONObject v=Json.obj("packageName",p.packageName,"versionName",p.versionName,"versionCode",Build.VERSION.SDK_INT>=28?p.getLongVersionCode():(long)p.versionCode,"firstInstallTime",p.firstInstallTime,"lastUpdateTime",p.lastUpdateTime,"sharedUserId",p.sharedUserId,"installLocation",p.installLocation,"requestedPermissions",p.requestedPermissions,"requestedPermissionsFlags",p.requestedPermissionsFlags,"activities",components(p.activities),"receivers",components(p.receivers),"services",components(p.services),"providers",components(p.providers),"signatures",signatures(p));
                if(a!=null)Json.put(v,"application",Json.obj("uid",a.uid,"flags",a.flags,"enabled",a.enabled,"targetSdkVersion",a.targetSdkVersion,"minSdkVersion",a.minSdkVersion,"sourceDir",a.sourceDir,"publicSourceDir",a.publicSourceDir,"dataDir",a.dataDir,"nativeLibraryDir",a.nativeLibraryDir,"processName",a.processName,"splitSourceDirs",a.splitSourceDirs));
                s.ok("packages",key,p.packageName,"android-api","PackageManager.getInstalledPackages("+flags+")",v);
            }catch(Exception e){s.failed("packages",key,p.packageName,"android-api","PackageManager.getInstalledPackages","error",e);}
            s.read("packages",key+"/installer",p.packageName+" 安装来源","PackageManager.getInstallSourceInfo/getInstallerPackageName",()->{if(Build.VERSION.SDK_INT>=30){InstallSourceInfo i=pm.getInstallSourceInfo(p.packageName);return Json.obj("installingPackageName",i.getInstallingPackageName(),"initiatingPackageName",i.getInitiatingPackageName(),"originatingPackageName",i.getOriginatingPackageName());}return pm.getInstallerPackageName(p.packageName);});
        }
    }
    // 业务块：只输出包环境统计，不重复包名；packages/* 原始条目继续保留供复核。
    private static JSONObject packageSummary(List<PackageInfo> list){
        JSONObject targetSdkCounts=new JSONObject();int applicationInfoCount=0,enabledCount=0,debuggableCount=0,systemAppCount=0,updatedSystemAppCount=0,nativeCodeCount=0,splitPackageCount=0,sharedUserIdCount=0,versionNameCount=0,requestedPermissionCount=0,activityCount=0,receiverCount=0,serviceCount=0,providerCount=0;
        for(PackageInfo info:list){if(info.versionName!=null)versionNameCount++;if(info.sharedUserId!=null)sharedUserIdCount++;if(info.requestedPermissions!=null)requestedPermissionCount+=info.requestedPermissions.length;if(info.activities!=null)activityCount+=info.activities.length;if(info.receivers!=null)receiverCount+=info.receivers.length;if(info.services!=null)serviceCount+=info.services.length;if(info.providers!=null)providerCount+=info.providers.length;ApplicationInfo app=info.applicationInfo;if(app==null)continue;applicationInfoCount++;if(app.enabled)enabledCount++;if((app.flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0)debuggableCount++;if((app.flags&ApplicationInfo.FLAG_SYSTEM)!=0)systemAppCount++;if((app.flags&ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)!=0)updatedSystemAppCount++;if((app.flags&ApplicationInfo.FLAG_HAS_CODE)!=0&&app.nativeLibraryDir!=null)nativeCodeCount++;if(info.splitNames!=null&&info.splitNames.length>0)splitPackageCount++;String sdk=String.valueOf(app.targetSdkVersion);Json.put(targetSdkCounts,sdk,targetSdkCounts.optInt(sdk,0)+1);}
        return Json.obj("returnedCount",list.size(),"applicationInfoCount",applicationInfoCount,"enabledCount",enabledCount,"debuggableCount",debuggableCount,"systemAppCount",systemAppCount,"updatedSystemAppCount",updatedSystemAppCount,"nativeCodeCount",nativeCodeCount,"splitPackageCount",splitPackageCount,"sharedUserIdCount",sharedUserIdCount,"versionNameCount",versionNameCount,"requestedPermissionCount",requestedPermissionCount,"activityCount",activityCount,"receiverCount",receiverCount,"serviceCount",serviceCount,"providerCount",providerCount,"targetSdkCounts",targetSdkCounts);
    }
    private void input(ItemSink s){
        s.ok("input","input/summary","输入设备能力摘要","android-api","InputDevice.getDeviceIds / InputDevice",inputSummary());
        for(int id:InputDevice.getDeviceIds())s.read("input","input/device/"+id,"输入设备 "+id,"InputDevice.getDevice",()->{InputDevice d=InputDevice.getDevice(id);if(d==null)throw new IllegalStateException("设备在枚举后移除");JSONArray ranges=new JSONArray();for(InputDevice.MotionRange r:d.getMotionRanges())ranges.put(Json.obj("axis",r.getAxis(),"source",r.getSource(),"min",r.getMin(),"max",r.getMax(),"range",r.getRange(),"flat",r.getFlat(),"fuzz",r.getFuzz(),"resolution",r.getResolution()));JSONObject v=Json.obj("id",d.getId(),"name",d.getName(),"descriptor",d.getDescriptor(),"vendorId",d.getVendorId(),"productId",d.getProductId(),"sources",d.getSources(),"keyboardType",d.getKeyboardType(),"controllerNumber",d.getControllerNumber(),"virtual",d.isVirtual(),"hasMicrophone",d.hasMicrophone(),"hasVibrator",d.getVibrator().hasVibrator(),"motionRanges",ranges);if(Build.VERSION.SDK_INT>=29)Json.put(v,"external",d.isExternal());return v;});
    }
    // 业务块：汇总输入硬件能力，不读取按键/触摸事件；input/device/* 原始条目继续保留。
    private static JSONObject inputSummary(){
        int[] ids=InputDevice.getDeviceIds();JSONObject keyboardTypeCounts=new JSONObject(),sourceCounts=new JSONObject();int virtualCount=0,microphoneCount=0,vibratorCount=0,motionRangeCount=0,accessErrorCount=0;Integer externalCount=Build.VERSION.SDK_INT>=29?0:null;
        for(int id:ids)try{InputDevice device=InputDevice.getDevice(id);if(device==null){accessErrorCount++;continue;}if(device.isVirtual())virtualCount++;if(Build.VERSION.SDK_INT>=29&&device.isExternal())externalCount++;if(device.hasMicrophone())microphoneCount++;if(device.getVibrator().hasVibrator())vibratorCount++;motionRangeCount+=device.getMotionRanges().size();String keyboard=String.valueOf(device.getKeyboardType());Json.put(keyboardTypeCounts,keyboard,keyboardTypeCounts.optInt(keyboard,0)+1);String source=String.valueOf(device.getSources());Json.put(sourceCounts,source,sourceCounts.optInt(source,0)+1);}catch(Exception ignored){accessErrorCount++;}
        return Json.obj("count",ids.length,"virtualCount",virtualCount,"externalCount",externalCount,"microphoneCount",microphoneCount,"vibratorCount",vibratorCount,"motionRangeCount",motionRangeCount,"accessErrorCount",accessErrorCount,"keyboardTypeCounts",keyboardTypeCounts,"sourceCounts",sourceCounts);
    }
    private void displays(ItemSink s)throws Exception{
        DisplayManager manager=s.context.getSystemService(DisplayManager.class);Display[] displays=manager.getDisplays();Display[] presentationDisplays=manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);s.ok("display","display/summary","显示能力摘要","android-api","DisplayManager.getDisplays / Display",displaySummary(displays,presentationDisplays.length));for(Display d:displays)s.read("display","display/"+d.getDisplayId(),d.getName(),"DisplayManager.getDisplays / Display",()->{DisplayMetrics m=new DisplayMetrics();d.getRealMetrics(m);JSONArray modes=new JSONArray();for(Display.Mode mode:d.getSupportedModes())modes.put(Json.obj("modeId",mode.getModeId(),"physicalWidth",mode.getPhysicalWidth(),"physicalHeight",mode.getPhysicalHeight(),"refreshRate",mode.getRefreshRate()));JSONObject v=Json.obj("displayId",d.getDisplayId(),"name",d.getName(),"flags",d.getFlags(),"state",d.getState(),"rotation",d.getRotation(),"refreshRate",d.getRefreshRate(),"widthPixels",m.widthPixels,"heightPixels",m.heightPixels,"density",m.density,"densityDpi",m.densityDpi,"scaledDensity",m.scaledDensity,"xdpi",m.xdpi,"ydpi",m.ydpi,"supportedModes",modes,"presentationDeadlineNanos",d.getPresentationDeadlineNanos(),"appVsyncOffsetNanos",d.getAppVsyncOffsetNanos());Display.HdrCapabilities hdr=d.getHdrCapabilities();if(hdr!=null)Json.put(v,"hdr",Json.obj("types",hdr.getSupportedHdrTypes(),"maxLuminance",hdr.getDesiredMaxLuminance(),"maxAverageLuminance",hdr.getDesiredMaxAverageLuminance(),"minLuminance",hdr.getDesiredMinLuminance()));return v;});
    }
    // 业务块：汇总屏幕能力，display/* 原始条目继续保留；数组上限避免异常设备放大报告。
    private static JSONObject displaySummary(Display[] displays,int presentationCategoryCount)throws JSONException{
        int validCount=0,secureCount=0,presentationCount=0,hdrCount=0;JSONArray widths=new JSONArray(),heights=new JSONArray(),densityDpi=new JSONArray(),refreshRates=new JSONArray();
        for(Display d:displays){if(d.isValid())validCount++;int flags=d.getFlags();if((flags&Display.FLAG_SECURE)!=0)secureCount++;if((flags&Display.FLAG_PRESENTATION)!=0)presentationCount++;Display.HdrCapabilities hdr=d.getHdrCapabilities();if(hdr!=null&&hdr.getSupportedHdrTypes().length>0)hdrCount++;DisplayMetrics m=new DisplayMetrics();d.getRealMetrics(m);if(widths.length()<64)widths.put(m.widthPixels);if(heights.length()<64)heights.put(m.heightPixels);if(densityDpi.length()<64)densityDpi.put(m.densityDpi);if(refreshRates.length()<64)refreshRates.put(d.getRefreshRate());}
        return Json.obj("count",displays.length,"validCount",validCount,"secureCount",secureCount,"presentationCount",presentationCount,"presentationCategoryCount",presentationCategoryCount,"hdrCount",hdrCount,"widths",widths,"heights",heights,"densityDpi",densityDpi,"refreshRates",refreshRates);
    }
}
