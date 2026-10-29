package com.life.fingerprint;

import android.content.*;
import android.content.pm.*;
import android.content.res.XmlResourceParser;
import android.hardware.display.DisplayManager;
import android.view.*;
import android.os.Build;
import android.util.DisplayMetrics;
import org.json.*;
import org.xmlpull.v1.XmlPullParser;
import java.io.File;
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
    private static final String ANDROID_NS="http://schemas.android.com/apk/res/android";
    private static final int INTENT_FILTER_LIMIT=64, INTENT_FILTER_TOTAL_LIMIT=4096, FILTER_VALUE_LIMIT=256, FILTER_TEXT_LIMIT=4096;
    private static final class FilterBuilder {
        final JSONArray actions=new JSONArray(), categories=new JSONArray(), data=new JSONArray();
        final int priority, order; final boolean autoVerify;
        FilterBuilder(int priority,int order,boolean autoVerify){this.priority=priority;this.order=order;this.autoVerify=autoVerify;}
    }
    private static String manifestText(XmlResourceParser parser,String name){String value=parser.getAttributeValue(ANDROID_NS,name);if(value!=null&&value.length()>FILTER_TEXT_LIMIT)throw new IllegalArgumentException("manifest attribute exceeds "+FILTER_TEXT_LIMIT+" characters: "+name);return value;}
    private static String manifestComponentName(String raw,String packageName){if(raw==null||raw.isEmpty())return raw;if(raw.charAt(0)=='.')return packageName==null?raw:packageName+raw;if(raw.indexOf('.')<0)return packageName==null?raw:packageName+'.'+raw;return raw;}
    private static void addFilterValue(JSONArray values,String value,String label){if(value==null)return;if(values.length()>=FILTER_VALUE_LIMIT)throw new IllegalArgumentException("intent filter "+label+" exceeds "+FILTER_VALUE_LIMIT+" values");values.put(value);}
    private static JSONObject filterData(XmlResourceParser parser){JSONObject value=new JSONObject();String[] names={"scheme","schemeSpecificPart","host","port","path","pathPrefix","pathPattern","pathAdvancedPattern","mimeType"};for(String name:names){String text=manifestText(parser,name);if(text!=null)Json.put(value,name,text);}return value;}
    private static void parseIntentFilterTag(XmlResourceParser parser,FilterBuilder filter)throws Exception{
        String tag=parser.getName();
        if("action".equals(tag)){addFilterValue(filter.actions,manifestText(parser,"name"),"actions");return;}
        if("category".equals(tag)){addFilterValue(filter.categories,manifestText(parser,"name"),"categories");return;}
        if("data".equals(tag)){JSONObject data=filterData(parser);if(data.length()>0){if(filter.data.length()>=FILTER_VALUE_LIMIT)throw new IllegalArgumentException("intent filter data exceeds "+FILTER_VALUE_LIMIT+" values");filter.data.put(data);}}
    }
    private static JSONObject filterValue(FilterBuilder filter){return Json.obj("actions",filter.actions,"categories",filter.categories,"data",filter.data,"priority",filter.priority,"order",filter.order,"autoVerify",filter.autoVerify);}
    // 业务块：API35+从 APK 二进制 Manifest 读取组件的静态 intent-filter；旧 API 不伪造运行时 Intent 数据。
    static JSONObject manifestIntentFilters(PackageManager pm,String sourceDir){
        JSONObject result=Json.obj("status",Build.VERSION.SDK_INT<35?"unsupported":"error","components",new JSONObject());
        if(Build.VERSION.SDK_INT<35)return result;
        if(sourceDir==null||sourceDir.trim().isEmpty()){Json.put(result,"status","unavailable");Json.put(result,"errorClass","missing-source-dir");return result;}
        try{
            JSONObject components=result.getJSONObject("components");
            pm.parseAndroidManifest(new File(sourceDir),parser->{
                try{
                    int event;String packageName=null,componentName=null;int componentDepth=-1;FilterBuilder filter=null;int totalFilters=0;
                    while((event=parser.next())!=XmlPullParser.END_DOCUMENT){
                        if(event==XmlPullParser.START_TAG){String tag=parser.getName();int depth=parser.getDepth();
                            if("manifest".equals(tag)&&depth==1){packageName=parser.getAttributeValue(null,"package");if(packageName!=null&&packageName.length()>FILTER_TEXT_LIMIT)throw new IllegalArgumentException("manifest package name too long");continue;}
                            if(componentName==null&&depth>=2&&("activity".equals(tag)||"activity-alias".equals(tag)||"receiver".equals(tag)||"service".equals(tag)||"provider".equals(tag))){componentName=manifestComponentName(manifestText(parser,"name"),packageName);componentDepth=depth;continue;}
                            if(componentName!=null&&"intent-filter".equals(tag)&&depth==componentDepth+1){if(filter!=null)throw new IllegalArgumentException("nested intent-filter");int priority=parser.getAttributeIntValue(ANDROID_NS,"priority",0),order=parser.getAttributeIntValue(ANDROID_NS,"order",0);boolean autoVerify=parser.getAttributeBooleanValue(ANDROID_NS,"autoVerify",false);filter=new FilterBuilder(priority,order,autoVerify);continue;}
                            if(filter!=null&&depth==componentDepth+2)parseIntentFilterTag(parser,filter);
                        }else if(event==XmlPullParser.END_TAG){int depth=parser.getDepth();String tag=parser.getName();
                            if(filter!=null&&"intent-filter".equals(tag)&&depth==componentDepth+1){JSONObject existing=components.optJSONObject(componentName);if(existing==null){existing=Json.obj("status","ok","count",0,"returnedCount",0,"filterLimit",INTENT_FILTER_LIMIT,"truncated",false,"filtersSha256",null,"filters",new JSONArray());components.put(componentName,existing);}JSONArray all=existing.getJSONArray("filters");JSONArray full=existing.optJSONArray("_all");if(full==null){full=new JSONArray();existing.put("_all",full);}if(totalFilters++>=INTENT_FILTER_TOTAL_LIMIT)throw new IllegalArgumentException("package intent filters exceed "+INTENT_FILTER_TOTAL_LIMIT);JSONObject item=filterValue(filter);full.put(item);if(all.length()<INTENT_FILTER_LIMIT)all.put(item);filter=null;}
                            if(componentName!=null&&depth==componentDepth&&("activity".equals(tag)||"activity-alias".equals(tag)||"receiver".equals(tag)||"service".equals(tag)||"provider".equals(tag))){componentName=null;componentDepth=-1;filter=null;}
                        }
                    }
                    ArrayList<String> names=new ArrayList<>();Iterator<String> keys=components.keys();while(keys.hasNext())names.add(keys.next());
                    for(String name:names){JSONObject value=components.getJSONObject(name);JSONArray full=value.getJSONArray("_all");JSONArray visible=value.getJSONArray("filters");Json.put(value,"status",full.length()>INTENT_FILTER_LIMIT?"partial":"ok");Json.put(value,"count",full.length());Json.put(value,"returnedCount",visible.length());Json.put(value,"truncated",full.length()>INTENT_FILTER_LIMIT);Json.put(value,"filtersSha256",sha(full.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));value.remove("_all");}
                    return null;
                }catch(Exception e){throw new IllegalStateException(e);}
            });
            Json.put(result,"status","ok");return result;
        }catch(Exception|LinkageError e){Json.put(result,"status",e instanceof UnsupportedOperationException?"unsupported":"error");Json.put(result,"errorClass",e.getClass().getName());Json.put(result,"components",new JSONObject());return result;}
    }
    private static JSONObject unavailableIntentFilters(JSONObject manifest){String status=manifest.optString("status","error");if("ok".equals(status)){try{return Json.obj("status","ok","count",0,"returnedCount",0,"filterLimit",INTENT_FILTER_LIMIT,"truncated",false,"filtersSha256",sha("[]".getBytes(java.nio.charset.StandardCharsets.UTF_8)),"filters",new JSONArray());}catch(Exception e){throw new IllegalStateException(e);}}JSONObject value=Json.obj("status",status,"count",null,"returnedCount",0,"filterLimit",INTENT_FILTER_LIMIT,"truncated",null,"filtersSha256",null,"filters",new JSONArray());if(manifest.has("errorClass"))Json.put(value,"errorClass",manifest.opt("errorClass"));return value;}
    private static JSONArray components(ComponentInfo[] values,JSONObject manifest){JSONArray a=new JSONArray();if(values!=null)for(ComponentInfo c:values){JSONObject o=Json.obj("name",c.name,"packageName",c.packageName,"processName",c.processName,"enabled",c.enabled,"exported",c.exported,"directBootAware",c.directBootAware);if(c instanceof ActivityInfo){ActivityInfo i=(ActivityInfo)c;Json.put(o,"permission",i.permission);Json.put(o,"flags",i.flags);Json.put(o,"componentFlags",i.flags);Json.put(o,"intentFlags",Json.obj("status","unavailable","value",null,"reason","运行时 Intent flags 不存储在 APK 清单"));Json.put(o,"launchMode",i.launchMode);Json.put(o,"taskAffinity",i.taskAffinity);}if(c instanceof ServiceInfo){Json.put(o,"permission",((ServiceInfo)c).permission);Json.put(o,"flags",((ServiceInfo)c).flags);Json.put(o,"componentFlags",((ServiceInfo)c).flags);}if(c instanceof ProviderInfo){ProviderInfo i=(ProviderInfo)c;Json.put(o,"authority",i.authority);Json.put(o,"readPermission",i.readPermission);Json.put(o,"writePermission",i.writePermission);Json.put(o,"grantUriPermissions",i.grantUriPermissions);}JSONObject filters=manifest.optJSONObject("components");JSONObject filter=filters==null?null:filters.optJSONObject(c.name);Json.put(o,"intentFilters",filter==null?unavailableIntentFilters(manifest):filter);a.put(o);}return a;}
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
            String key="packages/"+p.packageName;try{ApplicationInfo a=p.applicationInfo;JSONObject manifest=manifestIntentFilters(pm,a==null?null:a.sourceDir);JSONObject v=Json.obj("packageName",p.packageName,"versionName",p.versionName,"versionCode",Build.VERSION.SDK_INT>=28?p.getLongVersionCode():(long)p.versionCode,"firstInstallTime",p.firstInstallTime,"lastUpdateTime",p.lastUpdateTime,"sharedUserId",p.sharedUserId,"installLocation",p.installLocation,"requestedPermissions",p.requestedPermissions,"requestedPermissionsFlags",p.requestedPermissionsFlags,"activities",components(p.activities,manifest),"receivers",components(p.receivers,manifest),"services",components(p.services,manifest),"providers",components(p.providers,manifest),"signatures",signatures(p));
                if(a!=null)Json.put(v,"application",Json.obj("uid",a.uid,"flags",a.flags,"enabled",a.enabled,"targetSdkVersion",a.targetSdkVersion,"minSdkVersion",a.minSdkVersion,"sourceDir",a.sourceDir,"publicSourceDir",a.publicSourceDir,"dataDir",a.dataDir,"nativeLibraryDir",a.nativeLibraryDir,"processName",a.processName,"splitSourceDirs",a.splitSourceDirs));
                s.ok("packages",key,p.packageName,"android-api","PackageManager.getInstalledPackages("+flags+")",v);
            }catch(Exception e){s.failed("packages",key,p.packageName,"android-api","PackageManager.getInstalledPackages","error",e);}
            s.read("packages",key+"/installer",p.packageName+" 安装来源","PackageManager.getInstallSourceInfo/getInstallerPackageName",()->{if(Build.VERSION.SDK_INT>=30){InstallSourceInfo i=pm.getInstallSourceInfo(p.packageName);return Json.obj("installingPackageName",i.getInstallingPackageName(),"initiatingPackageName",i.getInitiatingPackageName(),"originatingPackageName",i.getOriginatingPackageName());}return pm.getInstallerPackageName(p.packageName);});
        }
    }
    // 业务块：只输出包环境统计，不重复包名；packages/* 原始条目继续保留供复核。
    private static JSONObject packageSummary(List<PackageInfo> list){
        JSONObject targetSdkCounts=new JSONObject();TreeSet<String> permissionNames=new TreeSet<>();int applicationInfoCount=0,enabledCount=0,debuggableCount=0,systemAppCount=0,updatedSystemAppCount=0,nativeCodeCount=0,splitPackageCount=0,sharedUserIdCount=0,versionNameCount=0,requestedPermissionCount=0,permissionPackageCount=0,grantedRequestedPermissionCount=0,activityCount=0,receiverCount=0,serviceCount=0,providerCount=0;
        for(PackageInfo info:list){if(info.versionName!=null)versionNameCount++;if(info.sharedUserId!=null)sharedUserIdCount++;String[] permissions=info.requestedPermissions;int[] permissionFlags=info.requestedPermissionsFlags;if(permissions!=null&&permissions.length>0)permissionPackageCount++;if(permissions!=null){requestedPermissionCount+=permissions.length;for(int i=0;i<permissions.length;i++){if(permissions[i]!=null)permissionNames.add(permissions[i]);if(permissionFlags!=null&&i<permissionFlags.length&&(permissionFlags[i]&PackageInfo.REQUESTED_PERMISSION_GRANTED)!=0)grantedRequestedPermissionCount++;}}if(info.activities!=null)activityCount+=info.activities.length;if(info.receivers!=null)receiverCount+=info.receivers.length;if(info.services!=null)serviceCount+=info.services.length;if(info.providers!=null)providerCount+=info.providers.length;ApplicationInfo app=info.applicationInfo;if(app==null)continue;applicationInfoCount++;if(app.enabled)enabledCount++;if((app.flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0)debuggableCount++;if((app.flags&ApplicationInfo.FLAG_SYSTEM)!=0)systemAppCount++;if((app.flags&ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)!=0)updatedSystemAppCount++;if((app.flags&ApplicationInfo.FLAG_HAS_CODE)!=0&&app.nativeLibraryDir!=null)nativeCodeCount++;if(info.splitNames!=null&&info.splitNames.length>0)splitPackageCount++;String sdk=String.valueOf(app.targetSdkVersion);Json.put(targetSdkCounts,sdk,targetSdkCounts.optInt(sdk,0)+1);}
        JSONArray names=new JSONArray();int limit=Math.min(256,permissionNames.size());int index=0;for(String name:permissionNames){if(index++>=limit)break;names.put(name);}
        return Json.obj("returnedCount",list.size(),"applicationInfoCount",applicationInfoCount,"enabledCount",enabledCount,"debuggableCount",debuggableCount,"systemAppCount",systemAppCount,"updatedSystemAppCount",updatedSystemAppCount,"nativeCodeCount",nativeCodeCount,"splitPackageCount",splitPackageCount,"sharedUserIdCount",sharedUserIdCount,"versionNameCount",versionNameCount,"requestedPermissionCount",requestedPermissionCount,"permissionPackageCount",permissionPackageCount,"grantedRequestedPermissionCount",grantedRequestedPermissionCount,"uniqueRequestedPermissionCount",permissionNames.size(),"requestedPermissionNames",names,"requestedPermissionNamesTruncated",permissionNames.size()>limit,"activityCount",activityCount,"receiverCount",receiverCount,"serviceCount",serviceCount,"providerCount",providerCount,"targetSdkCounts",targetSdkCounts);
    }
    private void input(ItemSink s){
        s.ok("input","input/summary","输入设备能力摘要","android-api","InputDevice.getDeviceIds / InputDevice",inputSummary());
        for(int id:InputDevice.getDeviceIds())s.read("input","input/device/"+id,"输入设备 "+id,"InputDevice.getDevice",()->{InputDevice d=InputDevice.getDevice(id);if(d==null)throw new IllegalStateException("设备在枚举后移除");JSONArray ranges=new JSONArray();for(InputDevice.MotionRange r:d.getMotionRanges())ranges.put(Json.obj("axis",r.getAxis(),"source",r.getSource(),"min",r.getMin(),"max",r.getMax(),"range",r.getRange(),"flat",r.getFlat(),"fuzz",r.getFuzz(),"resolution",r.getResolution()));JSONObject v=Json.obj("id",d.getId(),"name",d.getName(),"descriptor",d.getDescriptor(),"vendorId",d.getVendorId(),"productId",d.getProductId(),"sources",d.getSources(),"keyboardType",d.getKeyboardType(),"controllerNumber",d.getControllerNumber(),"virtual",d.isVirtual(),"hasMicrophone",d.hasMicrophone(),"hasVibrator",d.getVibrator().hasVibrator(),"motionRanges",ranges);if(Build.VERSION.SDK_INT>=29)Json.put(v,"external",d.isExternal());Json.put(v,"batteryState",inputBatteryState(Build.VERSION.SDK_INT,()->d.getBatteryState()));Json.put(v,"lights",inputLights(Build.VERSION.SDK_INT,()->d.getLightsManager().getLights()));return v;});
    }
    // 业务块：汇总输入硬件能力，不读取按键/触摸事件；input/device/* 原始条目继续保留。
    private static JSONObject inputSummary(){
        int[] ids=InputDevice.getDeviceIds();JSONObject keyboardTypeCounts=new JSONObject(),sourceCounts=new JSONObject();int virtualCount=0,microphoneCount=0,vibratorCount=0,motionRangeCount=0,accessErrorCount=0,batteryPresentCount=0,batteryStateErrorCount=0,lightDeviceCount=0,lightQueryErrorCount=0;Integer externalCount=Build.VERSION.SDK_INT>=29?0:null;
        for(int id:ids)try{InputDevice device=InputDevice.getDevice(id);if(device==null){accessErrorCount++;continue;}if(device.isVirtual())virtualCount++;if(Build.VERSION.SDK_INT>=29&&device.isExternal())externalCount++;if(device.hasMicrophone())microphoneCount++;if(device.getVibrator().hasVibrator())vibratorCount++;motionRangeCount+=device.getMotionRanges().size();if(Build.VERSION.SDK_INT>=31)try{android.hardware.BatteryState state=device.getBatteryState();if(state!=null&&state.isPresent())batteryPresentCount++;}catch(Exception|LinkageError ignored){batteryStateErrorCount++;}if(Build.VERSION.SDK_INT>=31)try{java.util.List<android.hardware.lights.Light> lights=device.getLightsManager().getLights();if(lights!=null&&!lights.isEmpty())lightDeviceCount++;}catch(Exception|LinkageError ignored){lightQueryErrorCount++;}String keyboard=String.valueOf(device.getKeyboardType());Json.put(keyboardTypeCounts,keyboard,keyboardTypeCounts.optInt(keyboard,0)+1);String source=String.valueOf(device.getSources());Json.put(sourceCounts,source,sourceCounts.optInt(source,0)+1);}catch(Exception ignored){accessErrorCount++;}
        return Json.obj("count",ids.length,"virtualCount",virtualCount,"externalCount",externalCount,"microphoneCount",microphoneCount,"vibratorCount",vibratorCount,"motionRangeCount",motionRangeCount,"accessErrorCount",accessErrorCount,"batteryPresentCount",Build.VERSION.SDK_INT>=31?batteryPresentCount:null,"batteryStateErrorCount",Build.VERSION.SDK_INT>=31?batteryStateErrorCount:null,"lightDeviceCount",Build.VERSION.SDK_INT>=31?lightDeviceCount:null,"lightQueryErrorCount",Build.VERSION.SDK_INT>=31?lightQueryErrorCount:null,"keyboardTypeCounts",keyboardTypeCounts,"sourceCounts",sourceCounts);
    }
    // 业务块：API31+读取输入设备公开电池状态；无电池与查询失败分开，不从容量猜测健康度。
    static JSONObject inputBatteryState(int sdk,java.util.function.Supplier<android.hardware.BatteryState> query){
        JSONObject missing=Json.obj("status",sdk<31?"unsupported":"error","present",null,"batteryStatus",null,"batteryStatusName",null,"capacity",null);
        if(sdk<31)return missing;
        Objects.requireNonNull(query,"missing battery state query");
        try{
            android.hardware.BatteryState state=Objects.requireNonNull(query.get(),"battery state is null");
            boolean present=state.isPresent();Json.put(missing,"status","ok");Json.put(missing,"present",present);
            if(!present)return missing;
            int status=state.getStatus();Json.put(missing,"batteryStatus",status);Json.put(missing,"batteryStatusName",inputBatteryStatusName(status));Json.put(missing,"capacity",Json.value(state.getCapacity()));return missing;
        }catch(SecurityException e){Json.put(missing,"status","denied");Json.put(missing,"errorClass",e.getClass().getName());return missing;
        }catch(UnsupportedOperationException|NoSuchMethodError e){Json.put(missing,"status","unsupported");Json.put(missing,"errorClass",e.getClass().getName());return missing;
        }catch(Exception|LinkageError e){Json.put(missing,"status","error");Json.put(missing,"errorClass",e.getClass().getName());return missing;}
    }
    private static String inputBatteryStatusName(int status){
        switch(status){case android.hardware.BatteryState.STATUS_UNKNOWN:return "unknown";case android.hardware.BatteryState.STATUS_CHARGING:return "charging";case android.hardware.BatteryState.STATUS_DISCHARGING:return "discharging";case android.hardware.BatteryState.STATUS_NOT_CHARGING:return "not-charging";case android.hardware.BatteryState.STATUS_FULL:return "full";default:return "unknown-value";}
    }
    // 业务块：API31+读取输入外设灯的公开能力元数据；只查询清单，不开启灯或修改状态。
    static JSONObject inputLights(int sdk,java.util.function.Supplier<java.util.List<android.hardware.lights.Light>> query){
        JSONObject missing=Json.obj("status",sdk<31?"unsupported":"error","count",null,"returnedCount",0,"lightLimit",64,"truncated",null,"lightsSha256",null,"lights",null);
        if(sdk<31)return missing;
        Objects.requireNonNull(query,"missing lights query");
        try{
            java.util.List<android.hardware.lights.Light> values=Objects.requireNonNull(query.get(),"lights list is null");if(values.size()>1024)throw new IllegalArgumentException("lights exceeds 1024 items");
            JSONArray all=new JSONArray(),entries=new JSONArray();for(android.hardware.lights.Light light:values){if(light==null)throw new IllegalArgumentException("lights contains null");String name=light.getName();if(name!=null&&name.length()>2048)throw new IllegalArgumentException("light name exceeds 2048 characters");JSONObject value=Json.obj("id",light.getId(),"name",name,"ordinal",light.getOrdinal(),"type",light.getType(),"brightnessControl",light.hasBrightnessControl(),"rgbControl",light.hasRgbControl());all.put(value);if(entries.length()<64)entries.put(value);}
            return Json.obj("status",values.size()>64?"partial":"ok","count",values.size(),"returnedCount",entries.length(),"lightLimit",64,"truncated",values.size()>64,"lightsSha256",sha(all.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),"lights",entries);
        }catch(SecurityException e){Json.put(missing,"status","denied");Json.put(missing,"errorClass",e.getClass().getName());return missing;
        }catch(UnsupportedOperationException|NoSuchMethodError e){Json.put(missing,"status","unsupported");Json.put(missing,"errorClass",e.getClass().getName());return missing;
        }catch(Exception|LinkageError e){Json.put(missing,"status","error");Json.put(missing,"errorClass",e.getClass().getName());return missing;}
    }
    private void displays(ItemSink s)throws Exception{
        DisplayManager manager=s.context.getSystemService(DisplayManager.class);Display[] displays=manager.getDisplays();Display[] presentationDisplays=manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);s.ok("display","display/summary","显示能力摘要","android-api","DisplayManager.getDisplays / Display",displaySummary(displays,presentationDisplays.length));for(Display d:displays)s.read("display","display/"+d.getDisplayId(),d.getName(),"DisplayManager.getDisplays / Display",()->{DisplayMetrics m=new DisplayMetrics();d.getRealMetrics(m);JSONArray modes=new JSONArray();for(Display.Mode mode:d.getSupportedModes())modes.put(Json.obj("modeId",mode.getModeId(),"physicalWidth",mode.getPhysicalWidth(),"physicalHeight",mode.getPhysicalHeight(),"refreshRate",mode.getRefreshRate(),"hdrTypeSupport",displayModeHdr(mode),"alternativeRefreshRateSupport",displayModeAlternativeRates(mode,Build.VERSION.SDK_INT)));JSONObject highestHdrSdrRatio=displayCapabilityQuery(Build.VERSION.SDK_INT,()->d.getHighestHdrSdrRatio()),arrSupport=displayCapabilityQuery(Build.VERSION.SDK_INT,()->d.hasArrSupport());JSONObject v=Json.obj("displayId",d.getDisplayId(),"name",d.getName(),"flags",d.getFlags(),"state",d.getState(),"rotation",d.getRotation(),"refreshRate",d.getRefreshRate(),"widthPixels",m.widthPixels,"heightPixels",m.heightPixels,"density",m.density,"densityDpi",m.densityDpi,"scaledDensity",m.scaledDensity,"xdpi",m.xdpi,"ydpi",m.ydpi,"supportedModes",modes,"presentationDeadlineNanos",d.getPresentationDeadlineNanos(),"appVsyncOffsetNanos",d.getAppVsyncOffsetNanos(),"highestHdrSdrRatio",highestHdrSdrRatio.opt("value"),"highestHdrSdrRatioStatus",highestHdrSdrRatio.optString("status"),"hasArrSupport",arrSupport.opt("value"),"hasArrSupportStatus",arrSupport.optString("status"));if(highestHdrSdrRatio.has("errorClass"))Json.put(v,"highestHdrSdrRatioErrorClass",highestHdrSdrRatio.opt("errorClass"));if(arrSupport.has("errorClass"))Json.put(v,"hasArrSupportErrorClass",arrSupport.opt("errorClass"));Display.HdrCapabilities hdr=d.getHdrCapabilities();if(hdr!=null)Json.put(v,"hdr",Json.obj("types",hdr.getSupportedHdrTypes(),"maxLuminance",hdr.getDesiredMaxLuminance(),"maxAverageLuminance",hdr.getDesiredMaxAverageLuminance(),"minLuminance",hdr.getDesiredMinLuminance()));Json.put(v,"colorCapabilities",displayColorCapabilities(d,Build.VERSION.SDK_INT));Json.put(v,"deviceProductInfo",displayProductInfo(Build.VERSION.SDK_INT,()->displayProductInfoValues(d.getDeviceProductInfo())));return v;});
    }
    // 业务块：API36+读取公开的HDR/自动刷新率能力；每项独立保留状态，不改变显示模式或刷新率。
    static JSONObject displayCapabilityQuery(int sdk,ItemSink.Read read){
        if(sdk<36)return Json.obj("status","unsupported","value",null);
        try{return Json.obj("status","ok","value",Objects.requireNonNull(read,"missing display capability query").get());}
        catch(SecurityException e){return Json.obj("status","denied","value",null,"errorClass",e.getClass().getName());}
        catch(UnsupportedOperationException|NoSuchMethodError e){return Json.obj("status","unsupported","value",null,"errorClass",e.getClass().getName());}
        catch(Exception|LinkageError e){return Json.obj("status","error","value",null,"errorClass",e.getClass().getName());}
    }
    // 业务块：读取独立的显示设备产品元数据，不影响旧HDR、模式或色彩查询。
    static JSONObject displayProductInfo(int sdk,ItemSink.Read read){
        // 业务块：API31+显示链路产品信息独立查询；成功null与不支持/读取失败分开，不从显示名称补值。
        if(sdk<31)return Json.obj("status","unsupported","value",null);
        try{return Json.obj("status","ok","value",Objects.requireNonNull(read,"missing product query").get());}
        catch(Exception|LinkageError e){return Json.obj("status",e instanceof SecurityException?"denied":"error","value",null,"errorClass",e.getClass().getName());}
    }
    // 业务块：固定7项公开元数据，字符串最多2048字符；年份/周数-1及未知连接类型按原始整数保留。
    static JSONObject displayProductInfoValues(android.hardware.display.DeviceProductInfo product){
        if(product==null)return null;
        String name=product.getName(),manufacturer=Objects.requireNonNull(product.getManufacturerPnpId(),"missing manufacturer PnP ID"),id=Objects.requireNonNull(product.getProductId(),"missing product ID");
        for(String value:new String[]{name,manufacturer,id})if(value!=null&&value.length()>2048)throw new IllegalArgumentException("display product string exceeds 2048");
        return Json.obj("name",name,"manufacturerPnpId",manufacturer,"productId",id,"modelYear",product.getModelYear(),
                "manufactureYear",product.getManufactureYear(),"manufactureWeek",product.getManufactureWeek(),"connectionToSinkType",product.getConnectionToSinkType());
    }
    // 业务块：分别读取宽色域声明和合成管线偏好，缺失/失败不影响旧HDR、模式或另一项查询。
    static JSONObject displayColorCapabilities(Display display,int sdk){
        return Json.obj("wideColorGamut",displayColorQuery(sdk,26,()->Objects.requireNonNull(display,"missing display").isWideColorGamut()),
                "preferredWideGamutColorSpace",displayColorQuery(sdk,29,()->displayColorSpaceValues(Objects.requireNonNull(display,"missing display").getPreferredWideGamutColorSpace())));
    }
    static JSONObject displayColorQuery(int sdk,int requiredSdk,ItemSink.Read read){
        if(sdk<requiredSdk)return Json.obj("status","unsupported","value",null);
        try{return Json.obj("status","ok","value",Objects.requireNonNull(read,"missing color query").get());}
        catch(Exception|LinkageError e){return Json.obj("status",e instanceof SecurityException?"denied":"error","value",null,"errorClass",e.getClass().getName());}
    }
    // 业务块：成功的null偏好保持null；仅保存公开色彩空间元数据，不读取ICC/画面或改变色彩模式。
    static JSONObject displayColorSpaceValues(android.graphics.ColorSpace color)throws Exception{
        if(color==null)return null;
        String name=Objects.requireNonNull(color.getName(),"missing color space name");if(name.isEmpty()||name.length()>2048)throw new IllegalArgumentException("color space name length outside 1..2048");
        android.graphics.ColorSpace.Model model=Objects.requireNonNull(color.getModel(),"missing color model");int count=color.getComponentCount();if(count<1||count>4)throw new IllegalArgumentException("color component count outside 1..4");
        JSONArray ranges=new JSONArray();for(int i=0;i<count;i++)ranges.put(Json.obj("index",i,"min",color.getMinValue(i),"max",color.getMaxValue(i)));
        return Json.obj("name",name,"id",color.getId(),"model",model.name(),"componentCount",count,"wideGamut",color.isWideGamut(),"srgb",color.isSrgb(),"componentRanges",ranges);
    }
    // 业务块：API31+只读可无缝切换的替代刷新率；查询失败独立于当前模式与HDR，不触发模式切换。
    static JSONObject displayModeAlternativeRates(Display.Mode mode,int sdk){
        JSONObject missing=Json.obj("status",sdk<31?"unsupported":"error","rateCount",null,"returnedCount",0,
                "rateLimit",64,"ratesTruncated",null,"ratesSha256",null,"ratesHz",null);
        if(sdk<31)return missing;
        Objects.requireNonNull(mode,"missing display mode");
        try{return displayModeAlternativeRateValues(mode.getAlternativeRefreshRates());}
        catch(Exception|LinkageError e){Json.put(missing,"status",e instanceof SecurityException?"denied":"error");Json.put(missing,"errorClass",e.getClass().getName());return missing;}
    }
    // 业务块：最多4096输入/64项明细；快照保留顺序、重复及非有限类型，完整JSON哈希覆盖截断外变化。
    static JSONObject displayModeAlternativeRateValues(float[] rates)throws Exception{
        if(rates==null||rates.length>4096)throw new IllegalArgumentException("替代刷新率缺失或超过4096项");
        float[] snapshot=rates.clone();JSONArray all=new JSONArray(),entries=new JSONArray();
        for(float rate:snapshot){Object value=Json.value(rate);all.put(value);if(entries.length()<64)entries.put(value);}
        return Json.obj("status",snapshot.length>64?"partial":"ok","rateCount",snapshot.length,"returnedCount",entries.length(),"rateLimit",64,
                "ratesTruncated",snapshot.length>64,"ratesSha256",sha(all.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),"ratesHz",entries);
    }
    // 业务块：逐显示模式读取HDR类型，保留原序/重复/未知数值；失败不影响原模式和屏幕整体HDR数据。
    static JSONObject displayModeHdr(Display.Mode mode){
        Objects.requireNonNull(mode,"missing display mode");
        JSONObject missing=Json.obj("status",Build.VERSION.SDK_INT<34?"unsupported":"error","typeCount",null,"returnedCount",0,
                "typeLimit",64,"typesTruncated",null,"typeListSha256",null,"types",null);
        if(Build.VERSION.SDK_INT<34)return missing;
        try{return displayModeHdrValues(mode.getSupportedHdrTypes());}
        catch(Exception|LinkageError e){Json.put(missing,"status",e instanceof SecurityException?"denied":"error");Json.put(missing,"errorClass",e.getClass().getName());return missing;}
    }
    static JSONObject displayModeHdrValues(int[] types)throws Exception{
        if(types==null||types.length>20000)throw new IllegalArgumentException("HDR类型缺失或超过20000项");
        JSONArray all=new JSONArray(),entries=new JSONArray();for(int type:types){all.put(type);if(entries.length()<64)entries.put(type);}
        return Json.obj("status",types.length>64?"partial":"ok","typeCount",types.length,"returnedCount",entries.length(),"typeLimit",64,
                "typesTruncated",types.length>64,"typeListSha256",sha(all.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),"types",entries);
    }
    // 业务块：汇总屏幕能力，display/* 原始条目继续保留；数组上限避免异常设备放大报告。
    private static JSONObject displaySummary(Display[] displays,int presentationCategoryCount)throws JSONException{
        int validCount=0,secureCount=0,presentationCount=0,hdrCount=0;JSONArray widths=new JSONArray(),heights=new JSONArray(),densityDpi=new JSONArray(),refreshRates=new JSONArray();
        for(Display d:displays){if(d.isValid())validCount++;int flags=d.getFlags();if((flags&Display.FLAG_SECURE)!=0)secureCount++;if((flags&Display.FLAG_PRESENTATION)!=0)presentationCount++;Display.HdrCapabilities hdr=d.getHdrCapabilities();if(hdr!=null&&hdr.getSupportedHdrTypes().length>0)hdrCount++;DisplayMetrics m=new DisplayMetrics();d.getRealMetrics(m);if(widths.length()<64)widths.put(m.widthPixels);if(heights.length()<64)heights.put(m.heightPixels);if(densityDpi.length()<64)densityDpi.put(m.densityDpi);if(refreshRates.length()<64)refreshRates.put(d.getRefreshRate());}
        return Json.obj("count",displays.length,"validCount",validCount,"secureCount",secureCount,"presentationCount",presentationCount,"presentationCategoryCount",presentationCategoryCount,"hdrCount",hdrCount,"widths",widths,"heights",heights,"densityDpi",densityDpi,"refreshRates",refreshRates);
    }
}
