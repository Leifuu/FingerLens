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
        s.read("hardware","hardware/graphics-summary","图形能力摘要","EGL / OpenGL ES / Vulkan",HardwareCollector::graphicsSummary);
        s.read("hardware","hardware/features","系统声明特性","PackageManager.getSystemAvailableFeatures",()->{JSONArray a=new JSONArray();for(FeatureInfo f:s.context.getPackageManager().getSystemAvailableFeatures())a.put(Json.obj("name",f.name,"version",f.version,"flags",f.flags,"reqGlEsVersion",f.reqGlEsVersion));return a;});
        s.read("hardware","hardware/features-summary","系统特性能力摘要","PackageManager.getSystemAvailableFeatures",()->featureSummary(s.context));
        s.read("hardware","hardware/codecs-summary","媒体编解码能力摘要","MediaCodecList.ALL_CODECS",HardwareCollector::codecSummary);
        audio(s);
        vibrator(s);
        usb(s);
        nfc(s);
        drm(s);
        s.read("hardware","hardware/camera-concurrency","摄像头并发组合摘要","CameraManager.getConcurrentCameraIds",()->cameraConcurrencySummary(s.context));
        CameraManager cameras=s.context.getSystemService(CameraManager.class);
        if(cameras==null)s.ok("hardware","hardware/camera-summary","摄像头能力摘要","CameraManager","CameraManager unavailable",Json.obj("available",false,"count",0,"cameraIds",new JSONArray()));
        else {String[] cameraIds=cameras.getCameraIdList();for(String id:cameraIds)s.read("hardware","hardware/camera/"+id,"摄像头 "+id,"CameraManager.getCameraCharacteristics",()->{CameraCharacteristics c=cameras.getCameraCharacteristics(id);JSONObject o=Json.obj("id",id);for(CameraCharacteristics.Key<?> k:c.getKeys()){try{Json.put(o,k.getName(),cameraValue(c.get(k)));}catch(Exception e){Json.put(o,k.getName(),Json.obj("error",e.getClass().getName(),"message",e.getMessage()));}}return o;});s.read("hardware","hardware/camera-summary","摄像头能力摘要","CameraManager.getCameraCharacteristics",()->cameraSummary(cameras,cameraIds));}
        s.read("hardware","hardware/codecs","媒体编解码器","MediaCodecList.ALL_CODECS",()->{JSONArray a=new JSONArray();for(MediaCodecInfo info:new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()){JSONObject o=Json.obj("name",info.getName(),"encoder",info.isEncoder(),"supportedTypes",info.getSupportedTypes());if(Build.VERSION.SDK_INT>=29){Json.put(o,"hardwareAccelerated",info.isHardwareAccelerated());Json.put(o,"softwareOnly",info.isSoftwareOnly());Json.put(o,"vendor",info.isVendor());Json.put(o,"canonicalName",info.getCanonicalName());}JSONArray types=new JSONArray();for(String type:info.getSupportedTypes())try{MediaCodecInfo.CodecCapabilities cap=info.getCapabilitiesForType(type);JSONArray profiles=new JSONArray();for(MediaCodecInfo.CodecProfileLevel p:cap.profileLevels)profiles.put(Json.obj("profile",p.profile,"level",p.level));types.put(Json.obj("type",type,"colorFormats",cap.colorFormats,"maxSupportedInstances",cap.getMaxSupportedInstances(),"profileLevels",profiles,"featureFlags",codecFeatureFlags(cap),"videoCapabilities",codecVideoCapabilities(cap),"audioCapabilities",codecAudioCapabilities(cap),"encoderCapabilities",codecEncoderCapabilities(cap)));}catch(Exception e){types.put(Json.obj("type",type,"error",e.getMessage()));}Json.put(o,"capabilities",types);a.put(o);}return a;});
    }
    // 业务块：压缩图形扩展长字符串为可比较摘要；原始 hardware/graphics 继续保留。
    private static JSONObject graphicsSummary()throws Exception{
        JSONObject raw=new JSONObject(NativeProbe.graphics());String eglExtensions=raw.optString("eglExtensions",null),glExtensions=raw.optString("glExtensions",null);JSONArray vulkan=raw.optJSONArray("vulkanExtensions"),vulkanNames=new JSONArray();
        if(vulkan!=null)for(int i=0;i<vulkan.length()&&vulkanNames.length()<256;i++){JSONObject extension=vulkan.optJSONObject(i);if(extension!=null&&extension.has("name"))vulkanNames.put(extension.optString("name"));}
        return Json.obj("available",true,"eglInitialized",raw.has("eglInitialized")&&!raw.isNull("eglInitialized")?raw.optBoolean("eglInitialized"):null,"eglError",raw.has("eglError")&&!raw.isNull("eglError")?raw.optInt("eglError"):null,"eglVendor",raw.optString("eglVendor",null),"eglVersion",raw.optString("eglVersion",null),"glVendor",raw.optString("glVendor",null),"glRenderer",raw.optString("glRenderer",null),"glVersion",raw.optString("glVersion",null),"eglExtensionsLength",eglExtensions==null?null:eglExtensions.length(),"eglExtensionCount",extensionCount(eglExtensions),"glExtensionsLength",glExtensions==null?null:glExtensions.length(),"glExtensionCount",extensionCount(glExtensions),"vulkanLibraryLoaded",raw.has("vulkanLibraryLoaded")&&!raw.isNull("vulkanLibraryLoaded")?raw.optBoolean("vulkanLibraryLoaded"):null,"vulkanExtensionResult",raw.has("vulkanExtensionResult")&&!raw.isNull("vulkanExtensionResult")?raw.optInt("vulkanExtensionResult"):null,"vulkanExtensionCount",vulkan==null?null:vulkan.length(),"vulkanExtensionNames",vulkanNames,"vulkanExtensionNamesTruncated",vulkan!=null&&vulkan.length()>256);
    }
    private static int extensionCount(String extensions){if(extensions==null||extensions.trim().isEmpty())return 0;return extensions.trim().split("\\s+").length;}
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
    // 业务块：固定查询四项公开特性的支持/必需标志；单项失败不丢失原始MIME能力。
    static JSONObject codecFeatureFlags(MediaCodecInfo.CodecCapabilities capabilities){
        Objects.requireNonNull(capabilities,"missing codec capabilities");
        String[] names={MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback,MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback,
                MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback,MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency};
        int[] minimumApis={19,21,21,30};JSONObject flags=new JSONObject();
        for(int i=0;i<names.length;i++){
            String name=names[i];
            if(Build.VERSION.SDK_INT<minimumApis[i]){Json.put(flags,name,Json.obj("status","unsupported","supported",null,"required",null));continue;}
            try{Json.put(flags,name,Json.obj("status","ok","supported",capabilities.isFeatureSupported(name),"required",capabilities.isFeatureRequired(name)));}
            catch(Exception|LinkageError e){Json.put(flags,name,Json.obj("status",e instanceof SecurityException?"denied":"error","supported",null,"required",null,"errorClass",e.getClass().getName()));}
        }
        return flags;
    }
    // 业务块：只读视频能力的固定范围，不枚举宽高组合、不创建编解码器；失败只影响该 MIME 的新增对象。
    static JSONObject codecVideoCapabilities(MediaCodecInfo.CodecCapabilities capabilities){
        Objects.requireNonNull(capabilities,"missing codec capabilities");
        JSONObject missing=Json.obj("available",false,"status","not-video","bitrateBitsPerSecond",null,"frameRatesPerSecond",null,
                "widthPixels",null,"heightPixels",null,"widthAlignmentPixels",null,"heightAlignmentPixels",null);
        try{
            MediaCodecInfo.VideoCapabilities video=capabilities.getVideoCapabilities();if(video==null)return missing;
            return Json.obj("available",true,"status","ok","bitrateBitsPerSecond",codecRange(video.getBitrateRange()),
                    "frameRatesPerSecond",codecRange(video.getSupportedFrameRates()),"widthPixels",codecRange(video.getSupportedWidths()),
                    "heightPixels",codecRange(video.getSupportedHeights()),"widthAlignmentPixels",video.getWidthAlignment(),"heightAlignmentPixels",video.getHeightAlignment());
        }catch(Exception|LinkageError e){Json.put(missing,"status",e instanceof SecurityException?"denied":"error");Json.put(missing,"errorClass",e.getClass().getName());return missing;}
    }
    // 业务块：区分非音频、连续采样率和查询失败，保留长清单的完整哈希，不创建编解码器。
    static JSONObject codecAudioCapabilities(MediaCodecInfo.CodecCapabilities capabilities){
        Objects.requireNonNull(capabilities,"missing codec capabilities");
        JSONObject missing=Json.obj("available",false,"status","not-audio","bitrateBitsPerSecond",null,"maxInputChannelCount",null,
                "sampleRateMode",null,"sampleRatesHz",null,"sampleRateRangesHz",null);
        try{
            MediaCodecInfo.AudioCapabilities audio=capabilities.getAudioCapabilities();if(audio==null)return missing;
            JSONObject value=codecAudioValues(audio.getBitrateRange(),audio.getMaxInputChannelCount(),audio.getSupportedSampleRateRanges(),audio.getSupportedSampleRates());
            Json.put(value,"inputChannelSupport",codecInputChannels(audio));return value;
        }catch(Exception|LinkageError e){Json.put(missing,"status",e instanceof SecurityException?"denied":"error");Json.put(missing,"errorClass",e.getClass().getName());return missing;}
    }
    static JSONObject codecAudioValues(Range<Integer> bitrate,int maxChannels,Range<Integer>[] ranges,int[] rates)throws Exception{
        if(ranges==null||ranges.length>20000||(rates!=null&&rates.length>20000))throw new IllegalArgumentException("采样率区间缺失或清单超过20000项");
        JSONArray allRanges=new JSONArray(),entries=new JSONArray();for(Range<Integer> range:ranges){JSONObject value=codecRange(range);allRanges.put(value);if(entries.length()<64)entries.put(value);}
        JSONArray allRates=rates==null?null:new JSONArray(rates);
        return Json.obj("available",true,"status",ranges.length>64||(rates!=null&&rates.length>256)?"partial":"ok",
                "bitrateBitsPerSecond",codecRange(bitrate),"maxInputChannelCount",maxChannels,"sampleRateMode",rates==null?"ranges":"discrete",
                "sampleRateRangesHz",entries,"sampleRateRangeCount",ranges.length,"sampleRateRangeLimit",64,"sampleRateRangesTruncated",ranges.length>64,
                "sampleRateRangesSha256",PackageInputCollector.sha(allRanges.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "sampleRatesHz",rates==null?null:Arrays.copyOf(rates,Math.min(256,rates.length)),"sampleRateCount",rates==null?null:rates.length,
                "sampleRateLimit",256,"sampleRatesTruncated",rates==null?null:rates.length>256,
                "sampleRatesSha256",allRates==null?null:PackageInputCollector.sha(allRates.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    // 业务块：API31+读取精确声道区间，不从最大声道数补出中间值；失败不抹去原音频能力。
    static JSONObject codecInputChannels(MediaCodecInfo.AudioCapabilities audio){
        Objects.requireNonNull(audio,"missing audio capabilities");
        JSONObject missing=Json.obj("available",false,"status","unsupported","minInputChannelCount",null,"rangeCount",null,
                "returnedCount",0,"rangeLimit",64,"truncated",null,"rangesSha256",null,"ranges",null);
        if(Build.VERSION.SDK_INT<31)return missing;
        try{return codecInputChannelValues(audio.getMinInputChannelCount(),audio.getInputChannelCountRanges());}
        catch(Exception|LinkageError e){Json.put(missing,"status",e instanceof SecurityException?"denied":"error");Json.put(missing,"errorClass",e.getClass().getName());return missing;}
    }
    static JSONObject codecInputChannelValues(int minimum,Range<Integer>[] ranges)throws Exception{
        if(ranges==null||ranges.length>20000)throw new IllegalArgumentException("声道区间缺失或超过20000项");
        JSONArray all=new JSONArray(),entries=new JSONArray();for(Range<Integer> range:ranges){JSONObject value=codecRange(range);all.put(value);if(entries.length()<64)entries.put(value);}
        return Json.obj("available",true,"status",ranges.length>64?"partial":"ok","minInputChannelCount",minimum,
                "rangeCount",ranges.length,"returnedCount",entries.length(),"rangeLimit",64,"truncated",ranges.length>64,
                "rangesSha256",PackageInputCollector.sha(all.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),"ranges",entries);
    }
    // 业务块：固定读取编码器的两个范围与四种公开码率模式，不创建实例；失败和旧API均保留未知值。
    static JSONObject codecEncoderCapabilities(MediaCodecInfo.CodecCapabilities capabilities){
        Objects.requireNonNull(capabilities,"missing codec capabilities");
        JSONObject value=Json.obj("available",false,"status","not-encoder","complexity",null,"quality",null,"bitrateModes",null);
        MediaCodecInfo.EncoderCapabilities encoder;
        try{encoder=capabilities.getEncoderCapabilities();if(encoder==null)return value;}
        catch(Exception|LinkageError e){Json.put(value,"status",e instanceof SecurityException?"denied":"error");Json.put(value,"errorClass",e.getClass().getName());return value;}
        Json.put(value,"available",true);Json.put(value,"status","ok");
        for(String name:new String[]{"complexity","quality"}){
            JSONObject range=Json.obj("status","unsupported","range",null);Json.put(value,name,range);
            if(name.equals("quality")&&Build.VERSION.SDK_INT<28)continue;
            try{Json.put(range,"range",codecRange(name.equals("quality")?encoder.getQualityRange():encoder.getComplexityRange()));Json.put(range,"status","ok");}
            catch(Exception|LinkageError e){Json.put(range,"status",e instanceof SecurityException?"denied":"error");Json.put(range,"errorClass",e.getClass().getName());Json.put(value,"status","partial");}
        }
        String[] names={"CQ","VBR","CBR","CBR_FD"};int[] modes={MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR_FD};
        JSONObject flags=new JSONObject();Json.put(value,"bitrateModes",flags);
        for(int i=0;i<names.length;i++){
            JSONObject mode=Json.obj("mode",modes[i],"status","unsupported","supported",null);Json.put(flags,names[i],mode);
            if(modes[i]==MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR_FD&&Build.VERSION.SDK_INT<31)continue;
            try{Json.put(mode,"supported",encoder.isBitrateModeSupported(modes[i]));Json.put(mode,"status","ok");}
            catch(Exception|LinkageError e){Json.put(mode,"status",e instanceof SecurityException?"denied":"error");Json.put(mode,"errorClass",e.getClass().getName());Json.put(value,"status","partial");}
        }
        return value;
    }
    private static JSONObject codecRange(Range<Integer> range){Objects.requireNonNull(range,"missing codec capability range");return Json.obj("lower",range.getLower(),"upper",range.getUpper());}
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
        if(manager==null){s.ok("hardware","hardware/audio-summary","音频能力摘要","AudioManager","AudioManager unavailable",Json.obj("available",false,"count",0,"productNames",new JSONArray()));s.ok("hardware","hardware/microphone-summary","麦克风阵列能力摘要","AudioManager.getMicrophones","AudioManager unavailable",microphoneUnavailable("unavailable"));return;}
        s.read("hardware","hardware/audio","音频设备公开清单","AudioManager.getDevices",()->audioDevices(manager));
        s.read("hardware","hardware/audio-summary","音频能力摘要","AudioManager.getDevices",()->audioSummary(manager));
        s.read("hardware","hardware/microphone-summary","麦克风阵列能力摘要","AudioManager.getMicrophones",()->microphoneSummary(manager));
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
    // 业务块：读取公开麦克风阵列能力，限制集合规模，不导出地址/描述或任何音频样本。
    private static JSONObject microphoneSummary(AudioManager manager)throws Exception{
        if(Build.VERSION.SDK_INT<28)return microphoneUnavailable("unsupported");
        if(manager==null)return microphoneUnavailable("unavailable");
        try{
            List<MicrophoneInfo> microphones=manager.getMicrophones();if(microphones==null)return microphoneUnavailable("unavailable");
            TreeSet<Integer> ids=new TreeSet<>(),locations=new TreeSet<>(),directionalities=new TreeSet<>(),groups=new TreeSet<>();JSONObject typeCounts=new JSONObject();
            int limit=Math.min(256,microphones.size()),addressPresent=0,descriptionPresent=0,channelMappingCount=0,frequencyResponsePointCount=0;
            for(int i=0;i<limit;i++){MicrophoneInfo microphone=microphones.get(i);if(microphone==null)continue;ids.add(microphone.getId());count(typeCounts,String.valueOf(microphone.getType()));locations.add(microphone.getLocation());directionalities.add(microphone.getDirectionality());if(microphone.getGroup()!=MicrophoneInfo.GROUP_UNKNOWN)groups.add(microphone.getGroup());String address=microphone.getAddress();if(address!=null&&!address.isEmpty())addressPresent++;String description=microphone.getDescription();if(description!=null&&!description.isEmpty())descriptionPresent++;List<android.util.Pair<Integer,Integer>> mapping=microphone.getChannelMapping();if(mapping!=null)channelMappingCount+=mapping.size();List<android.util.Pair<Float,Float>> response=microphone.getFrequencyResponse();if(response!=null)frequencyResponsePointCount+=response.size();}
            return Json.obj("available",true,"status","ok","count",microphones.size(),"returnedCount",limit,"truncated",microphones.size()>limit,"microphoneIds",sortedIntegers(ids,64),"microphoneIdsTruncated",ids.size()>64,"typeCounts",typeCounts,"locationValues",sortedIntegers(locations,32),"directionalityValues",sortedIntegers(directionalities,32),"groupCount",groups.size(),"addressPresentCount",addressPresent,"descriptionPresentCount",descriptionPresent,"channelMappingCount",channelMappingCount,"frequencyResponsePointCount",frequencyResponsePointCount);
        }catch(SecurityException e){return microphoneUnavailable("denied");}catch(java.io.IOException e){return microphoneUnavailable("error");}catch(RuntimeException e){return Json.obj("available",false,"status","error","count",null,"returnedCount",null,"truncated",false,"microphoneIds",new JSONArray(),"microphoneIdsTruncated",null,"typeCounts",new JSONObject(),"locationValues",new JSONArray(),"directionalityValues",new JSONArray(),"groupCount",null,"addressPresentCount",null,"descriptionPresentCount",null,"channelMappingCount",null,"frequencyResponsePointCount",null,"error",e.getClass().getName());}
    }
    private static JSONObject microphoneUnavailable(String status){return Json.obj("available",false,"status",status,"count",null,"returnedCount",null,"truncated",false,"microphoneIds",new JSONArray(),"microphoneIdsTruncated",null,"typeCounts",new JSONObject(),"locationValues",new JSONArray(),"directionalityValues",new JSONArray(),"groupCount",null,"addressPresentCount",null,"descriptionPresentCount",null,"channelMappingCount",null,"frequencyResponsePointCount",null);}
    private static JSONArray sortedIntegers(Set<Integer> values,int limit){JSONArray out=new JSONArray();int count=0;for(Integer value:values){if(count++>=limit)break;out.put(value);}return out;}
    // 业务块：只读取振动器硬件能力，不触发振动或改变系统状态。
    private void vibrator(ItemSink s){
        s.read("hardware","hardware/vibrator","振动器能力","Vibrator / VibratorManager",()->{
            JSONArray values=new JSONArray();
            if(Build.VERSION.SDK_INT>=31){
                VibratorManager manager=s.context.getSystemService(VibratorManager.class);
                if(manager==null)return Json.obj("available",false,"count",0,"vibrators",values);
                int[] ids=manager.getVibratorIds();if(ids.length>32)throw new IllegalStateException("振动器超过 32");
                for(int id:ids)values.put(vibratorValue(manager.getVibrator(id),id));
            }else{
                Vibrator vibrator=s.context.getSystemService(Vibrator.class);
                if(vibrator==null)return Json.obj("available",false,"count",0,"vibrators",values);
                values.put(vibratorValue(vibrator,0));
            }
            boolean hasVibrator=false;for(int i=0;i<values.length();i++){JSONObject value=values.optJSONObject(i);hasVibrator|=value!=null&&value.optBoolean("hasVibrator");}
            return Json.obj("available",true,"count",values.length(),"hasVibrator",hasVibrator,"hasAmplitudeControl",vibratorAmplitudeSummary(values),"vibrators",values);
        });
    }
    private static JSONObject vibratorValue(Vibrator vibrator,int id){
        JSONObject amplitude=vibratorAmplitudeControl(vibrator,Build.VERSION.SDK_INT);
        JSONObject value=Json.obj("id",id,"hasVibrator",vibrator.hasVibrator(),"hasAmplitudeControl",amplitude.opt("value"),"amplitudeControlStatus",amplitude.opt("status"));
        if(amplitude.has("errorClass"))Json.put(value,"amplitudeControlErrorClass",amplitude.opt("errorClass"));
        if(Build.VERSION.SDK_INT>=30)try{Json.put(value,"effectSupportCodes",vibrator.areEffectsSupported(VibrationEffect.EFFECT_CLICK,VibrationEffect.EFFECT_DOUBLE_CLICK,VibrationEffect.EFFECT_HEAVY_CLICK,VibrationEffect.EFFECT_TICK));}catch(Exception e){Json.put(value,"effectSupportCodes",null);Json.put(value,"effectSupportStatus","error");}
        Json.put(value,"frequencyCharacteristics",vibratorFrequencyCharacteristics(vibrator));
        return value;
    }
    // 业务块：API24/25不调用API26接口；查询失败保留未知，避免抹去存在性和其它能力。
    static JSONObject vibratorAmplitudeControl(Vibrator vibrator,int sdk){
        if(sdk<26)return Json.obj("status","unsupported","value",null);
        Objects.requireNonNull(vibrator,"missing vibrator");
        try{return Json.obj("status","ok","value",vibrator.hasAmplitudeControl());}
        catch(Exception|LinkageError e){return Json.obj("status",e instanceof SecurityException?"denied":"error","value",null,"errorClass",e.getClass().getName());}
    }
    // 业务块：最多32个振动器按三值OR汇总；任一true为true，仅全部明确false才是false。
    static Boolean vibratorAmplitudeSummary(JSONArray values){
        Objects.requireNonNull(values,"missing vibrators");
        if(values.length()>32)throw new IllegalArgumentException("振动器超过 32");
        boolean supported=false,unknown=false;
        for(int i=0;i<values.length();i++){
            JSONObject device=values.optJSONObject(i);Object value=device==null?null:device.opt("hasAmplitudeControl");
            if(Boolean.TRUE.equals(value))supported=true;
            else if(!Boolean.FALSE.equals(value))unknown=true;
        }
        if(supported)return true;
        return unknown?null:Boolean.FALSE;
    }
    // 业务块：只读两个公开频率特性；NaN按平台语义保留为未知，单项异常不丢失其它振动器能力。
    static JSONObject vibratorFrequencyCharacteristics(Vibrator vibrator){
        Objects.requireNonNull(vibrator,"missing vibrator");JSONObject out=new JSONObject();
        for(String name:new String[]{"resonantFrequencyHz","qFactor"}){
            JSONObject query=Json.obj("status",Build.VERSION.SDK_INT<34?"unsupported":"error","value",null);
            if(Build.VERSION.SDK_INT>=34)try{query=vibratorFrequencyValue(name.equals("resonantFrequencyHz")?vibrator.getResonantFrequency():vibrator.getQFactor());}
            catch(Exception|LinkageError e){Json.put(query,"status",e instanceof SecurityException?"denied":"error");Json.put(query,"errorClass",e.getClass().getName());}
            Json.put(out,name,query);
        }
        return out;
    }
    static JSONObject vibratorFrequencyValue(float value){
        return Json.obj("status",Float.isNaN(value)?"unknown":Float.isInfinite(value)?"non-finite":"ok","value",value);
    }
    // 业务块：只读取 UsbManager 已公开的设备描述符，不申请 USB 权限、不读取序列号。
    private void usb(ItemSink s){
        s.read("hardware","hardware/usb-summary","USB 能力摘要","UsbManager.getDeviceList / UsbDevice",()->usbSummary(s.context));
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
    // 业务块：压缩 USB 描述符为可比较摘要；不复制设备路径、序列号或接口逐项数据。
    private static JSONObject usbSummary(Context context){
        UsbManager manager=context.getSystemService(UsbManager.class);
        if(manager==null)return Json.obj("available",false,"deviceCount",null,"deviceCountTruncated",false,"interfaceCount",null,"endpointCount",null,"permissionQueryStatus","unavailable","permissionGrantedCount",null,"vendorIdCount",null,"productIdCount",null,"vendorIds",new JSONArray(),"productIds",new JSONArray(),"vendorIdsTruncated",false,"productIdsTruncated",false,"deviceClassCounts",new JSONObject(),"interfaceClassCounts",new JSONObject());
        Map<String,UsbDevice> devices=manager.getDeviceList();ArrayList<String> names=new ArrayList<>(devices.keySet());Collections.sort(names);TreeSet<Integer> vendors=new TreeSet<>(),products=new TreeSet<>();JSONObject deviceClasses=new JSONObject(),interfaceClasses=new JSONObject();int interfaces=0,endpoints=0,permissionGranted=0;String permissionStatus="allowed";int limit=Math.min(256,names.size());
        for(int i=0;i<limit;i++){UsbDevice device=devices.get(names.get(i));if(device==null)continue;vendors.add(device.getVendorId());products.add(device.getProductId());count(deviceClasses,String.valueOf(device.getDeviceClass()));try{if(manager.hasPermission(device))permissionGranted++;}catch(SecurityException e){permissionStatus="denied";}for(int j=0;j<device.getInterfaceCount();j++){UsbInterface face=device.getInterface(j);interfaces++;endpoints+=face.getEndpointCount();count(interfaceClasses,String.valueOf(face.getInterfaceClass()));}}
        JSONArray vendorValues=new JSONArray(),productValues=new JSONArray();for(Integer id:vendors)if(vendorValues.length()<128)vendorValues.put(id);for(Integer id:products)if(productValues.length()<128)productValues.put(id);
        return Json.obj("available",true,"deviceCount",devices.size(),"deviceCountTruncated",devices.size()>256,"interfaceCount",interfaces,"endpointCount",endpoints,"permissionQueryStatus",permissionStatus,"permissionGrantedCount",permissionGranted,"vendorIdCount",vendors.size(),"productIdCount",products.size(),"vendorIds",vendorValues,"productIds",productValues,"vendorIdsTruncated",vendors.size()>128,"productIdsTruncated",products.size()>128,"deviceClassCounts",deviceClasses,"interfaceClassCounts",interfaceClasses);
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
    // 业务块：枚举平台 DRM 方案并保留既有 Widevine 能力，绝不输出原始 DRM deviceUniqueId。
    private void drm(ItemSink s){
        s.read("hardware","hardware/drm-schemes","DRM 支持方案摘要","MediaDrm.getSupportedCryptoSchemes",()->{
            if(Build.VERSION.SDK_INT<30)throw new UnsupportedOperationException("requires API 30");
            return drmSchemeValues(MediaDrm.getSupportedCryptoSchemes());
        });
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
                        "maxSecurityLevel",Build.VERSION.SDK_INT>=28?MediaDrm.getMaxSecurityLevel():null,
                        "maxSecurityLevelKind",Build.VERSION.SDK_INT>=28?"request-sentinel":null,
                        "securityLevelProperty",drmSecurityLevelProperty(drm));
                try{
                    byte[] unique=drm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID);
                    Json.put(value,"deviceUniqueId",unique==null?null:Json.obj("sha256",PackageInputCollector.sha(unique),"length",unique.length,"rawExported",false));
                }catch(Exception e){Json.put(value,"deviceUniqueId",Json.obj("status","error","class",e.getClass().getName(),"rawExported",false));}
                return value;
            }finally{if(drm!=null)drm.release();}
        });
    }
    // 业务块：Widevine插件属性与请求用占位常量分开；读取失败不猜L1/L3，也不抹去原DRM元数据。
    static JSONObject drmSecurityLevelProperty(MediaDrm drm){
        Objects.requireNonNull(drm,"missing MediaDrm instance");
        JSONObject value=Json.obj("property","securityLevel","status","error","value",null);
        try{
            String level=drm.getPropertyString("securityLevel");
            if(level==null)throw new IllegalStateException("missing Widevine securityLevel property");
            Json.put(value,"value",level);Json.put(value,"status","ok");
        }catch(Exception|LinkageError e){Json.put(value,"status",e instanceof SecurityException?"denied":"error");Json.put(value,"errorClass",e.getClass().getName());}
        return value;
    }
    // 业务块：只枚举平台支持方案，保留重复与未知 UUID；完整排序集合参与哈希，不打开 DRM 会话。
    static JSONObject drmSchemeValues(List<UUID> schemes)throws Exception{
        if(schemes==null)throw new IllegalArgumentException("missing DRM scheme list");
        ArrayList<String> values=new ArrayList<>();
        for(UUID scheme:schemes){if(scheme==null)throw new IllegalArgumentException("null DRM scheme");values.add(scheme.toString());}
        Collections.sort(values);JSONArray complete=new JSONArray(values),returned=new JSONArray();
        for(int i=0;i<Math.min(32,values.size());i++)returned.put(values.get(i));
        return Json.obj("available",true,"status",values.size()>32?"partial":"ok","scope","platform-supported-crypto-schemes",
                "schemeCount",values.size(),"returnedCount",returned.length(),"schemeLimit",32,"truncated",values.size()>32,
                "schemeListSha256",PackageInputCollector.sha(complete.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),"schemeUuids",returned);
    }
    // 业务块：只查询当前连接摄像头的并发组合，不打开设备；服务缺失不能冒充有效空集合。
    static JSONObject cameraConcurrencySummary(Context context)throws Exception{
        if(Build.VERSION.SDK_INT<30)throw new UnsupportedOperationException("requires API 30");
        CameraManager manager=context.getSystemService(CameraManager.class);
        if(manager==null)return Json.obj("available",false,"status","unavailable","scope","currently-connected-camera-combinations",
                "combinationCount",null,"returnedCount",null,"combinationLimit",32,"cameraIdsPerCombinationLimit",16,
                "truncated",null,"combinationSetSha256",null,"combinations",null);
        return cameraConcurrencyValues(manager.getConcurrentCameraIds());
    }
    // 业务块：内外两层集合排序后哈希，截断外变化仍参与比较，保留原始ID及集合成员关系。
    static JSONObject cameraConcurrencyValues(Set<Set<String>> combinations)throws Exception{
        if(combinations==null)throw new IllegalArgumentException("missing camera combinations");
        ArrayList<JSONArray> sorted=new ArrayList<>();
        for(Set<String> combination:combinations){
            if(combination==null)throw new IllegalArgumentException("null camera combination");
            ArrayList<String> ids=new ArrayList<>();
            for(String id:combination){if(id==null)throw new IllegalArgumentException("null camera id");ids.add(id);}
            Collections.sort(ids);sorted.add(new JSONArray(ids));
        }
        sorted.sort(Comparator.comparing(JSONArray::toString));
        JSONArray complete=new JSONArray(sorted),entries=new JSONArray();boolean truncated=sorted.size()>32;
        for(int i=0;i<Math.min(32,sorted.size());i++){
            JSONArray ids=sorted.get(i),returned=new JSONArray();
            for(int j=0;j<Math.min(16,ids.length());j++)returned.put(ids.getString(j));
            boolean idsTruncated=ids.length()>16;truncated|=idsTruncated;
            entries.put(Json.obj("cameraCount",ids.length(),"cameraIds",returned,"idsTruncated",idsTruncated));
        }
        return Json.obj("available",true,"status",truncated?"partial":"ok","scope","currently-connected-camera-combinations",
                "combinationCount",sorted.size(),"returnedCount",entries.length(),"combinationLimit",32,"cameraIdsPerCombinationLimit",16,
                "truncated",truncated,"combinationSetSha256",PackageInputCollector.sha(complete.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),"combinations",entries);
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
        s.read("battery","battery/cycle-count","电池循环次数","ACTION_BATTERY_CHANGED EXTRA_CYCLE_COUNT",()->batteryCycleCount(s.context));
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
    // 业务块：只读取 Android 14+ 电池广播提供的循环次数，不把缺失值解释为零。
    private static JSONObject batteryCycleCount(Context context){
        if(Build.VERSION.SDK_INT<34)return Json.obj("available",false,"status","unsupported","cycleCount",null);
        Intent intent=context.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if(intent==null)return Json.obj("available",false,"status","unavailable","cycleCount",null);
        if(!intent.hasExtra(BatteryManager.EXTRA_CYCLE_COUNT))return Json.obj("available",true,"status","unavailable","cycleCount",null);
        int count=intent.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT,Integer.MIN_VALUE);
        return Json.obj("available",true,"status",count==Integer.MIN_VALUE||count<0?"unknown":"ok","cycleCount",count==Integer.MIN_VALUE||count<0?null:count);
    }
    private static Integer knownInt(int value){return value<0||value==Integer.MIN_VALUE?null:value;}
    private static Integer knownPropertyInt(int value){return value==Integer.MIN_VALUE?null:value;}
    private static Long knownLong(long value){return value==Long.MIN_VALUE?null:value;}
}
