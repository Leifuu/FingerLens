package com.life.fingerprint;

import android.content.*;
import android.content.pm.FeatureInfo;
import android.hardware.*;
import android.hardware.camera2.*;
import android.hardware.usb.*;
import android.media.*;
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
        usb(s);
        drm(s);
        CameraManager cameras=s.context.getSystemService(CameraManager.class);
        for(String id:cameras.getCameraIdList())s.read("hardware","hardware/camera/"+id,"摄像头 "+id,"CameraManager.getCameraCharacteristics",()->{CameraCharacteristics c=cameras.getCameraCharacteristics(id);JSONObject o=Json.obj("id",id);for(CameraCharacteristics.Key<?> k:c.getKeys()){try{Json.put(o,k.getName(),cameraValue(c.get(k)));}catch(Exception e){Json.put(o,k.getName(),Json.obj("error",e.getClass().getName(),"message",e.getMessage()));}}return o;});
        s.read("hardware","hardware/codecs","媒体编解码器","MediaCodecList.ALL_CODECS",()->{JSONArray a=new JSONArray();for(MediaCodecInfo info:new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()){JSONObject o=Json.obj("name",info.getName(),"encoder",info.isEncoder(),"supportedTypes",info.getSupportedTypes());if(Build.VERSION.SDK_INT>=29){Json.put(o,"hardwareAccelerated",info.isHardwareAccelerated());Json.put(o,"softwareOnly",info.isSoftwareOnly());Json.put(o,"vendor",info.isVendor());Json.put(o,"canonicalName",info.getCanonicalName());}JSONArray types=new JSONArray();for(String type:info.getSupportedTypes())try{MediaCodecInfo.CodecCapabilities cap=info.getCapabilitiesForType(type);JSONArray profiles=new JSONArray();for(MediaCodecInfo.CodecProfileLevel p:cap.profileLevels)profiles.put(Json.obj("profile",p.profile,"level",p.level));types.put(Json.obj("type",type,"colorFormats",cap.colorFormats,"maxSupportedInstances",cap.getMaxSupportedInstances(),"profileLevels",profiles));}catch(Exception e){types.put(Json.obj("type",type,"error",e.getMessage()));}Json.put(o,"capabilities",types);a.put(o);}return a;});
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
        SensorManager m=s.context.getSystemService(SensorManager.class);JSONArray a=new JSONArray();
        for(Sensor sensor:m.getSensorList(Sensor.TYPE_ALL)){JSONObject v=Json.obj("name",sensor.getName(),"vendor",sensor.getVendor(),"type",sensor.getType(),"stringType",sensor.getStringType(),"version",sensor.getVersion(),"resolution",sensor.getResolution(),"maximumRange",sensor.getMaximumRange(),"power",sensor.getPower(),"minDelay",sensor.getMinDelay(),"maxDelay",sensor.getMaxDelay(),"fifoMaxEventCount",sensor.getFifoMaxEventCount(),"fifoReservedEventCount",sensor.getFifoReservedEventCount(),"reportingMode",sensor.getReportingMode(),"wakeUp",sensor.isWakeUpSensor(),"dynamic",sensor.isDynamicSensor());if(Build.VERSION.SDK_INT>=26){Json.put(v,"highestDirectReportRateLevel",sensor.getHighestDirectReportRateLevel());Json.put(v,"memoryFileDirectChannel",sensor.isDirectChannelTypeSupported(SensorDirectChannel.TYPE_MEMORY_FILE));Json.put(v,"hardwareBufferDirectChannel",sensor.isDirectChannelTypeSupported(SensorDirectChannel.TYPE_HARDWARE_BUFFER));}a.put(v);}
        s.ok("sensors","sensors/list","传感器完整公开清单","android-api","SensorManager.getSensorList(TYPE_ALL)",a);
    }
    private void battery(ItemSink s){
        s.read("battery","battery/broadcast","电池广播快照","ACTION_BATTERY_CHANGED sticky broadcast",()->{Intent i=s.context.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));if(i==null)return null;Bundle extras=i.getExtras();JSONObject v=new JSONObject();if(extras!=null)for(String k:extras.keySet()){Object x=extras.get(k);Json.put(v,k,x==null||x instanceof Number||x instanceof Boolean||x instanceof String?x:String.valueOf(x));}return v;});
        BatteryManager b=s.context.getSystemService(BatteryManager.class);for(int id:new int[]{BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER,BatteryManager.BATTERY_PROPERTY_CURRENT_NOW,BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE,BatteryManager.BATTERY_PROPERTY_CAPACITY,BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER,BatteryManager.BATTERY_PROPERTY_STATUS})s.read("battery","battery/property/"+id,"电池属性 "+id,"BatteryManager property "+id,()->id==BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER?Json.obj("raw",b.getLongProperty(id),"unsupportedSentinel",String.valueOf(Long.MIN_VALUE)):Json.obj("raw",b.getIntProperty(id),"unsupportedSentinel",Integer.MIN_VALUE));
        for(String p:new String[]{"/sys/class/power_supply/battery/uevent","/sys/class/power_supply/battery/capacity","/sys/class/power_supply/battery/status","/sys/class/power_supply/battery/voltage_now","/sys/class/power_supply/battery/temp"})s.file("battery","battery/file"+p,p);
    }
}
