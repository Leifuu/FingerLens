package com.life.fingerprint;

import android.content.*;
import android.content.pm.FeatureInfo;
import android.hardware.*;
import android.hardware.camera2.*;
import android.hardware.usb.*;
import android.media.*;
import android.nfc.*;
import android.os.*;
import android.util.*;
import org.json.*;
import java.util.*;

public final class HardwareCollector implements Collector {
    private final String module;
    public HardwareCollector(String module){this.module=module;}
    public void collect(ItemSink s)throws Exception {
        if(module.equals("battery")){battery(s);return;}
        if(module.equals("sensors")){sensors(s);return;}
        s.ok("hardware","hardware/graphics","图形能力查询","jni","EGL / OpenGL ES / vkEnumerateInstanceExtensionProperties",new JSONObject(NativeProbe.graphics()));
        s.read("hardware","hardware/features","系统声明特性","PackageManager.getSystemAvailableFeatures",()->{JSONArray a=new JSONArray();for(FeatureInfo f:s.context.getPackageManager().getSystemAvailableFeatures())a.put(Json.obj("name",f.name,"version",f.version,"flags",f.flags,"reqGlEsVersion",f.reqGlEsVersion));return a;});
        s.read("hardware","hardware/features-summary","系统特性能力摘要","PackageManager.getSystemAvailableFeatures",()->featureSummary(s.context));
        s.read("hardware","hardware/codecs-summary","媒体编解码能力摘要","MediaCodecList.ALL_CODECS",HardwareCollector::codecSummary);
        audio(s);
        usb(s);
        nfc(s);
        drm(s);
        CameraManager cameras=s.context.getSystemService(CameraManager.class);
        if(cameras==null)s.ok("hardware","hardware/camera-summary","摄像头能力摘要","CameraManager","CameraManager unavailable",Json.obj("available",false,"count",0,"cameraIds",new JSONArray()));
        else {String[] cameraIds=cameras.getCameraIdList();for(String id:cameraIds)s.read("hardware","hardware/camera/"+id,"摄像头 "+id,"CameraManager.getCameraCharacteristics",()->{CameraCharacteristics c=cameras.getCameraCharacteristics(id);JSONObject o=Json.obj("id",id);for(CameraCharacteristics.Key<?> k:c.getKeys()){try{Json.put(o,k.getName(),cameraValue(c.get(k)));}catch(Exception e){Json.put(o,k.getName(),Json.obj("error",e.getClass().getName(),"message",e.getMessage()));}}return o;});s.read("hardware","hardware/camera-summary","摄像头能力摘要","CameraManager.getCameraCharacteristics",()->cameraSummary(cameras,cameraIds));}
        s.read("hardware","hardware/codecs","媒体编解码器","MediaCodecList.ALL_CODECS",()->{JSONArray a=new JSONArray();for(MediaCodecInfo info:new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()){JSONObject o=Json.obj("name",info.getName(),"encoder",info.isEncoder(),"supportedTypes",info.getSupportedTypes());if(Build.VERSION.SDK_INT>=29){Json.put(o,"hardwareAccelerated",info.isHardwareAccelerated());Json.put(o,"softwareOnly",info.isSoftwareOnly());Json.put(o,"vendor",info.isVendor());Json.put(o,"canonicalName",info.getCanonicalName());}JSONArray types=new JSONArray();for(String type:info.getSupportedTypes())try{MediaCodecInfo.CodecCapabilities cap=info.getCapabilitiesForType(type);JSONArray profiles=new JSONArray();for(MediaCodecInfo.CodecProfileLevel p:cap.profileLevels)profiles.put(Json.obj("profile",p.profile,"level",p.level));types.put(Json.obj("type",type,"colorFormats",cap.colorFormats,"maxSupportedInstances",cap.getMaxSupportedInstances(),"profileLevels",profiles));}catch(Exception e){types.put(Json.obj("type",type,"error",e.getMessage()));}Json.put(o,"capabilities",types);a.put(o);}return a;});
    }
    // 业务块：从完整系统特性中提取可比较摘要；原始 hardware/features 继续保留。
    private static JSONObject featureSummary(Context context){
        FeatureInfo[] features=context.getPackageManager().getSystemAvailableFeatures();TreeSet<String> names=new TreeSet<>(String.CASE_INSENSITIVE_ORDER);TreeSet<Integer> glEsVersions=new TreeSet<>();int namedCount=0,versionedCount=0,maxVersion=0;
        for(FeatureInfo feature:features){if(feature.name!=null){namedCount++;names.add(feature.name);}if(feature.version>0){versionedCount++;maxVersion=Math.max(maxVersion,feature.version);}if(feature.reqGlEsVersion>0)glEsVersions.add(feature.reqGlEsVersion);}
        JSONArray featureNames=new JSONArray();for(String name:names)if(featureNames.length()<256)featureNames.put(name);JSONArray glEs=new JSONArray();for(Integer version:glEsVersions)glEs.put(version);
        return Json.obj("count",features.length,"namedCount",namedCount,"versionedCount",versionedCount,"maxVersion",maxVersion,"featureNames",featureNames,"featureNamesTruncated",names.size()>256,"glEsVersions",glEs);
    }
    // 业务块：把编解码器集合压缩成可比较摘要；原始 hardware/codecs 继续保留。
    private static JSONObject codecSummary(){
        MediaCodecInfo[] codecs=new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();TreeSet<String> names=new TreeSet<>(String.CASE_INSENSITIVE_ORDER);TreeSet<String> types=new TreeSet<>(String.CASE_INSENSITIVE_ORDER);int encoderCount=0,decoderCount=0;Integer hardwareAcceleratedCount=Build.VERSION.SDK_INT>=29?0:null,softwareOnlyCount=Build.VERSION.SDK_INT>=29?0:null,vendorCount=Build.VERSION.SDK_INT>=29?0:null;
        for(MediaCodecInfo codec:codecs){names.add(codec.getName());String[] supported=codec.getSupportedTypes();if(supported!=null)for(String type:supported)if(type!=null)types.add(type);if(codec.isEncoder())encoderCount++;else decoderCount++;if(Build.VERSION.SDK_INT>=29){if(codec.isHardwareAccelerated())hardwareAcceleratedCount++;if(codec.isSoftwareOnly())softwareOnlyCount++;if(codec.isVendor())vendorCount++;}}
        JSONArray codecNames=new JSONArray();for(String name:names)if(codecNames.length()<256)codecNames.put(name);JSONArray mimeTypes=new JSONArray();for(String type:types)if(mimeTypes.length()<256)mimeTypes.put(type);
        return Json.obj("count",codecs.length,"encoderCount",encoderCount,"decoderCount",decoderCount,"hardwareAcceleratedCount",hardwareAcceleratedCount,"softwareOnlyCount",softwareOnlyCount,"vendorCount",vendorCount,"codecNames",codecNames,"codecNamesTruncated",names.size()>256,"mimeTypes",mimeTypes,"mimeTypesTruncated",types.size()>256);
    }
    // 业务块：只提取摄像头能力元数据，异常按设备计数并保留原始 hardware/camera/* 条目。
    private static JSONObject cameraSummary(CameraManager cameras,String[] ids)throws Exception{
        JSONObject lensFacingCounts=new JSONObject(),hardwareLevelCounts=new JSONObject(),orientationCounts=new JSONObject();JSONArray cameraIds=new JSONArray();int flashAvailableCount=0,logicalMultiCameraCount=0,physicalCameraIdCount=0,errorCount=0;
        String[] sorted=ids.clone();Arrays.sort(sorted,String.CASE_INSENSITIVE_ORDER);for(String id:sorted)if(cameraIds.length()<64)cameraIds.put(id);
        for(String id:ids)try{CameraCharacteristics c=cameras.getCameraCharacteristics(id);Integer facing=c.get(CameraCharacteristics.LENS_FACING),level=c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL),orientation=c.get(CameraCharacteristics.SENSOR_ORIENTATION);count(lensFacingCounts,facing==null?"unknown":String.valueOf(facing));count(hardwareLevelCounts,level==null?"unknown":String.valueOf(level));count(orientationCounts,orientation==null?"unknown":String.valueOf(orientation));if(Boolean.TRUE.equals(c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)))flashAvailableCount++;if(Build.VERSION.SDK_INT>=28){int[] capabilities=c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);if(capabilities!=null)for(int capability:capabilities)if(capability==CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA){logicalMultiCameraCount++;break;}Set<String> physical=c.getPhysicalCameraIds();if(physical!=null)physicalCameraIdCount+=physical.size();}}catch(Exception ignored){errorCount++;}
        return Json.obj("available",true,"count",ids.length,"cameraIds",cameraIds,"cameraIdsTruncated",ids.length>64,"flashAvailableCount",flashAvailableCount,"logicalMultiCameraCount",logicalMultiCameraCount,"physicalCameraIdCount",physicalCameraIdCount,"cameraAccessErrorCount",errorCount,"lensFacingCounts",lensFacingCounts,"hardwareLevelCounts",hardwareLevelCounts,"sensorOrientationCounts",orientationCounts);
    }
    private static void count(JSONObject counts,String key){Json.put(counts,key,counts.optInt(key,0)+1);}
    // 业务块：只读取音频设备能力元数据，不获取音频样本；原始清单与摘要并存。
    private void audio(ItemSink s){
        AudioManager manager=s.context.getSystemService(AudioManager.class);
        if(manager==null){s.ok("hardware","hardware/audio-summary","音频能力摘要","AudioManager","AudioManager unavailable",Json.obj("available",false,"count",0,"productNames",new JSONArray()));return;}
        s.read("hardware","hardware/audio","音频设备公开清单","AudioManager.getDevices",()->audioDevices(manager));
        s.read("hardware","hardware/audio-summary","音频能力摘要","AudioManager.getDevices",()->audioSummary(manager));
    }
    private static JSONArray audioDevices(AudioManager manager){
        JSONArray devices=new JSONArray();for(AudioDeviceInfo device:manager.getDevices(AudioManager.GET_DEVICES_ALL)){CharSequence productName=device.getProductName();devices.put(Json.obj("id",device.getId(),"type",device.getType(),"productName",productName==null?null:productName.toString(),"isSource",device.isSource(),"isSink",device.isSink(),"addressPresent",device.getAddress()!=null&&!device.getAddress().isEmpty(),"channelCounts",device.getChannelCounts(),"sampleRates",device.getSampleRates(),"encodings",device.getEncodings()));}return devices;
    }
    private static JSONObject audioSummary(AudioManager manager){
        AudioDeviceInfo[] devices=manager.getDevices(AudioManager.GET_DEVICES_ALL);JSONObject typeCounts=new JSONObject();TreeSet<String> productNames=new TreeSet<>(String.CASE_INSENSITIVE_ORDER);boolean productNamesTruncated=false;int sourceCount=0,sinkCount=0;TreeSet<Integer> sampleRates=new TreeSet<>(),encodings=new TreeSet<>();
        for(AudioDeviceInfo device:devices){if(device.isSource())sourceCount++;if(device.isSink())sinkCount++;count(typeCounts,String.valueOf(device.getType()));CharSequence productName=device.getProductName();String product=productName==null?"":productName.toString();if(product.length()>0&&!productNames.contains(product)){if(productNames.size()<128)productNames.add(product);else productNamesTruncated=true;}int[] rates=device.getSampleRates();if(rates!=null)for(int rate:rates)sampleRates.add(rate);int[] formats=device.getEncodings();if(formats!=null)for(int encoding:formats)encodings.add(encoding);}
        JSONArray names=new JSONArray();for(String name:productNames)names.put(name);JSONArray rates=new JSONArray();for(Integer rate:sampleRates)rates.put(rate);JSONArray formats=new JSONArray();for(Integer encoding:encodings)formats.put(encoding);
        return Json.obj("available",true,"count",devices.length,"sourceCount",sourceCount,"sinkCount",sinkCount,"typeCounts",typeCounts,"productNames",names,"productNamesTruncated",productNamesTruncated,"sampleRates",rates,"encodings",formats);
    }
    // 业务块：只读取 UsbManager 已公开的设备描述符，不申请 USB 权限、不读取序列号。
    private void usb(ItemSink s){
        s.read("hardware","hardware/usb","USB 设备公开清单","UsbManager.getDeviceList / UsbDevice",()->{
            UsbManager manager=s.context.getSystemService(UsbManager.class);
            if(manager==null)return Json.obj("count",0,"devices",new JSONArray());
            Map<String,UsbDevice> devices=manager.getDeviceList();
            if(devices.size()>256)throw new IllegalStateException("USB 设备超过 256");
            ArrayList<String> names=new ArrayList<>(devices.keySet());Collections.sort(names);
            JSONArray values=new JSONArray();
            for(String name:names){
                UsbDevice device=devices.get(name);if(device==null)continue;
                JSONArray interfaces=new JSONArray();
                for(int i=0;i<device.getInterfaceCount();i++){UsbInterface face=device.getInterface(i);interfaces.put(Json.obj("id",face.getId(),"alternateSetting",face.getAlternateSetting(),"class",face.getInterfaceClass(),"subclass",face.getInterfaceSubclass(),"protocol",face.getInterfaceProtocol(),"endpointCount",face.getEndpointCount()));}
                values.put(Json.obj("deviceName",name,"vendorId",device.getVendorId(),"productId",device.getProductId(),"deviceClass",device.getDeviceClass(),"deviceSubclass",device.getDeviceSubclass(),"deviceProtocol",device.getDeviceProtocol(),"configurationCount",device.getConfigurationCount(),"interfaceCount",device.getInterfaceCount(),"interfaces",interfaces));
            }
            return Json.obj("count",values.length(),"devices",values);
        });
    }
    // 业务块：只读取 NFC 适配器公开能力，不启停适配器、不读取标签内容。
    private void nfc(ItemSink s){
        s.read("hardware","hardware/nfc","NFC 能力","NfcAdapter",()->{
            NfcAdapter adapter=NfcAdapter.getDefaultAdapter(s.context);
            if(adapter==null)return Json.obj("available",false,"enabled",false);
            JSONObject value=Json.obj("available",true,"enabled",adapter.isEnabled());
            if(Build.VERSION.SDK_INT>=29){Json.put(value,"secureNfcSupported",adapter.isSecureNfcSupported());Json.put(value,"secureNfcEnabled",adapter.isSecureNfcEnabled());}
            if(Build.VERSION.SDK_INT>=34){NfcAntennaInfo antenna=adapter.getNfcAntennaInfo();Json.put(value,"antennaInfoAvailable",antenna!=null);if(antenna!=null)Json.put(value,"antennaCount",antenna.getAvailableNfcAntennas()==null?0:antenna.getAvailableNfcAntennas().size());Json.put(value,"deviceWidth",antenna==null?null:antenna.getDeviceWidth());Json.put(value,"deviceHeight",antenna==null?null:antenna.getDeviceHeight());Json.put(value,"deviceFoldable",antenna==null?null:antenna.isDeviceFoldable());}
            return value;
        });
    }
    // 业务块：只导出 Widevine 能力和唯一值摘要，绝不输出原始 DRM deviceUniqueId。
    private void drm(ItemSink s){
        final UUID widevine=UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed");
        s.read("hardware","hardware/drm/widevine","Widevine DRM 能力","MediaDrm",()->{
            if(!MediaDrm.isCryptoSchemeSupported(widevine))return Json.obj("supported",false,"schemeUuid",widevine.toString());
            MediaDrm drm=null;
            try{
                drm=new MediaDrm(widevine);
                JSONObject value=Json.obj("supported",true,"schemeUuid",widevine.toString(),
                        "vendor",drm.getPropertyString(MediaDrm.PROPERTY_VENDOR),
                        "version",drm.getPropertyString(MediaDrm.PROPERTY_VERSION),
                        "description",drm.getPropertyString(MediaDrm.PROPERTY_DESCRIPTION),
                        "algorithms",drm.getPropertyString(MediaDrm.PROPERTY_ALGORITHMS),
                        "maxSecurityLevel",Build.VERSION.SDK_INT>=28?MediaDrm.getMaxSecurityLevel():null);
                try{
                    byte[] unique=drm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID);
                    Json.put(value,"deviceUniqueId",unique==null?null:Json.obj("sha256",PackageInputCollector.sha(unique),"length",unique.length,"rawExported",false));
                }catch(Exception e){Json.put(value,"deviceUniqueId",Json.obj("status","error","class",e.getClass().getName(),"rawExported",false));}
                return value;
            }finally{if(drm!=null)drm.release();}
        });
    }
    private Object cameraValue(Object value){
        if(value==null)return null;
        if(value instanceof android.hardware.camera2.params.StreamConfigurationMap){android.hardware.camera2.params.StreamConfigurationMap map=(android.hardware.camera2.params.StreamConfigurationMap)value;JSONArray outputs=new JSONArray();int[] formats=map.getOutputFormats();for(int format:formats){JSONArray sizes=new JSONArray();Size[] ss=map.getOutputSizes(format);if(ss!=null)for(Size size:ss)sizes.put(Json.obj("width",size.getWidth(),"height",size.getHeight(),"minFrameDuration",map.getOutputMinFrameDuration(format,size),"stallDuration",map.getOutputStallDuration(format,size)));outputs.put(Json.obj("format",format,"sizes",sizes));}return Json.obj("outputFormats",outputs,"highSpeedVideoSizes",map.getHighSpeedVideoSizes()==null?null:Arrays.toString(map.getHighSpeedVideoSizes()));}
        if(value.getClass().isArray()){JSONArray a=new JSONArray();int n=java.lang.reflect.Array.getLength(value);for(int i=0;i<n;i++)a.put(Json.value(cameraValue(java.lang.reflect.Array.get(value,i))));return a;}
        if(value instanceof String||value instanceof Number||value instanceof Boolean)return value;
        return Json.obj("$type",value.getClass().getName(),"value",value.toString());
    }
    private void sensors(ItemSink s){
        SensorManager m=s.context.getSystemService(SensorManager.class);JSONArray a=new JSONArray();JSONObject typeCounts=new JSONObject();TreeSet<String> vendors=new TreeSet<>(String.CASE_INSENSITIVE_ORDER);boolean vendorsTruncated=false;int wakeUpCount=0,dynamicCount=0,directChannelCount=0;
        for(Sensor sensor:m.getSensorList(Sensor.TYPE_ALL)){JSONObject v=Json.obj("name",sensor.getName(),"vendor",sensor.getVendor(),"type",sensor.getType(),"stringType",sensor.getStringType(),"version",sensor.getVersion(),"resolution",sensor.getResolution(),"maximumRange",sensor.getMaximumRange(),"power",sensor.getPower(),"minDelay",sensor.getMinDelay(),"maxDelay",sensor.getMaxDelay(),"fifoMaxEventCount",sensor.getFifoMaxEventCount(),"fifoReservedEventCount",sensor.getFifoReservedEventCount(),"reportingMode",sensor.getReportingMode(),"wakeUp",sensor.isWakeUpSensor(),"dynamic",sensor.isDynamicSensor());if(sensor.isWakeUpSensor())wakeUpCount++;if(sensor.isDynamicSensor())dynamicCount++;String typeKey=String.valueOf(sensor.getType());Json.put(typeCounts,typeKey,typeCounts.optInt(typeKey,0)+1);String vendor=sensor.getVendor();if(vendor!=null&&!vendors.contains(vendor)){if(vendors.size()<128)vendors.add(vendor);else vendorsTruncated=true;}if(Build.VERSION.SDK_INT>=26){Json.put(v,"highestDirectReportRateLevel",sensor.getHighestDirectReportRateLevel());boolean memory=sensor.isDirectChannelTypeSupported(SensorDirectChannel.TYPE_MEMORY_FILE),hardware=sensor.isDirectChannelTypeSupported(SensorDirectChannel.TYPE_HARDWARE_BUFFER);Json.put(v,"memoryFileDirectChannel",memory);Json.put(v,"hardwareBufferDirectChannel",hardware);if(memory||hardware)directChannelCount++;}a.put(v);}
        JSONArray vendorValues=new JSONArray();for(String vendor:vendors)vendorValues.put(vendor);
        s.ok("sensors","sensors/summary","传感器能力摘要","android-api","SensorManager.getSensorList(TYPE_ALL)",Json.obj("count",a.length(),"wakeUpCount",wakeUpCount,"dynamicCount",dynamicCount,"directChannelCount",directChannelCount,"typeCounts",typeCounts,"vendors",vendorValues,"vendorsTruncated",vendorsTruncated));
        s.ok("sensors","sensors/list","传感器完整公开清单","android-api","SensorManager.getSensorList(TYPE_ALL)",a);
    }
    private void battery(ItemSink s){
        s.read("battery","battery/summary","电池环境摘要","BatteryManager / ACTION_BATTERY_CHANGED",()->batterySummary(s.context));
        s.read("battery","battery/broadcast","电池广播快照","ACTION_BATTERY_CHANGED sticky broadcast",()->{Intent i=s.context.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));if(i==null)return null;Bundle extras=i.getExtras();JSONObject v=new JSONObject();if(extras!=null)for(String k:extras.keySet()){Object x=extras.get(k);Json.put(v,k,x==null||x instanceof Number||x instanceof Boolean||x instanceof String?x:String.valueOf(x));}return v;});
        BatteryManager b=s.context.getSystemService(BatteryManager.class);for(int id:new int[]{BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER,BatteryManager.BATTERY_PROPERTY_CURRENT_NOW,BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE,BatteryManager.BATTERY_PROPERTY_CAPACITY,BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER,BatteryManager.BATTERY_PROPERTY_STATUS})s.read("battery","battery/property/"+id,"电池属性 "+id,"BatteryManager property "+id,()->id==BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER?Json.obj("raw",b.getLongProperty(id),"unsupportedSentinel",String.valueOf(Long.MIN_VALUE)):Json.obj("raw",b.getIntProperty(id),"unsupportedSentinel",Integer.MIN_VALUE));
        for(String p:new String[]{"/sys/class/power_supply/battery/uevent","/sys/class/power_supply/battery/capacity","/sys/class/power_supply/battery/status","/sys/class/power_supply/battery/voltage_now","/sys/class/power_supply/battery/temp"})s.file("battery","battery/file"+p,p);
    }
    // 业务块：把电池广播与公开 BatteryManager 属性整理成稳定摘要；旧广播、属性和 sysfs 条目继续保留。
    private static JSONObject batterySummary(Context context)throws Exception{
        Intent intent=context.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if(intent==null)return Json.obj("available",false);
        int level=intent.getIntExtra(BatteryManager.EXTRA_LEVEL,-1),scale=intent.getIntExtra(BatteryManager.EXTRA_SCALE,-1),status=intent.getIntExtra(BatteryManager.EXTRA_STATUS,-1),plugged=intent.getIntExtra(BatteryManager.EXTRA_PLUGGED,-1),health=intent.getIntExtra(BatteryManager.EXTRA_HEALTH,-1),temperature=intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,Integer.MIN_VALUE),voltage=intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE,Integer.MIN_VALUE);
        BatteryManager manager=context.getSystemService(BatteryManager.class);Integer capacity=null,chargeCounter=null,currentNow=null,currentAverage=null;Long energyCounter=null;
        if(manager!=null){capacity=knownPropertyInt(manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));chargeCounter=knownPropertyInt(manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER));currentNow=knownPropertyInt(manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW));currentAverage=knownPropertyInt(manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE));energyCounter=knownLong(manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER));}
        return Json.obj("available",true,"level",knownInt(level),"scale",knownInt(scale),"percentage",level>=0&&scale>0?((double)level*100.0/scale):null,"status",knownInt(status),"charging",status==BatteryManager.BATTERY_STATUS_CHARGING||status==BatteryManager.BATTERY_STATUS_FULL,"plugged",knownInt(plugged),"pluggedAc",plugged>=0&&(plugged&BatteryManager.BATTERY_PLUGGED_AC)!=0,"pluggedUsb",plugged>=0&&(plugged&BatteryManager.BATTERY_PLUGGED_USB)!=0,"pluggedWireless",plugged>=0&&(plugged&BatteryManager.BATTERY_PLUGGED_WIRELESS)!=0,"health",knownInt(health),"temperatureC",temperature==Integer.MIN_VALUE?null:temperature/10.0,"voltageMv",knownInt(voltage),"technology",intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY),"present",intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT,false),"capacityPercent",capacity,"chargeCounterUah",chargeCounter,"currentNowUa",currentNow,"currentAverageUa",currentAverage,"energyCounterNwh",energyCounter);
    }
    private static Integer knownInt(int value){return value<0||value==Integer.MIN_VALUE?null:value;}
    private static Integer knownPropertyInt(int value){return value==Integer.MIN_VALUE?null:value;}
    private static Long knownLong(long value){return value==Long.MIN_VALUE?null:value;}
}
