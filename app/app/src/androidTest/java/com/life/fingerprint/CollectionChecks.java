package com.life.fingerprint;

import android.content.*;
import android.app.KeyguardManager;
import android.os.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.core.app.ActivityScenario;
import org.junit.*;
import org.junit.runner.RunWith;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import java.util.zip.GZIPInputStream;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class CollectionChecks {
    private Context context;
    private final ArrayList<CollectionController> controllers=new ArrayList<>();
    @Before public void setup(){context=InstrumentationRegistry.getInstrumentation().getTargetContext();}
    private void emitWifiStandardReport(String field,JSONObject item,boolean synthetic)throws Exception{
        android.content.pm.PackageInfo info=context.getPackageManager().getPackageInfo(context.getPackageName(),0);String now=CollectionController.now();
        JSONObject data=Json.obj("schemaVersion","1.0","collectorVersion",info.versionName,"sessionId",UUID.randomUUID().toString(),"startedAt",now,"finishedAt",now,"state","complete",
                "config",Json.obj("instrumentation",true,"fixture",synthetic,"scope",synthetic?"SYNTHETIC Wi-Fi标准查询夹具，非设备实测":"独立公开Wi-Fi标准API快照，非正式全量报告"),
                "permissions",CollectionController.permissions(context),"app",Json.obj("packageName",context.getPackageName(),"versionName",info.versionName,"androidSdk",Build.VERSION.SDK_INT),"items",new JSONArray().put(item));
        Bundle bundle=new Bundle();bundle.putString(field,Json.obj("data",data,"message",synthetic?"SYNTHETIC Wi-Fi标准报告":"独立公开Wi-Fi标准验证报告","code",0).toString());InstrumentationRegistry.getInstrumentation().sendStatus(0,bundle);
    }
    @Test public void wifiStandardQueriesKeepFalseAndIndependentFailures()throws Exception{
        int[] calls={0};JSONObject value=NetworkCollector.wifiStandardSupport(true,33,standard->{calls[0]++;switch(standard){case 1:return false;case 4:return true;case 5:throw new SecurityException("SYNTHETIC");case 6:throw new NoSuchMethodError("SYNTHETIC");case 7:throw new UnsupportedOperationException("SYNTHETIC");default:throw new IllegalStateException("SYNTHETIC");}});
        assertEquals(6,calls[0]);assertEquals(6,value.getInt("queryCount"));assertEquals(6,value.getInt("queryLimit"));assertTrue(value.getBoolean("managerAvailable"));assertEquals("chipset-supported-wifi-standards",value.getString("scope"));
        int[] standards={1,4,5,6,7,8};String[] statuses={"ok","ok","denied","unsupported","unsupported","error"};JSONArray entries=value.getJSONArray("entries");assertEquals(6,entries.length());
        for(int i=0;i<6;i++){JSONObject entry=entries.getJSONObject(i);assertEquals(standards[i],entry.getInt("standard"));assertEquals(statuses[i],entry.getString("status"));if(i<2){assertEquals(i==1,entry.getBoolean("supported"));assertFalse(entry.has("errorClass"));}else{assertTrue(entry.isNull("supported"));assertTrue(entry.has("errorClass"));}}
        ItemSink sink=new ItemSink(context,"SYNTHETIC-wifi-standards");sink.ok("network","network/wifi-standard-support","SYNTHETIC Wi-Fi芯片标准支持","collector","SYNTHETIC IntPredicate fixture",value);emitWifiStandardReport("wifiStandardSyntheticReport",sink.items.getJSONObject(0),true);
    }
    @Test public void wifiStandardQueriesGuardPerStandardApiAndMissingService()throws Exception{
        int[] standards={1,4,5,6,7,8},minimumApis={30,30,30,30,31,33};
        for(int sdk:new int[]{24,29,30,31,32,33,37}){
            ArrayList<Integer> called=new ArrayList<>();JSONObject value=NetworkCollector.wifiStandardSupport(true,sdk,standard->{called.add(standard);return false;});JSONArray entries=value.getJSONArray("entries");assertEquals(6,entries.length());ArrayList<Integer> expectedCalls=new ArrayList<>();
            for(int i=0;i<6;i++){JSONObject entry=entries.getJSONObject(i);assertEquals(standards[i],entry.getInt("standard"));if(sdk>=minimumApis[i]){expectedCalls.add(standards[i]);assertEquals("ok",entry.getString("status"));assertFalse(entry.getBoolean("supported"));}else{assertEquals("unsupported",entry.getString("status"));assertTrue(entry.isNull("supported"));}assertFalse(entry.has("errorClass"));}
            assertEquals(expectedCalls,called);assertEquals(called.size(),value.getInt("queryCount"));
            int[] absentCalls={0};JSONObject absent=NetworkCollector.wifiStandardSupport(false,sdk,standard->{absentCalls[0]++;throw new AssertionError("无服务不得查询");});assertFalse(absent.getBoolean("managerAvailable"));assertEquals(0,absentCalls[0]);assertEquals(0,absent.getInt("queryCount"));
            for(int i=0;i<6;i++){JSONObject entry=absent.getJSONArray("entries").getJSONObject(i);assertEquals(sdk<minimumApis[i]?"unsupported":"unavailable",entry.getString("status"));assertTrue(entry.isNull("supported"));}
        }
        assertEquals(0,NetworkCollector.wifiStandardSupport(true,29,null).getInt("queryCount"));assertEquals(0,NetworkCollector.wifiStandardSupport(false,37,null).getInt("queryCount"));try{NetworkCollector.wifiStandardSupport(true,30,null);fail("新API且有服务必须提供查询函数");}catch(NullPointerException expected){}
        JSONObject linkage=NetworkCollector.wifiStandardSupport(true,33,standard->{throw new UnsatisfiedLinkError("SYNTHETIC");});assertEquals(6,linkage.getInt("queryCount"));for(int i=0;i<6;i++){JSONObject entry=linkage.getJSONArray("entries").getJSONObject(i);assertEquals("error",entry.getString("status"));assertTrue(entry.isNull("supported"));assertEquals("java.lang.UnsatisfiedLinkError",entry.getString("errorClass"));}
    }
    @Test public void wifiStandardQueriesMatchPublicApiAndKeepLinkEvidence()throws Exception{
        ItemSink sink=new ItemSink(context,"wifi-standard-api");new NetworkCollector("network").collect(sink);JSONObject item=null;boolean old=false,summary=false;
        for(int i=0;i<sink.items.length();i++){JSONObject record=sink.items.getJSONObject(i);String key=record.getString("key");if(key.equals("network/wifi-standard-support"))item=record;if(key.equals("network/wifi-capabilities"))old=true;if(key.equals("network/wifi-summary"))summary=true;}
        assertNotNull(item);assertTrue(old);assertTrue(summary);assertEquals("ok",item.getString("status"));JSONObject value=item.getJSONObject("value");android.net.wifi.WifiManager wifi=context.getApplicationContext().getSystemService(android.net.wifi.WifiManager.class);assertEquals(wifi!=null,value.getBoolean("managerAvailable"));
        int[] standards={1,4,5,6,7,8},minimumApis={30,30,30,30,31,33};JSONArray entries=value.getJSONArray("entries");assertEquals(6,entries.length());assertEquals(6,value.getInt("queryLimit"));int queries=0;
        for(int i=0;i<6;i++){
            JSONObject entry=entries.getJSONObject(i);assertEquals(standards[i],entry.getInt("standard"));
            if(Build.VERSION.SDK_INT<minimumApis[i]||wifi==null){assertEquals(Build.VERSION.SDK_INT<minimumApis[i]?"unsupported":"unavailable",entry.getString("status"));assertTrue(entry.isNull("supported"));continue;}
            queries++;
            try{boolean supported=wifi.isWifiStandardSupported(standards[i]);assertEquals("ok",entry.getString("status"));assertEquals(supported,entry.getBoolean("supported"));}
            catch(Exception|LinkageError e){assertEquals(e instanceof SecurityException?"denied":e instanceof UnsupportedOperationException||e instanceof NoSuchMethodError?"unsupported":"error",entry.getString("status"));assertTrue(entry.isNull("supported"));assertEquals(e.getClass().getName(),entry.getString("errorClass"));}
        }
        assertEquals(queries,value.getInt("queryCount"));assertFalse(value.has("ssid"));assertFalse(value.has("bssid"));System.out.println("Wi-Fi public standard evidence="+value);emitWifiStandardReport("wifiStandardReport",item,false);
    }
    private void emitWidevineCapabilitiesReport(String field,JSONObject item,boolean synthetic)throws Exception{
        android.content.pm.PackageInfo info=context.getPackageManager().getPackageInfo(context.getPackageName(),0);String now=CollectionController.now();
        JSONObject data=Json.obj("schemaVersion","1.0","collectorVersion",info.versionName,"sessionId",UUID.randomUUID().toString(),"startedAt",now,"finishedAt",now,"state","complete",
                "config",Json.obj("instrumentation",true,"fixture",synthetic,"scope",synthetic?"SYNTHETIC Widevine查询夹具，非设备实测":"独立公开Widevine能力API快照，非正式全量报告"),
                "permissions",CollectionController.permissions(context),"app",Json.obj("packageName",context.getPackageName(),"versionName",info.versionName,"androidSdk",Build.VERSION.SDK_INT),"items",new JSONArray().put(item));
        Bundle bundle=new Bundle();bundle.putString(field,Json.obj("data",data,"message",synthetic?"SYNTHETIC Widevine能力报告":"独立公开Widevine能力验证报告","code",0).toString());InstrumentationRegistry.getInstrumentation().sendStatus(0,bundle);
    }
    @Test public void widevineCapabilitiesKeepFixedOrderAndIndependentFailures()throws Exception{
        int[] calls={0};JSONObject value=HardwareCollector.widevineCryptoCapabilities(29,(mime,level)->{
            calls[0]++;
            if(!mime.equals("video/mp4"))return true;
            switch(level){case 1:return false;case 2:throw new SecurityException("SYNTHETIC");case 3:throw new UnsupportedOperationException("SYNTHETIC");case 4:throw new NoSuchMethodError("SYNTHETIC");default:throw new IllegalStateException("SYNTHETIC");}
        });
        assertEquals(20,calls[0]);assertEquals(20,value.getInt("queryCount"));assertEquals(20,value.getInt("queryLimit"));assertEquals("container-security-level-support",value.getString("scope"));
        String[] mimes={"video/mp4","video/webm","audio/mp4","audio/webm"},statuses={"ok","denied","unsupported","unsupported","error"};JSONArray entries=value.getJSONArray("entries");assertEquals(20,entries.length());
        for(int i=0;i<20;i++){JSONObject entry=entries.getJSONObject(i);assertEquals(mimes[i/5],entry.getString("mimeType"));assertEquals(i%5+1,entry.getInt("securityLevel"));assertEquals(i<5?statuses[i]:"ok",entry.getString("status"));if(i==0){assertFalse(entry.getBoolean("supported"));assertFalse(entry.has("errorClass"));}else if(i<5){assertTrue(entry.isNull("supported"));assertTrue(entry.has("errorClass"));}else assertTrue(entry.getBoolean("supported"));}
        ItemSink sink=new ItemSink(context,"SYNTHETIC-widevine-capabilities");sink.ok("hardware","hardware/drm/widevine-capabilities","SYNTHETIC Widevine容器与安全级别","collector","SYNTHETIC BiPredicate fixture",value);emitWidevineCapabilitiesReport("widevineCapabilitiesSyntheticReport",sink.items.getJSONObject(0),true);
    }
    @Test public void widevineCapabilitiesGuardApiAndPreserveLinkageErrors()throws Exception{
        int[] calls={0};java.util.function.BiPredicate<String,Integer> never=(mime,level)->{calls[0]++;throw new AssertionError("旧API不得调用getter");};
        for(int sdk:new int[]{24,28}){
            for(JSONObject value:Arrays.asList(HardwareCollector.widevineCryptoCapabilities(sdk,never),HardwareCollector.widevineCryptoCapabilities(sdk,null))){assertEquals(0,value.getInt("queryCount"));assertEquals(20,value.getJSONArray("entries").length());for(int i=0;i<20;i++){JSONObject entry=value.getJSONArray("entries").getJSONObject(i);assertEquals("unsupported",entry.getString("status"));assertTrue(entry.isNull("supported"));assertFalse(entry.has("errorClass"));}}
        }
        assertEquals(0,calls[0]);try{HardwareCollector.widevineCryptoCapabilities(29,null);fail("新API必须有查询函数");}catch(NullPointerException expected){}
        for(int sdk:new int[]{29,Math.max(29,Build.VERSION.SDK_INT)}){
            JSONObject result=HardwareCollector.widevineCryptoCapabilities(sdk,(mime,level)->{if(level==1)throw new UnsatisfiedLinkError("SYNTHETIC");if(level==2)throw new IllegalArgumentException("SYNTHETIC");return false;});
            JSONArray entries=result.getJSONArray("entries");for(int i=0;i<20;i++){JSONObject entry=entries.getJSONObject(i);assertEquals(i%5<2?"error":"ok",entry.getString("status"));if(i%5<2)assertTrue(entry.isNull("supported"));else assertFalse(entry.getBoolean("supported"));}
        }
    }
    @Test public void widevineCapabilitiesMatchPublicApiAndKeepLegacyItems()throws Exception{
        ItemSink sink=new ItemSink(context,"widevine-capabilities-api");new HardwareCollector("hardware").collect(sink);JSONObject item=null;boolean old=false,schemes=false;
        for(int i=0;i<sink.items.length();i++){JSONObject record=sink.items.getJSONObject(i);String key=record.getString("key");if(key.equals("hardware/drm/widevine-capabilities"))item=record;if(key.equals("hardware/drm/widevine"))old=true;if(key.equals("hardware/drm-schemes"))schemes=true;}
        assertNotNull(item);assertTrue(old);assertTrue(schemes);assertEquals("ok",item.getString("status"));JSONObject value=item.getJSONObject("value");UUID uuid=UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed");assertEquals(uuid.toString(),value.getString("schemeUuid"));assertEquals(Build.VERSION.SDK_INT<29?0:20,value.getInt("queryCount"));
        String[] mimes={"video/mp4","video/webm","audio/mp4","audio/webm"};JSONArray entries=value.getJSONArray("entries");assertEquals(20,entries.length());
        for(int i=0;i<20;i++){
            JSONObject entry=entries.getJSONObject(i);String mime=mimes[i/5];int level=i%5+1;assertEquals(mime,entry.getString("mimeType"));assertEquals(level,entry.getInt("securityLevel"));
            if(Build.VERSION.SDK_INT<29){assertEquals("unsupported",entry.getString("status"));assertTrue(entry.isNull("supported"));continue;}
            try{boolean supported=android.media.MediaDrm.isCryptoSchemeSupported(uuid,mime,level);assertEquals("ok",entry.getString("status"));assertEquals(supported,entry.getBoolean("supported"));}
            catch(Exception|LinkageError e){assertEquals(e instanceof SecurityException?"denied":e instanceof UnsupportedOperationException||e instanceof NoSuchMethodError?"unsupported":"error",entry.getString("status"));assertEquals(e.getClass().getName(),entry.getString("errorClass"));assertTrue(entry.isNull("supported"));}
        }
        assertFalse(value.has("deviceUniqueId"));assertFalse(value.has("sessionId"));System.out.println("Widevine public capability evidence="+value);emitWidevineCapabilitiesReport("widevineCapabilitiesReport",item,false);
    }
    private void emitThermalThresholdReport(String field,JSONObject item,boolean synthetic)throws Exception{
        android.content.pm.PackageInfo info=context.getPackageManager().getPackageInfo(context.getPackageName(),0);String now=CollectionController.now();
        JSONObject data=Json.obj("schemaVersion","1.0","collectorVersion",info.versionName,"sessionId",UUID.randomUUID().toString(),"startedAt",now,"finishedAt",now,"state","complete","config",Json.obj("instrumentation",true,"fixture",synthetic,"scope",synthetic?"SYNTHETIC热余量阈值夹具，非设备实测":"独立公开热阈值API快照验证，非正式全量报告"),"permissions",CollectionController.permissions(context),"app",Json.obj("packageName",context.getPackageName(),"versionName",info.versionName,"androidSdk",Build.VERSION.SDK_INT),"items",new JSONArray().put(item));
        Bundle bundle=new Bundle();bundle.putString(field,Json.obj("data",data,"message",synthetic?"SYNTHETIC热阈值测试报告":"独立公开热阈值API验证报告","code",0).toString());InstrumentationRegistry.getInstrumentation().sendStatus(0,bundle);
    }
    @Test public void thermalThresholdValuesKeepRawKeysAndFloatingValues()throws Exception{
        LinkedHashMap<Integer,Float> input=new LinkedHashMap<>();input.put(999,Float.POSITIVE_INFINITY);input.put(3,1f);input.put(-1,-0.5f);input.put(2,Float.NaN);input.put(1,0f);input.put(6,null);
        JSONObject result=BuildSystemCollector.thermalHeadroomThresholdValues(input);JSONArray entries=result.getJSONArray("entries");assertEquals("ok",result.getString("status"));assertEquals(6,result.getInt("entryCount"));assertEquals(-1,entries.getJSONObject(0).getInt("thermalStatus"));assertEquals(-0.5,entries.getJSONObject(0).getDouble("headroom"),0);assertEquals(0,entries.getJSONObject(1).getDouble("headroom"),0);assertEquals("NaN",entries.getJSONObject(2).getJSONObject("headroom").getString("value"));assertTrue(entries.getJSONObject(4).isNull("headroom"));assertEquals("Infinity",entries.getJSONObject(5).getJSONObject("headroom").getString("value"));
        input.put(3,0f);assertEquals(1,entries.getJSONObject(3).getDouble("headroom"),0);TreeMap<Integer,Float> reversed=new TreeMap<>(Comparator.reverseOrder());reversed.putAll(input);assertEquals(BuildSystemCollector.thermalHeadroomThresholdValues(input).toString(),BuildSystemCollector.thermalHeadroomThresholdValues(reversed).toString());
        for(int count:new int[]{0,64,65}){HashMap<Integer,Float> many=new HashMap<>();for(int i=0;i<count;i++)many.put(i,i/10f);if(count>64){try{BuildSystemCollector.thermalHeadroomThresholdValues(many);fail("超过64项必须失败");}catch(IllegalArgumentException expected){}}else{JSONObject value=BuildSystemCollector.thermalHeadroomThresholdValues(many);assertEquals(count,value.getInt("entryCount"));assertEquals(count,value.getJSONArray("entries").length());}}
        try{BuildSystemCollector.thermalHeadroomThresholdValues(null);fail("null不能当空映射");}catch(IllegalArgumentException expected){}
        input.put(null,0f);try{BuildSystemCollector.thermalHeadroomThresholdValues(input);fail("null键必须失败");}catch(IllegalArgumentException expected){}input.remove(null);
        ItemSink sink=new ItemSink(context,"SYNTHETIC-thermal-thresholds");sink.ok("environment","environment/thermal-headroom-thresholds","SYNTHETIC热阈值","collector","SYNTHETIC public threshold map fixture",result);emitThermalThresholdReport("thermalThresholdSyntheticReport",sink.items.getJSONObject(0),true);
    }
    @Test public void thermalThresholdQueriesKeepVersionAndFailureStates()throws Exception{
        int[] calls={0};java.util.function.Supplier<Map<Integer,Float>> never=()->{calls[0]++;throw new AssertionError("旧API和缺服务不得调用getter");};
        for(int sdk:new int[]{24,30,34}){JSONObject value=BuildSystemCollector.thermalHeadroomThresholds(true,sdk,never);assertEquals("unsupported",value.getString("status"));assertTrue(value.isNull("managerAvailable"));assertTrue(value.isNull("entries"));}
        JSONObject absent=BuildSystemCollector.thermalHeadroomThresholds(false,35,never);assertEquals("unavailable",absent.getString("status"));assertFalse(absent.getBoolean("managerAvailable"));assertEquals(0,calls[0]);
        JSONObject empty=BuildSystemCollector.thermalHeadroomThresholds(true,35,Collections::emptyMap);assertEquals("ok",empty.getString("status"));assertEquals(0,empty.getInt("entryCount"));assertEquals("[]",empty.getJSONArray("entries").toString());
        for(Throwable error:new Throwable[]{new SecurityException("SYNTHETIC"),new UnsupportedOperationException("SYNTHETIC"),new NoSuchMethodError("SYNTHETIC"),new IllegalStateException("SYNTHETIC")}){
            JSONObject value=BuildSystemCollector.thermalHeadroomThresholds(true,35,()->{if(error instanceof Error)throw (Error)error;throw (RuntimeException)error;});assertEquals(error instanceof SecurityException?"denied":error instanceof UnsupportedOperationException||error instanceof NoSuchMethodError?"unsupported":"error",value.getString("status"));assertTrue(value.getBoolean("managerAvailable"));assertTrue(value.isNull("entries"));assertTrue(value.isNull("entryCount"));assertEquals(error.getClass().getName(),value.getString("errorClass"));
        }
        assertEquals("error",BuildSystemCollector.thermalHeadroomThresholds(true,35,()->null).getString("status"));
    }
    @Test public void thermalThresholdSinkMatchesIndependentPublicApi()throws Exception{
        ItemSink sink=new ItemSink(context,"thermal-threshold-api");new BuildSystemCollector("environment").collect(sink);JSONObject item=null;boolean old=false;
        for(int i=0;i<sink.items.length();i++){JSONObject record=sink.items.getJSONObject(i);if(record.getString("key").equals("environment/thermal-headroom-thresholds"))item=record;if(record.getString("key").equals("environment/power-thermal"))old=true;}
        assertNotNull(item);assertTrue(old);assertEquals("ok",item.getString("status"));JSONObject value=item.getJSONObject("value");
        if(Build.VERSION.SDK_INT<35){assertEquals("unsupported",value.getString("status"));assertTrue(value.isNull("entries"));}
        else {PowerManager power=context.getSystemService(PowerManager.class);assertEquals(power!=null,value.getBoolean("managerAvailable"));if(power==null){assertEquals("unavailable",value.getString("status"));assertTrue(value.isNull("entries"));}else{
            Map<Integer,Float> direct=null;Throwable failure=null;try{direct=power.getThermalHeadroomThresholds();}catch(Exception|LinkageError e){failure=e;}
            if(failure!=null){assertEquals(failure.getClass().getName(),value.getString("errorClass"));assertTrue(value.isNull("entries"));}else{
                assertEquals("ok",value.getString("status"));assertEquals(direct.size(),value.getInt("entryCount"));JSONArray entries=value.getJSONArray("entries");assertEquals(direct.size(),entries.length());int previous=Integer.MIN_VALUE;
                for(int i=0;i<entries.length();i++){JSONObject entry=entries.getJSONObject(i);int key=entry.getInt("thermalStatus");assertTrue(i==0||key>previous);previous=key;assertTrue(direct.containsKey(key));assertEquals(Json.value(direct.get(key)).toString(),entry.get("headroom").toString());}
            }
        }}
        System.out.println("Thermal threshold public API evidence="+value);emitThermalThresholdReport("thermalThresholdReport",item,false);
    }
    private void emitSensorPrivacyReport(String field,JSONObject item,boolean synthetic)throws Exception{
        android.content.pm.PackageInfo info=context.getPackageManager().getPackageInfo(context.getPackageName(),0);String now=CollectionController.now();
        JSONObject data=Json.obj("schemaVersion","1.0","collectorVersion",info.versionName,"sessionId",UUID.randomUUID().toString(),"startedAt",now,"finishedAt",now,"state","complete","config",Json.obj("instrumentation",true,"fixture",synthetic,"scope",synthetic?"SYNTHETIC隐私开关支持能力夹具，非设备状态":"独立隐私开关公开能力API验证，非正式全量报告"),"permissions",CollectionController.permissions(context),"app",Json.obj("packageName",context.getPackageName(),"versionName",info.versionName,"androidSdk",Build.VERSION.SDK_INT),"items",new JSONArray().put(item));
        Bundle bundle=new Bundle();bundle.putString(field,Json.obj("data",data,"message",synthetic?"SYNTHETIC隐私开关能力测试报告":"独立隐私开关公开能力API验证报告","code",0).toString());InstrumentationRegistry.getInstrumentation().sendStatus(0,bundle);
    }
    @Test public void sensorPrivacyQueriesKeepIndependentFalseAndFailures()throws Exception{
        JSONObject values=SecurityCollector.sensorPrivacyCapabilities(true,33,()->true,()->false,()->false,()->true);
        assertTrue(values.getBoolean("managerAvailable"));assertEquals(Boolean.TRUE,values.getJSONObject("microphone").getJSONObject("softwareToggle").get("value"));assertEquals(Boolean.FALSE,values.getJSONObject("microphone").getJSONObject("hardwareToggle").get("value"));assertEquals(Boolean.FALSE,values.getJSONObject("camera").getJSONObject("softwareToggle").get("value"));assertEquals(Boolean.TRUE,values.getJSONObject("camera").getJSONObject("hardwareToggle").get("value"));
        int[] calls={0};JSONObject mixed=SecurityCollector.sensorPrivacyCapabilities(true,33,()->{calls[0]++;throw new SecurityException("SYNTHETIC");},()->{calls[0]++;return false;},()->{calls[0]++;throw new IllegalStateException("SYNTHETIC");},()->{calls[0]++;return true;});assertEquals(4,calls[0]);
        JSONObject denied=mixed.getJSONObject("microphone").getJSONObject("softwareToggle"),failed=mixed.getJSONObject("camera").getJSONObject("softwareToggle");assertEquals("denied",denied.getString("status"));assertTrue(denied.isNull("value"));assertEquals(SecurityException.class.getName(),denied.getString("errorClass"));assertEquals("error",failed.getString("status"));assertTrue(failed.isNull("value"));assertEquals(IllegalStateException.class.getName(),failed.getString("errorClass"));assertEquals(Boolean.FALSE,mixed.getJSONObject("microphone").getJSONObject("hardwareToggle").get("value"));assertEquals(Boolean.TRUE,mixed.getJSONObject("camera").getJSONObject("hardwareToggle").get("value"));
        JSONObject errors=SecurityCollector.sensorPrivacyCapabilities(true,33,()->{throw new NoSuchMethodError();},()->{throw new UnsupportedOperationException();},()->{throw new LinkageError();},null);assertEquals("unsupported",errors.getJSONObject("microphone").getJSONObject("softwareToggle").getString("status"));assertEquals("unsupported",errors.getJSONObject("microphone").getJSONObject("hardwareToggle").getString("status"));assertEquals("error",errors.getJSONObject("camera").getJSONObject("softwareToggle").getString("status"));assertEquals(NullPointerException.class.getName(),errors.getJSONObject("camera").getJSONObject("hardwareToggle").getString("errorClass"));
        ItemSink sink=new ItemSink(context,"SYNTHETIC-sensor-privacy");sink.ok("security","security/sensor-privacy-capabilities","SYNTHETIC隐私开关支持能力","collector","SYNTHETIC BooleanSupplier fixture",mixed);emitSensorPrivacyReport("sensorPrivacySyntheticReport",sink.items.getJSONObject(0),true);
    }
    @Test public void sensorPrivacyVersionAndServiceBoundaries()throws Exception{
        int[] calls={0};java.util.function.BooleanSupplier never=()->{calls[0]++;throw new AssertionError("不应调用");};
        for(int sdk:new int[]{24,30}){JSONObject old=SecurityCollector.sensorPrivacyCapabilities(true,sdk,never,never,never,never);assertTrue(old.isNull("managerAvailable"));for(String sensor:new String[]{"microphone","camera"})for(String toggle:new String[]{"softwareToggle","hardwareToggle"}){JSONObject query=old.getJSONObject(sensor).getJSONObject(toggle);assertEquals("unsupported",query.getString("status"));assertTrue(query.isNull("value"));}}assertEquals(0,calls[0]);
        for(int sdk:new int[]{31,32}){int[] softwareCalls={0};JSONObject partial=SecurityCollector.sensorPrivacyCapabilities(true,sdk,()->{softwareCalls[0]++;return true;},never,()->{softwareCalls[0]++;return false;},never);assertEquals(2,softwareCalls[0]);assertEquals(Boolean.TRUE,partial.getJSONObject("microphone").getJSONObject("softwareToggle").get("value"));assertEquals(Boolean.FALSE,partial.getJSONObject("camera").getJSONObject("softwareToggle").get("value"));for(String sensor:new String[]{"microphone","camera"}){JSONObject hardware=partial.getJSONObject(sensor).getJSONObject("hardwareToggle");assertEquals("unsupported",hardware.getString("status"));assertTrue(hardware.isNull("value"));}}
        JSONObject missing=SecurityCollector.sensorPrivacyCapabilities(false,33,never,never,never,never);assertFalse(missing.getBoolean("managerAvailable"));for(String sensor:new String[]{"microphone","camera"})for(String toggle:new String[]{"softwareToggle","hardwareToggle"}){JSONObject query=missing.getJSONObject(sensor).getJSONObject(toggle);assertEquals("unavailable",query.getString("status"));assertTrue(query.isNull("value"));}assertEquals(0,calls[0]);
    }
    @Test public void sensorPrivacyCapabilitiesMatchPublicGetters()throws Exception{
        android.hardware.SensorPrivacyManager manager=Build.VERSION.SDK_INT<31?null:context.getSystemService(android.hardware.SensorPrivacyManager.class);
        ItemSink sink=new ItemSink(context,"sensor-privacy-api");new SecurityCollector("security").collect(sink);JSONObject item=null;boolean old=false;
        for(int i=0;i<sink.items.length();i++){JSONObject value=sink.items.getJSONObject(i);if(value.getString("key").equals("security/sensor-privacy-capabilities"))item=value;if(value.getString("key").equals("security/lock-biometric"))old=true;}assertNotNull(item);assertTrue(old);assertEquals("ok",item.getString("status"));JSONObject value=item.getJSONObject("value");if(Build.VERSION.SDK_INT<31)assertTrue(value.isNull("managerAvailable"));else assertEquals(manager!=null,value.getBoolean("managerAvailable"));
        java.util.function.BooleanSupplier[] queries={()->manager.supportsSensorToggle(android.hardware.SensorPrivacyManager.Sensors.MICROPHONE),()->manager.supportsSensorToggle(android.hardware.SensorPrivacyManager.TOGGLE_TYPE_HARDWARE,android.hardware.SensorPrivacyManager.Sensors.MICROPHONE),()->manager.supportsSensorToggle(android.hardware.SensorPrivacyManager.Sensors.CAMERA),()->manager.supportsSensorToggle(android.hardware.SensorPrivacyManager.TOGGLE_TYPE_HARDWARE,android.hardware.SensorPrivacyManager.Sensors.CAMERA)};
        for(int i=0;i<queries.length;i++){JSONObject actual=value.getJSONObject(i<2?"microphone":"camera").getJSONObject(i%2==0?"softwareToggle":"hardwareToggle");int since=i%2==0?31:33;if(Build.VERSION.SDK_INT<since||manager==null){assertEquals(Build.VERSION.SDK_INT<since?"unsupported":"unavailable",actual.getString("status"));assertTrue(actual.isNull("value"));continue;}try{boolean expected=queries[i].getAsBoolean();assertEquals("ok",actual.getString("status"));assertEquals(expected,actual.getBoolean("value"));}catch(Exception|LinkageError e){assertTrue(actual.isNull("value"));assertEquals(e.getClass().getName(),actual.getString("errorClass"));}}
        System.out.println("Sensor privacy public capability evidence="+value);emitSensorPrivacyReport("sensorPrivacyReport",item,false);
    }
    private void emitBluetoothLeAudioReport(String field,JSONObject item,boolean synthetic)throws Exception{
        android.content.pm.PackageInfo info=context.getPackageManager().getPackageInfo(context.getPackageName(),0);String now=CollectionController.now();
        JSONObject data=Json.obj("schemaVersion","1.0","collectorVersion",info.versionName,"sessionId",UUID.randomUUID().toString(),"startedAt",now,"finishedAt",now,"state","complete","config",Json.obj("instrumentation",true,"fixture",synthetic,"scope",synthetic?"SYNTHETIC LE Audio结果码夹具，非设备能力":"独立LE Audio公开API验证，非正式全量报告"),"permissions",CollectionController.permissions(context),"app",Json.obj("packageName",context.getPackageName(),"versionName",info.versionName,"androidSdk",Build.VERSION.SDK_INT),"items",new JSONArray().put(item));
        Bundle bundle=new Bundle();bundle.putString(field,Json.obj("data",data,"message",synthetic?"SYNTHETIC LE Audio测试报告":"独立LE Audio公开API验证报告","code",0).toString());InstrumentationRegistry.getInstrumentation().sendStatus(0,bundle);
    }
    @Test public void bluetoothLeAudioKeepsRawCodesAndIndependentFailures()throws Exception{
        for(int code:new int[]{10,11,1,0,30,Integer.MIN_VALUE,Integer.MAX_VALUE}){
            JSONObject value=NetworkCollector.bluetoothLeAudioDetails(true,33,()->code,()->code,()->code);
            for(String key:new String[]{"leAudio","broadcastSource","broadcastAssistant"}){assertEquals("ok",value.getJSONObject(key).getString("status"));assertEquals(code,value.getJSONObject(key).getInt("code"));assertTrue(value.getJSONObject(key).get("code") instanceof Integer);}
        }
        int[] calls={0};JSONObject value=NetworkCollector.bluetoothLeAudioDetails(true,33,()->{calls[0]++;throw new SecurityException("SYNTHETIC");},()->{calls[0]++;return 10;},()->{calls[0]++;return 11;});
        assertEquals(3,calls[0]);assertEquals("denied",value.getJSONObject("leAudio").getString("status"));assertTrue(value.getJSONObject("leAudio").isNull("code"));assertEquals(SecurityException.class.getName(),value.getJSONObject("leAudio").getString("errorClass"));assertEquals(10,value.getJSONObject("broadcastSource").getInt("code"));assertEquals(11,value.getJSONObject("broadcastAssistant").getInt("code"));
        JSONObject unsupported=NetworkCollector.bluetoothLeAudioDetails(true,33,()->{throw new UnsupportedOperationException();},()->{throw new NoSuchMethodError();},()->{throw new LinkageError();});
        assertEquals("unsupported",unsupported.getJSONObject("leAudio").getString("status"));assertEquals("unsupported",unsupported.getJSONObject("broadcastSource").getString("status"));assertEquals("error",unsupported.getJSONObject("broadcastAssistant").getString("status"));assertTrue(unsupported.getJSONObject("broadcastAssistant").isNull("code"));
        JSONObject fixture=NetworkCollector.bluetoothLeAudioDetails(true,33,()->10,()->1,()->{throw new IllegalStateException("SYNTHETIC");});ItemSink sink=new ItemSink(context,"SYNTHETIC-le-audio");sink.ok("network","network/bluetooth-le-audio","SYNTHETIC LE Audio能力结果码","collector","SYNTHETIC IntSupplier fixture",fixture);emitBluetoothLeAudioReport("bluetoothLeAudioSyntheticReport",sink.items.getJSONObject(0),true);
    }
    @Test public void bluetoothLeAudioSkipsOldApiAndAbsentAdapter()throws Exception{
        int[] calls={0};java.util.function.IntSupplier never=()->{calls[0]++;throw new AssertionError("不应调用");};
        for(boolean available:new boolean[]{true,false}){
            JSONObject old=NetworkCollector.bluetoothLeAudioDetails(available,32,never,never,never);assertEquals(available,old.getBoolean("adapterAvailable"));
            for(String key:new String[]{"leAudio","broadcastSource","broadcastAssistant"}){assertEquals("unsupported",old.getJSONObject(key).getString("status"));assertTrue(old.getJSONObject(key).isNull("code"));}
        }
        JSONObject missing=NetworkCollector.bluetoothLeAudioDetails(false,33,never,never,never);assertFalse(missing.getBoolean("adapterAvailable"));for(String key:new String[]{"leAudio","broadcastSource","broadcastAssistant"}){assertEquals("unavailable",missing.getJSONObject(key).getString("status"));assertTrue(missing.getJSONObject(key).isNull("code"));}assertEquals(0,calls[0]);
    }
    @Test public void bluetoothLeAudioMatchesPublicGetters()throws Exception{
        android.bluetooth.BluetoothManager manager=context.getSystemService(android.bluetooth.BluetoothManager.class);android.bluetooth.BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
        ItemSink sink=new ItemSink(context,"le-audio-api");new NetworkCollector("network").collect(sink);JSONObject item=null;boolean old=false,capabilities=false;
        for(int i=0;i<sink.items.length();i++){JSONObject value=sink.items.getJSONObject(i);String key=value.getString("key");if(key.equals("network/bluetooth-le-audio"))item=value;if(key.equals("network/bluetooth-summary"))old=true;if(key.equals("network/bluetooth-capabilities"))capabilities=true;}
        assertNotNull(item);assertTrue(old);assertTrue(capabilities);assertEquals("ok",item.getString("status"));JSONObject value=item.getJSONObject("value");assertEquals(adapter!=null,value.getBoolean("adapterAvailable"));
        String[] keys={"leAudio","broadcastSource","broadcastAssistant"};java.util.function.IntSupplier[] queries={()->adapter.isLeAudioSupported(),()->adapter.isLeAudioBroadcastSourceSupported(),()->adapter.isLeAudioBroadcastAssistantSupported()};
        for(int i=0;i<keys.length;i++){JSONObject actual=value.getJSONObject(keys[i]);if(Build.VERSION.SDK_INT<33||adapter==null){assertEquals(Build.VERSION.SDK_INT<33?"unsupported":"unavailable",actual.getString("status"));assertTrue(actual.isNull("code"));continue;}try{int code=queries[i].getAsInt();assertEquals("ok",actual.getString("status"));assertEquals(code,actual.getInt("code"));}catch(Exception|LinkageError e){assertTrue(actual.isNull("code"));assertEquals(e.getClass().getName(),actual.getString("errorClass"));}}
        System.out.println("LE Audio public evidence="+value);emitBluetoothLeAudioReport("bluetoothLeAudioReport",item,false);
    }
    private void emitNfcAntennaReport(String field,JSONObject item,boolean synthetic)throws Exception{
        android.content.pm.PackageInfo info=context.getPackageManager().getPackageInfo(context.getPackageName(),0);String now=CollectionController.now();
        JSONObject data=Json.obj("schemaVersion","1.0","collectorVersion",info.versionName,"sessionId",UUID.randomUUID().toString(),"startedAt",now,"finishedAt",now,"state","complete","config",Json.obj("instrumentation",true,"fixture",synthetic,"scope",synthetic?"SYNTHETIC NFC天线夹具，非真机天线位置":"独立NFC天线API验证，非正式全量报告"),"permissions",CollectionController.permissions(context),"app",Json.obj("packageName",context.getPackageName(),"versionName",info.versionName,"androidSdk",Build.VERSION.SDK_INT),"items",new JSONArray().put(item));
        Bundle bundle=new Bundle();bundle.putString(field,Json.obj("data",data,"message",synthetic?"SYNTHETIC NFC天线测试报告":"独立NFC天线API验证报告","code",0).toString());InstrumentationRegistry.getInstrumentation().sendStatus(0,bundle);
    }
    @Test public void nfcAntennaValuesKeepRawOrderBoundsAndHash()throws Exception{
        android.nfc.AvailableNfcAntenna a=new android.nfc.AvailableNfcAntenna(0,-1),b=new android.nfc.AvailableNfcAntenna(Integer.MIN_VALUE,Integer.MAX_VALUE);
        ArrayList<android.nfc.AvailableNfcAntenna> list=new ArrayList<>(Arrays.asList(a,b,a));
        android.nfc.NfcAntennaInfo info=new android.nfc.NfcAntennaInfo(-1,0,true,list);JSONObject value=HardwareCollector.nfcAntennaValues(info);JSONArray all=value.getJSONArray("antennas");assertEquals(-1,value.getInt("deviceWidthMm"));assertEquals(0,value.getInt("deviceHeightMm"));assertTrue(value.getBoolean("deviceFoldable"));assertEquals(3,all.length());assertEquals(0,all.getJSONObject(0).getInt("locationXmm"));assertEquals(-1,all.getJSONObject(0).getInt("locationYmm"));assertEquals(Integer.MIN_VALUE,all.getJSONObject(1).getInt("locationXmm"));assertEquals(Integer.MAX_VALUE,all.getJSONObject(1).getInt("locationYmm"));assertEquals(all.getJSONObject(0).toString(),all.getJSONObject(2).toString());assertEquals(PackageInputCollector.sha(all.toString().getBytes(StandardCharsets.UTF_8)),value.getString("antennasSha256"));list.set(0,b);assertEquals(0,all.getJSONObject(0).getInt("locationXmm"));
        for(int count:new int[]{0,64,65,256}){ArrayList<android.nfc.AvailableNfcAntenna> many=new ArrayList<>(Collections.nCopies(count,a));JSONObject original=HardwareCollector.nfcAntennaValues(new android.nfc.NfcAntennaInfo(70,150,false,many));assertEquals(count,original.getInt("antennaCount"));assertEquals(Math.min(64,count),original.getInt("returnedCount"));assertEquals(count>64,original.getBoolean("antennasTruncated"));assertEquals(count>64?"partial":"ok",original.getString("status"));if(count>64){many.set(count-1,b);JSONObject changed=HardwareCollector.nfcAntennaValues(new android.nfc.NfcAntennaInfo(70,150,false,many));assertEquals(original.getJSONArray("antennas").toString(),changed.getJSONArray("antennas").toString());assertNotEquals(original.getString("antennasSha256"),changed.getString("antennasSha256"));}}
        for(List<android.nfc.AvailableNfcAntenna> invalid:Arrays.asList(Collections.<android.nfc.AvailableNfcAntenna>singletonList(null),Collections.nCopies(257,a))){try{HardwareCollector.nfcAntennaValues(new android.nfc.NfcAntennaInfo(1,1,false,invalid));fail("非法清单必须失败");}catch(IllegalArgumentException|NullPointerException expected){}}
        try{HardwareCollector.nfcAntennaValues(new android.nfc.NfcAntennaInfo(1,1,false,null));fail("null不是空集合");}catch(NullPointerException expected){}
        ItemSink sink=new ItemSink(context,"SYNTHETIC-nfc-antennas");sink.ok("hardware","hardware/nfc-antennas","SYNTHETIC NFC天线","collector","SYNTHETIC public NfcAntennaInfo fixture",HardwareCollector.nfcAntennaValues(new android.nfc.NfcAntennaInfo(70,150,false,Collections.nCopies(65,a))));emitNfcAntennaReport("nfcAntennaSyntheticReport",sink.items.getJSONObject(0),true);
    }
    @Test public void nfcAntennaQueryKeepsMissingAndFailureDistinct()throws Exception{
        int[] calls={0};java.util.function.Supplier<android.nfc.NfcAntennaInfo> never=()->{calls[0]++;throw new AssertionError("不应调用");};
        JSONObject old=HardwareCollector.nfcAntennaDetails(true,33,never),missing=HardwareCollector.nfcAntennaDetails(false,34,never);assertEquals("unsupported",old.getString("status"));assertEquals("unavailable",missing.getString("status"));assertEquals(0,calls[0]);assertTrue(old.isNull("antennas"));assertTrue(missing.isNull("antennaCount"));
        JSONObject absent=HardwareCollector.nfcAntennaDetails(true,34,()->null);assertEquals("not-provided",absent.getString("status"));assertFalse(absent.getBoolean("infoAvailable"));assertTrue(absent.isNull("antennas"));assertTrue(absent.isNull("antennasSha256"));
        JSONObject empty=HardwareCollector.nfcAntennaDetails(true,34,()->new android.nfc.NfcAntennaInfo(0,0,false,Collections.emptyList()));assertEquals("ok",empty.getString("status"));assertEquals("[]",empty.getJSONArray("antennas").toString());assertTrue(empty.getBoolean("infoAvailable"));
        assertEquals("denied",HardwareCollector.nfcAntennaDetails(true,34,()->{throw new SecurityException("SYNTHETIC");}).getString("status"));assertEquals("unsupported",HardwareCollector.nfcAntennaDetails(true,34,()->{throw new NoSuchMethodError("SYNTHETIC");}).getString("status"));JSONObject failed=HardwareCollector.nfcAntennaDetails(true,34,()->{throw new IllegalStateException("SYNTHETIC");});assertEquals("error",failed.getString("status"));assertTrue(failed.isNull("infoAvailable"));assertEquals(IllegalStateException.class.getName(),failed.getString("errorClass"));
    }
    @Test public void nfcAntennaDetailsMatchPublicGetters()throws Exception{
        android.nfc.NfcAdapter adapter=android.nfc.NfcAdapter.getDefaultAdapter(context);JSONObject expected=HardwareCollector.nfcAntennaDetails(adapter!=null,Build.VERSION.SDK_INT,()->adapter.getNfcAntennaInfo());
        ItemSink sink=new ItemSink(context,"nfc-antenna-api");new HardwareCollector("hardware").collect(sink);JSONObject item=null;boolean old=false,observe=false;for(int i=0;i<sink.items.length();i++){JSONObject value=sink.items.getJSONObject(i);if(value.getString("key").equals("hardware/nfc-antennas"))item=value;if(value.getString("key").equals("hardware/nfc"))old=true;if(value.getString("key").equals("hardware/nfc-observe-mode"))observe=true;}
        assertNotNull(item);assertTrue(old);assertTrue(observe);assertEquals("ok",item.getString("status"));assertEquals(expected.toString(),item.getJSONObject("value").toString());System.out.println("NFC antenna public evidence="+expected);emitNfcAntennaReport("nfcAntennaReport",item,false);
    }
    @Test public void nfcObserveModeKeepsIndependentBooleanResults()throws Exception{
        JSONObject value=HardwareCollector.nfcObserveMode(true,35,()->false,()->true);
        assertEquals("ok",value.getJSONObject("supported").getString("status"));assertEquals(Boolean.FALSE,value.getJSONObject("supported").get("value"));assertEquals(Boolean.TRUE,value.getJSONObject("enabled").get("value"));
        value=HardwareCollector.nfcObserveMode(true,36,()->{throw new SecurityException("SYNTHETIC");},()->false);
        assertEquals("denied",value.getJSONObject("supported").getString("status"));assertTrue(value.getJSONObject("supported").isNull("value"));assertEquals("ok",value.getJSONObject("enabled").getString("status"));assertEquals(Boolean.FALSE,value.getJSONObject("enabled").get("value"));
        value=HardwareCollector.nfcObserveMode(true,35,()->true,()->{throw new NoSuchMethodError("SYNTHETIC");});
        assertEquals(Boolean.TRUE,value.getJSONObject("supported").get("value"));assertEquals("error",value.getJSONObject("enabled").getString("status"));assertEquals(NoSuchMethodError.class.getName(),value.getJSONObject("enabled").getString("errorClass"));
    }
    @Test public void nfcObserveModeSkipsUnsupportedOrMissingAdapter()throws Exception{
        int[] calls={0};java.util.function.BooleanSupplier query=()->{calls[0]++;throw new AssertionError("不应调用");};
        for(int sdk:new int[]{24,34}){JSONObject value=HardwareCollector.nfcObserveMode(true,sdk,query,query);for(String field:new String[]{"supported","enabled"}){assertEquals("unsupported",value.getJSONObject(field).getString("status"));assertTrue(value.getJSONObject(field).isNull("value"));}}
        JSONObject missing=HardwareCollector.nfcObserveMode(false,35,query,query);assertFalse(missing.getBoolean("available"));for(String field:new String[]{"supported","enabled"}){assertEquals("unavailable",missing.getJSONObject(field).getString("status"));assertTrue(missing.getJSONObject(field).isNull("value"));}assertEquals(0,calls[0]);
    }
    @Test public void nfcObserveModeMatchesPublicGetters()throws Exception{
        android.nfc.NfcAdapter adapter=android.nfc.NfcAdapter.getDefaultAdapter(context);
        JSONObject expected=HardwareCollector.nfcObserveMode(adapter!=null,Build.VERSION.SDK_INT,()->adapter.isObserveModeSupported(),()->adapter.isObserveModeEnabled());
        ItemSink sink=new ItemSink(context,"nfc-observe-api");new HardwareCollector("hardware").collect(sink);JSONObject item=null;boolean old=false;
        for(int i=0;i<sink.items.length();i++){JSONObject current=sink.items.getJSONObject(i);if(current.getString("key").equals("hardware/nfc-observe-mode"))item=current;if(current.getString("key").equals("hardware/nfc"))old=true;}
        assertNotNull(item);assertTrue(old);assertEquals("ok",item.getString("status"));assertEquals(expected.toString(),item.getJSONObject("value").toString());
        System.out.println("NFC observe public evidence="+expected);
        android.content.pm.PackageInfo info=context.getPackageManager().getPackageInfo(context.getPackageName(),0);String now=CollectionController.now();
        JSONObject data=Json.obj("schemaVersion","1.0","collectorVersion",info.versionName,"sessionId",UUID.randomUUID().toString(),"startedAt",now,"finishedAt",now,"state","complete","config",Json.obj("instrumentation",true,"scope","独立NFC Observe Mode API验证，非正式全量采集"),"permissions",CollectionController.permissions(context),"app",Json.obj("packageName",context.getPackageName(),"versionName",info.versionName,"androidSdk",Build.VERSION.SDK_INT),"items",new JSONArray().put(item));
        Bundle bundle=new Bundle();bundle.putString("nfcObserveReport",Json.obj("data",data,"message","独立NFC Observe Mode API验证报告","code",0).toString());InstrumentationRegistry.getInstrumentation().sendStatus(0,bundle);
    }
    private static android.hardware.usb.UsbEndpoint endpointFixture(int address,int attributes,int packet,int interval){
        Parcel parcel=Parcel.obtain();try{parcel.writeInt(address);parcel.writeInt(attributes);parcel.writeInt(packet);parcel.writeInt(interval);parcel.setDataPosition(0);return android.hardware.usb.UsbEndpoint.CREATOR.createFromParcel(parcel);}finally{parcel.recycle();}
    }
    private static android.hardware.usb.UsbInterface interfaceFixture(android.hardware.usb.UsbEndpoint[] endpoints){
        Parcel parcel=Parcel.obtain();try{parcel.writeInt(0);parcel.writeInt(0);parcel.writeString("SYNTHETIC");parcel.writeInt(255);parcel.writeInt(0);parcel.writeInt(0);parcel.writeParcelableArray(endpoints,0);parcel.setDataPosition(0);return android.hardware.usb.UsbInterface.CREATOR.createFromParcel(parcel);}finally{parcel.recycle();}
    }
    private void emitUsbReport(String field,JSONObject item,boolean synthetic)throws Exception{
        android.content.pm.PackageInfo info=context.getPackageManager().getPackageInfo(context.getPackageName(),0);String now=CollectionController.now();
        JSONObject data=Json.obj("schemaVersion","1.0","collectorVersion",info.versionName,"sessionId",UUID.randomUUID().toString(),"startedAt",now,"finishedAt",now,"state","complete",
            "config",Json.obj("instrumentation",true,"fixture",synthetic,"scope",synthetic?"SYNTHETIC Parcelable USB端点夹具，非真机USB外设":"独立USB描述符API验证，非正式全量采集"),"permissions",CollectionController.permissions(context),
            "app",Json.obj("packageName",context.getPackageName(),"versionName",info.versionName,"androidSdk",Build.VERSION.SDK_INT,"targetSdk",context.getApplicationInfo().targetSdkVersion),"items",new JSONArray().put(item));
        Bundle bundle=new Bundle();bundle.putString(field,Json.obj("data",data,"message",synthetic?"SYNTHETIC USB端点测试报告":"独立USB API验证报告","code",0).toString());InstrumentationRegistry.getInstrumentation().sendStatus(0,bundle);
    }
    @Test public void usbEndpointValuesKeepOrderBoundsAndRawFields()throws Exception{
        android.hardware.usb.UsbEndpoint first=endpointFixture(129,254,512,0),other=endpointFixture(Integer.MIN_VALUE,Integer.MAX_VALUE,-1,Integer.MIN_VALUE);
        ArrayList<android.hardware.usb.UsbEndpoint> input=new ArrayList<>(Arrays.asList(first,other,first));JSONObject actual=HardwareCollector.usbEndpointValues(input);JSONArray values=actual.getJSONArray("endpoints");assertEquals(3,values.length());
        assertEquals(129,values.getJSONObject(0).getInt("address"));assertEquals(1,values.getJSONObject(0).getInt("endpointNumber"));assertEquals(128,values.getJSONObject(0).getInt("direction"));assertEquals(254,values.getJSONObject(0).getInt("attributes"));assertEquals(2,values.getJSONObject(0).getInt("type"));assertEquals(512,values.getJSONObject(0).getInt("maxPacketSize"));assertEquals(0,values.getJSONObject(0).getInt("interval"));
        assertEquals(Integer.MIN_VALUE,values.getJSONObject(1).getInt("address"));assertEquals(Integer.MAX_VALUE,values.getJSONObject(1).getInt("attributes"));assertEquals(-1,values.getJSONObject(1).getInt("maxPacketSize"));assertEquals(Integer.MIN_VALUE,values.getJSONObject(1).getInt("interval"));assertEquals(values.getJSONObject(0).toString(),values.getJSONObject(2).toString());
        assertEquals(PackageInputCollector.sha(values.toString().getBytes(StandardCharsets.UTF_8)),actual.getString("endpointsSha256"));input.set(0,other);assertEquals(129,values.getJSONObject(0).getInt("address"));
        JSONObject empty=HardwareCollector.usbEndpointValues(Collections.emptyList());assertEquals("ok",empty.getString("status"));assertEquals("[]",empty.getJSONArray("endpoints").toString());assertEquals(PackageInputCollector.sha("[]".getBytes(StandardCharsets.UTF_8)),empty.getString("endpointsSha256"));
        for(int count:new int[]{64,65,256}){ArrayList<android.hardware.usb.UsbEndpoint> many=new ArrayList<>(Collections.nCopies(count,first));JSONObject bounded=HardwareCollector.usbEndpointValues(many);assertEquals(count,bounded.getInt("endpointCount"));assertEquals(Math.min(64,count),bounded.getInt("returnedCount"));assertEquals(count>64,bounded.getBoolean("endpointsTruncated"));assertEquals(count>64?"partial":"ok",bounded.getString("status"));if(count>64){many.set(count-1,other);JSONObject changed=HardwareCollector.usbEndpointValues(many);assertEquals(bounded.getJSONArray("endpoints").toString(),changed.getJSONArray("endpoints").toString());assertNotEquals(bounded.getString("endpointsSha256"),changed.getString("endpointsSha256"));}}
        try{HardwareCollector.usbEndpointValues(Collections.nCopies(257,first));fail("最多256输入");}catch(IllegalArgumentException expected){}try{HardwareCollector.usbEndpointValues(null);fail("缺失不是空集合");}catch(NullPointerException expected){}try{HardwareCollector.usbEndpointValues(Collections.singletonList(null));fail("缺失端点");}catch(NullPointerException expected){}
        ItemSink sink=new ItemSink(context,"SYNTHETIC-usb-endpoints");sink.ok("hardware","hardware/usb","合成USB端点夹具","collector","SYNTHETIC Parcelable UsbEndpoint fixture",Json.obj("count",1,"devices",new JSONArray().put(Json.obj("deviceName","SYNTHETIC_USB_DEVICE","vendorId",18,"productId",52,"interfaces",new JSONArray().put(Json.obj("id",0,"endpointCount",65,"endpointDescriptors",HardwareCollector.usbEndpointValues(Collections.nCopies(65,first))))))));emitUsbReport("usbEndpointSyntheticReport",sink.items.getJSONObject(0),true);
    }
    @Test public void usbEndpointBudgetsKeepFailureAndEmptyDistinct()throws Exception{
        android.hardware.usb.UsbEndpoint endpoint=endpointFixture(1,3,64,1);android.hardware.usb.UsbInterface face=interfaceFixture(new android.hardware.usb.UsbEndpoint[]{endpoint,endpoint});int[] remaining={2};
        assertEquals("ok",HardwareCollector.usbEndpoints(face,remaining).getString("status"));assertEquals(0,remaining[0]);JSONObject skipped=HardwareCollector.usbEndpoints(face,remaining);assertEquals("skipped",skipped.getString("status"));assertEquals(2,skipped.getInt("endpointCount"));assertTrue(skipped.isNull("endpoints"));assertTrue(skipped.isNull("endpointsSha256"));assertEquals("snapshot-endpoint-budget",skipped.getString("reason"));
        assertEquals("ok",HardwareCollector.usbEndpoints(interfaceFixture(new android.hardware.usb.UsbEndpoint[0]),remaining).getString("status"));
        for(int[] invalid:new int[][]{null,{}, {-1},{4097}}){JSONObject failed=HardwareCollector.usbEndpoints(face,invalid);assertEquals("error",failed.getString("status"));assertTrue(failed.isNull("endpoints"));}
        assertEquals("error",HardwareCollector.usbEndpoints(null,new int[]{4096}).getString("status"));
        android.hardware.usb.UsbEndpoint[] tooMany=new android.hardware.usb.UsbEndpoint[257];Arrays.fill(tooMany,endpoint);assertEquals("error",HardwareCollector.usbEndpoints(interfaceFixture(tooMany),new int[]{4096}).getString("status"));
    }
    @Test public void usbEndpointsMatchVisiblePublicDescriptors()throws Exception{
        android.hardware.usb.UsbManager manager=context.getSystemService(android.hardware.usb.UsbManager.class);assertNotNull(manager);Map<String,android.hardware.usb.UsbDevice> devices=manager.getDeviceList();
        ItemSink sink=new ItemSink(context,"usb-endpoint-api");new HardwareCollector("hardware").collect(sink);JSONObject item=null;boolean summary=false;for(int i=0;i<sink.items.length();i++){JSONObject value=sink.items.getJSONObject(i);if(value.getString("key").equals("hardware/usb"))item=value;if(value.getString("key").equals("hardware/usb-summary"))summary=true;}assertNotNull(item);assertTrue(summary);assertEquals("ok",item.getString("status"));JSONArray stored=item.getJSONObject("value").getJSONArray("devices");assertEquals(devices.size(),stored.length());int interfaces=0,endpoints=0;
        for(int i=0;i<stored.length();i++){JSONObject value=stored.getJSONObject(i);android.hardware.usb.UsbDevice device=devices.get(value.getString("deviceName"));assertNotNull(device);JSONArray faces=value.getJSONArray("interfaces");assertEquals(device.getInterfaceCount(),faces.length());for(int j=0;j<faces.length();j++){android.hardware.usb.UsbInterface face=device.getInterface(j);JSONObject descriptors=faces.getJSONObject(j).getJSONObject("endpointDescriptors");ArrayList<android.hardware.usb.UsbEndpoint> list=new ArrayList<>();for(int k=0;k<face.getEndpointCount();k++)list.add(face.getEndpoint(k));assertEquals(HardwareCollector.usbEndpointValues(list).toString(),descriptors.toString());interfaces++;endpoints+=list.size();}}
        System.out.println("USB visible devices="+devices.size()+", interfaces="+interfaces+", endpoints="+endpoints);emitUsbReport("usbEndpointApiReport",item,false);
    }
    @Test public void audioPropertiesKeepRawStringsAndNull()throws Exception{
        String[] values={" 0048000 ","","false",null,"<img src=x onerror=alert(1)>"};int[] calls={0};
        ItemSink sink=new ItemSink(context,"audio-property-values");HardwareCollector.audioProperties(sink,key->values[calls[0]++]);assertEquals(5,calls[0]);assertEquals(5,sink.items.length());
        for(int i=0;i<values.length;i++){JSONObject item=sink.items.getJSONObject(i);assertEquals("ok",item.getString("status"));assertEquals(values[i]==null?"null":"string",item.getString("dataType"));if(values[i]==null)assertTrue(item.isNull("value"));else assertEquals(values[i],item.getString("value"));}
    }
    @Test public void audioPropertyFailuresAreIsolatedAndBounded()throws Exception{
        ItemSink sink=new ItemSink(context,"audio-property-errors");int[] calls={0};String maximum=new String(new char[2048]).replace('\0','x');
        HardwareCollector.audioProperties(sink,key->{int index=calls[0]++;if(index==0)throw new SecurityException("synthetic");if(index==1)throw new NoSuchMethodError("synthetic");if(index==2)throw new IllegalStateException("synthetic");return index==3?maximum+"x":maximum;});
        assertEquals(5,calls[0]);String[] states={"denied","unsupported","error","error","ok"},classes={"java.lang.SecurityException","java.lang.NoSuchMethodError","java.lang.IllegalStateException","java.lang.IllegalArgumentException"};
        for(int i=0;i<5;i++){JSONObject item=sink.items.getJSONObject(i);assertEquals(states[i],item.getString("status"));if(i<4){assertTrue(item.isNull("value"));assertEquals(classes[i],item.getJSONObject("error").getString("class"));}else assertEquals(maximum,item.getString("value"));}
        ItemSink missing=new ItemSink(context,"audio-property-unavailable");HardwareCollector.audioProperties(missing,null);assertEquals(5,missing.items.length());for(int i=0;i<5;i++){assertEquals("error",missing.items.getJSONObject(i).getString("status"));assertTrue(missing.items.getJSONObject(i).isNull("value"));}
    }
    @Test public void audioPropertiesMatchPublicApiAndKeepExistingHardware()throws Exception{
        android.media.AudioManager manager=context.getSystemService(android.media.AudioManager.class);assertNotNull(manager);String started=CollectionController.now();
        String[] ids={"output-sample-rate","output-frames-per-buffer","mic-near-ultrasound","speaker-near-ultrasound","source-unprocessed"};
        String[] keys={android.media.AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE,android.media.AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER,android.media.AudioManager.PROPERTY_SUPPORT_MIC_NEAR_ULTRASOUND,android.media.AudioManager.PROPERTY_SUPPORT_SPEAKER_NEAR_ULTRASOUND,android.media.AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED};
        ItemSink sink=new ItemSink(context,"audio-property-api");new HardwareCollector("hardware").collect(sink);JSONArray evidence=new JSONArray();boolean audio=false,summary=false;
        for(int i=0;i<ids.length;i++){JSONObject actual=null;for(int j=0;j<sink.items.length();j++){JSONObject item=sink.items.getJSONObject(j);if(item.getString("key").equals("hardware/audio-property/"+ids[i])){assertNull("属性key必须唯一",actual);actual=item;}if(item.getString("key").equals("hardware/audio"))audio=true;if(item.getString("key").equals("hardware/audio-summary"))summary=true;}assertNotNull(actual);assertEquals("ok",actual.getString("status"));assertEquals("AudioManager.getProperty("+keys[i]+")",actual.getJSONObject("source").getString("api"));String value=manager.getProperty(keys[i]);if(value==null)assertTrue(actual.isNull("value"));else assertEquals(value,actual.getString("value"));evidence.put(actual);}
        assertTrue(audio);assertTrue(summary);
        android.content.pm.PackageInfo info=context.getPackageManager().getPackageInfo(context.getPackageName(),0);
        JSONObject data=Json.obj("schemaVersion","1.0","collectorVersion",info.versionName,"sessionId",UUID.randomUUID().toString(),"startedAt",started,"finishedAt",CollectionController.now(),"state","complete",
            "config",Json.obj("profile","audio-property-api-check","instrumentation",true,"scope","仅五项公开音频属性，非正式全量采集"),"permissions",CollectionController.permissions(context),
            "app",Json.obj("packageName",context.getPackageName(),"versionName",info.versionName,"versionCode",info.versionCode,"androidSdk",Build.VERSION.SDK_INT,"targetSdk",context.getApplicationInfo().targetSdkVersion,"uid",android.os.Process.myUid()),"items",evidence);
        Bundle bundle=new Bundle();bundle.putString("audioPropertyReport",Json.obj("data",data,"message","独立API instrumentation验证报告，非正式全量采集","code",0).toString());InstrumentationRegistry.getInstrumentation().sendStatus(0,bundle);
    }
    @Test public void microphoneSentinelsAndResponseBounds()throws Exception{
        assertEquals("unknown-sentinel",HardwareCollector.microphoneFloatValue(-Float.MAX_VALUE).getString("kind"));
        for(float value:new float[]{0f,-0f,-99.5f,Float.MAX_VALUE}){JSONObject actual=HardwareCollector.microphoneFloatValue(value);assertEquals("finite",actual.getString("kind"));assertEquals((double)value,actual.getDouble("raw"),0d);}
        for(float value:new float[]{Float.NaN,Float.NEGATIVE_INFINITY,Float.POSITIVE_INFINITY}){JSONObject actual=HardwareCollector.microphoneFloatValue(value);assertEquals("non-finite",actual.getString("kind"));assertEquals("float",actual.getJSONObject("raw").getString("$type"));assertEquals(String.valueOf(value),actual.getJSONObject("raw").getString("value"));}
        assertEquals("unknown-sentinel",HardwareCollector.microphoneCoordinateValue(-Float.MAX_VALUE,-Float.MAX_VALUE,-Float.MAX_VALUE,true).getString("kind"));
        assertEquals("finite",HardwareCollector.microphoneCoordinateValue(0f,0f,0f,true).getString("kind"));assertEquals("unknown-sentinel",HardwareCollector.microphoneCoordinateValue(0f,-0f,0f,false).getString("kind"));assertEquals("finite",HardwareCollector.microphoneCoordinateValue(1f,2f,3f,false).getString("kind"));assertEquals("non-finite",HardwareCollector.microphoneCoordinateValue(Float.NaN,0f,0f,false).getString("kind"));
        android.util.Pair<Float,Float> point=android.util.Pair.create(1000f,-3f);ArrayList<android.util.Pair<Float,Float>> points=new ArrayList<>(Arrays.asList(point,android.util.Pair.create(0f,Float.NaN),point));
        JSONObject actual=HardwareCollector.microphoneResponseValues(points);assertEquals(3,actual.getInt("pointCount"));assertEquals(1000d,actual.getJSONArray("points").getJSONObject(0).getDouble("frequencyHz"),0d);assertEquals(-3d,actual.getJSONArray("points").getJSONObject(2).getDouble("responseDb"),0d);assertEquals("NaN",actual.getJSONArray("points").getJSONObject(1).getJSONObject("responseDb").getString("value"));assertEquals(point,points.get(0));points.set(0,android.util.Pair.create(999f,1f));assertEquals(1000d,actual.getJSONArray("points").getJSONObject(0).getDouble("frequencyHz"),0d);
        JSONObject maximum=HardwareCollector.microphoneResponseValues(Collections.nCopies(4096,point));assertEquals(128,maximum.getJSONArray("points").length());assertTrue(maximum.getBoolean("truncated"));assertEquals(4096,maximum.getInt("pointCount"));JSONObject shorter=HardwareCollector.microphoneResponseValues(Collections.nCopies(4095,point));assertEquals(maximum.getJSONArray("points").toString(),shorter.getJSONArray("points").toString());assertNotEquals(maximum.getString("pointsSha256"),shorter.getString("pointsSha256"));
        JSONObject empty=HardwareCollector.microphoneResponseValues(Collections.emptyList());assertEquals("[]",empty.getJSONArray("points").toString());assertEquals(PackageInputCollector.sha("[]".getBytes(StandardCharsets.UTF_8)),empty.getString("pointsSha256"));
        try{HardwareCollector.microphoneResponseValues(Collections.nCopies(4097,point));fail("最多4096点");}catch(IllegalArgumentException expected){}
        for(List<android.util.Pair<Float,Float>> invalid:Arrays.asList(null,Collections.<android.util.Pair<Float,Float>>singletonList(null),Collections.singletonList(android.util.Pair.<Float,Float>create(null,1f)),Collections.singletonList(android.util.Pair.<Float,Float>create(1f,null)))){try{HardwareCollector.microphoneResponseValues(invalid);fail("缺失不能当空频响");}catch(NullPointerException expected){}}
        assertEquals("unsupported",HardwareCollector.microphoneDetails(null,24).getString("status"));assertTrue(HardwareCollector.microphoneDetails(null,27).isNull("microphones"));assertEquals("unavailable",HardwareCollector.microphoneDetails(null,28).getString("status"));
        try{HardwareCollector.microphoneValues(null);fail("缺失清单");}catch(NullPointerException expected){}try{HardwareCollector.microphoneValues(Collections.singletonList(null));fail("缺失麦克风");}catch(NullPointerException expected){}try{HardwareCollector.microphoneValues(Collections.nCopies(257,null));fail("最多256麦克风");}catch(IllegalArgumentException expected){}
        JSONObject microphones=HardwareCollector.microphoneValues(Collections.emptyList());assertEquals("ok",microphones.getString("status"));assertEquals("[]",microphones.getJSONArray("microphones").toString());
    }
    @Test public void microphoneQueryFailuresKeepNull()throws Exception{
        JSONObject denied=HardwareCollector.microphoneQuery(()->{throw new SecurityException("synthetic");});assertEquals("denied",denied.getString("status"));assertTrue(denied.isNull("value"));assertEquals("java.lang.SecurityException",denied.getString("errorClass"));
        JSONObject error=HardwareCollector.microphoneQuery(()->{throw new IOException("synthetic");});assertEquals("error",error.getString("status"));assertTrue(error.isNull("value"));
        assertEquals("error",HardwareCollector.microphoneQuery(()->{throw new NoSuchMethodError("synthetic");}).getString("status"));
        JSONObject success=HardwareCollector.microphoneQuery(()->HardwareCollector.microphoneFloatValue(0f));assertEquals("ok",success.getString("status"));assertEquals(0d,success.getJSONObject("value").getDouble("raw"),0d);
    }
    @Test public void microphonesMatchPublicApi()throws Exception{
        android.media.AudioManager manager=context.getSystemService(android.media.AudioManager.class);assertNotNull(manager);
        if(Build.VERSION.SDK_INT<28){assertEquals("unsupported",HardwareCollector.microphoneDetails(manager,Build.VERSION.SDK_INT).getString("status"));return;}
        List<android.media.MicrophoneInfo> expected=manager.getMicrophones();JSONObject actual=HardwareCollector.microphoneValues(expected);assertEquals(expected.size(),actual.getInt("count"));assertEquals(Math.min(64,expected.size()),actual.getInt("returnedCount"));JSONArray all=new JSONArray();
        for(int index=0;index<expected.size();index++){
            android.media.MicrophoneInfo mic=expected.get(index);JSONObject value=HardwareCollector.microphoneValue(mic);all.put(value);assertEquals(mic.getId(),value.getInt("id"));assertEquals(mic.getType(),value.getInt("type"));assertEquals(mic.getLocation(),value.getInt("location"));assertEquals(mic.getDirectionality(),value.getInt("directionality"));assertEquals(mic.getGroup(),value.getInt("group"));assertEquals(mic.getIndexInTheGroup(),value.getInt("indexInGroup"));
            JSONObject calibration=value.getJSONObject("calibration");assertEquals("ok",calibration.getString("status"));float[] scalars={mic.getSensitivity(),mic.getMaxSpl(),mic.getMinSpl()};String[] keys={"sensitivityDbfs","maxSplDb","minSplDb"};for(int j=0;j<keys.length;j++){JSONObject reading=calibration.getJSONObject("value").getJSONObject(keys[j]);assertEquals(Json.obj("raw",scalars[j]).get("raw").toString(),reading.get("raw").toString());assertEquals(scalars[j]==android.media.MicrophoneInfo.SENSITIVITY_UNKNOWN?"unknown-sentinel":Float.isFinite(scalars[j])?"finite":"non-finite",reading.getString("kind"));}
            android.media.MicrophoneInfo.Coordinate3F[] coordinates={mic.getPosition(),mic.getOrientation()};String[] coordinateKeys={"positionMeters","orientation"};for(int j=0;j<2;j++){JSONObject reading=value.getJSONObject(coordinateKeys[j]);assertEquals("ok",reading.getString("status"));JSONObject point=reading.getJSONObject("value");assertEquals(Json.obj("x",coordinates[j].x,"y",coordinates[j].y,"z",coordinates[j].z).toString(),Json.obj("x",point.get("x"),"y",point.get("y"),"z",point.get("z")).toString());boolean unknown=coordinates[j].equals(j==0?android.media.MicrophoneInfo.POSITION_UNKNOWN:android.media.MicrophoneInfo.ORIENTATION_UNKNOWN);assertEquals(unknown?"unknown-sentinel":Float.isFinite(coordinates[j].x)&&Float.isFinite(coordinates[j].y)&&Float.isFinite(coordinates[j].z)?"finite":"non-finite",point.getString("kind"));}
            JSONObject response=value.getJSONObject("frequencyResponse");assertEquals("ok",response.getString("status"));JSONObject result=response.getJSONObject("value");List<android.util.Pair<Float,Float>> points=mic.getFrequencyResponse();JSONArray complete=new JSONArray();for(android.util.Pair<Float,Float> point:points)complete.put(Json.obj("frequencyHz",point.first,"responseDb",point.second));assertEquals(points.size(),result.getInt("pointCount"));assertEquals(Math.min(128,points.size()),result.getJSONArray("points").length());assertEquals(PackageInputCollector.sha(complete.toString().getBytes(StandardCharsets.UTF_8)),result.getString("pointsSha256"));for(int j=0;j<result.getJSONArray("points").length();j++)assertEquals(complete.getJSONObject(j).toString(),result.getJSONArray("points").getJSONObject(j).toString());
            assertFalse(value.has("address"));assertFalse(value.has("description"));if(index<64)assertEquals(value.toString(),actual.getJSONArray("microphones").getJSONObject(index).toString());
        }
        assertEquals(PackageInputCollector.sha(all.toString().getBytes(StandardCharsets.UTF_8)),actual.getString("microphonesSha256"));
        if(!expected.isEmpty()){JSONObject maximum=HardwareCollector.microphoneValues(Collections.nCopies(256,expected.get(0))),shorter=HardwareCollector.microphoneValues(Collections.nCopies(255,expected.get(0)));assertEquals("partial",maximum.getString("status"));assertEquals(64,maximum.getJSONArray("microphones").length());assertEquals(maximum.getJSONArray("microphones").toString(),shorter.getJSONArray("microphones").toString());assertNotEquals(maximum.getString("microphonesSha256"),shorter.getString("microphonesSha256"));}
        Bundle status=new Bundle();status.putString("microphoneDetails",actual.toString());InstrumentationRegistry.getInstrumentation().sendStatus(0,status);
    }
    @Test public void speakerLayoutStatesKeepRawMasksAndSkipInvalidQueries()throws Exception{
        for(int sdk:new int[]{24,35}){JSONObject value=HardwareCollector.audioSpeakerLayout(2,sdk,()->{throw new AssertionError("旧API不应查询");});assertEquals("unsupported",value.getString("status"));assertTrue(value.isNull("channelMask"));}
        for(int type:new int[]{0,1,3,15,18,24,-1,Integer.MAX_VALUE}){JSONObject value=HardwareCollector.audioSpeakerLayout(type,36,()->{throw new AssertionError("仅内置扬声器适用");});assertEquals("not-applicable",value.getString("status"));assertTrue(value.isNull("channelMask"));}
        for(int mask:new int[]{0,1,12,Integer.MIN_VALUE,-1,Integer.MAX_VALUE}){JSONObject value=HardwareCollector.audioSpeakerLayout(2,36,()->mask);assertEquals("ok",value.getString("status"));assertEquals(mask,value.getInt("channelMask"));assertEquals(mask,new JSONObject(value.toString()).getInt("channelMask"));}
        JSONObject denied=HardwareCollector.audioSpeakerLayout(2,36,()->{throw new SecurityException("synthetic");});assertEquals("denied",denied.getString("status"));assertTrue(denied.isNull("channelMask"));assertEquals("java.lang.SecurityException",denied.getString("errorClass"));
        JSONObject error=HardwareCollector.audioSpeakerLayout(2,36,()->{throw new IllegalStateException("synthetic");});assertEquals("error",error.getString("status"));assertTrue(error.isNull("channelMask"));
        JSONObject linkage=HardwareCollector.audioSpeakerLayout(2,36,()->{throw new NoSuchMethodError("synthetic");});assertEquals("error",linkage.getString("status"));assertTrue(linkage.isNull("channelMask"));assertEquals("java.lang.NoSuchMethodError",linkage.getString("errorClass"));
    }
    @Test public void speakerLayoutMatchesPublicApiAndPreservesAudioDevices()throws Exception{
        org.junit.Assume.assumeTrue(Build.VERSION.SDK_INT>=36);
        android.media.AudioManager manager=context.getSystemService(android.media.AudioManager.class);assertNotNull(manager);android.media.AudioDeviceInfo[] devices=manager.getDevices(android.media.AudioManager.GET_DEVICES_ALL);
        ItemSink sink=new ItemSink(context,"speaker-layout-test");new HardwareCollector("hardware").collect(sink);JSONObject item=null;for(int i=0;i<sink.items.length();i++)if(sink.items.getJSONObject(i).getString("key").equals("hardware/audio"))item=sink.items.getJSONObject(i);
        assertNotNull(item);assertEquals("ok",item.getString("status"));JSONArray collected=item.getJSONArray("value"),evidence=new JSONArray();assertEquals(devices.length,collected.length());int speakers=0;
        for(android.media.AudioDeviceInfo device:devices){
            JSONObject expected=device.getType()==android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER?Json.obj("status","ok","channelMask",device.getSpeakerLayoutChannelMask()):Json.obj("status","not-applicable","channelMask",null);
            if(device.getType()==android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)speakers++;
            JSONObject stored=null;for(int i=0;i<collected.length();i++)if(collected.getJSONObject(i).getInt("id")==device.getId())stored=collected.getJSONObject(i);assertNotNull(stored);assertEquals(device.getType(),stored.getInt("type"));assertEquals(device.isSource(),stored.getBoolean("isSource"));assertEquals(device.isSink(),stored.getBoolean("isSink"));assertTrue(stored.has("encodings"));assertTrue(stored.has("channelCounts"));assertTrue(stored.has("sampleRates"));assertTrue(stored.has("audioProfiles"));assertTrue(stored.has("supportedMixerAttributes"));assertEquals(expected.toString(),stored.getJSONObject("speakerLayout").toString());
            evidence.put(Json.obj("id",device.getId(),"type",device.getType(),"isSource",device.isSource(),"isSink",device.isSink(),"speakerLayout",expected));
        }
        assertTrue("需要至少一个内置扬声器来验证真实getter",speakers>0);Bundle result=new Bundle();result.putString("speakerLayoutEvidence",evidence.toString());InstrumentationRegistry.getInstrumentation().sendStatus(0,result);
    }
    @Test public void audioMixerValuesPreserveFormatsOrderAndBounds()throws Exception{
        for(int sdk:new int[]{24,33}){JSONObject missing=HardwareCollector.audioMixerAttributes(null,null,sdk);assertEquals("unsupported",missing.getString("status"));assertTrue(missing.isNull("attributes"));}
        assertEquals("unavailable",HardwareCollector.audioMixerAttributes(null,null,34).getString("status"));
        org.junit.Assume.assumeTrue(Build.VERSION.SDK_INT>=34);
        android.media.AudioFormat stereo=new android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48000).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO).build();
        android.media.AudioFormat indexed=new android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(96000).setChannelIndexMask(5).build();
        android.media.AudioMixerAttributes first=new android.media.AudioMixerAttributes.Builder(stereo).build(),second=new android.media.AudioMixerAttributes.Builder(indexed).setMixerBehavior(android.media.AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT).build();
        ArrayList<android.media.AudioMixerAttributes> input=new ArrayList<>(Arrays.asList(first,second,first));JSONObject value=HardwareCollector.audioMixerAttributeValues(input);JSONArray entries=value.getJSONArray("attributes");
        assertEquals("ok",value.getString("status"));assertEquals(3,value.getInt("attributeCount"));assertEquals(3,value.getInt("returnedCount"));assertEquals(64,value.getInt("attributeLimit"));assertFalse(value.getBoolean("attributesTruncated"));
        assertEquals(0,entries.getJSONObject(0).getInt("mixerBehavior"));assertEquals(48000,entries.getJSONObject(0).getInt("sampleRateHz"));assertEquals(2,entries.getJSONObject(0).getInt("encoding"));assertEquals(12,entries.getJSONObject(0).getInt("channelMask"));assertEquals(0,entries.getJSONObject(0).getInt("channelIndexMask"));assertEquals(2,entries.getJSONObject(0).getInt("channelCount"));
        assertEquals(1,entries.getJSONObject(1).getInt("mixerBehavior"));assertEquals(96000,entries.getJSONObject(1).getInt("sampleRateHz"));assertEquals(4,entries.getJSONObject(1).getInt("encoding"));assertEquals(0,entries.getJSONObject(1).getInt("channelMask"));assertEquals(5,entries.getJSONObject(1).getInt("channelIndexMask"));assertEquals(2,entries.getJSONObject(1).getInt("channelCount"));assertEquals(entries.getJSONObject(0).toString(),entries.getJSONObject(2).toString());
        assertEquals(PackageInputCollector.sha(entries.toString().getBytes(StandardCharsets.UTF_8)),value.getString("attributesSha256"));assertEquals(Arrays.asList(first,second,first),input);input.set(0,second);assertEquals(0,entries.getJSONObject(0).getInt("mixerBehavior"));assertNotEquals(value.getString("attributesSha256"),HardwareCollector.audioMixerAttributeValues(input).getString("attributesSha256"));
        JSONObject empty=HardwareCollector.audioMixerAttributeValues(Collections.emptyList());assertEquals("ok",empty.getString("status"));assertEquals(0,empty.getInt("attributeCount"));assertEquals("[]",empty.getJSONArray("attributes").toString());assertEquals(PackageInputCollector.sha("[]".getBytes(StandardCharsets.UTF_8)),empty.getString("attributesSha256"));
        JSONObject limit=HardwareCollector.audioMixerAttributeValues(Collections.nCopies(64,first));assertEquals("ok",limit.getString("status"));assertFalse(limit.getBoolean("attributesTruncated"));
        for(int count:new int[]{65,256}){ArrayList<android.media.AudioMixerAttributes> many=new ArrayList<>(Collections.nCopies(count,first));JSONObject bounded=HardwareCollector.audioMixerAttributeValues(many);assertEquals("partial",bounded.getString("status"));assertTrue(bounded.getBoolean("attributesTruncated"));assertEquals(count,bounded.getInt("attributeCount"));assertEquals(64,bounded.getInt("returnedCount"));assertEquals(64,bounded.getJSONArray("attributes").length());many.set(count-1,second);JSONObject changed=HardwareCollector.audioMixerAttributeValues(many);assertEquals(bounded.getJSONArray("attributes").toString(),changed.getJSONArray("attributes").toString());assertNotEquals(bounded.getString("attributesSha256"),changed.getString("attributesSha256"));assertEquals(count,many.size());}
        try{HardwareCollector.audioMixerAttributeValues(Collections.nCopies(257,first));fail("最多256项");}catch(IllegalArgumentException expected){}
        try{HardwareCollector.audioMixerAttributeValues(null);fail("缺失不能当空清单");}catch(NullPointerException expected){}try{HardwareCollector.audioMixerAttributeValues(Collections.singletonList(null));fail("缺失混音属性");}catch(NullPointerException expected){}
    }
    @Test public void audioMixerMatchesPublicApiAndKeepsExistingDevices()throws Exception{
        org.junit.Assume.assumeTrue(Build.VERSION.SDK_INT>=34);
        android.media.AudioManager manager=context.getSystemService(android.media.AudioManager.class);assertNotNull(manager);
        JSONObject missing=HardwareCollector.audioMixerAttributes(manager,null,Build.VERSION.SDK_INT);assertEquals("error",missing.getString("status"));assertEquals("java.lang.NullPointerException",missing.getString("errorClass"));assertTrue(missing.isNull("attributes"));
        android.media.AudioDeviceInfo[] devices=manager.getDevices(android.media.AudioManager.GET_DEVICES_ALL);assertTrue(devices.length>0);
        ItemSink sink=new ItemSink(context,"audio-mixer-test");new HardwareCollector("hardware").collect(sink);JSONObject item=null;for(int i=0;i<sink.items.length();i++)if(sink.items.getJSONObject(i).getString("key").equals("hardware/audio"))item=sink.items.getJSONObject(i);
        assertNotNull(item);assertEquals("ok",item.getString("status"));JSONArray collected=item.getJSONArray("value"),evidence=new JSONArray();assertEquals(devices.length,collected.length());
        for(android.media.AudioDeviceInfo device:devices){
            List<android.media.AudioMixerAttributes> expected=manager.getSupportedMixerAttributes(device);JSONArray all=new JSONArray();
            for(android.media.AudioMixerAttributes attribute:expected){android.media.AudioFormat format=attribute.getFormat();all.put(Json.obj("mixerBehavior",attribute.getMixerBehavior(),"sampleRateHz",format.getSampleRate(),"encoding",format.getEncoding(),"channelMask",format.getChannelMask(),"channelIndexMask",format.getChannelIndexMask(),"channelCount",format.getChannelCount()));}
            JSONObject actual=HardwareCollector.audioMixerAttributes(manager,device,Build.VERSION.SDK_INT);assertEquals(expected.size()>64?"partial":"ok",actual.getString("status"));assertEquals(expected.size(),actual.getInt("attributeCount"));assertEquals(Math.min(64,expected.size()),actual.getInt("returnedCount"));assertEquals(expected.size()>64,actual.getBoolean("attributesTruncated"));assertEquals(PackageInputCollector.sha(all.toString().getBytes(StandardCharsets.UTF_8)),actual.getString("attributesSha256"));for(int i=0;i<actual.getJSONArray("attributes").length();i++)assertEquals(all.getJSONObject(i).toString(),actual.getJSONArray("attributes").getJSONObject(i).toString());
            JSONObject stored=null;for(int i=0;i<collected.length();i++)if(collected.getJSONObject(i).getInt("id")==device.getId())stored=collected.getJSONObject(i);assertNotNull(stored);assertEquals(device.getType(),stored.getInt("type"));assertEquals(device.isSource(),stored.getBoolean("isSource"));assertEquals(device.isSink(),stored.getBoolean("isSink"));assertEquals(Json.obj("x",device.getSampleRates()).getJSONArray("x").toString(),stored.getJSONArray("sampleRates").toString());assertTrue(stored.has("audioProfiles"));assertEquals(actual.toString(),stored.getJSONObject("supportedMixerAttributes").toString());
            evidence.put(Json.obj("id",device.getId(),"type",device.getType(),"isSource",device.isSource(),"isSink",device.isSink(),"supportedMixerAttributes",actual));
        }
        Bundle result=new Bundle();result.putString("audioMixerEvidence",evidence.toString());InstrumentationRegistry.getInstrumentation().sendStatus(0,result);
    }
    @Test public void audioProfileValuesPreserveMasksOrderAndBounds()throws Exception{
        int[] rates={48000,0,48000,-1},masks={12,Integer.MIN_VALUE,0,12},indexes={Integer.MAX_VALUE,-1};
        JSONObject value=HardwareCollector.audioProfileValue(-99,rates,masks,indexes,999);
        assertEquals(-99,value.getInt("format"));assertEquals(999,value.getInt("encapsulationType"));assertEquals("[48000,0,48000,-1]",value.getJSONArray("sampleRatesHz").toString());assertEquals("[12,-2147483648,0,12]",value.getJSONArray("channelMasks").toString());assertEquals("[2147483647,-1]",value.getJSONArray("channelIndexMasks").toString());assertArrayEquals(new int[]{48000,0,48000,-1},rates);assertEquals(12,masks[0]);
        rates[0]=96000;assertEquals(48000,value.getJSONArray("sampleRatesHz").getInt(0));
        assertEquals("[]",HardwareCollector.audioProfileValue(0,new int[0],new int[0],new int[0],0).getJSONArray("channelMasks").toString());
        assertEquals(1024,HardwareCollector.audioProfileValue(0,new int[1024],new int[1024],new int[1024],0).getJSONArray("channelMasks").length());
        for(int position=0;position<3;position++){int[][] arrays={new int[0],new int[0],new int[0]};arrays[position]=new int[1025];try{HardwareCollector.audioProfileValue(0,arrays[0],arrays[1],arrays[2],0);fail("最多1024整数");}catch(IllegalArgumentException expected){}arrays[position]=null;try{HardwareCollector.audioProfileValue(0,arrays[0],arrays[1],arrays[2],0);fail("null不能当成空数组");}catch(NullPointerException expected){}}
        assertEquals("unsupported",HardwareCollector.audioDeviceProfiles(null,30).getString("status"));assertTrue(HardwareCollector.audioDeviceProfiles(null,24).isNull("profiles"));
        try{HardwareCollector.audioDeviceProfiles(null,31);fail("新API缺少设备实例");}catch(NullPointerException expected){}
        try{HardwareCollector.audioProfileValues(null);fail("缺失不能当作空集合");}catch(NullPointerException expected){}
        try{HardwareCollector.audioProfileValues(Collections.singletonList(null));fail("缺失profile");}catch(NullPointerException expected){}
        try{HardwareCollector.audioProfileValues(Collections.nCopies(257,null));fail("最多256项");}catch(IllegalArgumentException expected){}
        JSONObject empty=HardwareCollector.audioProfileValues(Collections.emptyList());assertEquals("ok",empty.getString("status"));assertEquals("[]",empty.getJSONArray("profiles").toString());assertEquals(PackageInputCollector.sha("[]".getBytes(StandardCharsets.UTF_8)),empty.getString("profilesSha256"));
    }
    @Test public void audioProfilesMatchPublicApiAndKeepOldDevices()throws Exception{
        android.media.AudioManager manager=context.getSystemService(android.media.AudioManager.class);assertNotNull(manager);
        android.media.AudioDeviceInfo[] devices=manager.getDevices(android.media.AudioManager.GET_DEVICES_ALL);JSONArray evidence=new JSONArray();android.media.AudioProfile first=null;
        for(android.media.AudioDeviceInfo device:devices){
            JSONObject value=HardwareCollector.audioDeviceProfiles(device,Build.VERSION.SDK_INT);
            if(Build.VERSION.SDK_INT<31){assertEquals("unsupported",value.getString("status"));assertTrue(value.isNull("profiles"));continue;}
            List<android.media.AudioProfile> expected=device.getAudioProfiles();assertEquals(expected.size(),value.getInt("profileCount"));assertEquals(Math.min(64,expected.size()),value.getInt("returnedCount"));JSONArray all=new JSONArray();
            for(android.media.AudioProfile profile:expected){if(first==null)first=profile;all.put(Json.obj("format",profile.getFormat(),"sampleRatesHz",profile.getSampleRates(),"channelMasks",profile.getChannelMasks(),"channelIndexMasks",profile.getChannelIndexMasks(),"encapsulationType",profile.getEncapsulationType()));}
            assertEquals(PackageInputCollector.sha(all.toString().getBytes(StandardCharsets.UTF_8)),value.getString("profilesSha256"));for(int i=0;i<value.getJSONArray("profiles").length();i++)assertEquals(all.getJSONObject(i).toString(),value.getJSONArray("profiles").getJSONObject(i).toString());
            evidence.put(Json.obj("id",device.getId(),"type",device.getType(),"audioProfiles",value));
        }
        if(first!=null){List<android.media.AudioProfile> maximum=Collections.nCopies(256,first);JSONObject bounded=HardwareCollector.audioProfileValues(maximum);assertEquals("partial",bounded.getString("status"));assertTrue(bounded.getBoolean("profilesTruncated"));assertEquals(64,bounded.getJSONArray("profiles").length());assertEquals(256,bounded.getInt("profileCount"));JSONObject shorter=HardwareCollector.audioProfileValues(Collections.nCopies(255,first));assertEquals(bounded.getJSONArray("profiles").toString(),shorter.getJSONArray("profiles").toString());assertNotEquals(bounded.getString("profilesSha256"),shorter.getString("profilesSha256"));assertEquals(256,maximum.size());}
        Bundle status=new Bundle();status.putString("audioDeviceProfiles",evidence.toString());InstrumentationRegistry.getInstrumentation().sendStatus(0,status);
    }
    @Test public void sensorMetadataPreservesSentinelsAndSignedRange()throws Exception{
        for(int id:new int[]{Integer.MIN_VALUE,-2,-1,0,1,Integer.MAX_VALUE}){
            JSONObject actual=HardwareCollector.sensorIdValue(id);assertEquals("ok",actual.getString("status"));assertEquals(id,actual.getInt("value"));
            assertEquals(id==0?"unsupported":id==-1?"type-and-name":"app-scoped-id",actual.getString("kind"));
            assertEquals(id,new JSONObject(actual.toString()).getInt("value"));
        }
        try{HardwareCollector.sensorPublicMetadata(null);fail("缺失实例不能被当作不支持");}catch(NullPointerException expected){}
    }
    @Test public void sensorMetadataMatchesPublicApiAndKeepsOldFields()throws Exception{
        android.hardware.SensorManager manager=context.getSystemService(android.hardware.SensorManager.class);assertNotNull(manager);
        List<android.hardware.Sensor> sensors=manager.getSensorList(android.hardware.Sensor.TYPE_ALL);
        ItemSink sink=new ItemSink(context,"sensor-metadata-test");new HardwareCollector("sensors").collect(sink);
        JSONObject item=null,summary=null;for(int i=0;i<sink.items.length();i++){JSONObject entry=sink.items.getJSONObject(i);if(entry.getString("key").equals("sensors/list"))item=entry;if(entry.getString("key").equals("sensors/summary"))summary=entry;}
        assertNotNull(item);assertNotNull(summary);assertEquals("ok",item.getString("status"));JSONArray actual=item.getJSONArray("value"),evidence=new JSONArray();
        assertEquals(sensors.size(),actual.length());assertEquals(actual.length(),summary.getJSONObject("value").getInt("count"));
        for(int i=0;i<actual.length();i++){
            android.hardware.Sensor sensor=sensors.get(i);JSONObject entry=actual.getJSONObject(i),metadata=entry.getJSONObject("publicMetadata");
            assertEquals(sensor.getName(),entry.getString("name"));assertEquals(sensor.getVendor(),entry.getString("vendor"));assertEquals(sensor.getType(),entry.getInt("type"));assertTrue(entry.has("fifoMaxEventCount"));assertTrue(entry.has("reportingMode"));assertTrue(entry.has("wakeUp"));
            JSONObject id=metadata.getJSONObject("sensorId"),additional=metadata.getJSONObject("additionalInfoSupported");
            assertEquals("ok",id.getString("status"));assertEquals(sensor.getId(),id.getInt("value"));assertEquals(HardwareCollector.sensorIdValue(sensor.getId()).getString("kind"),id.getString("kind"));
            assertEquals("ok",additional.getString("status"));assertEquals(sensor.isAdditionalInfoSupported(),additional.getBoolean("value"));
            evidence.put(Json.obj("index",i,"name",sensor.getName(),"type",sensor.getType(),"publicMetadata",metadata));
        }
        Bundle status=new Bundle();status.putString("sensorPublicMetadata",evidence.toString());InstrumentationRegistry.getInstrumentation().sendStatus(0,status);
    }
    @Test public void apexPackagesMatchPublicEnumeration()throws Exception{
        ItemSink sink=new ItemSink(context,"apex-test");new BuildSystemCollector("build").collect(sink);
        JSONObject collected=null;boolean modules=false,summary=false;
        for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);String key=item.getString("key");if(key.equals("build/apex-packages"))collected=item;if(key.equals("build/modules"))modules=true;if(key.equals("build/module-summary"))summary=true;}
        assertNotNull(collected);assertTrue(modules);assertTrue(summary);
        if(Build.VERSION.SDK_INT<29){assertEquals("unsupported",collected.getString("status"));return;}
        assertEquals("ok",collected.getString("status"));JSONObject actual=collected.getJSONObject("value");
        List<android.content.pm.PackageInfo> installed=context.getPackageManager().getInstalledPackages(android.content.pm.PackageManager.MATCH_APEX);
        ArrayList<JSONObject> expected=new ArrayList<>();ArrayList<String> names=new ArrayList<>();
        for(android.content.pm.PackageInfo info:installed)if(info.isApex){expected.add(Json.obj("packageName",info.packageName,"versionName",info.versionName,"versionCode",info.getLongVersionCode()));names.add(info.packageName);}
        expected.sort(Comparator.comparing(JSONObject::toString));JSONArray all=new JSONArray(expected),entries=actual.getJSONArray("packages");
        assertEquals("current-app-visible-installed-apex",actual.getString("scope"));assertTrue(actual.getBoolean("available"));
        assertEquals(expected.size(),actual.getInt("packageCount"));assertEquals(Math.min(256,expected.size()),actual.getInt("returnedCount"));assertEquals(256,actual.getInt("packageLimit"));
        assertEquals(expected.size()>256,actual.getBoolean("truncated"));assertEquals(expected.size()>256?"partial":"ok",actual.getString("status"));
        assertEquals(PackageInputCollector.sha(all.toString().getBytes(StandardCharsets.UTF_8)),actual.getString("packageSetSha256"));
        for(int i=0;i<entries.length();i++){assertEquals(expected.get(i).toString(),entries.getJSONObject(i).toString());assertEquals("int64",entries.getJSONObject(i).getJSONObject("versionCode").getString("$type"));}
        Bundle evidence=new Bundle();evidence.putString("apexPackageCount",String.valueOf(expected.size()));evidence.putString("apexPackageNames",names.toString());InstrumentationRegistry.getInstrumentation().sendStatus(0,evidence);
    }
    @Test public void apexPackagesKeepPrecisionDuplicatesAndFullHash()throws Exception{
        Assume.assumeTrue(Build.VERSION.SDK_INT>=29);
        ArrayList<android.content.pm.PackageInfo> input=new ArrayList<>();
        for(int i=259;i>=0;i--){android.content.pm.PackageInfo info=new android.content.pm.PackageInfo();info.isApex=true;info.packageName=String.format(Locale.ROOT,"apex.%03d",i);info.versionName=i==0?null:"";info.setLongVersionCode(Long.MAX_VALUE-i);input.add(info);}
        android.content.pm.PackageInfo apk=new android.content.pm.PackageInfo();apk.packageName="ordinary.apk";input.add(apk);
        android.content.pm.PackageInfo duplicate=input.get(0);input.add(duplicate);
        ArrayList<android.content.pm.PackageInfo> original=new ArrayList<>(input);
        JSONObject result=BuildSystemCollector.apexPackageValues(input);JSONArray values=result.getJSONArray("packages");
        assertEquals(original,input);assertEquals(261,result.getInt("packageCount"));assertEquals(256,values.length());assertTrue(result.getBoolean("truncated"));assertEquals("partial",result.getString("status"));
        assertEquals("apex.000",values.getJSONObject(0).getString("packageName"));assertTrue(values.getJSONObject(0).isNull("versionName"));assertEquals("",values.getJSONObject(1).getString("versionName"));
        assertEquals(String.valueOf(Long.MAX_VALUE),values.getJSONObject(0).getJSONObject("versionCode").getString("value"));
        Collections.reverse(input);assertEquals(result.toString(),BuildSystemCollector.apexPackageValues(input).toString());
        duplicate.setLongVersionCode(9007199254740993L);JSONObject changed=BuildSystemCollector.apexPackageValues(input);assertEquals(values.toString(),changed.getJSONArray("packages").toString());assertNotEquals(result.getString("packageSetSha256"),changed.getString("packageSetSha256"));
        JSONObject repeated=BuildSystemCollector.apexPackageValues(Arrays.asList(duplicate,apk,duplicate));assertEquals(2,repeated.getInt("packageCount"));assertEquals(repeated.getJSONArray("packages").getJSONObject(0).toString(),repeated.getJSONArray("packages").getJSONObject(1).toString());
        duplicate.packageName=null;duplicate.versionName="";assertTrue(BuildSystemCollector.apexPackageValues(Collections.singletonList(duplicate)).getJSONArray("packages").getJSONObject(0).isNull("packageName"));
        JSONObject empty=BuildSystemCollector.apexPackageValues(Collections.singletonList(apk));assertEquals("ok",empty.getString("status"));assertEquals(0,empty.getInt("packageCount"));assertFalse(empty.getBoolean("truncated"));assertEquals(PackageInputCollector.sha("[]".getBytes(StandardCharsets.UTF_8)),empty.getString("packageSetSha256"));
        try{BuildSystemCollector.apexPackageValues(null);fail("缺失清单不能冒充空集合");}catch(IllegalArgumentException expected){}
        try{BuildSystemCollector.apexPackageValues(Collections.singletonList(null));fail("null元素不能静默丢失");}catch(IllegalArgumentException expected){}
        try{BuildSystemCollector.apexPackageValues(Collections.nCopies(20001,apk));fail("超过输入上限应明确失败");}catch(IllegalArgumentException expected){}
    }
    @Test public void moduleVersionsMatchVisiblePackagesAndKeepFailuresSeparate()throws Exception{
        android.content.pm.PackageManager manager=context.getPackageManager();
        if(Build.VERSION.SDK_INT<29){assertEquals("unsupported",BuildSystemCollector.modulePackageVersion(manager,context.getPackageName()).getString("status"));return;}
        assertEquals("missing-package-name",BuildSystemCollector.modulePackageVersion(manager,null).getString("status"));assertEquals("missing-package-name",BuildSystemCollector.modulePackageVersion(manager,"").getString("status"));assertEquals("unavailable",BuildSystemCollector.modulePackageVersion(null,context.getPackageName()).getString("status"));
        JSONObject missing=BuildSystemCollector.modulePackageVersion(manager,context.getPackageName()+".fingerlens_missing_module");assertEquals("not-found-or-not-visible",missing.getString("status"));assertTrue(missing.isNull("versionCode"));assertTrue(missing.isNull("versionName"));assertTrue(missing.isNull("isApex"));
        android.content.pm.PackageInfo self=manager.getPackageInfo(context.getPackageName(),android.content.pm.PackageManager.MATCH_APEX);JSONObject own=BuildSystemCollector.modulePackageVersion(manager,context.getPackageName());assertEquals("ok",own.getString("status"));assertEquals(String.valueOf(self.getLongVersionCode()),own.getJSONObject("versionCode").getString("value"));assertEquals(self.isApex,own.getBoolean("isApex"));
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new BuildSystemCollector("build").collect(sink);JSONObject raw=null;boolean summary=false;
        for(int i=0;i<sink.items.length();i++){JSONObject entry=sink.items.getJSONObject(i);if(entry.getString("key").equals("build/modules"))raw=entry;if(entry.getString("key").equals("build/module-summary"))summary=true;}
        assertNotNull(raw);assertTrue(summary);assertEquals("ok",raw.getString("status"));JSONObject value=raw.getJSONObject("value");JSONArray entries=value.getJSONArray("modules");assertTrue(entries.length()<=512);assertEquals(entries.length(),value.getInt("returnedCount"));
        List<android.content.pm.ModuleInfo> expected=manager.getInstalledModules(android.content.pm.PackageManager.MATCH_ALL);assertEquals(expected.size(),value.getInt("count"));int index=0;
        for(int i=0;i<Math.min(512,expected.size());i++){
            android.content.pm.ModuleInfo module=expected.get(i);if(module==null)continue;JSONObject entry=entries.getJSONObject(index++),version=entry.getJSONObject("packageVersion");assertEquals(module.getPackageName()==null?JSONObject.NULL:module.getPackageName(),entry.get("packageName"));assertEquals(module.isHidden(),entry.getBoolean("hidden"));assertEquals(module.getName()==null?JSONObject.NULL:module.getName().toString(),entry.get("name"));
            if(module.getPackageName()==null||module.getPackageName().isEmpty()){assertEquals("missing-package-name",version.getString("status"));assertTrue(version.isNull("versionCode"));continue;}
            try{android.content.pm.PackageInfo info=manager.getPackageInfo(module.getPackageName(),android.content.pm.PackageManager.MATCH_APEX);assertEquals("ok",version.getString("status"));assertEquals(info.versionName==null?JSONObject.NULL:info.versionName,version.get("versionName"));assertEquals("int64",version.getJSONObject("versionCode").getString("$type"));assertEquals(String.valueOf(info.getLongVersionCode()),version.getJSONObject("versionCode").getString("value"));assertEquals(info.isApex,version.getBoolean("isApex"));}
            catch(android.content.pm.PackageManager.NameNotFoundException e){assertEquals("not-found-or-not-visible",version.getString("status"));assertTrue(version.isNull("versionCode"));assertTrue(version.isNull("isApex"));}
            catch(SecurityException e){assertEquals("denied",version.getString("status"));assertTrue(version.isNull("versionCode"));}
        }
        assertEquals(entries.toString(),new JSONArray(entries.toString()).toString());
    }
    @Test public void globalSecurityStateValuesKeepRawStringsAndCompleteHash()throws Exception{
        Bundle state=new Bundle();state.putString("vendor_spl","");state.putString("system_spl","2026-02-05");state.putString("future.package",null);state.putString("kernel_version","6.1.0.0");
        JSONObject actual=SecurityCollector.globalSecurityStateValues(state);JSONArray entries=actual.getJSONArray("entries");
        assertTrue(actual.getBoolean("available"));assertEquals("ok",actual.getString("status"));assertEquals(4,actual.getInt("entryCount"));assertFalse(actual.getBoolean("entriesTruncated"));
        assertEquals("future.package",entries.getJSONObject(0).getString("key"));assertTrue(entries.getJSONObject(0).has("value"));assertTrue(entries.getJSONObject(0).isNull("value"));assertEquals("6.1.0.0",entries.getJSONObject(1).getString("value"));assertEquals("2026-02-05",entries.getJSONObject(2).getString("value"));assertEquals("",entries.getJSONObject(3).getString("value"));
        assertEquals(PackageInputCollector.sha(entries.toString().getBytes(StandardCharsets.UTF_8)),actual.getString("entriesSha256"));assertEquals(4,state.size());assertTrue(state.containsKey("future.package"));
        Bundle reversed=new Bundle();for(int i=entries.length()-1;i>=0;i--){JSONObject entry=entries.getJSONObject(i);reversed.putString(entry.getString("key"),entry.isNull("value")?null:entry.getString("value"));}assertEquals(actual.toString(),SecurityCollector.globalSecurityStateValues(reversed).toString());
        JSONObject empty=SecurityCollector.globalSecurityStateValues(new Bundle());assertTrue(empty.getBoolean("available"));assertEquals(0,empty.getInt("entryCount"));assertEquals("[]",empty.getJSONArray("entries").toString());
        Bundle large=new Bundle();for(int i=0;i<130;i++)large.putString(String.format(java.util.Locale.ROOT,"package.%03d",i),i==0?"中文\n  空白\u0000":"1.0.0.0");
        JSONObject bounded=SecurityCollector.globalSecurityStateValues(large);assertEquals("partial",bounded.getString("status"));assertEquals(130,bounded.getInt("entryCount"));assertEquals(128,bounded.getInt("returnedCount"));assertTrue(bounded.getBoolean("entriesTruncated"));
        large.putString("package.129","changed-beyond-limit");JSONObject changed=SecurityCollector.globalSecurityStateValues(large);assertEquals(bounded.getJSONArray("entries").toString(),changed.getJSONArray("entries").toString());assertNotEquals(bounded.getString("entriesSha256"),changed.getString("entriesSha256"));
        assertEquals("中文\n  空白\u0000",changed.getJSONArray("entries").getJSONObject(0).getString("value"));assertEquals(130,large.size());
    }
    @Test public void globalSecurityStateGuardsVersionAndInputBounds()throws Exception{
        try{SecurityCollector.globalSecurityState(null,34);fail("旧API不能调用新服务");}catch(UnsupportedOperationException expected){}
        try{SecurityCollector.globalSecurityState(null,35);fail("API35必须有context");}catch(NullPointerException expected){}
        try{SecurityCollector.globalSecurityStateValues(null);fail("缺失不能当作空集合");}catch(NullPointerException expected){}
        Bundle maximum=new Bundle();for(int i=0;i<512;i++)maximum.putString("package."+i,"v");assertEquals(512,SecurityCollector.globalSecurityStateValues(maximum).getInt("entryCount"));maximum.putString("extra","v");
        try{SecurityCollector.globalSecurityStateValues(maximum);fail("最多512项");}catch(IllegalArgumentException expected){}
        Bundle limits=new Bundle();limits.putString(String.join("",Collections.nCopies(1024,"k")),String.join("",Collections.nCopies(8192,"v")));assertEquals(1,SecurityCollector.globalSecurityStateValues(limits).getInt("entryCount"));
        Bundle wrongType=new Bundle();wrongType.putBoolean("key",false);Bundle emptyKey=new Bundle();emptyKey.putString("","v");Bundle nullKey=new Bundle();nullKey.putString(null,"v");
        Bundle longKey=new Bundle();longKey.putString(String.join("",Collections.nCopies(1025,"k")),"v");Bundle longValue=new Bundle();longValue.putString("key",String.join("",Collections.nCopies(8193,"v")));
        for(Bundle invalid:new Bundle[]{wrongType,emptyKey,nullKey,longKey,longValue}){
            try{SecurityCollector.globalSecurityStateValues(invalid);fail("非法键/值不能静默变为字符串或丢弃");}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void globalSecurityStateMatchesPublicApiAndKeepsExistingEvidence()throws Exception{
        ItemSink sink=new ItemSink(context,"platform-security-state-test");new SecurityCollector("security").collect(sink);
        JSONObject item=null;boolean summary=false,providers=false;for(int i=0;i<sink.items.length();i++){JSONObject entry=sink.items.getJSONObject(i);if(entry.getString("key").equals("security/platform-security-state"))item=entry;if(entry.getString("key").equals("security/summary"))summary=true;if(entry.getString("key").equals("security/providers"))providers=true;}
        assertNotNull(item);assertTrue(summary);assertTrue(providers);
        if(Build.VERSION.SDK_INT<35){assertEquals("unsupported",item.getString("status"));assertTrue(item.isNull("value"));return;}
        android.os.SecurityStateManager manager=context.getSystemService(android.os.SecurityStateManager.class);
        if(manager==null){assertEquals("ok",item.getString("status"));JSONObject value=item.getJSONObject("value");assertFalse(value.getBoolean("available"));assertEquals("unavailable",value.getString("status"));assertTrue(value.isNull("entries"));return;}
        Bundle expected;
        try{expected=manager.getGlobalSecurityState();}catch(Exception|LinkageError e){assertEquals(e instanceof SecurityException?"denied":"error",item.getString("status"));assertTrue(item.isNull("value"));return;}
        assertEquals("ok",item.getString("status"));JSONObject value=item.getJSONObject("value");assertTrue(value.getBoolean("available"));assertEquals(expected.size(),value.getInt("entryCount"));ArrayList<String> keys=new ArrayList<>(expected.keySet());Collections.sort(keys);JSONArray all=new JSONArray();
        for(String key:keys)all.put(Json.obj("key",key,"value",expected.get(key)));
        JSONArray entries=value.getJSONArray("entries");assertEquals(Math.min(128,keys.size()),entries.length());for(int i=0;i<entries.length();i++)assertEquals(all.getJSONObject(i).toString(),entries.getJSONObject(i).toString());
        assertEquals(PackageInputCollector.sha(all.toString().getBytes(StandardCharsets.UTF_8)),value.getString("entriesSha256"));
        Bundle evidence=new Bundle();evidence.putString("platformSecurityState",value.toString());InstrumentationRegistry.getInstrumentation().sendStatus(0,evidence);
    }
    @Test public void tlsParametersMatchDefaultContextAndKeepOrderedEvidence()throws Exception{
        javax.net.ssl.SSLContext original=javax.net.ssl.SSLContext.getDefault();
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new SecurityCollector("security").collect(sink);
        JSONObject item=null;boolean providers=false;for(int i=0;i<sink.items.length();i++){JSONObject entry=sink.items.getJSONObject(i);if(entry.getString("key").equals("security/tls-parameters"))item=entry;if(entry.getString("key").equals("security/providers"))providers=true;}
        assertNotNull(item);assertTrue(providers);assertEquals("ok",item.getString("status"));assertSame(original,javax.net.ssl.SSLContext.getDefault());
        JSONObject value=item.getJSONObject("value");assertEquals(original.getProvider().getName(),value.getString("providerName"));assertEquals(original.getProtocol(),value.getString("contextProtocol"));
        javax.net.ssl.SSLParameters supported=original.getSupportedSSLParameters(),defaults=original.getDefaultSSLParameters();
        String[] keys={"supportedProtocols","defaultProtocols","supportedCipherSuites","defaultCipherSuites"};String[][] lists={supported.getProtocols(),defaults.getProtocols(),supported.getCipherSuites(),defaults.getCipherSuites()};
        for(int i=0;i<keys.length;i++){JSONArray all=new JSONArray(Arrays.asList(lists[i])),entries=value.getJSONArray(keys[i]);assertEquals(lists[i].length,value.getInt(keys[i]+"Count"));assertEquals(Math.min(256,lists[i].length),entries.length());assertEquals(lists[i].length>256,value.getBoolean(keys[i]+"Truncated"));for(int j=0;j<entries.length();j++)assertEquals(all.get(j),entries.get(j));assertEquals(PackageInputCollector.sha(all.toString().getBytes(StandardCharsets.UTF_8)),value.getString(keys[i]+"Sha256"));}
        assertEquals(value.toString(),new JSONObject(value.toString()).toString());
        String[] names=new String[258];Arrays.fill(names,"TLS_future");names[0]="";names[1]=null;String[] before=names.clone();
        supported=new javax.net.ssl.SSLParameters(names,new String[]{"TLS_future","TLS_future"});defaults=new javax.net.ssl.SSLParameters(new String[0],new String[0]);
        JSONObject bounded=SecurityCollector.tlsParameterValues("Default","test",supported,defaults);assertEquals("partial",bounded.getString("status"));assertEquals(258,bounded.getInt("supportedCipherSuitesCount"));assertEquals(256,bounded.getJSONArray("supportedCipherSuites").length());assertTrue(bounded.getBoolean("supportedCipherSuitesTruncated"));assertTrue(bounded.getJSONArray("supportedCipherSuites").isNull(1));assertEquals("",bounded.getJSONArray("supportedCipherSuites").getString(0));assertEquals(0,bounded.getInt("defaultProtocolsCount"));assertEquals(2,bounded.getJSONArray("supportedProtocols").length());assertArrayEquals(before,names);
        names[257]="changed-beyond-limit";supported.setCipherSuites(names);JSONObject changed=SecurityCollector.tlsParameterValues("Default","test",supported,defaults);assertEquals(bounded.getJSONArray("supportedCipherSuites").toString(),changed.getJSONArray("supportedCipherSuites").toString());assertNotEquals(bounded.getString("supportedCipherSuitesSha256"),changed.getString("supportedCipherSuitesSha256"));
        try{SecurityCollector.tlsParameterValues("Default","test",new javax.net.ssl.SSLParameters(),defaults);fail("缺失列表不能冒充空集合");}catch(IllegalArgumentException expected){}
        try{SecurityCollector.tlsParameterValues("Default","test",new javax.net.ssl.SSLParameters(new String[4097],new String[0]),defaults);fail("必须拒绝超范围列表");}catch(IllegalArgumentException expected){}
        try{SecurityCollector.tlsParameterValues("Default","test",new javax.net.ssl.SSLParameters(new String[]{String.join("",Collections.nCopies(1025,"x"))},new String[0]),defaults);fail("必须拒绝超长名称");}catch(IllegalArgumentException expected){}
    }
    @After public void finish()throws Exception{for(CollectionController controller:controllers){controller.cancel();long end=SystemClock.elapsedRealtime()+3000;while(controller.running&&SystemClock.elapsedRealtime()<end)Thread.sleep(20);java.lang.reflect.Field f=CollectionController.class.getDeclaredField("control");f.setAccessible(true);((Handler)f.get(controller)).getLooper().quitSafely();}}
    @Test public void originalBytesAndTrueTruncation()throws Exception{
        File f=new File(context.getCacheDir(),"fingerprint-utf8-test.bin");byte[] content="中文\n  原始空白\u0000".getBytes(StandardCharsets.UTF_8);try(FileOutputStream out=new FileOutputStream(f)){out.write(content);}
        JSONObject exact=new JSONObject(NativeProbe.readFile(f.getPath(),content.length));assertTrue(exact.getBoolean("ok"));assertFalse(exact.getBoolean("truncated"));assertEquals(new String(content,StandardCharsets.UTF_8),Json.bytes(exact.getString("base64")));
        JSONObject truncated=new JSONObject(NativeProbe.readFile(f.getPath(),content.length-1));assertTrue(truncated.getBoolean("truncated"));assertEquals(content.length-1,android.util.Base64.decode(truncated.getString("base64"),0).length);
        assertEquals(22,new JSONObject(NativeProbe.readFile(f.getPath(),0)).getInt("errno"));
    }
    @Test public void packageSummaryKeepsRawEnumerationSeparate()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new PackageInputCollector("packages").collect(sink);
        JSONObject summary=null,enumeration=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("packages/summary"))summary=item;if(item.getString("key").equals("packages/enumeration"))enumeration=item;}
        assertNotNull("应生成应用清单摘要",summary);assertNotNull("应保留应用枚举范围",enumeration);assertEquals("ok",summary.getString("status"));assertEquals("ok",enumeration.getString("status"));JSONObject value=summary.getJSONObject("value");assertEquals(value.getInt("returnedCount"),enumeration.getJSONObject("value").getInt("returnedCount"));assertTrue(value.has("debuggableCount"));assertTrue(value.has("systemAppCount"));assertTrue(value.has("targetSdkCounts"));assertTrue(value.has("permissionPackageCount"));assertTrue(value.has("uniqueRequestedPermissionCount"));assertTrue(value.getJSONArray("requestedPermissionNames").length()<=256);assertTrue(value.has("requestedPermissionNamesTruncated"));
    }
    @Test public void inputSummaryKeepsRawDevicesSeparate()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new PackageInputCollector("input").collect(sink);
        JSONObject summary=null;int rawCount=0;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);String key=item.getString("key");if(key.equals("input/summary"))summary=item;if(key.startsWith("input/device/"))rawCount++;}
        assertNotNull("应生成输入设备摘要",summary);assertEquals("ok",summary.getString("status"));JSONObject value=summary.getJSONObject("value");assertEquals(value.getInt("count"),rawCount);assertTrue(value.has("virtualCount"));assertTrue(value.has("keyboardTypeCounts"));assertTrue(value.has("sourceCounts"));
    }
    @Test public void displaySummaryKeepsRawDisplaysSeparate()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new PackageInputCollector("display").collect(sink);
        JSONObject summary=null;int rawCount=0;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);String key=item.getString("key");if(key.equals("display/summary"))summary=item;if(key.startsWith("display/")&&!key.equals("display/summary"))rawCount++;}
        assertNotNull("应生成显示能力摘要",summary);assertEquals("ok",summary.getString("status"));JSONObject value=summary.getJSONObject("value");assertEquals(value.getInt("count"),rawCount);assertTrue(value.has("validCount"));assertTrue(value.has("secureCount"));assertTrue(value.has("presentationCategoryCount"));assertTrue(value.has("hdrCount"));assertTrue(value.getJSONArray("widths").length()<=64);assertTrue(value.getJSONArray("heights").length()<=64);assertTrue(value.getJSONArray("densityDpi").length()<=64);assertTrue(value.getJSONArray("refreshRates").length()<=64);
    }
    @Test public void displayAlternativeRatesPreserveOrderTypesAndBounds()throws Exception{
        float[] rates={90f,59.94f,90f,0f,-1f,Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY},original=rates.clone();
        JSONObject actual=PackageInputCollector.displayModeAlternativeRateValues(rates);JSONArray expected=(JSONArray)Json.value(original);
        assertArrayEquals(original,rates,0f);assertEquals("ok",actual.getString("status"));assertEquals(expected.toString(),actual.getJSONArray("ratesHz").toString());
        assertEquals(PackageInputCollector.sha(expected.toString().getBytes(StandardCharsets.UTF_8)),actual.getString("ratesSha256"));assertEquals(8,actual.getInt("rateCount"));assertFalse(actual.getBoolean("ratesTruncated"));
        rates[0]=120f;assertEquals(90d,actual.getJSONArray("ratesHz").getDouble(0),0d);
        JSONObject empty=PackageInputCollector.displayModeAlternativeRateValues(new float[0]);assertEquals("ok",empty.getString("status"));assertEquals("[]",empty.getJSONArray("ratesHz").toString());assertEquals(0,empty.getInt("rateCount"));assertEquals(PackageInputCollector.sha("[]".getBytes(StandardCharsets.UTF_8)),empty.getString("ratesSha256"));
        float[] many=new float[4096];Arrays.fill(many,60f);JSONObject first=PackageInputCollector.displayModeAlternativeRateValues(many);many[4095]=120f;JSONObject changed=PackageInputCollector.displayModeAlternativeRateValues(many);
        assertEquals("partial",first.getString("status"));assertEquals(4096,first.getInt("rateCount"));assertEquals(64,first.getInt("returnedCount"));assertEquals(64,first.getInt("rateLimit"));assertTrue(first.getBoolean("ratesTruncated"));assertEquals(first.getJSONArray("ratesHz").toString(),changed.getJSONArray("ratesHz").toString());assertNotEquals(first.getString("ratesSha256"),changed.getString("ratesSha256"));
        assertEquals("ok",PackageInputCollector.displayModeAlternativeRateValues(new float[64]).getString("status"));assertEquals("partial",PackageInputCollector.displayModeAlternativeRateValues(new float[65]).getString("status"));
        for(float[] bad:new float[][]{null,new float[4097]})try{PackageInputCollector.displayModeAlternativeRateValues(bad);fail("缺失或超量不能当作空集合");}catch(IllegalArgumentException expectedFailure){}
        for(int sdk:new int[]{24,30}){JSONObject old=PackageInputCollector.displayModeAlternativeRates(null,sdk);assertEquals("unsupported",old.getString("status"));assertTrue(old.isNull("ratesHz"));assertTrue(old.isNull("rateCount"));assertTrue(old.isNull("ratesTruncated"));}
        try{PackageInputCollector.displayModeAlternativeRates(null,31);fail("新API需要模式实例");}catch(NullPointerException expectedFailure){}
    }
    @Test public void displayAlternativeRatesMatchPublicApiAndPreserveModes()throws Exception{
        ItemSink sink=new ItemSink(context,"display-alternative-rate-test");new PackageInputCollector("display").collect(sink);JSONArray evidence=new JSONArray();
        android.hardware.display.DisplayManager manager=context.getSystemService(android.hardware.display.DisplayManager.class);
        for(android.view.Display display:manager.getDisplays()){
            JSONObject item=null;for(int i=0;i<sink.items.length();i++)if(sink.items.getJSONObject(i).getString("key").equals("display/"+display.getDisplayId()))item=sink.items.getJSONObject(i);
            assertNotNull(item);assertEquals("ok",item.getString("status"));JSONArray modes=item.getJSONObject("value").getJSONArray("supportedModes");android.view.Display.Mode[] direct=display.getSupportedModes();assertEquals(direct.length,modes.length());
            for(int i=0;i<direct.length;i++){
                JSONObject mode=modes.getJSONObject(i),actual=mode.getJSONObject("alternativeRefreshRateSupport");assertEquals(direct[i].getModeId(),mode.getInt("modeId"));assertEquals(direct[i].getPhysicalWidth(),mode.getInt("physicalWidth"));assertEquals(direct[i].getPhysicalHeight(),mode.getInt("physicalHeight"));assertEquals((double)direct[i].getRefreshRate(),mode.getDouble("refreshRate"),0d);assertTrue(mode.has("hdrTypeSupport"));
                if(Build.VERSION.SDK_INT<31){assertEquals("unsupported",actual.getString("status"));assertTrue(actual.isNull("ratesHz"));continue;}
                float[] rates=direct[i].getAlternativeRefreshRates();JSONArray all=(JSONArray)Json.value(rates);assertEquals(rates.length>64?"partial":"ok",actual.getString("status"));assertEquals(rates.length,actual.getInt("rateCount"));assertEquals(Math.min(64,rates.length),actual.getInt("returnedCount"));assertEquals(PackageInputCollector.sha(all.toString().getBytes(StandardCharsets.UTF_8)),actual.getString("ratesSha256"));for(int j=0;j<Math.min(64,rates.length);j++)assertEquals(all.get(j).toString(),actual.getJSONArray("ratesHz").get(j).toString());
                evidence.put(Json.obj("displayId",display.getDisplayId(),"modeId",direct[i].getModeId(),"ratesHz",all));
            }
        }
        assertTrue(evidence.length()>0||Build.VERSION.SDK_INT<31);Bundle result=new Bundle();result.putString("alternativeRefreshRates",evidence.toString());InstrumentationRegistry.getInstrumentation().sendStatus(0,result);
    }
    @Test public void displayProductInfoBoundsAndQueryStates()throws Exception{
        assertNull(PackageInputCollector.displayProductInfoValues(null));
        for(int sdk:new int[]{24,30}){JSONObject old=PackageInputCollector.displayProductInfo(sdk,()->{throw new AssertionError("旧API不可执行查询");});assertEquals("unsupported",old.getString("status"));assertTrue(old.isNull("value"));}
        JSONObject absent=PackageInputCollector.displayProductInfo(31,()->null);assertEquals("ok",absent.getString("status"));assertTrue(absent.isNull("value"));
        for(Throwable failure:new Throwable[]{new SecurityException("synthetic"),new IOException("synthetic"),new NoSuchMethodError("synthetic")}){
            JSONObject result=PackageInputCollector.displayProductInfo(31,()->{if(failure instanceof Exception)throw (Exception)failure;throw (LinkageError)failure;});
            assertEquals(failure instanceof SecurityException?"denied":"error",result.getString("status"));assertTrue(result.isNull("value"));assertEquals(failure.getClass().getName(),result.getString("errorClass"));
        }
        if(Build.VERSION.SDK_INT<31)return;
        android.hardware.display.DeviceProductInfo product=new android.hardware.display.DeviceProductInfo(null,"SYN","0007",-1,999);
        JSONObject value=PackageInputCollector.displayProductInfoValues(product);assertEquals(7,value.length());assertTrue(value.isNull("name"));assertEquals("SYN",value.getString("manufacturerPnpId"));assertEquals("0007",value.getString("productId"));assertEquals(-1,value.getInt("modelYear"));assertEquals(-1,value.getInt("manufactureYear"));assertEquals(-1,value.getInt("manufactureWeek"));assertEquals(999,value.getInt("connectionToSinkType"));
        String maximum=new String(new char[2048]).replace('\0','x');
        for(int field=0;field<3;field++){
            String[] strings={"", "SYN", "0007"};strings[field]=maximum;
            JSONObject full=PackageInputCollector.displayProductInfoValues(new android.hardware.display.DeviceProductInfo(strings[0],strings[1],strings[2],2026,0));assertEquals(maximum,full.getString(new String[]{"name","manufacturerPnpId","productId"}[field]));
            strings[field]=maximum+"x";try{PackageInputCollector.displayProductInfoValues(new android.hardware.display.DeviceProductInfo(strings[0],strings[1],strings[2],2026,0));fail("字符串最多2048字符");}catch(IllegalArgumentException expected){}
        }
        JSONObject empty=PackageInputCollector.displayProductInfoValues(new android.hardware.display.DeviceProductInfo("","","",0,-1));assertEquals("",empty.getString("name"));assertEquals("",empty.getString("productId"));assertEquals(0,empty.getInt("modelYear"));assertEquals(-1,empty.getInt("connectionToSinkType"));
    }
    @Test public void displayProductInfoMatchesPublicApiAndPreservesDisplay()throws Exception{
        ItemSink sink=new ItemSink(context,"display-product-test");new PackageInputCollector("display").collect(sink);JSONArray evidence=new JSONArray();
        android.hardware.display.DisplayManager manager=context.getSystemService(android.hardware.display.DisplayManager.class);
        for(android.view.Display display:manager.getDisplays()){
            JSONObject item=null;for(int i=0;i<sink.items.length();i++)if(sink.items.getJSONObject(i).getString("key").equals("display/"+display.getDisplayId()))item=sink.items.getJSONObject(i);
            assertNotNull(item);assertEquals("ok",item.getString("status"));JSONObject value=item.getJSONObject("value"),query=value.getJSONObject("deviceProductInfo");
            if(Build.VERSION.SDK_INT<31){assertEquals("unsupported",query.getString("status"));assertTrue(query.isNull("value"));}else{
                assertEquals("ok",query.getString("status"));android.hardware.display.DeviceProductInfo product=display.getDeviceProductInfo();
                if(product==null)assertTrue(query.isNull("value"));else{
                    JSONObject actual=query.getJSONObject("value");assertEquals(product.getName(),actual.isNull("name")?null:actual.getString("name"));assertEquals(product.getManufacturerPnpId(),actual.getString("manufacturerPnpId"));assertEquals(product.getProductId(),actual.getString("productId"));assertEquals(product.getModelYear(),actual.getInt("modelYear"));assertEquals(product.getManufactureYear(),actual.getInt("manufactureYear"));assertEquals(product.getManufactureWeek(),actual.getInt("manufactureWeek"));assertEquals(product.getConnectionToSinkType(),actual.getInt("connectionToSinkType"));
                }
            }
            assertEquals(display.getSupportedModes().length,value.getJSONArray("supportedModes").length());assertTrue(value.has("colorCapabilities"));if(display.getHdrCapabilities()!=null)assertTrue(value.has("hdr"));evidence.put(Json.obj("displayId",display.getDisplayId(),"deviceProductInfo",query));
        }
        assertTrue(evidence.length()>0);Bundle result=new Bundle();result.putString("displayProductEvidence",evidence.toString());InstrumentationRegistry.getInstrumentation().sendStatus(0,result);
    }
    @Test public void displayColorValuesAndQueryStates()throws Exception{
        assertNull(PackageInputCollector.displayColorSpaceValues(null));
        for(android.graphics.ColorSpace.Named named:new android.graphics.ColorSpace.Named[]{android.graphics.ColorSpace.Named.SRGB,android.graphics.ColorSpace.Named.DISPLAY_P3,android.graphics.ColorSpace.Named.LINEAR_EXTENDED_SRGB,android.graphics.ColorSpace.Named.CIE_LAB}){
            android.graphics.ColorSpace color=android.graphics.ColorSpace.get(named);JSONObject value=PackageInputCollector.displayColorSpaceValues(color);assertEquals(color.getName(),value.getString("name"));assertEquals(color.getId(),value.getInt("id"));assertEquals(color.getModel().name(),value.getString("model"));assertEquals(color.getComponentCount(),value.getInt("componentCount"));assertEquals(color.isWideGamut(),value.getBoolean("wideGamut"));assertEquals(color.isSrgb(),value.getBoolean("srgb"));
            JSONArray ranges=value.getJSONArray("componentRanges");assertEquals(color.getComponentCount(),ranges.length());for(int i=0;i<ranges.length();i++){assertEquals(i,ranges.getJSONObject(i).getInt("index"));assertEquals((double)color.getMinValue(i),ranges.getJSONObject(i).getDouble("min"),0d);assertEquals((double)color.getMaxValue(i),ranges.getJSONObject(i).getDouble("max"),0d);}
        }
        JSONObject lab=PackageInputCollector.displayColorSpaceValues(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.CIE_LAB));assertTrue(lab.getJSONArray("componentRanges").getJSONObject(1).getDouble("min")<0);assertTrue(lab.getJSONArray("componentRanges").getJSONObject(0).getDouble("max")>1);
        float[] primaries={0.64f,0.33f,0.30f,0.60f,0.15f,0.06f},white={0.3127f,0.3290f};android.graphics.ColorSpace.Rgb custom=new android.graphics.ColorSpace.Rgb("合成色彩空间",primaries,white,2.2);assertEquals(-1,PackageInputCollector.displayColorSpaceValues(custom).getInt("id"));
        String maximum=new String(new char[2048]).replace('\0','x');assertEquals(maximum,PackageInputCollector.displayColorSpaceValues(new android.graphics.ColorSpace.Rgb(maximum,primaries,white,2.2)).getString("name"));try{PackageInputCollector.displayColorSpaceValues(new android.graphics.ColorSpace.Rgb(maximum+"x",primaries,white,2.2));fail("名称最多2048字符");}catch(IllegalArgumentException expected){}
        for(int sdk:new int[]{24,25}){JSONObject value=PackageInputCollector.displayColorCapabilities(null,sdk);for(String key:new String[]{"wideColorGamut","preferredWideGamutColorSpace"}){assertEquals("unsupported",value.getJSONObject(key).getString("status"));assertTrue(value.getJSONObject(key).isNull("value"));}}
        JSONObject old=PackageInputCollector.displayColorQuery(28,29,()->{throw new AssertionError("不能调用旧API");});assertEquals("unsupported",old.getString("status"));
        assertFalse(PackageInputCollector.displayColorQuery(26,26,()->false).getBoolean("value"));assertTrue(PackageInputCollector.displayColorQuery(29,29,()->null).isNull("value"));
        JSONObject denied=PackageInputCollector.displayColorQuery(29,29,()->{throw new SecurityException("synthetic");});assertEquals("denied",denied.getString("status"));assertTrue(denied.isNull("value"));assertEquals("java.lang.SecurityException",denied.getString("errorClass"));
        for(ItemSink.Read read:new ItemSink.Read[]{()->{throw new IOException("synthetic");},()->{throw new NoSuchMethodError("synthetic");}}){JSONObject failure=PackageInputCollector.displayColorQuery(29,29,read);assertEquals("error",failure.getString("status"));assertTrue(failure.isNull("value"));assertTrue(failure.has("errorClass"));}
    }
    @Test public void displayColorMatchesPublicApiAndKeepsExistingModes()throws Exception{
        ItemSink sink=new ItemSink(context,"display-color-test");new PackageInputCollector("display").collect(sink);JSONArray evidence=new JSONArray();
        android.hardware.display.DisplayManager manager=context.getSystemService(android.hardware.display.DisplayManager.class);
        for(android.view.Display display:manager.getDisplays()){
            JSONObject item=null;for(int i=0;i<sink.items.length();i++)if(sink.items.getJSONObject(i).getString("key").equals("display/"+display.getDisplayId()))item=sink.items.getJSONObject(i);assertNotNull(item);assertEquals("ok",item.getString("status"));JSONObject value=item.getJSONObject("value"),color=value.getJSONObject("colorCapabilities"),wide=color.getJSONObject("wideColorGamut"),preferred=color.getJSONObject("preferredWideGamutColorSpace");
            if(Build.VERSION.SDK_INT>=26){assertEquals("ok",wide.getString("status"));assertEquals(display.isWideColorGamut(),wide.getBoolean("value"));}else{assertEquals("unsupported",wide.getString("status"));assertTrue(wide.isNull("value"));}
            if(Build.VERSION.SDK_INT<29){assertEquals("unsupported",preferred.getString("status"));assertTrue(preferred.isNull("value"));}else{
                assertEquals("ok",preferred.getString("status"));android.graphics.ColorSpace direct=display.getPreferredWideGamutColorSpace();if(direct==null)assertTrue(preferred.isNull("value"));else{
                    JSONObject actual=preferred.getJSONObject("value");assertEquals(direct.getName(),actual.getString("name"));assertEquals(direct.getId(),actual.getInt("id"));assertEquals(direct.getModel().name(),actual.getString("model"));assertEquals(direct.isWideGamut(),actual.getBoolean("wideGamut"));assertEquals(direct.isSrgb(),actual.getBoolean("srgb"));assertEquals(direct.getComponentCount(),actual.getInt("componentCount"));JSONArray ranges=actual.getJSONArray("componentRanges");assertEquals(direct.getComponentCount(),ranges.length());for(int c=0;c<ranges.length();c++){assertEquals(c,ranges.getJSONObject(c).getInt("index"));assertEquals((double)direct.getMinValue(c),ranges.getJSONObject(c).getDouble("min"),0d);assertEquals((double)direct.getMaxValue(c),ranges.getJSONObject(c).getDouble("max"),0d);}
                }
            }
            if(display.getHdrCapabilities()!=null)assertTrue(value.has("hdr"));assertEquals(display.getSupportedModes().length,value.getJSONArray("supportedModes").length());evidence.put(Json.obj("displayId",display.getDisplayId(),"colorCapabilities",color));
        }
        assertTrue(evidence.length()>0);Bundle result=new Bundle();result.putString("displayColorEvidence",evidence.toString());InstrumentationRegistry.getInstrumentation().sendStatus(0,result);
    }
    @Test public void displayModeHdrKeepsUnknownValuesAndCompleteHash()throws Exception{
        int[] types={4,2,4,999,-1,0},original=types.clone();JSONObject value=PackageInputCollector.displayModeHdrValues(types);
        assertArrayEquals(original,types);assertEquals("ok",value.getString("status"));assertEquals(6,value.getInt("typeCount"));assertEquals(6,value.getInt("returnedCount"));assertFalse(value.getBoolean("typesTruncated"));
        assertEquals("[4,2,4,999,-1,0]",value.getJSONArray("types").toString());assertEquals(PackageInputCollector.sha("[4,2,4,999,-1,0]".getBytes(StandardCharsets.UTF_8)),value.getString("typeListSha256"));
        JSONObject empty=PackageInputCollector.displayModeHdrValues(new int[0]);assertEquals("ok",empty.getString("status"));assertEquals(0,empty.getInt("typeCount"));assertEquals("[]",empty.getJSONArray("types").toString());
        int[] many=new int[65];for(int i=0;i<many.length;i++)many[i]=i;JSONObject first=PackageInputCollector.displayModeHdrValues(many);many[64]=9001;JSONObject changed=PackageInputCollector.displayModeHdrValues(many);
        assertEquals("partial",first.getString("status"));assertEquals(65,first.getInt("typeCount"));assertEquals(64,first.getInt("returnedCount"));assertEquals(64,first.getInt("typeLimit"));assertTrue(first.getBoolean("typesTruncated"));assertEquals(first.getJSONArray("types").toString(),changed.getJSONArray("types").toString());assertNotEquals(first.getString("typeListSha256"),changed.getString("typeListSha256"));
        for(int[] bad:new int[][]{null,new int[20001]})try{PackageInputCollector.displayModeHdrValues(bad);fail("缺失/过量HDR类型不能猜为空集合");}catch(IllegalArgumentException expected){}
        try{PackageInputCollector.displayModeHdr(null);fail("缺失显示模式不能查询");}catch(NullPointerException expected){}
    }
    @Test public void displayModeHdrMatchesPublicApiAndPreservesLegacyHdr()throws Exception{
        ItemSink sink=new ItemSink(context,"display-mode-hdr-test");new PackageInputCollector("display").collect(sink);
        android.hardware.display.DisplayManager manager=context.getSystemService(android.hardware.display.DisplayManager.class);int checked=0;
        for(android.view.Display display:manager.getDisplays()){
            JSONObject item=null;for(int i=0;i<sink.items.length();i++)if(sink.items.getJSONObject(i).getString("key").equals("display/"+display.getDisplayId()))item=sink.items.getJSONObject(i);
            assertNotNull(item);assertEquals("ok",item.getString("status"));JSONObject value=new JSONObject(item.toString()).getJSONObject("value");JSONArray modes=value.getJSONArray("supportedModes");android.view.Display.Mode[] direct=display.getSupportedModes();assertEquals(direct.length,modes.length());
            for(int i=0;i<direct.length;i++){
                JSONObject mode=modes.getJSONObject(i),hdr=mode.getJSONObject("hdrTypeSupport");assertEquals(direct[i].getModeId(),mode.getInt("modeId"));assertEquals(direct[i].getPhysicalWidth(),mode.getInt("physicalWidth"));assertEquals(direct[i].getPhysicalHeight(),mode.getInt("physicalHeight"));assertEquals(direct[i].getRefreshRate(),mode.getDouble("refreshRate"),0.001);
                if(Build.VERSION.SDK_INT<34){assertEquals("unsupported",hdr.getString("status"));assertTrue(hdr.isNull("types"));assertTrue(hdr.isNull("typeCount"));continue;}
                int[] types=direct[i].getSupportedHdrTypes();JSONArray all=new JSONArray();for(int type:types)all.put(type);assertEquals(types.length>64?"partial":"ok",hdr.getString("status"));assertEquals(types.length,hdr.getInt("typeCount"));assertEquals(Math.min(64,types.length),hdr.getInt("returnedCount"));assertEquals(PackageInputCollector.sha(all.toString().getBytes(StandardCharsets.UTF_8)),hdr.getString("typeListSha256"));for(int t=0;t<Math.min(64,types.length);t++)assertEquals(types[t],hdr.getJSONArray("types").getInt(t));checked++;
            }
            android.view.Display.HdrCapabilities old=display.getHdrCapabilities();if(old!=null){assertTrue(value.has("hdr"));JSONArray types=value.getJSONObject("hdr").getJSONArray("types");assertEquals(old.getSupportedHdrTypes().length,types.length());for(int t=0;t<types.length();t++)assertEquals(old.getSupportedHdrTypes()[t],types.getInt(t));}
        }
        Bundle evidence=new Bundle();evidence.putInt("displayModesHdrCompared",checked);InstrumentationRegistry.getInstrumentation().sendStatus(0,evidence);
    }
    @Test public void sysconfSummaryKeepsPositiveValuesAndRawEvidence()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new BuildSystemCollector("kernel").collect(sink);
        JSONObject item=null;boolean uname=false;for(int i=0;i<sink.items.length();i++){JSONObject entry=sink.items.getJSONObject(i);if(entry.getString("key").equals("kernel/sysconf-summary"))item=entry;if(entry.getString("key").equals("kernel/uname"))uname=true;}
        assertNotNull(item);assertTrue("原内核条目应保留",uname);assertEquals("ok",item.getString("status"));JSONObject value=new JSONObject(item.toString()).getJSONObject("value");assertEquals("current-app-runtime",value.getString("scope"));assertEquals(2,value.getInt("queryCount"));assertEquals(2,value.getInt("knownCount")+value.getInt("unknownCount"));
        String[] fields={"pageSizeBytes","clockTicksPerSecond"},names={"_SC_PAGESIZE","_SC_CLK_TCK"};int[] selectors={android.system.OsConstants._SC_PAGESIZE,android.system.OsConstants._SC_CLK_TCK};JSONArray entries=value.getJSONArray("entries");assertEquals(2,entries.length());int known=0;
        for(int i=0;i<2;i++){JSONObject entry=entries.getJSONObject(i);assertEquals(names[i],entry.getString("name"));String status=entry.getString("status");if(status.equals("ok")){known++;long observed=Long.parseLong(value.getJSONObject(fields[i]).getString("value"));assertEquals(android.system.Os.sysconf(selectors[i]),observed);assertTrue(observed>0);assertEquals("int64",value.getJSONObject(fields[i]).getString("$type"));assertEquals(observed,Long.parseLong(entry.getJSONObject("rawValue").getString("value")));}else{assertTrue(Arrays.asList("unavailable","denied","error").contains(status));assertTrue(value.isNull(fields[i]));}}
        assertEquals(known,value.getInt("knownCount"));assertEquals(known>0,value.getBoolean("available"));assertEquals(known==2?"ok":known>0?"partial":"unavailable",value.getString("status"));
    }
    @Test public void rationalNumbersUseLosslessJsonObjects()throws Exception{
        android.util.Rational[] values={new android.util.Rational(1,6),new android.util.Rational(-3,7),android.util.Rational.ZERO,android.util.Rational.NaN,android.util.Rational.POSITIVE_INFINITY,android.util.Rational.NEGATIVE_INFINITY};
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());
        sink.ok("hardware","test/rational","分数","java","synthetic",values[0]);
        sink.ok("hardware","test/rational-array","分数数组","java","synthetic",values);
        sink.ok("hardware","test/rational-object","嵌套分数","java","synthetic",Json.obj("step",values[0],"values",Arrays.asList(values)));
        JSONArray parsed=new JSONArray(sink.items.toString());assertEquals("object",parsed.getJSONObject(0).getString("dataType"));assertEquals("1/6",parsed.getJSONObject(0).getJSONObject("value").getString("value"));
        JSONArray array=parsed.getJSONObject(1).getJSONArray("value");assertEquals(values.length,array.length());
        for(int i=0;i<values.length;i++){JSONObject entry=array.getJSONObject(i);assertEquals("android.util.Rational",entry.getString("$type"));assertEquals(values[i].toString(),entry.getString("value"));assertEquals(values[i].getNumerator(),entry.getInt("numerator"));assertEquals(values[i].getDenominator(),entry.getInt("denominator"));}
        JSONObject nested=parsed.getJSONObject(2).getJSONObject("value");assertEquals("1/6",nested.getJSONObject("step").getString("value"));assertEquals(array.toString(),nested.getJSONArray("values").toString());
        File checkpoint=new File(context.getCacheDir(),"rational-checkpoint.json");ReportStore.write(checkpoint,sink.items.toString());assertEquals(parsed.toString(),new JSONArray(ReportStore.read(checkpoint)).toString());
    }
    @Test public void binaryLongAndStatusBoundaries()throws Exception{
        Object binary=Json.bytes(android.util.Base64.encodeToString(new byte[]{(byte)255,0},android.util.Base64.NO_WRAP));assertTrue(binary instanceof JSONObject);assertEquals("base64",((JSONObject)binary).getString("$type"));
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());sink.ok("build","test/long","long","java","test",Long.MAX_VALUE);sink.read("build","test/null","null","test",()->null);sink.read("build","test/empty","empty","test",()->"");sink.read("build","test/denied","denied","test",()->{throw new SecurityException("合成拒绝");});sink.read("build","test/unsupported","unsupported","test",()->{throw new UnsupportedOperationException("合成不支持");});
        assertEquals("9223372036854775807",sink.items.getJSONObject(0).getString("value"));assertEquals("int64",sink.items.getJSONObject(0).getString("dataType"));assertTrue(sink.items.getJSONObject(1).isNull("value"));assertEquals("ok",sink.items.getJSONObject(2).getString("status"));assertEquals("denied",sink.items.getJSONObject(3).getString("status"));assertEquals("unsupported",sink.items.getJSONObject(4).getString("status"));
    }
    @Test public void directoryLimitAndSymlinkAncestors()throws Exception{
        File root=new File(context.getCacheDir(),"directory-fixture-"+UUID.randomUUID());root.mkdirs();for(int i=0;i<4;i++)new File(root,"entry-"+i).createNewFile();
        JSONObject limited=new JSONObject(NativeProbe.listDirectory(root.getCanonicalPath(),2));assertTrue(limited.toString(),limited.getBoolean("ok"));assertTrue(limited.toString(),limited.getBoolean("truncated"));assertEquals(2,limited.getJSONArray("names").length());
        File link=new File(root.getParentFile(),root.getName()+"-link");android.system.Os.symlink(root.getCanonicalPath(),link.getCanonicalPath());String path=link.getParentFile().getCanonicalPath()+"/"+link.getName();
        assertEquals("symlink",new JSONObject(NativeProbe.inspectPath(path)).getString("type"));assertFalse(new JSONObject(NativeProbe.listDirectory(path,10)).getBoolean("ok"));assertFalse(new JSONObject(NativeProbe.inspectPath(path+"/entry-0")).getBoolean("ok"));
    }
    @Test public void processStatusSummaryKeepsRawStatusSeparate()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new BuildSystemCollector("kernel").collect(sink);
        JSONObject summary=null,raw=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("kernel/process-status-summary"))summary=item;if(item.getString("key").equals("kernel/file/proc/self/status"))raw=item;}
        assertNotNull("应生成当前进程状态摘要",summary);assertNotNull("应保留 /proc/self/status 原始条目",raw);assertEquals("ok",summary.getString("status"));JSONObject value=summary.getJSONObject("value");assertTrue(value.getBoolean("available"));assertTrue(value.has("traced"));assertTrue(value.has("tracerPid"));assertTrue(value.has("threadCount"));assertTrue(value.has("noNewPrivs"));assertTrue(value.has("seccompMode"));assertTrue(value.has("capEffPresent"));assertTrue(value.has("capBndPresent"));assertFalse(summary.toString().contains("Name:"));assertFalse(summary.toString().contains("VmRSS:"));
    }
    @Test public void mapsSummaryUsesBoundedEvidenceWithoutPaths()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new BuildSystemCollector("kernel").collect(sink);
        JSONObject summary=null,raw=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("kernel/maps-summary"))summary=item;if(item.getString("key").equals("kernel/file/proc/self/maps"))raw=item;}
        assertNotNull("应生成内存映射摘要",summary);assertNotNull("应保留 /proc/self/maps 原始条目",raw);String status=summary.getString("status");assertTrue("maps 读取状态应明确: "+status,status.equals("ok")||status.equals("denied")||status.equals("unavailable")||status.equals("error"));
        if(status.equals("ok")){JSONObject value=summary.getJSONObject("value");assertTrue("maps should be available: "+value,value.getBoolean("available"));assertTrue("mapCount="+value.optInt("mapCount",-1),value.getInt("mapCount")>0);assertTrue("executableCount="+value.optInt("executableMappingCount",-1),value.getInt("executableMappingCount")<=value.getInt("mapCount"));assertTrue("writableCount="+value.optInt("writableMappingCount",-1),value.getInt("writableMappingCount")<=value.getInt("mapCount"));assertTrue("markerPresence missing: "+value,value.has("markerPresence"));assertTrue("permissionCounts missing: "+value,value.has("permissionCounts"));assertTrue("truncated missing: "+value,value.has("truncated"));assertFalse("summary leaked path: "+summary,summary.toString().contains("/data/"));}
    }
    @Test public void mountSummaryKeepsRawMountInfoSeparate()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new BuildSystemCollector("kernel").collect(sink);
        JSONObject summary=null,raw=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("kernel/mount-summary"))summary=item;if(item.getString("key").equals("kernel/file/proc/self/mountinfo"))raw=item;}
        assertNotNull("应生成挂载拓扑摘要",summary);assertNotNull("应保留 mountinfo 原始条目",raw);assertEquals("ok",summary.getString("status"));JSONObject value=summary.getJSONObject("value");assertTrue(value.getBoolean("available"));assertTrue(value.getInt("entryCount")>0);assertTrue(value.has("overlayCount"));assertTrue(value.has("readOnlyCount"));assertTrue(value.has("rootFsType"));assertTrue(value.has("fsTypeCounts"));assertTrue(value.getInt("fsTypeCount")>0);assertFalse(summary.toString().contains("/data/"));assertFalse(summary.toString().contains("/proc/"));
    }
    @Test public void cgroupSummaryKeepsPathsOutOfEvidence()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new BuildSystemCollector("kernel").collect(sink);
        JSONObject summary=null,raw=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("kernel/cgroup-summary"))summary=item;if(item.getString("key").equals("kernel/file/proc/self/cgroup"))raw=item;}
        assertNotNull("应生成 cgroup 环境摘要",summary);assertNotNull("应保留 cgroup 原始条目",raw);assertEquals("ok",summary.getString("status"));JSONObject value=summary.getJSONObject("value");assertTrue(value.getBoolean("available"));assertTrue(value.has("cgroupVersion"));assertTrue(value.getInt("entryCount")>0);assertTrue(value.has("controllerNames"));assertTrue(value.has("maxPathDepth"));assertTrue(value.has("dockerMarker"));assertFalse(summary.toString().contains("/uid_"));assertFalse(summary.toString().contains("/user.slice"));
    }
    @Test public void cpuSummaryKeepsTopologyBounded()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new BuildSystemCollector("kernel").collect(sink);
        JSONObject summary=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("kernel/cpu-summary")){summary=item;break;}}
        assertNotNull("应生成 CPU 拓扑摘要",summary);assertEquals("ok",summary.getString("status"));JSONObject value=summary.getJSONObject("value");assertTrue(value.getBoolean("available"));assertTrue(value.getInt("logicalProcessorCount")>0);assertTrue(value.getInt("runtimeProcessorCount")>0);assertTrue(value.has("presentCount"));assertTrue(value.has("onlineCount"));assertTrue(value.has("governorCounts"));assertTrue(value.getJSONArray("coreIds").length()<=64);assertTrue(value.getJSONArray("physicalPackageIds").length()<=64);
    }
    @Test public void storageSummaryKeepsRawVolumesSeparate()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new BuildSystemCollector("storage").collect(sink);
        JSONObject summary=null,capacity=null,volumes=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("storage/summary"))summary=item;if(item.getString("key").equals("storage/capacity-summary"))capacity=item;if(item.getString("key").equals("storage/volumes"))volumes=item;}
        assertNotNull("应生成存储卷摘要",summary);assertNotNull("应生成存储容量摘要",capacity);assertNotNull("应保留存储卷原始清单",volumes);assertEquals("ok",summary.getString("status"));assertEquals("ok",capacity.getString("status"));assertEquals("ok",volumes.getString("status"));JSONObject value=summary.getJSONObject("value");assertEquals(value.getInt("volumeCount"),volumes.getJSONArray("value").length());assertTrue(value.has("mountedCount"));assertTrue(value.has("stateCounts"));JSONObject capacityValue=capacity.getJSONObject("value");assertTrue(capacityValue.getInt("entryCount")<=8);assertEquals(capacityValue.getInt("entryCount"),capacityValue.getJSONArray("entries").length());for(int i=0;i<capacityValue.getJSONArray("entries").length();i++){JSONObject entry=capacityValue.getJSONArray("entries").getJSONObject(i);assertTrue(entry.has("label"));assertTrue(entry.has("totalBytes"));assertTrue(entry.has("availableBytes"));}
    }
    @Test public void atomicJsonGzipRoundTrip()throws Exception{
        File f=new File(context.getCacheDir(),"fingerprint-export-test.json");String raw=Json.obj("text","中文\n\u0000","long",Long.MAX_VALUE).toString();ReportStore.write(f,raw);File gz=ReportStore.gzip(f);ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(GZIPInputStream in=new GZIPInputStream(new FileInputStream(gz))){byte[] b=new byte[1024];int n;while((n=in.read(b))!=-1)bytes.write(b,0,n);}assertArrayEquals(raw.getBytes(StandardCharsets.UTF_8),bytes.toByteArray());assertFalse(raw.startsWith("\ufeff"));
    }
    private Context hungContext(){File root=new File(context.getCacheDir(),"controller-fixture-"+UUID.randomUUID());root.mkdirs();return new ContextWrapper(context){
        public File getFilesDir(){File f=new File(root,"files");f.mkdirs();return f;}
        public File getExternalFilesDir(String type){File f=new File(root,"external");f.mkdirs();return f;}
        public boolean bindService(Intent intent,ServiceConnection connection,int flags){Handler handler=new Handler(Looper.getMainLooper(),msg->{if(msg.what==CollectorService.HELLO){Message reply=Message.obtain(null,CollectorService.HELLO);reply.arg1=android.os.Process.myPid();try{msg.replyTo.send(reply);}catch(Exception e){throw new RuntimeException(e);}}return true;});new Handler(Looper.getMainLooper()).post(()->connection.onServiceConnected(new ComponentName(context,CollectorService.class),new Messenger(handler).getBinder()));return true;}
        public void unbindService(ServiceConnection connection){}
    };}
    private JSONObject waitForFile(CollectionController c,long timeout)throws Exception{long end=SystemClock.elapsedRealtime()+timeout;while(SystemClock.elapsedRealtime()<end){if(c.reportFile!=null&&c.reportFile.isFile())return new JSONObject(ReportStore.read(c.reportFile));Thread.sleep(20);}throw new AssertionError("控制器未写出指纹");}
    @Test public void duplicateStartAndCancelPreservePartial()throws Exception{
        CollectionController c=new CollectionController(hungContext());controllers.add(c);c.start();c.start();JSONObject first=waitForFile(c,5000);String session=first.getJSONObject("data").getString("sessionId");c.cancel();long end=SystemClock.elapsedRealtime()+3000;while(c.running&&SystemClock.elapsedRealtime()<end)Thread.sleep(20);JSONObject last=new JSONObject(ReportStore.read(c.reportFile));assertEquals(session,last.getJSONObject("data").getString("sessionId"));assertEquals("cancelled",last.getJSONObject("data").getString("state"));assertFalse(c.running);
    }
    @Test public void activityRecreationKeepsSessionAndCanCancel()throws Exception{
        KeyguardManager keyguard=context.getSystemService(KeyguardManager.class);
        Assume.assumeFalse("ActivityScenario 重建需要设备处于解锁状态",keyguard!=null&&keyguard.isKeyguardLocked());
        CollectionController c=CollectionController.get(context);
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            File previous=c.reportFile;
            scenario.onActivity(activity->c.start());
            long end=SystemClock.elapsedRealtime()+5000;
            while((c.reportFile==null||c.reportFile.equals(previous)||!c.reportFile.isFile())&&SystemClock.elapsedRealtime()<end)Thread.sleep(20);
            assertNotEquals("必须等本轮会话发布，不能消费历史报告",previous,c.reportFile);
            String session=waitForFile(c,5000).getJSONObject("data").getString("sessionId");
            scenario.recreate();
            scenario.onActivity(activity->assertSame(c,CollectionController.get(activity)));
            assertEquals(session,new JSONObject(ReportStore.read(c.reportFile)).getJSONObject("data").getString("sessionId"));
            c.cancel();end=SystemClock.elapsedRealtime()+5000;while(c.running&&SystemClock.elapsedRealtime()<end)Thread.sleep(20);
            assertFalse(c.running);
            assertEquals("cancelled",new JSONObject(ReportStore.read(c.reportFile)).getJSONObject("data").getString("state"));
        }finally{c.cancel();}
    }
    @Test public void hungBinderTimesOutAndMovesToNextModule()throws Exception{
        CollectionController c=new CollectionController(hungContext());controllers.add(c);c.start();waitForFile(c,5000);long end=SystemClock.elapsedRealtime()+19000;boolean timedOut=false;while(SystemClock.elapsedRealtime()<end){JSONObject data=new JSONObject(ReportStore.read(c.reportFile)).getJSONObject("data");JSONArray items=data.getJSONArray("items");for(int i=0;i<items.length();i++)if(items.getJSONObject(i).optString("status").equals("timeout"))timedOut=true;if(timedOut)break;Thread.sleep(50);}assertTrue("阻塞模块应被标记超时",timedOut);assertTrue("超时后仍应继续下一模块",c.running);assertTrue(c.progress>0);c.cancel();
    }
    @Test public void interruptedReportIsNotReportedComplete()throws Exception{
        Context isolated=hungContext();File report=new File(ReportStore.exports(isolated),"fingerprint-interrupted-test.json");ReportStore.write(report,Json.obj("data",Json.obj("state","running"),"message","合成中断测试","code",2).toString());ReportStore.write(new File(isolated.getFilesDir(),"latest-path.txt"),report.getAbsolutePath());CollectionController c=new CollectionController(isolated);controllers.add(c);JSONObject result=new JSONObject(ReportStore.read(report));assertEquals("interrupted",result.getJSONObject("data").getString("state"));assertEquals(1,result.getInt("code"));
    }
    @Test public void temporaryKeyIsRemovedEvenAfterGeneration()throws Exception{
        String alias="fp_collect_test_"+UUID.randomUUID();try{SecurityCollector.key(alias,false,false);}finally{KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);assertFalse("仅验证本测试 alias，不触碰其它密钥",store.containsAlias(alias));}
    }
    @Test public void virtualizationSummaryKeepsRawEvidence()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new SecurityCollector("virtualization").collect(sink);
        JSONObject summary=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("virtualization/risk-summary")){summary=item;break;}}
        assertNotNull("应生成虚拟化与提权线索摘要",summary);JSONObject value=summary.getJSONObject("value");assertEquals(13,value.getInt("pathChecks"));assertTrue(value.getBoolean("evidenceOnly"));assertTrue(value.has("presentPaths"));assertTrue(value.has("matchedBuildMarkers"));
    }
    @Test public void nativeRiskPropertiesSummaryKeepsStableShape()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new BuildSystemCollector("build").collect(sink);
        JSONObject summary=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("build/risk-properties-summary")){summary=item;break;}}
        assertNotNull("应生成原生风控属性摘要",summary);assertEquals("ok",summary.getString("status"));JSONObject value=summary.getJSONObject("value");assertEquals(19,value.getInt("knownCount"));assertTrue(value.has("observedCount"));assertTrue(value.has("observedProperties"));assertTrue(value.has("missingProperties"));assertEquals(value.getInt("knownCount"),value.getJSONArray("observedProperties").length()+value.getJSONArray("missingProperties").length());JSONObject properties=value.getJSONObject("properties");assertTrue(properties.has("ro.boot.verifiedbootstate"));assertTrue(properties.has("ro.boot.vbmeta.digest"));assertTrue(properties.has("ro.boot.slot_suffix"));assertTrue(properties.has("ro.debuggable"));assertTrue(properties.has("ro.adb.secure"));
    }
    @Test public void abiSummaryKeepsRawBuildFieldsSeparate()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new BuildSystemCollector("build").collect(sink);
        JSONObject summary=null,raw=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("build/abi-summary"))summary=item;if(item.getString("key").equals("build/Build/SUPPORTED_ABIS"))raw=item;}
        assertNotNull("应生成 ABI 摘要",summary);assertNotNull("应保留 Build.SUPPORTED_ABIS 原始字段",raw);assertEquals("ok",summary.getString("status"));JSONObject value=summary.getJSONObject("value");assertTrue(value.getBoolean("available"));int total=value.getInt("supportedAbiCount");assertTrue(total>=0);assertTrue(value.getInt("supported32BitAbiCount")<=total);assertTrue(value.getInt("supported64BitAbiCount")<=total);assertEquals(total,value.getInt("armAbiCount")+value.getInt("x86AbiCount")+value.getInt("riscvAbiCount")+value.getInt("otherAbiCount"));if(total>0)assertTrue(value.getString("primaryAbi").length()>0);assertTrue(value.has("runtime64Bit"));
    }
    @Test public void hardwareSummaryKeepsRawBuildIdentitySeparate()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new BuildSystemCollector("build").collect(sink);
        JSONObject summary=null,raw=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("build/hardware-summary"))summary=item;if(item.getString("key").equals("build/Build/FINGERPRINT"))raw=item;}
        assertNotNull("应生成硬件型号摘要",summary);assertNotNull("应保留 Build.FINGERPRINT 原始字段",raw);assertEquals("ok",summary.getString("status"));JSONObject value=summary.getJSONObject("value");assertTrue(value.getBoolean("available"));assertTrue(value.has("manufacturer"));assertTrue(value.has("model"));assertTrue(value.has("fingerprintPresent"));if(value.getBoolean("fingerprintPresent")){assertTrue(value.getInt("fingerprintLength")>0);assertTrue(value.getString("fingerprintSha256").matches("[0-9a-f]{64}"));}if(Build.VERSION.SDK_INT>=31){assertTrue(value.has("socManufacturer"));assertTrue(value.has("socModel"));}assertFalse(summary.toString().contains("ro.build.fingerprint"));
    }
    @Test public void osSummaryKeepsRawVersionFieldsSeparate()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new BuildSystemCollector("build").collect(sink);
        JSONObject summary=null,raw=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("build/os-summary"))summary=item;if(item.getString("key").equals("build/VERSION/SDK_INT"))raw=item;}
        assertNotNull("应生成 Android 系统版本摘要",summary);assertNotNull("应保留 Build.VERSION 原始字段",raw);assertEquals("ok",summary.getString("status"));JSONObject value=summary.getJSONObject("value");assertTrue(value.getBoolean("available"));assertEquals(Build.VERSION.SDK_INT,value.getInt("sdkInt"));assertTrue(value.has("release"));assertTrue(value.has("codename"));assertTrue(value.has("securityPatch"));assertFalse(summary.toString().contains("SDK_INT"));
    }
    @Test public void sdkExtensionsKeepZeroVersionsAndBoundThePublicSet()throws Exception{
        Map<Integer,Integer> versions=new HashMap<>();for(int id=69;id>=30;id--)versions.put(id,id-30);JSONObject synthetic=BuildSystemCollector.sdkExtensionValues(versions,true);JSONArray entries=synthetic.getJSONArray("entries");assertEquals(40,synthetic.getInt("entryCount"));assertEquals(32,entries.length());assertTrue(synthetic.getBoolean("entriesTruncated"));assertEquals(30,entries.getJSONObject(0).getInt("extensionId"));assertEquals(0,entries.getJSONObject(0).getInt("version"));assertEquals(61,entries.getJSONObject(31).getInt("extensionId"));String hash=synthetic.getString("extensionSetSha256");versions.put(69,99);assertNotEquals(hash,BuildSystemCollector.sdkExtensionValues(versions,true).getString("extensionSetSha256"));
        JSONObject scoped=BuildSystemCollector.sdkExtensionValues(Collections.singletonMap(30,0),false);assertEquals("android-r-only",scoped.getString("enumerationMode"));assertFalse(scoped.getBoolean("entriesTruncated"));
        try{BuildSystemCollector.sdkExtensionValues(Collections.singletonMap(30,-1),true);fail("负版本不能当作成功采集");}catch(IllegalArgumentException expected){}
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new BuildSystemCollector("build").collect(sink);JSONObject item=null;boolean old=false;for(int i=0;i<sink.items.length();i++){JSONObject entry=sink.items.getJSONObject(i);if(entry.getString("key").equals("build/sdk-extensions"))item=entry;if(entry.getString("key").equals("build/os-summary"))old=true;}assertNotNull(item);assertTrue(old);JSONObject value=new JSONObject(item.toString()).getJSONObject("value");assertEquals(32,value.getInt("entryLimit"));assertTrue(value.getJSONArray("entries").length()<=32);
        if(Build.VERSION.SDK_INT>=31){int count=android.os.ext.SdkExtensions.getAllExtensionVersions().size();assertEquals(count>32?"partial":"ok",value.getString("status"));assertEquals("all-reported-extensions",value.getString("enumerationMode"));assertEquals(count,value.getInt("entryCount"));for(int i=0;i<value.getJSONArray("entries").length();i++){JSONObject entry=value.getJSONArray("entries").getJSONObject(i);assertEquals(android.os.ext.SdkExtensions.getExtensionVersion(entry.getInt("extensionId")),entry.getInt("version"));}}else if(Build.VERSION.SDK_INT<30)assertEquals("unsupported",value.getString("status"));else assertEquals("android-r-only",value.getString("enumerationMode"));
    }
    @Test public void systemFontsBoundMetadataAndIgnoreEnumerationOrder()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new BuildSystemCollector("environment").collect(sink);JSONObject item=null;boolean old=false;
        for(int i=0;i<sink.items.length();i++){JSONObject entry=sink.items.getJSONObject(i);if(entry.getString("key").equals("environment/system-fonts"))item=entry;if(entry.getString("key").equals("environment/locales"))old=true;}
        assertNotNull(item);assertTrue(old);if(Build.VERSION.SDK_INT<29){assertEquals("unsupported",item.getString("status"));return;}
        assertEquals("ok",item.getString("status"));List<android.graphics.fonts.Font> fonts=new ArrayList<>(android.graphics.fonts.SystemFonts.getAvailableFonts());assertFalse(fonts.isEmpty());
        JSONObject value=new JSONObject(item.toString()).getJSONObject("value");assertEquals(fonts.size(),value.getInt("fontCount"));assertEquals(128,value.getInt("entryLimit"));assertEquals(Math.min(128,fonts.size()),value.getJSONArray("entries").length());assertEquals(fonts.size()>128,value.getBoolean("entriesTruncated"));
        Collections.reverse(fonts);JSONObject reversed=BuildSystemCollector.systemFontValues(fonts);assertEquals(value.getString("metadataSetSha256"),reversed.getString("metadataSetSha256"));assertEquals(value.getJSONArray("entries").toString(),reversed.getJSONArray("entries").toString());
        android.graphics.fonts.Font source=fonts.get(0),memory=new android.graphics.fonts.Font.Builder(source.getBuffer()).setTtcIndex(source.getTtcIndex()).setWeight(400).setSlant(0).build();assertNull(memory.getFile());
        JSONObject bounded=BuildSystemCollector.systemFontValues(Collections.nCopies(129,memory));assertEquals(129,bounded.getInt("fontCount"));assertEquals(129,bounded.getInt("noFileCount"));assertEquals(0,bounded.getInt("fileBackedCount"));assertEquals(128,bounded.getInt("returnedCount"));assertEquals("partial",bounded.getString("status"));assertTrue(bounded.getBoolean("entriesTruncated"));
        JSONObject entry=bounded.getJSONArray("entries").getJSONObject(0);assertTrue(entry.isNull("fileName"));assertEquals(400,entry.getInt("weight"));assertEquals(0,entry.getInt("slant"));assertEquals("",entry.getString("localeTags"));
        String tuple=new JSONArray().put(JSONObject.NULL).put(memory.getTtcIndex()).put(400).put(0).put("").put(entry.get("variationSettings")).toString()+"\n";assertEquals(PackageInputCollector.sha(String.join("",Collections.nCopies(129,tuple)).getBytes(StandardCharsets.UTF_8)),bounded.getString("metadataSetSha256"));
        JSONObject empty=BuildSystemCollector.systemFontValues(Collections.emptyList());assertEquals("ok",empty.getString("status"));assertEquals(0,empty.getInt("fontCount"));assertFalse(empty.getBoolean("entriesTruncated"));
    }
    @Test public void resourceLocalesKeepRawTagsAndHashTheCompleteCollection()throws Exception{
        String[] app=new String[258];for(int i=0;i<app.length;i++)app[i]=String.format(Locale.ROOT,"tag-%03d",app.length-i);String[] original=app.clone();String[] system={"zh_CN","iw","",null,"iw"};
        JSONObject value=BuildSystemCollector.resourceLocaleValues(app,system);assertArrayEquals(original,app);assertEquals("partial",value.getString("status"));assertEquals(258,value.getInt("appAssetLocaleCount"));assertEquals(256,value.getInt("appAssetReturnedCount"));assertTrue(value.getBoolean("appAssetLocalesTruncated"));assertEquals("tag-001",value.getJSONArray("appAssetLocaleTags").getString(0));assertEquals("tag-256",value.getJSONArray("appAssetLocaleTags").getString(255));
        assertEquals(1,value.getInt("systemAssetNullCount"));JSONArray tags=value.getJSONArray("systemAssetLocaleTags");assertTrue(tags.isNull(0));assertEquals("",tags.getString(1));assertEquals("iw",tags.getString(2));assertEquals("iw",tags.getString(3));assertEquals("zh_CN",tags.getString(4));
        String hash=value.getString("appAssetLocalesSha256");Collections.reverse(Arrays.asList(app));assertEquals(hash,BuildSystemCollector.resourceLocaleValues(app,system).getString("appAssetLocalesSha256"));app[257]="tag-999";assertEquals(value.getJSONArray("appAssetLocaleTags").toString(),BuildSystemCollector.resourceLocaleValues(app,system).getJSONArray("appAssetLocaleTags").toString());assertNotEquals(hash,BuildSystemCollector.resourceLocaleValues(app,system).getString("appAssetLocalesSha256"));
        assertEquals("ok",BuildSystemCollector.resourceLocaleValues(new String[0],new String[0]).getString("status"));try{BuildSystemCollector.resourceLocaleValues(null,system);fail("缺失集合不能当作空集合");}catch(IllegalArgumentException expected){}
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new BuildSystemCollector("environment").collect(sink);JSONObject item=null;boolean old=false;for(int i=0;i<sink.items.length();i++){JSONObject entry=sink.items.getJSONObject(i);if(entry.getString("key").equals("environment/resource-locales"))item=entry;if(entry.getString("key").equals("environment/locale-summary"))old=true;}
        assertNotNull(item);assertTrue(old);assertEquals("ok",item.getString("status"));JSONObject actual=new JSONObject(item.toString()).getJSONObject("value");String[] actualApp=context.getAssets().getLocales(),actualSystem=android.content.res.Resources.getSystem().getAssets().getLocales();assertEquals(actualApp.length,actual.getInt("appAssetLocaleCount"));assertEquals(actualSystem.length,actual.getInt("systemAssetLocaleCount"));String[] sorted=actualApp.clone();Arrays.sort(sorted,Comparator.nullsFirst(Comparator.naturalOrder()));assertEquals(PackageInputCollector.sha(new JSONArray(Arrays.asList(sorted)).toString().getBytes(StandardCharsets.UTF_8)),actual.getString("appAssetLocalesSha256"));
    }
    @Test public void moduleSummaryKeepsMainlineSetBounded()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new BuildSystemCollector("build").collect(sink);
        JSONObject summary=null,raw=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("build/module-summary"))summary=item;if(item.getString("key").equals("build/modules"))raw=item;}
        assertNotNull("应生成 Mainline 模块摘要",summary);assertNotNull("应保留 Mainline 模块清单",raw);String status=summary.getString("status");assertTrue("模块读取状态应明确: "+status,status.equals("ok")||status.equals("unsupported")||status.equals("denied")||status.equals("error"));if(status.equals("ok")){JSONObject value=summary.getJSONObject("value");assertTrue(value.getBoolean("available"));assertTrue(value.getInt("count")>=0);assertTrue(value.getJSONArray("moduleNames").length()<=256);assertTrue(value.getJSONArray("packageNames").length()<=256);assertTrue(value.getString("moduleSetSha256").matches("[0-9a-f]{64}"));assertTrue(raw.getString("status").equals("ok"));assertTrue(raw.getJSONObject("value").getJSONArray("modules").length()<=512);}
    }
    @Test public void widevineSignalNeverExportsRawUniqueId()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new HardwareCollector("hardware").collect(sink);
        JSONObject drm=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("hardware/drm/widevine")){drm=item;break;}}
        assertNotNull("应记录 Widevine 支持状态",drm);if("ok".equals(drm.getString("status"))){JSONObject value=drm.getJSONObject("value");assertFalse(value.toString().contains("deviceUniqueIdBase64"));if(value.optJSONObject("deviceUniqueId")!=null)assertFalse(value.getJSONObject("deviceUniqueId").optBoolean("rawExported",true));}
    }
    @Test public void widevineSecurityLevelSeparatesPropertyAndRequestConstant()throws Exception{
        try{HardwareCollector.drmSecurityLevelProperty(null);fail("缺失DRM实例不能猜安全级别");}catch(NullPointerException expected){}
        ItemSink sink=new ItemSink(context,"widevine-level-test");new HardwareCollector("hardware").collect(sink);
        JSONObject item=null;for(int i=0;i<sink.items.length();i++)if(sink.items.getJSONObject(i).getString("key").equals("hardware/drm/widevine"))item=sink.items.getJSONObject(i);
        assertNotNull(item);assertEquals("ok",item.getString("status"));JSONObject value=new JSONObject(item.toString()).getJSONObject("value");
        UUID uuid=UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed");assertEquals(android.media.MediaDrm.isCryptoSchemeSupported(uuid),value.getBoolean("supported"));
        if(!value.getBoolean("supported")){assertFalse(value.has("securityLevelProperty"));return;}
        if(Build.VERSION.SDK_INT>=28){assertEquals(android.media.MediaDrm.getMaxSecurityLevel(),value.getInt("maxSecurityLevel"));assertEquals("request-sentinel",value.getString("maxSecurityLevelKind"));}
        else{assertTrue(value.isNull("maxSecurityLevel"));assertTrue(value.isNull("maxSecurityLevelKind"));}
        JSONObject level=value.getJSONObject("securityLevelProperty");assertEquals("securityLevel",level.getString("property"));
        android.media.MediaDrm drm=new android.media.MediaDrm(uuid);
        try{
            String expected;
            try{expected=drm.getPropertyString("securityLevel");}
            catch(Exception|LinkageError e){assertEquals(e instanceof SecurityException?"denied":"error",level.getString("status"));assertTrue(level.isNull("value"));assertEquals(e.getClass().getName(),level.getString("errorClass"));return;}
            assertNotNull(expected);assertEquals("ok",level.getString("status"));assertEquals(expected,level.getString("value"));assertFalse(level.has("errorClass"));
        }finally{drm.release();}
        assertFalse(value.toString().contains("deviceUniqueIdBase64"));
        if(value.optJSONObject("deviceUniqueId")!=null)assertFalse(value.getJSONObject("deviceUniqueId").optBoolean("rawExported",true));
        Bundle evidence=new Bundle();evidence.putString("widevineSecurityLevelPropertyStatus",level.getString("status"));evidence.putString("widevineSecurityLevelPropertyValue",level.getString("value"));InstrumentationRegistry.getInstrumentation().sendStatus(0,evidence);
    }
    @Test public void drmSchemesKeepCompleteHashAndPublicEnumeration()throws Exception{
        ArrayList<UUID> schemes=new ArrayList<>();for(int i=34;i>0;i--)schemes.add(new UUID(0,i));ArrayList<UUID> original=new ArrayList<>(schemes);
        JSONObject value=HardwareCollector.drmSchemeValues(schemes);assertEquals(original,schemes);
        assertEquals("partial",value.getString("status"));assertEquals(34,value.getInt("schemeCount"));assertEquals(32,value.getInt("returnedCount"));assertEquals(32,value.getInt("schemeLimit"));assertTrue(value.getBoolean("truncated"));
        assertEquals(new UUID(0,1).toString(),value.getJSONArray("schemeUuids").getString(0));assertEquals(new UUID(0,32).toString(),value.getJSONArray("schemeUuids").getString(31));
        JSONArray canonical=new JSONArray();for(int i=1;i<=34;i++)canonical.put(new UUID(0,i).toString());
        assertEquals(PackageInputCollector.sha(canonical.toString().getBytes(StandardCharsets.UTF_8)),value.getString("schemeListSha256"));
        Collections.reverse(schemes);assertEquals(value.toString(),HardwareCollector.drmSchemeValues(schemes).toString());
        schemes.set(33,new UUID(0,35));JSONObject outside=HardwareCollector.drmSchemeValues(schemes);
        assertEquals(value.getJSONArray("schemeUuids").toString(),outside.getJSONArray("schemeUuids").toString());assertNotEquals(value.getString("schemeListSha256"),outside.getString("schemeListSha256"));
        UUID unknown=UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        JSONObject duplicates=HardwareCollector.drmSchemeValues(Arrays.asList(unknown,new UUID(0,1),unknown));
        assertEquals(3,duplicates.getInt("schemeCount"));assertEquals(unknown.toString(),duplicates.getJSONArray("schemeUuids").getString(1));assertEquals(unknown.toString(),duplicates.getJSONArray("schemeUuids").getString(2));
        JSONObject empty=HardwareCollector.drmSchemeValues(Collections.emptyList());assertEquals("ok",empty.getString("status"));assertEquals(0,empty.getInt("schemeCount"));assertFalse(empty.getBoolean("truncated"));
        try{HardwareCollector.drmSchemeValues(null);fail("缺失列表不能冒充空集合");}catch(IllegalArgumentException expected){}
        try{HardwareCollector.drmSchemeValues(Collections.singletonList(null));fail("缺失 UUID 不能静默丢弃");}catch(IllegalArgumentException expected){}
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new HardwareCollector("hardware").collect(sink);
        JSONObject item=null;boolean old=false;for(int i=0;i<sink.items.length();i++){JSONObject entry=sink.items.getJSONObject(i);if(entry.getString("key").equals("hardware/drm-schemes"))item=entry;if(entry.getString("key").equals("hardware/drm/widevine"))old=true;}
        assertNotNull(item);assertTrue("旧 Widevine 条目必须保留",old);
        if(Build.VERSION.SDK_INT<30){assertEquals("unsupported",item.getString("status"));return;}
        assertEquals("ok",item.getString("status"));JSONObject actual=new JSONObject(item.toString()).getJSONObject("value");
        List<UUID> supported=android.media.MediaDrm.getSupportedCryptoSchemes();assertEquals(supported.size(),actual.getInt("schemeCount"));
        ArrayList<String> sorted=new ArrayList<>();for(UUID scheme:supported){assertTrue(android.media.MediaDrm.isCryptoSchemeSupported(scheme));sorted.add(scheme.toString());}Collections.sort(sorted);
        assertEquals(PackageInputCollector.sha(new JSONArray(sorted).toString().getBytes(StandardCharsets.UTF_8)),actual.getString("schemeListSha256"));
        assertEquals(Math.min(32,sorted.size()),actual.getInt("returnedCount"));for(int i=0;i<Math.min(32,sorted.size());i++)assertEquals(sorted.get(i),actual.getJSONArray("schemeUuids").getString(i));
        assertFalse(actual.has("deviceUniqueId"));assertFalse(actual.has("sessionId"));
    }
    @Test public void cameraConcurrencyKeepsMembershipBoundsAndPublicEvidence()throws Exception{
        Set<Set<String>> groups=new LinkedHashSet<>();
        for(int i=33;i>=0;i--)groups.add(new LinkedHashSet<>(Arrays.asList(String.format(Locale.ROOT,"camera%02d",i),"shared")));
        String before=groups.toString();JSONObject value=HardwareCollector.cameraConcurrencyValues(groups);
        assertEquals(before,groups.toString());assertEquals(34,value.getInt("combinationCount"));assertEquals(32,value.getInt("returnedCount"));assertTrue(value.getBoolean("truncated"));assertEquals("partial",value.getString("status"));
        ArrayList<Set<String>> reverse=new ArrayList<>();for(Set<String> group:groups){ArrayList<String> ids=new ArrayList<>(group);Collections.reverse(ids);reverse.add(new LinkedHashSet<>(ids));}Collections.reverse(reverse);
        assertEquals(value.toString(),HardwareCollector.cameraConcurrencyValues(new LinkedHashSet<>(reverse)).toString());
        groups.remove(new LinkedHashSet<>(Arrays.asList("camera33","shared")));groups.add(new LinkedHashSet<>(Arrays.asList("camera99","shared")));
        JSONObject outside=HardwareCollector.cameraConcurrencyValues(groups);assertEquals(value.getJSONArray("combinations").toString(),outside.getJSONArray("combinations").toString());assertNotEquals(value.getString("combinationSetSha256"),outside.getString("combinationSetSha256"));
        Set<String> large=new LinkedHashSet<>();for(int i=0;i<17;i++)large.add(String.format(Locale.ROOT,"id%02d",i));
        JSONObject limited=HardwareCollector.cameraConcurrencyValues(Collections.singleton(large));JSONObject entry=limited.getJSONArray("combinations").getJSONObject(0);
        assertEquals(17,entry.getInt("cameraCount"));assertEquals(16,entry.getJSONArray("cameraIds").length());assertTrue(entry.getBoolean("idsTruncated"));assertTrue(limited.getBoolean("truncated"));
        large.remove("id16");large.add("id99");JSONObject innerOutside=HardwareCollector.cameraConcurrencyValues(Collections.singleton(large));assertEquals(limited.getJSONArray("combinations").toString(),innerOutside.getJSONArray("combinations").toString());assertNotEquals(limited.getString("combinationSetSha256"),innerOutside.getString("combinationSetSha256"));
        JSONObject pair=HardwareCollector.cameraConcurrencyValues(Collections.singleton(new HashSet<>(Arrays.asList("1","0"))));
        assertEquals(PackageInputCollector.sha("[[\"0\",\"1\"]]".getBytes(StandardCharsets.UTF_8)),pair.getString("combinationSetSha256"));
        JSONObject empty=HardwareCollector.cameraConcurrencyValues(Collections.emptySet());assertEquals(0,empty.getInt("combinationCount"));assertEquals("ok",empty.getString("status"));assertFalse(empty.getBoolean("truncated"));
        assertEquals(1,HardwareCollector.cameraConcurrencyValues(Collections.singleton(Collections.emptySet())).getInt("combinationCount"));
        try{HardwareCollector.cameraConcurrencyValues(null);fail("缺失集合不能冒充空集合");}catch(IllegalArgumentException expected){}
        try{HardwareCollector.cameraConcurrencyValues(Collections.singleton(null));fail("不能静默丢弃缺失组合");}catch(IllegalArgumentException expected){}
        try{HardwareCollector.cameraConcurrencyValues(Collections.singleton(Collections.singleton(null)));fail("不能静默丢弃缺失ID");}catch(IllegalArgumentException expected){}
        if(Build.VERSION.SDK_INT>=30){
            Context missing=new ContextWrapper(context){@Override public Object getSystemService(String name){return Context.CAMERA_SERVICE.equals(name)?null:super.getSystemService(name);}};
            JSONObject absent=HardwareCollector.cameraConcurrencySummary(missing);assertFalse(absent.getBoolean("available"));assertEquals("unavailable",absent.getString("status"));assertTrue(absent.isNull("combinationCount"));assertTrue(absent.isNull("combinations"));assertTrue(absent.isNull("combinationSetSha256"));
        }
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new HardwareCollector("hardware").collect(sink);
        JSONObject actual=null;boolean old=false;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("hardware/camera-concurrency"))actual=item;if(item.getString("key").equals("hardware/camera-summary"))old=true;}
        assertNotNull(actual);assertTrue("原摄像头摘要必须保留",old);
        if(Build.VERSION.SDK_INT<30){assertEquals("unsupported",actual.getString("status"));return;}
        assertEquals("ok",actual.getString("status"));
        android.hardware.camera2.CameraManager manager=context.getSystemService(android.hardware.camera2.CameraManager.class);
        JSONObject observed=new JSONObject(actual.toString()).getJSONObject("value");Set<Set<String>> platform=manager.getConcurrentCameraIds();
        assertEquals(platform.size(),observed.getInt("combinationCount"));assertEquals(HardwareCollector.cameraConcurrencyValues(platform).toString(),observed.toString());
        for(int i=0;i<observed.getJSONArray("combinations").length();i++){JSONObject group=observed.getJSONArray("combinations").getJSONObject(i);if(!group.getBoolean("idsTruncated")){Set<String> ids=new HashSet<>();JSONArray list=group.getJSONArray("cameraIds");for(int j=0;j<list.length();j++)ids.add(list.getString(j));assertTrue(platform.contains(ids));}}
    }
    @Test public void usbEvidenceUsesPublicDescriptorsOnly()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new HardwareCollector("hardware").collect(sink);
        JSONObject usb=null,summary=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("hardware/usb"))usb=item;if(item.getString("key").equals("hardware/usb-summary"))summary=item;}
        assertNotNull("应记录 USB 公开设备清单",usb);assertNotNull("应生成 USB 能力摘要",summary);assertEquals("ok",usb.getString("status"));assertEquals("ok",summary.getString("status"));JSONObject value=usb.getJSONObject("value"),summaryValue=summary.getJSONObject("value");assertTrue(value.has("count"));assertTrue(value.getInt("count")<=256);assertTrue(value.has("devices"));assertTrue(summaryValue.has("deviceCount"));assertTrue(summaryValue.has("interfaceCount"));assertTrue(summaryValue.has("endpointCount"));assertTrue(summaryValue.has("vendorIds"));assertTrue(summaryValue.has("productIds"));assertTrue(summaryValue.getJSONArray("vendorIds").length()<=128);assertTrue(summaryValue.getJSONArray("productIds").length()<=128);assertFalse( summary.toString().contains("deviceName"));assertFalse(summary.toString().contains("serialNumber"));assertFalse(usb.toString().contains("serialNumber"));
    }
    @Test public void nfcEvidenceUsesPublicAdapterState()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new HardwareCollector("hardware").collect(sink);
        JSONObject nfc=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("hardware/nfc")){nfc=item;break;}}
        assertNotNull("应记录 NFC 能力",nfc);assertEquals("ok",nfc.getString("status"));JSONObject value=nfc.getJSONObject("value");assertTrue(value.has("available"));assertFalse(nfc.toString().contains("tagId"));if(Build.VERSION.SDK_INT>=29){assertTrue(value.has("secureNfcSupported"));assertTrue(value.has("secureNfcEnabled"));}if(Build.VERSION.SDK_INT>=34)assertTrue(value.has("antennaInfoAvailable"));
    }
    @Test public void graphicsSummaryBoundsExtensionData()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new HardwareCollector("hardware").collect(sink);
        JSONObject summary=null,raw=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("hardware/graphics-summary"))summary=item;if(item.getString("key").equals("hardware/graphics"))raw=item;}
        assertNotNull("应生成图形能力摘要",summary);assertNotNull("应保留图形能力原始条目",raw);assertEquals("ok",summary.getString("status"));JSONObject value=summary.getJSONObject("value");assertTrue(value.has("eglExtensionCount"));assertTrue(value.has("glExtensionCount"));assertTrue(value.has("vulkanExtensionCount"));assertTrue(value.getJSONArray("vulkanExtensionNames").length()<=256);assertTrue(value.has("vulkanExtensionNamesTruncated"));assertFalse(value.has("eglExtensions"));assertFalse(value.has("glExtensions"));
    }
    @Test public void featureSummaryKeepsFullListSeparate()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new HardwareCollector("hardware").collect(sink);
        JSONObject summary=null,list=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("hardware/features-summary"))summary=item;if(item.getString("key").equals("hardware/features"))list=item;}
        assertNotNull("应生成系统特性摘要",summary);assertNotNull("应保留系统特性完整清单",list);assertEquals("ok",summary.getString("status"));assertEquals("ok",list.getString("status"));JSONObject value=summary.getJSONObject("value");assertEquals(value.getInt("count"),list.getJSONArray("value").length());assertTrue(value.getJSONArray("featureNames").length()<=256);assertTrue(value.has("featureNamesTruncated"));assertTrue(value.has("glEsVersions"));
    }
    @Test public void codecSummaryKeepsFullListSeparate()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new HardwareCollector("hardware").collect(sink);
        JSONObject summary=null,list=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("hardware/codecs-summary"))summary=item;if(item.getString("key").equals("hardware/codecs"))list=item;}
        assertNotNull("应生成编解码能力摘要",summary);assertNotNull("应保留编解码完整清单",list);assertEquals("ok",summary.getString("status"));assertEquals("ok",list.getString("status"));JSONObject value=summary.getJSONObject("value");assertEquals(value.getInt("count"),list.getJSONArray("value").length());assertTrue(value.getJSONArray("codecNames").length()<=256);assertTrue(value.getJSONArray("mimeTypes").length()<=256);assertTrue(value.has("codecNamesTruncated"));assertTrue(value.has("mimeTypesTruncated"));
    }
    @Test public void codecEncoderControlsMatchPublicCapabilities()throws Exception{
        try{HardwareCollector.codecEncoderCapabilities(null);fail("缺失能力不能冒充非编码器");}catch(NullPointerException expected){}
        ItemSink sink=new ItemSink(context,"encoder-controls-test");new HardwareCollector("hardware").collect(sink);
        JSONObject item=null;for(int i=0;i<sink.items.length();i++)if(sink.items.getJSONObject(i).getString("key").equals("hardware/codecs"))item=sink.items.getJSONObject(i);
        assertNotNull(item);assertEquals("ok",item.getString("status"));JSONArray codecs=new JSONObject(item.toString()).getJSONArray("value");
        android.media.MediaCodecInfo[] platform=new android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).getCodecInfos();assertEquals(platform.length,codecs.length());int encoders=0,decoders=0;
        String[] names={"CQ","VBR","CBR","CBR_FD"};int[] modes={0,1,2,3};
        for(int i=0;i<platform.length;i++){
            JSONObject codec=codecs.getJSONObject(i);assertEquals(platform[i].getName(),codec.getString("name"));JSONArray types=codec.getJSONArray("capabilities");
            for(int j=0;j<types.length();j++){
                JSONObject type=types.getJSONObject(j);if(type.has("error"))continue;assertTrue(type.has("audioCapabilities"));assertTrue(type.has("videoCapabilities"));assertTrue(type.has("featureFlags"));
                android.media.MediaCodecInfo.EncoderCapabilities encoder=platform[i].getCapabilitiesForType(type.getString("type")).getEncoderCapabilities();JSONObject value=type.getJSONObject("encoderCapabilities");
                if(encoder==null){decoders++;assertFalse(value.getBoolean("available"));assertEquals("not-encoder",value.getString("status"));for(String key:new String[]{"complexity","quality","bitrateModes"})assertTrue(value.isNull(key));continue;}
                encoders++;assertTrue(platform[i].isEncoder());assertTrue(value.getBoolean("available"));assertEquals("ok",value.getString("status"));JSONObject complexity=value.getJSONObject("complexity");assertEquals("ok",complexity.getString("status"));assertEquals(encoder.getComplexityRange().getLower(),complexity.getJSONObject("range").get("lower"));assertEquals(encoder.getComplexityRange().getUpper(),complexity.getJSONObject("range").get("upper"));
                JSONObject quality=value.getJSONObject("quality");
                if(Build.VERSION.SDK_INT<28){assertEquals("unsupported",quality.getString("status"));assertTrue(quality.isNull("range"));}
                else{assertEquals("ok",quality.getString("status"));assertEquals(encoder.getQualityRange().getLower(),quality.getJSONObject("range").get("lower"));assertEquals(encoder.getQualityRange().getUpper(),quality.getJSONObject("range").get("upper"));}
                JSONObject flags=value.getJSONObject("bitrateModes");assertEquals(4,flags.length());
                for(int k=0;k<names.length;k++){
                    JSONObject flag=flags.getJSONObject(names[k]);assertEquals(modes[k],flag.getInt("mode"));
                    if(modes[k]==3&&Build.VERSION.SDK_INT<31){assertEquals("unsupported",flag.getString("status"));assertTrue(flag.isNull("supported"));continue;}
                    assertEquals("ok",flag.getString("status"));assertEquals(encoder.isBitrateModeSupported(modes[k]),flag.getBoolean("supported"));
                }
            }
        }
        assertTrue(encoders>0);assertTrue(decoders>0);Bundle evidence=new Bundle();evidence.putString("encoderMimeCount",String.valueOf(encoders));evidence.putString("decoderMimeCount",String.valueOf(decoders));InstrumentationRegistry.getInstrumentation().sendStatus(0,evidence);
    }
    @Test public void codecInputChannelsMatchPublicRanges()throws Exception{
        try{HardwareCollector.codecInputChannels(null);fail("缺失音频能力不能伪装不支持");}catch(NullPointerException expected){}
        ItemSink sink=new ItemSink(context,"input-channel-test");new HardwareCollector("hardware").collect(sink);
        JSONObject item=null;for(int i=0;i<sink.items.length();i++)if(sink.items.getJSONObject(i).getString("key").equals("hardware/codecs"))item=sink.items.getJSONObject(i);
        assertNotNull(item);assertEquals("ok",item.getString("status"));JSONArray codecs=item.getJSONArray("value");android.media.MediaCodecInfo[] platform=new android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).getCodecInfos();assertEquals(platform.length,codecs.length());int checked=0,nonContiguous=0;
        for(int i=0;i<platform.length;i++){
            JSONObject codec=codecs.getJSONObject(i);assertEquals(platform[i].getName(),codec.getString("name"));JSONArray types=codec.getJSONArray("capabilities");
            for(int j=0;j<types.length();j++){
                JSONObject type=types.getJSONObject(j);if(type.has("error"))continue;JSONObject base=type.getJSONObject("audioCapabilities");if(!base.getBoolean("available"))continue;
                JSONObject value=new JSONObject(base.toString()).getJSONObject("inputChannelSupport");checked++;
                if(Build.VERSION.SDK_INT<31){assertEquals("unsupported",value.getString("status"));assertTrue(value.isNull("minInputChannelCount"));assertTrue(value.isNull("ranges"));assertTrue(value.isNull("truncated"));continue;}
                android.media.MediaCodecInfo.AudioCapabilities audio=platform[i].getCapabilitiesForType(type.getString("type")).getAudioCapabilities();android.util.Range<Integer>[] ranges=audio.getInputChannelCountRanges();JSONArray all=new JSONArray();for(android.util.Range<Integer> range:ranges)all.put(Json.obj("lower",range.getLower(),"upper",range.getUpper()));
                assertTrue(value.getBoolean("available"));assertEquals("ok",value.getString("status"));assertEquals(audio.getMinInputChannelCount(),value.getInt("minInputChannelCount"));assertEquals(ranges.length,value.getInt("rangeCount"));assertEquals(ranges.length,value.getInt("returnedCount"));assertEquals(64,value.getInt("rangeLimit"));assertFalse(value.getBoolean("truncated"));assertEquals(all.toString(),value.getJSONArray("ranges").toString());assertEquals(PackageInputCollector.sha(all.toString().getBytes(StandardCharsets.UTF_8)),value.getString("rangesSha256"));assertEquals(audio.getMaxInputChannelCount(),base.getInt("maxInputChannelCount"));if(ranges.length>1)nonContiguous++;
            }
        }
        assertTrue(checked>0);Bundle evidence=new Bundle();evidence.putString("audioChannelMimeCount",String.valueOf(checked));evidence.putString("multipleChannelRangeMimeCount",String.valueOf(nonContiguous));InstrumentationRegistry.getInstrumentation().sendStatus(0,evidence);
    }
    @Test public void codecInputChannelRangesKeepGapsAndFullHash()throws Exception{
        android.util.Range<Integer>[] ranges=new android.util.Range[]{new android.util.Range<>(2,2),new android.util.Range<>(6,8),new android.util.Range<>(6,8)};String before=Arrays.toString(ranges);
        JSONObject value=HardwareCollector.codecInputChannelValues(2,ranges);assertEquals(before,Arrays.toString(ranges));assertEquals(2,value.getInt("minInputChannelCount"));assertEquals(3,value.getInt("rangeCount"));assertEquals(2,value.getJSONArray("ranges").getJSONObject(0).getInt("upper"));assertEquals(6,value.getJSONArray("ranges").getJSONObject(1).getInt("lower"));assertEquals(value.getJSONArray("ranges").getJSONObject(1).toString(),value.getJSONArray("ranges").getJSONObject(2).toString());
        android.util.Range<Integer>[] large=new android.util.Range[70];Arrays.fill(large,new android.util.Range<>(2,2));JSONObject limited=HardwareCollector.codecInputChannelValues(2,large);assertEquals("partial",limited.getString("status"));assertTrue(limited.getBoolean("truncated"));assertEquals(70,limited.getInt("rangeCount"));assertEquals(64,limited.getInt("returnedCount"));large[69]=new android.util.Range<>(6,8);JSONObject changed=HardwareCollector.codecInputChannelValues(2,large);assertEquals(limited.getJSONArray("ranges").toString(),changed.getJSONArray("ranges").toString());assertNotEquals(limited.getString("rangesSha256"),changed.getString("rangesSha256"));
        JSONObject empty=HardwareCollector.codecInputChannelValues(0,new android.util.Range[0]);assertEquals("ok",empty.getString("status"));assertEquals(0,empty.getInt("rangeCount"));assertEquals("[]",empty.getJSONArray("ranges").toString());
        try{HardwareCollector.codecInputChannelValues(2,null);fail("缺失区间不能当空集合");}catch(IllegalArgumentException expected){}
        try{HardwareCollector.codecInputChannelValues(2,new android.util.Range[]{null});fail("null区间不能静默丢失");}catch(NullPointerException expected){}
        try{HardwareCollector.codecInputChannelValues(2,new android.util.Range[20001]);fail("输入不能越界");}catch(IllegalArgumentException expected){}
    }
    @Test public void codecAudioRangesMatchPublicCapabilities()throws Exception{
        try{HardwareCollector.codecAudioCapabilities(null);fail("缺失能力不能冒充非音频");}catch(NullPointerException expected){}
        ItemSink sink=new ItemSink(context,"audio-capabilities-test");new HardwareCollector("hardware").collect(sink);
        JSONObject item=null;for(int i=0;i<sink.items.length();i++)if(sink.items.getJSONObject(i).getString("key").equals("hardware/codecs"))item=sink.items.getJSONObject(i);
        assertNotNull(item);assertEquals("ok",item.getString("status"));JSONArray codecs=new JSONObject(item.toString()).getJSONArray("value");
        android.media.MediaCodecInfo[] platform=new android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).getCodecInfos();assertEquals(platform.length,codecs.length());int audioCount=0,otherCount=0,continuous=0;
        for(int i=0;i<platform.length;i++){
            JSONObject codec=codecs.getJSONObject(i);assertEquals(platform[i].getName(),codec.getString("name"));JSONArray types=codec.getJSONArray("capabilities");
            for(int j=0;j<types.length();j++){
                JSONObject type=types.getJSONObject(j);if(type.has("error"))continue;assertTrue(type.has("videoCapabilities"));assertTrue(type.has("featureFlags"));
                android.media.MediaCodecInfo.AudioCapabilities audio=platform[i].getCapabilitiesForType(type.getString("type")).getAudioCapabilities();JSONObject value=type.getJSONObject("audioCapabilities");
                if(audio==null){otherCount++;assertFalse(value.getBoolean("available"));assertEquals("not-audio",value.getString("status"));for(String key:new String[]{"bitrateBitsPerSecond","maxInputChannelCount","sampleRateMode","sampleRatesHz","sampleRateRangesHz"})assertTrue(value.isNull(key));continue;}
                audioCount++;assertTrue(value.getBoolean("available"));assertEquals("ok",value.getString("status"));assertEquals(audio.getBitrateRange().getLower(),value.getJSONObject("bitrateBitsPerSecond").get("lower"));assertEquals(audio.getBitrateRange().getUpper(),value.getJSONObject("bitrateBitsPerSecond").get("upper"));assertEquals(audio.getMaxInputChannelCount(),value.getInt("maxInputChannelCount"));
                android.util.Range<Integer>[] ranges=audio.getSupportedSampleRateRanges();JSONArray all=new JSONArray(),entries=value.getJSONArray("sampleRateRangesHz");for(android.util.Range<Integer> range:ranges)all.put(Json.obj("lower",range.getLower(),"upper",range.getUpper()));assertEquals(all.toString(),entries.toString());assertEquals(ranges.length,value.getInt("sampleRateRangeCount"));assertEquals(PackageInputCollector.sha(all.toString().getBytes(StandardCharsets.UTF_8)),value.getString("sampleRateRangesSha256"));
                int[] rates=audio.getSupportedSampleRates();assertEquals(rates==null?"ranges":"discrete",value.getString("sampleRateMode"));
                if(rates==null){continuous++;assertTrue(value.isNull("sampleRatesHz"));assertTrue(value.isNull("sampleRateCount"));assertTrue(value.isNull("sampleRatesTruncated"));assertTrue(value.isNull("sampleRatesSha256"));}
                else{JSONArray expected=new JSONArray(rates);assertEquals(expected.toString(),value.getJSONArray("sampleRatesHz").toString());assertEquals(rates.length,value.getInt("sampleRateCount"));assertFalse(value.getBoolean("sampleRatesTruncated"));assertEquals(PackageInputCollector.sha(expected.toString().getBytes(StandardCharsets.UTF_8)),value.getString("sampleRatesSha256"));}
            }
        }
        assertTrue(audioCount>0);Bundle evidence=new Bundle();evidence.putString("audioMimeCount",String.valueOf(audioCount));evidence.putString("nonAudioMimeCount",String.valueOf(otherCount));evidence.putString("continuousAudioMimeCount",String.valueOf(continuous));InstrumentationRegistry.getInstrumentation().sendStatus(0,evidence);
    }
    @Test public void codecAudioListsKeepOrderNullAndFullHash()throws Exception{
        android.util.Range<Integer> bitrate=new android.util.Range<>(0,320000);android.util.Range<Integer>[] ranges=new android.util.Range[70];int[] rates=new int[300];
        for(int i=0;i<ranges.length;i++)ranges[i]=new android.util.Range<>(i,i);for(int i=0;i<rates.length;i++)rates[i]=i;
        ranges[1]=ranges[0];rates[1]=rates[0];String original=Arrays.toString(ranges)+Arrays.toString(rates);
        JSONObject value=HardwareCollector.codecAudioValues(bitrate,2,ranges,rates);assertEquals(original,Arrays.toString(ranges)+Arrays.toString(rates));assertEquals("partial",value.getString("status"));assertEquals(70,value.getInt("sampleRateRangeCount"));assertEquals(64,value.getJSONArray("sampleRateRangesHz").length());assertEquals(300,value.getInt("sampleRateCount"));assertEquals(256,value.getJSONArray("sampleRatesHz").length());assertTrue(value.getBoolean("sampleRateRangesTruncated"));assertTrue(value.getBoolean("sampleRatesTruncated"));assertEquals(value.getJSONArray("sampleRatesHz").get(0),value.getJSONArray("sampleRatesHz").get(1));
        rates[299]=999;ranges[69]=new android.util.Range<>(999,999);JSONObject changed=HardwareCollector.codecAudioValues(bitrate,2,ranges,rates);assertEquals(value.getJSONArray("sampleRatesHz").toString(),changed.getJSONArray("sampleRatesHz").toString());assertEquals(value.getJSONArray("sampleRateRangesHz").toString(),changed.getJSONArray("sampleRateRangesHz").toString());assertNotEquals(value.getString("sampleRatesSha256"),changed.getString("sampleRatesSha256"));assertNotEquals(value.getString("sampleRateRangesSha256"),changed.getString("sampleRateRangesSha256"));
        JSONObject continuous=HardwareCollector.codecAudioValues(bitrate,2,new android.util.Range[]{new android.util.Range<>(8000,48000)},null);assertEquals("ranges",continuous.getString("sampleRateMode"));assertTrue(continuous.isNull("sampleRatesHz"));assertTrue(continuous.isNull("sampleRatesSha256"));
        JSONObject empty=HardwareCollector.codecAudioValues(bitrate,0,new android.util.Range[0],new int[0]);assertEquals("discrete",empty.getString("sampleRateMode"));assertEquals(0,empty.getInt("sampleRateCount"));assertEquals("ok",empty.getString("status"));assertEquals("[]",empty.getJSONArray("sampleRatesHz").toString());
        try{HardwareCollector.codecAudioValues(bitrate,2,null,null);fail("缺失区间不能伪装连续采样率");}catch(IllegalArgumentException expected){}
        try{HardwareCollector.codecAudioValues(bitrate,2,new android.util.Range[]{null},null);fail("null区间不能静默跳过");}catch(NullPointerException expected){}
        try{HardwareCollector.codecAudioValues(bitrate,2,new android.util.Range[20001],null);fail("区间输入不能越界");}catch(IllegalArgumentException expected){}
        try{HardwareCollector.codecAudioValues(bitrate,2,new android.util.Range[0],new int[20001]);fail("采样率输入不能越界");}catch(IllegalArgumentException expected){}
    }
    @Test public void codecPerformancePointBoundsAndCoverage()throws Exception{
        JSONObject missing=HardwareCollector.codecPerformancePointValues(null);assertEquals("not-published",missing.getString("status"));assertTrue(missing.isNull("points"));assertTrue(missing.isNull("pointCount"));assertTrue(missing.isNull("pointsTruncated"));
        JSONObject empty=HardwareCollector.codecPerformancePointValues(Collections.emptyList());assertEquals("ok",empty.getString("status"));assertEquals(0,empty.getInt("pointCount"));assertEquals("[]",empty.getJSONArray("points").toString());assertEquals(PackageInputCollector.sha("[]".getBytes(StandardCharsets.UTF_8)),empty.getString("pointsSha256"));
        android.media.MediaCodecInfo.VideoCapabilities.PerformancePoint small=new android.media.MediaCodecInfo.VideoCapabilities.PerformancePoint(1280,720,30),large=new android.media.MediaCodecInfo.VideoCapabilities.PerformancePoint(1920,1080,60);
        ArrayList<android.media.MediaCodecInfo.VideoCapabilities.PerformancePoint> points=new ArrayList<>(Arrays.asList(large,small,large));JSONObject actual=HardwareCollector.codecPerformancePointValues(points);JSONArray values=actual.getJSONArray("points");
        assertEquals(3,actual.getInt("pointCount"));assertEquals(large.toString(),values.getJSONObject(0).getString("description"));assertEquals(small.toString(),values.getJSONObject(1).getString("description"));assertEquals(values.getJSONObject(0).toString(),values.getJSONObject(2).toString());assertSame(large,points.get(0));points.set(0,small);assertEquals(large.toString(),values.getJSONObject(0).getString("description"));
        JSONObject covers=values.getJSONObject(0).getJSONObject("coversReferencePoints");assertEquals(9,covers.length());assertTrue(covers.getBoolean("HD_30"));assertTrue(covers.getBoolean("FHD_60"));assertFalse(covers.getBoolean("UHD_30"));assertFalse(covers.getBoolean("FHD_120"));
        assertEquals(PackageInputCollector.sha(values.toString().getBytes(StandardCharsets.UTF_8)),actual.getString("pointsSha256"));
        ArrayList<android.media.MediaCodecInfo.VideoCapabilities.PerformancePoint> many=new ArrayList<>(Collections.nCopies(256,small));JSONObject maximum=HardwareCollector.codecPerformancePointValues(many);many.set(255,large);JSONObject changed=HardwareCollector.codecPerformancePointValues(many);
        assertEquals("partial",maximum.getString("status"));assertEquals(256,maximum.getInt("pointCount"));assertEquals(64,maximum.getInt("returnedCount"));assertEquals(64,maximum.getInt("pointLimit"));assertTrue(maximum.getBoolean("pointsTruncated"));assertEquals(maximum.getJSONArray("points").toString(),changed.getJSONArray("points").toString());assertNotEquals(maximum.getString("pointsSha256"),changed.getString("pointsSha256"));
        assertEquals("ok",HardwareCollector.codecPerformancePointValues(Collections.nCopies(64,small)).getString("status"));assertEquals("partial",HardwareCollector.codecPerformancePointValues(Collections.nCopies(65,small)).getString("status"));
        try{HardwareCollector.codecPerformancePointValues(Collections.nCopies(257,small));fail("最多256输入");}catch(IllegalArgumentException expected){}
        try{HardwareCollector.codecPerformancePointValues(Collections.singletonList(null));fail("null项不是未发布");}catch(NullPointerException expected){}
        for(int sdk:new int[]{24,28}){JSONObject old=HardwareCollector.codecPerformancePoints(null,sdk);assertEquals("unsupported",old.getString("status"));assertTrue(old.isNull("points"));}
        try{HardwareCollector.codecPerformancePoints(null,29);fail("新API需要能力实例");}catch(NullPointerException expected){}
    }
    @Test public void codecPerformancePointsMatchPublicApi()throws Exception{
        android.media.MediaCodecInfo[] platform=new android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).getCodecInfos();JSONArray evidence=new JSONArray();
        String[] names={"HD_30","HD_60","HD_120","FHD_30","FHD_60","FHD_120","UHD_30","UHD_60","UHD_120"};int[][] dimensions={{1280,720,30},{1280,720,60},{1280,720,120},{1920,1080,30},{1920,1080,60},{1920,1080,120},{3840,2160,30},{3840,2160,60},{3840,2160,120}};
        for(android.media.MediaCodecInfo codec:platform)for(String type:codec.getSupportedTypes()){
            android.media.MediaCodecInfo.CodecCapabilities cap=codec.getCapabilitiesForType(type);JSONObject actual=HardwareCollector.codecPerformancePoints(cap,Build.VERSION.SDK_INT);evidence.put(Json.obj("name",codec.getName(),"type",type,"performancePoints",actual));
            if(Build.VERSION.SDK_INT<29){assertEquals("unsupported",actual.getString("status"));continue;}
            android.media.MediaCodecInfo.VideoCapabilities video=cap.getVideoCapabilities();if(video==null){assertEquals("not-video",actual.getString("status"));assertTrue(actual.isNull("points"));continue;}
            List<android.media.MediaCodecInfo.VideoCapabilities.PerformancePoint> points=video.getSupportedPerformancePoints();if(points==null){assertEquals("not-published",actual.getString("status"));assertTrue(actual.isNull("points"));continue;}
            assertEquals(points.size()>64?"partial":"ok",actual.getString("status"));assertEquals(points.size(),actual.getInt("pointCount"));assertEquals(Math.min(64,points.size()),actual.getInt("returnedCount"));JSONArray all=new JSONArray();
            for(int i=0;i<points.size();i++){JSONObject coverage=new JSONObject();for(int j=0;j<names.length;j++)Json.put(coverage,names[j],points.get(i).covers(new android.media.MediaCodecInfo.VideoCapabilities.PerformancePoint(dimensions[j][0],dimensions[j][1],dimensions[j][2])));JSONObject value=Json.obj("description",points.get(i).toString(),"coversReferencePoints",coverage);all.put(value);if(i<64)assertEquals(value.toString(),actual.getJSONArray("points").getJSONObject(i).toString());}
            assertEquals(PackageInputCollector.sha(all.toString().getBytes(StandardCharsets.UTF_8)),actual.getString("pointsSha256"));
        }
        Bundle result=new Bundle();result.putString("codecPerformanceEvidence",evidence.toString());InstrumentationRegistry.getInstrumentation().sendStatus(0,result);
    }
    @Test public void codecVideoRangesMatchPublicCapabilities()throws Exception{
        try{HardwareCollector.codecVideoCapabilities(null);fail("缺失能力不能冒充非视频MIME");}catch(NullPointerException expected){}
        ItemSink sink=new ItemSink(context,"video-capabilities-test");new HardwareCollector("hardware").collect(sink);
        JSONObject item=null;boolean summary=false;for(int i=0;i<sink.items.length();i++){JSONObject entry=sink.items.getJSONObject(i);if(entry.getString("key").equals("hardware/codecs"))item=entry;if(entry.getString("key").equals("hardware/codecs-summary"))summary=true;}
        assertNotNull(item);assertTrue(summary);assertEquals("ok",item.getString("status"));JSONArray codecs=new JSONObject(item.toString()).getJSONArray("value");
        android.media.MediaCodecInfo[] platform=new android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).getCodecInfos();assertEquals(platform.length,codecs.length());int videoCount=0,otherCount=0;
        for(int i=0;i<platform.length;i++){
            JSONObject codec=codecs.getJSONObject(i);assertEquals(platform[i].getName(),codec.getString("name"));JSONArray types=codec.getJSONArray("capabilities");
            for(int j=0;j<types.length();j++){
                JSONObject type=types.getJSONObject(j);if(type.has("error"))continue;
                assertTrue(type.has("colorFormats"));assertTrue(type.has("profileLevels"));assertTrue(type.has("featureFlags"));
                android.media.MediaCodecInfo.VideoCapabilities video=platform[i].getCapabilitiesForType(type.getString("type")).getVideoCapabilities();JSONObject value=type.getJSONObject("videoCapabilities");
                assertEquals(HardwareCollector.codecPerformancePoints(platform[i].getCapabilitiesForType(type.getString("type")),Build.VERSION.SDK_INT).toString(),type.getJSONObject("performancePoints").toString());
                if(video==null){otherCount++;assertFalse(value.getBoolean("available"));assertEquals("not-video",value.getString("status"));for(String key:new String[]{"bitrateBitsPerSecond","frameRatesPerSecond","widthPixels","heightPixels","widthAlignmentPixels","heightAlignmentPixels"})assertTrue(value.isNull(key));continue;}
                videoCount++;assertTrue(value.getBoolean("available"));assertEquals("ok",value.getString("status"));
                android.util.Range<?>[] ranges={video.getBitrateRange(),video.getSupportedFrameRates(),video.getSupportedWidths(),video.getSupportedHeights()};String[] keys={"bitrateBitsPerSecond","frameRatesPerSecond","widthPixels","heightPixels"};
                for(int k=0;k<keys.length;k++){JSONObject range=value.getJSONObject(keys[k]);assertEquals(ranges[k].getLower(),range.get("lower"));assertEquals(ranges[k].getUpper(),range.get("upper"));}
                assertEquals(video.getWidthAlignment(),value.getInt("widthAlignmentPixels"));assertEquals(video.getHeightAlignment(),value.getInt("heightAlignmentPixels"));
            }
        }
        assertTrue(videoCount>0);Bundle evidence=new Bundle();evidence.putString("videoMimeCount",String.valueOf(videoCount));evidence.putString("nonVideoMimeCount",String.valueOf(otherCount));InstrumentationRegistry.getInstrumentation().sendStatus(0,evidence);
    }
    @Test public void codecFeaturesKeepPerMimeEvidence()throws Exception{
        try{HardwareCollector.codecFeatureFlags(null);fail("缺失能力不能被解释为不支持");}catch(NullPointerException expected){}
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new HardwareCollector("hardware").collect(sink);
        JSONObject item=null;for(int i=0;i<sink.items.length();i++)if(sink.items.getJSONObject(i).getString("key").equals("hardware/codecs"))item=sink.items.getJSONObject(i);
        assertNotNull(item);assertEquals("ok",item.getString("status"));JSONArray codecs=new JSONObject(item.toString()).getJSONArray("value");
        android.media.MediaCodecInfo[] platform=new android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).getCodecInfos();assertEquals(platform.length,codecs.length());
        String[] names={"adaptive-playback","secure-playback","tunneled-playback","low-latency"};int checked=0,supported=0,required=0;
        for(int i=0;i<platform.length;i++){
            JSONObject codec=codecs.getJSONObject(i);assertEquals(platform[i].getName(),codec.getString("name"));JSONArray types=codec.getJSONArray("capabilities");
            for(int j=0;j<types.length();j++){
                JSONObject type=types.getJSONObject(j);if(type.has("error"))continue;
                assertTrue(type.has("colorFormats"));assertTrue(type.has("maxSupportedInstances"));assertTrue(type.has("profileLevels"));
                android.media.MediaCodecInfo.CodecCapabilities capability=platform[i].getCapabilitiesForType(type.getString("type"));JSONObject flags=type.getJSONObject("featureFlags");assertEquals(4,flags.length());
                for(String name:names){JSONObject flag=flags.getJSONObject(name);if(name.equals("low-latency")&&Build.VERSION.SDK_INT<30){assertEquals("unsupported",flag.getString("status"));assertTrue(flag.isNull("supported"));assertTrue(flag.isNull("required"));continue;}
                    assertEquals("ok",flag.getString("status"));assertEquals(capability.isFeatureSupported(name),flag.getBoolean("supported"));assertEquals(capability.isFeatureRequired(name),flag.getBoolean("required"));if(flag.getBoolean("supported"))supported++;if(flag.getBoolean("required"))required++;checked++;
                }
            }
        }
        assertTrue("应核对真实MIME特性",checked>0);System.out.println("codec feature queries="+checked+", supported="+supported+", required="+required);
    }
    @Test public void cameraSummaryKeepsPerCameraEvidence()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new HardwareCollector("hardware").collect(sink);
        File checkpoint=new File(context.getCacheDir(),"camera-checkpoint.json");ReportStore.write(checkpoint,sink.items.toString());assertEquals("完整硬件检查点必须可重新解析",sink.items.length(),new JSONArray(ReportStore.read(checkpoint)).length());
        JSONObject summary=null;int cameraItems=0;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);String key=item.getString("key");if(key.equals("hardware/camera-summary"))summary=item;if(key.startsWith("hardware/camera/")&&!key.equals("hardware/camera-summary"))cameraItems++;}
        assertNotNull("应生成摄像头能力摘要",summary);assertEquals("ok",summary.getString("status"));JSONObject value=summary.getJSONObject("value");assertTrue(value.has("count"));assertEquals(value.getInt("count"),cameraItems);assertTrue(value.getJSONArray("cameraIds").length()<=64);assertTrue(value.has("cameraIdsTruncated"));assertTrue(value.has("lensFacingCounts"));assertTrue(value.has("hardwareLevelCounts"));
    }
    @Test public void audioSummaryKeepsFullListSeparate()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new HardwareCollector("hardware").collect(sink);
        JSONObject summary=null,list=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("hardware/audio-summary"))summary=item;if(item.getString("key").equals("hardware/audio"))list=item;}
        assertNotNull("应生成音频能力摘要",summary);assertNotNull("应保留音频设备完整清单",list);assertEquals("ok",summary.getString("status"));assertEquals("ok",list.getString("status"));JSONObject value=summary.getJSONObject("value");assertEquals(value.getInt("count"),list.getJSONArray("value").length());assertTrue(value.getJSONArray("productNames").length()<=128);assertTrue(value.has("productNamesTruncated"));assertTrue(value.has("sampleRates"));assertTrue(value.has("encodings"));
    }
    @Test public void microphoneSummaryKeepsCapabilityBoundaryExplicit()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new HardwareCollector("hardware").collect(sink);
        JSONObject summary=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("hardware/microphone-summary")){summary=item;break;}}
        assertNotNull("应生成麦克风阵列摘要",summary);String status=summary.getString("status");assertTrue("麦克风状态应明确: "+status,status.equals("ok")||status.equals("unsupported")||status.equals("denied")||status.equals("unavailable")||status.equals("error"));if(status.equals("ok")){JSONObject value=summary.getJSONObject("value");assertTrue(value.getBoolean("available"));assertTrue(value.getInt("count")>=0);assertTrue(value.getInt("returnedCount")<=256);assertTrue(value.getJSONArray("microphoneIds").length()<=64);assertTrue(value.has("typeCounts"));assertTrue(value.has("locationValues"));assertTrue(value.has("directionalityValues"));assertFalse(summary.toString().contains("MicrophoneInfo{"));}
    }
    @Test public void vibratorPrimitivesGuardApiBoundaries()throws Exception{
        for(int sdk:new int[]{24,29}){
            JSONObject value=HardwareCollector.vibratorPrimitiveCapabilities(null,sdk);
            assertEquals("[1,4,5,6,7]",value.getJSONArray("primitiveIds").toString());
            for(String name:new String[]{"support","durationsMs"}){assertEquals("unsupported",value.getJSONObject(name).getString("status"));assertTrue(value.getJSONObject(name).has("values"));assertTrue(value.getJSONObject(name).isNull("values"));}
        }
        try{HardwareCollector.vibratorPrimitiveCapabilities(null,30);fail("API30查询必须有振动器实例");}catch(NullPointerException expected){}
        if(Build.VERSION.SDK_INT>=30){
            android.os.Vibrator vibrator=context.getSystemService(android.os.Vibrator.class);assertNotNull(vibrator);
            JSONObject value=HardwareCollector.vibratorPrimitiveCapabilities(vibrator,30);
            assertEquals("[1,4,5,6,7]",value.getJSONArray("primitiveIds").toString());
            assertEquals("ok",value.getJSONObject("support").getString("status"));
            boolean[] expected=vibrator.arePrimitivesSupported(1,4,5,6,7);JSONArray actual=value.getJSONObject("support").getJSONArray("values");assertEquals(expected.length,actual.length());for(int i=0;i<expected.length;i++)assertEquals(expected[i],actual.getBoolean(i));
            assertEquals("unsupported",value.getJSONObject("durationsMs").getString("status"));assertTrue(value.getJSONObject("durationsMs").isNull("values"));
        }
    }
    @Test public void vibratorPrimitivesMatchPublicApiAndKeepOldCapabilities()throws Exception{
        ItemSink sink=new ItemSink(context,"vibrator-primitives-test");new HardwareCollector("hardware").collect(sink);
        JSONObject item=null;for(int i=0;i<sink.items.length();i++)if(sink.items.getJSONObject(i).getString("key").equals("hardware/vibrator"))item=sink.items.getJSONObject(i);
        assertNotNull(item);assertEquals("ok",item.getString("status"));JSONArray devices=item.getJSONObject("value").getJSONArray("vibrators");
        for(int i=0;i<devices.length();i++){
            JSONObject device=devices.getJSONObject(i),value=device.getJSONObject("primitiveCapabilities");
            assertTrue(device.has("hasAmplitudeControl"));assertTrue(device.has("frequencyCharacteristics"));if(Build.VERSION.SDK_INT>=30)assertTrue(device.has("effectSupportCodes"));
            int[] ids=Build.VERSION.SDK_INT>=31?new int[]{1,2,3,4,5,6,7,8}:new int[]{1,4,5,6,7};
            assertEquals(Json.value(ids).toString(),value.getJSONArray("primitiveIds").toString());
            android.os.Vibrator vibrator=Build.VERSION.SDK_INT>=31?context.getSystemService(android.os.VibratorManager.class).getVibrator(device.getInt("id")):context.getSystemService(android.os.Vibrator.class);
            for(String name:new String[]{"support","durationsMs"}){
                JSONObject query=value.getJSONObject(name);boolean support=name.equals("support");
                if(Build.VERSION.SDK_INT<(support?30:31)){assertEquals("unsupported",query.getString("status"));assertTrue(query.isNull("values"));continue;}
                Object expected;
                try{expected=support?vibrator.arePrimitivesSupported(ids):vibrator.getPrimitiveDurations(ids);}
                catch(Exception|LinkageError e){assertEquals(e instanceof SecurityException?"denied":"error",query.getString("status"));assertTrue(query.isNull("values"));assertEquals(e.getClass().getName(),query.getString("errorClass"));continue;}
                assertEquals("ok",query.getString("status"));assertFalse(query.has("errorClass"));assertEquals(Json.value(expected).toString(),query.getJSONArray("values").toString());assertEquals(ids.length,query.getJSONArray("values").length());
            }
            Bundle evidence=new Bundle();evidence.putString("vibratorPrimitives"+device.getInt("id"),value.toString());InstrumentationRegistry.getInstrumentation().sendStatus(0,evidence);
        }
    }
    @Test public void vibratorAmplitudeGuardsOldApiAndPreservesUnknown()throws Exception{
        for(int sdk:new int[]{24,25}){
            JSONObject query=HardwareCollector.vibratorAmplitudeControl(null,sdk);
            assertEquals("unsupported",query.getString("status"));assertTrue(query.has("value"));assertTrue(query.isNull("value"));
        }
        try{HardwareCollector.vibratorAmplitudeControl(null,26);fail("API26查询必须有振动器实例");}catch(NullPointerException expected){}
        for(Object left:new Object[]{false,true,null})for(Object right:new Object[]{false,true,null}){
            Boolean expected=Boolean.TRUE.equals(left)||Boolean.TRUE.equals(right)?Boolean.TRUE:left==null||right==null?null:Boolean.FALSE;
            JSONArray values=new JSONArray().put(Json.obj("hasAmplitudeControl",left)).put(Json.obj("hasAmplitudeControl",right));
            String before=values.toString();assertEquals(expected,HardwareCollector.vibratorAmplitudeSummary(values));assertEquals(before,values.toString());
        }
        assertEquals(Boolean.FALSE,HardwareCollector.vibratorAmplitudeSummary(new JSONArray()));
        for(Object value:new Object[]{new JSONObject(),JSONObject.NULL,Json.obj("hasAmplitudeControl","false"),Json.obj("hasAmplitudeControl",0)})assertNull(HardwareCollector.vibratorAmplitudeSummary(new JSONArray().put(value)));
        JSONArray maximum=new JSONArray();for(int i=0;i<32;i++)maximum.put(Json.obj("hasAmplitudeControl",false));
        assertEquals(Boolean.FALSE,HardwareCollector.vibratorAmplitudeSummary(maximum));maximum.put(Json.obj("hasAmplitudeControl",true));
        try{HardwareCollector.vibratorAmplitudeSummary(maximum);fail("超过32个不能静默截断");}catch(IllegalArgumentException expected){}
        try{HardwareCollector.vibratorAmplitudeSummary(null);fail("缺失清单不能当空集合");}catch(NullPointerException expected){}
    }
    @Test public void vibratorFrequencyValuesKeepUnknownAndNonFiniteDistinct()throws Exception{
        for(float raw:new float[]{150.5f,0f,-1f,Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY}){
            JSONObject value=new JSONObject(HardwareCollector.vibratorFrequencyValue(raw).toString());
            assertEquals(Float.isNaN(raw)?"unknown":Float.isInfinite(raw)?"non-finite":"ok",value.getString("status"));
            if(Float.isFinite(raw))assertEquals(Float.floatToIntBits(raw),Float.floatToIntBits((float)value.getDouble("value")));
            else{JSONObject encoded=value.getJSONObject("value");assertEquals("float",encoded.getString("$type"));assertEquals(Float.toString(raw),encoded.getString("value"));}
        }
        try{HardwareCollector.vibratorFrequencyCharacteristics(null);fail("缺失振动器不能猜频率");}catch(NullPointerException expected){}
    }
    @Test public void vibratorFrequencyMatchesPublicApiAndKeepsOldEffects()throws Exception{
        ItemSink sink=new ItemSink(context,"vibrator-frequency-test");new HardwareCollector("hardware").collect(sink);
        JSONObject item=null;for(int i=0;i<sink.items.length();i++)if(sink.items.getJSONObject(i).getString("key").equals("hardware/vibrator"))item=sink.items.getJSONObject(i);
        assertNotNull(item);assertEquals("ok",item.getString("status"));JSONObject value=new JSONObject(item.toString()).getJSONObject("value");JSONArray devices=value.getJSONArray("vibrators");
        assertEquals(value.getInt("count"),devices.length());boolean amplitudeSupported=false,amplitudeUnknown=false;
        for(int i=0;i<devices.length();i++){
            JSONObject device=devices.getJSONObject(i);android.os.Vibrator vibrator=Build.VERSION.SDK_INT>=31?context.getSystemService(android.os.VibratorManager.class).getVibrator(device.getInt("id")):context.getSystemService(android.os.Vibrator.class);
            assertEquals(vibrator.hasVibrator(),device.getBoolean("hasVibrator"));
            if(Build.VERSION.SDK_INT<26){assertTrue(device.isNull("hasAmplitudeControl"));assertEquals("unsupported",device.getString("amplitudeControlStatus"));amplitudeUnknown=true;}
            else{
                try{boolean expected=vibrator.hasAmplitudeControl();assertEquals(expected,device.getBoolean("hasAmplitudeControl"));assertEquals("ok",device.getString("amplitudeControlStatus"));assertFalse(device.has("amplitudeControlErrorClass"));amplitudeSupported|=expected;}
                catch(Exception|LinkageError e){assertTrue(device.isNull("hasAmplitudeControl"));assertEquals(e instanceof SecurityException?"denied":"error",device.getString("amplitudeControlStatus"));assertEquals(e.getClass().getName(),device.getString("amplitudeControlErrorClass"));amplitudeUnknown=true;}
            }
            if(Build.VERSION.SDK_INT>=30){int[] expected=vibrator.areEffectsSupported(android.os.VibrationEffect.EFFECT_CLICK,android.os.VibrationEffect.EFFECT_DOUBLE_CLICK,android.os.VibrationEffect.EFFECT_HEAVY_CLICK,android.os.VibrationEffect.EFFECT_TICK);assertEquals(expected.length,device.getJSONArray("effectSupportCodes").length());for(int j=0;j<expected.length;j++)assertEquals(expected[j],device.getJSONArray("effectSupportCodes").getInt(j));}
            JSONObject characteristics=device.getJSONObject("frequencyCharacteristics");assertEquals(2,characteristics.length());
            for(String name:new String[]{"resonantFrequencyHz","qFactor"}){
                JSONObject query=characteristics.getJSONObject(name);
                if(Build.VERSION.SDK_INT<34){assertEquals("unsupported",query.getString("status"));assertTrue(query.isNull("value"));continue;}
                float expected;
                try{expected=name.equals("resonantFrequencyHz")?vibrator.getResonantFrequency():vibrator.getQFactor();}
                catch(Exception|LinkageError e){assertEquals(e instanceof SecurityException?"denied":"error",query.getString("status"));assertTrue(query.isNull("value"));assertEquals(e.getClass().getName(),query.getString("errorClass"));continue;}
                assertEquals(Float.isNaN(expected)?"unknown":Float.isInfinite(expected)?"non-finite":"ok",query.getString("status"));assertFalse(query.has("errorClass"));
                if(Float.isFinite(expected))assertEquals(Float.floatToIntBits(expected),Float.floatToIntBits((float)query.getDouble("value")));
                else{assertEquals("float",query.getJSONObject("value").getString("$type"));assertEquals(Float.toString(expected),query.getJSONObject("value").getString("value"));}
            }
            Bundle evidence=new Bundle();evidence.putString("vibratorFrequency"+device.getInt("id"),characteristics.toString());InstrumentationRegistry.getInstrumentation().sendStatus(0,evidence);
        }
        if(!amplitudeSupported&&amplitudeUnknown)assertTrue(value.isNull("hasAmplitudeControl"));else assertEquals(amplitudeSupported,value.getBoolean("hasAmplitudeControl"));
    }
    @Test public void vibratorEvidenceUsesPublicCapabilitiesOnly()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new HardwareCollector("hardware").collect(sink);
        JSONObject item=null;for(int i=0;i<sink.items.length();i++){JSONObject candidate=sink.items.getJSONObject(i);if(candidate.getString("key").equals("hardware/vibrator")){item=candidate;break;}}
        assertNotNull("应记录振动器能力",item);assertEquals("ok",item.getString("status"));JSONObject value=item.getJSONObject("value");assertTrue(value.has("available"));assertTrue(value.has("count"));assertTrue(value.has("vibrators"));assertTrue(value.getJSONArray("vibrators").length()<=32);assertFalse(item.toString().contains("vibrate("));
    }
    @Test public void bluetoothSummaryKeepsPermissionBoundaryExplicit()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new NetworkCollector("network").collect(sink);
        JSONObject summary=null,raw=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("network/bluetooth-summary"))summary=item;if(item.getString("key").equals("network/bluetooth"))raw=item;}
        assertNotNull("应生成蓝牙能力摘要",summary);assertNotNull("应保留蓝牙原始条目",raw);assertEquals("ok",summary.getString("status"));JSONObject value=summary.getJSONObject("value");assertTrue(value.has("available"));assertTrue(value.has("access"));assertTrue(value.has("bondedTypeCounts"));assertTrue(value.has("bondedClassCounts"));if("allowed".equals(value.optString("access")))assertTrue(value.has("bondedCount"));
    }
    @Test public void bluetoothLeCapabilitiesKeepPermissionBoundary()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new NetworkCollector("network").collect(sink);
        JSONObject capabilities=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("network/bluetooth-capabilities")){capabilities=item;break;}}
        assertNotNull("应记录蓝牙 LE 能力",capabilities);assertEquals("ok",capabilities.getString("status"));JSONObject value=capabilities.getJSONObject("value");assertTrue(value.has("available"));assertTrue(value.has("access"));assertTrue(value.has("le2MPhySupported"));assertTrue(value.has("leCodedPhySupported"));assertTrue(value.has("leExtendedAdvertisingSupported"));assertTrue(value.has("leMaximumAdvertisingDataLength"));
    }
    @Test public void activeNetworkKeepsVersionedCapabilityBoundaries()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new NetworkCollector("network").collect(sink);
        JSONObject risk=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("network/risk-signals")){risk=item;break;}}
        assertNotNull("应记录活动链路风控信号",risk);assertEquals("ok",risk.getString("status"));JSONObject value=risk.getJSONObject("value");if(value.optBoolean("capabilitiesAvailable")){assertTrue(value.has("capabilityStates"));JSONObject states=value.getJSONObject("capabilityStates");assertTrue(states.has("internet"));assertTrue(states.has("notSuspended"));assertTrue(states.has("notCongested"));assertTrue(states.has("temporarilyNotMetered"));assertTrue(states.has("notBandwidthConstrained"));assertTrue(states.has("enterprise"));assertTrue(states.has("localNetwork"));}
    }
    @Test public void wifiSummaryKeepsScanPermissionBoundaryExplicit()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new NetworkCollector("network").collect(sink);
        JSONObject summary=null,raw=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("network/wifi-summary"))summary=item;if(item.getString("key").equals("network/wifi-cached-scan"))raw=item;}
        assertNotNull("应生成 Wi-Fi 能力摘要",summary);assertNotNull("应保留 Wi-Fi 缓存原始条目",raw);assertEquals("ok",summary.getString("status"));JSONObject value=summary.getJSONObject("value");assertTrue(value.has("connectionAccess"));assertTrue(value.has("scanAccess"));assertTrue(value.has("cachedScanCount"));assertTrue(value.getJSONArray("frequencies").length()<=64);assertTrue(value.has("frequenciesTruncated"));
    }
    @Test public void wifiCapabilitiesKeepApiBoundariesExplicit()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new NetworkCollector("network").collect(sink);
        JSONObject item=null;for(int i=0;i<sink.items.length();i++){JSONObject candidate=sink.items.getJSONObject(i);if(candidate.getString("key").equals("network/wifi-capabilities")){item=candidate;break;}}
        assertNotNull("应记录 Wi-Fi 链路能力",item);assertEquals("ok",item.getString("status"));JSONObject value=item.getJSONObject("value");assertTrue(value.has("available"));assertTrue(value.has("access"));assertTrue(value.has("wifiStandard"));assertTrue(value.has("maxSupportedRxLinkSpeedMbps"));assertTrue(value.has("maxSupportedTxLinkSpeedMbps"));assertTrue(value.has("apMldMacPresent"));assertTrue(value.has("apMloLinkId"));assertTrue(value.has("affiliatedMloLinkCount"));assertFalse(item.toString().contains("apMldMacAddress"));
    }
    @Test public void connectivitySummaryKeepsRawNetworksSeparate()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new NetworkCollector("network").collect(sink);
        JSONObject summary=null,raw=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("network/connectivity-summary"))summary=item;if(item.getString("key").equals("network/connectivity"))raw=item;}
        assertNotNull("应生成网络集合摘要",summary);assertNotNull("应保留网络连接原始清单",raw);assertEquals("ok",summary.getString("status"));assertEquals("ok",raw.getString("status"));JSONObject value=summary.getJSONObject("value");assertTrue(value.has("networkCount"));assertTrue(value.has("activePresent"));assertTrue(value.has("capabilitiesAvailableCount"));assertTrue(value.has("transportCounts"));
    }
    @Test public void privateDnsSummaryKeepsAddressesOutOfEvidence()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new NetworkCollector("network").collect(sink);
        JSONObject summary=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("network/private-dns-summary")){summary=item;break;}}
        assertNotNull("应生成 Private DNS 摘要",summary);assertEquals("ok",summary.getString("status"));JSONObject value=summary.getJSONObject("value");assertTrue(value.has("activeNetworkPresent"));assertTrue(value.has("linkPropertiesAvailable"));assertTrue(value.has("privateDnsSupported"));assertTrue(value.has("dnsServerCount"));assertTrue(value.has("dnsIpv4Count"));assertTrue(value.has("dnsIpv6Count"));assertTrue(value.has("privateDnsServerNamePresent"));assertFalse(value.has("privateDnsServers"));assertFalse(value.has("privateDnsServerName"));
    }
    @Test public void proxySummaryKeepsRawProxySeparate()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new NetworkCollector("network").collect(sink);
        JSONObject summary=null,raw=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("network/proxy-summary"))summary=item;if(item.getString("key").equals("network/proxy"))raw=item;}
        assertNotNull("应生成代理环境摘要",summary);assertNotNull("应保留代理原始条目",raw);assertEquals("ok",summary.getString("status"));JSONObject value=summary.getJSONObject("value");assertTrue(value.has("available"));assertTrue(value.has("status"));assertTrue(value.has("configured"));assertTrue(value.has("hostPresent"));assertTrue(value.has("hostLength"));assertTrue(value.has("hostSha256"));assertTrue(value.has("port"));assertTrue(value.has("exclusionCount"));assertTrue(value.has("pacFilePresent"));assertTrue(value.has("pacScheme"));assertFalse(value.has("host"));assertFalse(value.has("exclusionList"));assertFalse(value.has("pacFileUrl"));
    }
    @Test public void interfaceSummaryKeepsRawInterfacesSeparate()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new NetworkCollector("network").collect(sink);
        JSONObject summary=null,raw=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("network/interface-summary"))summary=item;if(item.getString("key").equals("network/interfaces"))raw=item;}
        assertNotNull("应生成网络接口摘要",summary);assertNotNull("应保留网络接口原始条目",raw);assertEquals("ok",summary.getString("status"));assertEquals("ok",raw.getString("status"));JSONObject value=summary.getJSONObject("value");assertTrue(value.has("available"));if(value.getBoolean("available"))assertTrue(value.getInt("interfaceCount")>=0);else assertTrue(value.isNull("interfaceCount"));assertTrue(value.has("prefixCounts"));assertFalse(summary.toString().matches(".*(?:wlan|rmnet|eth|tun|tap|ppp)[0-9A-Za-z_-]*.*"));assertFalse(summary.toString().matches(".*(?:\\b\\d{1,3}\\.){3}\\d{1,3}\\b.*"));assertFalse(summary.toString().matches(".*(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}.*"));
    }
    @Test public void playIntegrityEnvironmentIsExplicitlyOffline()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new SecurityCollector("attestation").collect(sink);
        JSONObject environment=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("attestation/play-integrity-environment")){environment=item;break;}}
        assertNotNull("应记录 Play Integrity 本地环境",environment);assertEquals("ok",environment.getString("status"));JSONObject value=environment.getJSONObject("value");assertFalse(value.getBoolean("verdictAvailable"));assertTrue(value.has("playServices"));assertTrue(value.has("playStore"));
    }
    @Test public void webViewProviderKeepsPackageEvidence()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new BuildSystemCollector("environment").collect(sink);
        JSONObject provider=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("environment/webview-provider")){provider=item;break;}}
        assertNotNull("应记录 WebView 提供方条目",provider);assertTrue(provider.getString("status").equals("ok")||provider.getString("status").equals("unsupported"));
        if("ok".equals(provider.getString("status"))&&!provider.isNull("value")){JSONObject value=provider.getJSONObject("value");assertTrue(value.getString("packageName").length()>0);assertTrue(value.has("signatures"));}
    }
    @Test public void webViewSigningHistoryKeepsRotationOrderAndUnknownStates()throws Exception{
        JSONObject absent=new JSONObject();BuildSystemCollector.putWebViewSigningHistory(absent,null);
        assertEquals(Build.VERSION.SDK_INT<28?"unsupported":"unavailable",absent.getString("signingHistoryStatus"));
        assertTrue(absent.isNull("hasMultipleSigners"));assertTrue(absent.isNull("signingHistoryCount"));assertTrue(absent.isNull("signingHistorySha256"));
        if(Build.VERSION.SDK_INT>=28){
            android.content.pm.PackageInfo unknown=new android.content.pm.PackageInfo();unknown.signingInfo=new android.content.pm.SigningInfo();
            JSONObject missing=new JSONObject();BuildSystemCollector.putWebViewSigningHistory(missing,unknown);
            assertEquals("unavailable",missing.getString("signingHistoryStatus"));assertTrue(missing.isNull("signingHistoryCount"));
        }
        if(Build.VERSION.SDK_INT>=35){
            android.content.pm.Signature[] history=new android.content.pm.Signature[18];for(int i=0;i<history.length;i++)history[i]=new android.content.pm.Signature(new byte[]{(byte)i});history[1]=history[0];
            android.content.pm.PackageInfo synthetic=new android.content.pm.PackageInfo();
            synthetic.signingInfo=new android.content.pm.SigningInfo(3,Collections.singletonList(history[17]),null,Arrays.asList(history));
            JSONObject value=new JSONObject();BuildSystemCollector.putWebViewSigningHistory(value,synthetic);
            assertEquals("partial",value.getString("signingHistoryStatus"));assertFalse(value.getBoolean("hasMultipleSigners"));assertTrue(value.getBoolean("hasPastSigningCertificates"));
            assertEquals(18,value.getInt("signingHistoryCount"));assertEquals(16,value.getInt("signingHistoryLimit"));assertTrue(value.getBoolean("signingHistoryTruncated"));
            JSONArray hashes=value.getJSONArray("signingCertificateHistorySha256");assertEquals(16,hashes.length());
            StringBuilder canonical=new StringBuilder();for(android.content.pm.Signature signature:history)canonical.append(PackageInputCollector.sha(signature.toByteArray())).append('\n');
            assertEquals(PackageInputCollector.sha(canonical.toString().getBytes(StandardCharsets.UTF_8)),value.getString("signingHistorySha256"));
            assertEquals(hashes.getString(0),hashes.getString(1));assertEquals(PackageInputCollector.sha(new byte[]{15}),hashes.getString(15));
            android.content.pm.Signature[] changed=history.clone();changed[17]=new android.content.pm.Signature(new byte[]{99});
            synthetic.signingInfo=new android.content.pm.SigningInfo(3,Collections.singletonList(changed[17]),null,Arrays.asList(changed));
            JSONObject outside=new JSONObject();BuildSystemCollector.putWebViewSigningHistory(outside,synthetic);
            assertEquals(hashes.toString(),outside.getJSONArray("signingCertificateHistorySha256").toString());assertNotEquals(value.getString("signingHistorySha256"),outside.getString("signingHistorySha256"));
            List<android.content.pm.Signature> reversed=new ArrayList<>(Arrays.asList(history));Collections.reverse(reversed);
            synthetic.signingInfo=new android.content.pm.SigningInfo(3,Collections.singletonList(history[0]),null,reversed);
            JSONObject reordered=new JSONObject();BuildSystemCollector.putWebViewSigningHistory(reordered,synthetic);
            assertNotEquals(value.getString("signingHistorySha256"),reordered.getString("signingHistorySha256"));assertEquals(PackageInputCollector.sha(new byte[]{17}),reordered.getJSONArray("signingCertificateHistorySha256").getString(0));
            synthetic.signingInfo=new android.content.pm.SigningInfo(3,Arrays.asList(history[0],history[2]),null,null);
            JSONObject multiple=new JSONObject();BuildSystemCollector.putWebViewSigningHistory(multiple,synthetic);
            assertEquals("multiple-signers",multiple.getString("signingHistoryStatus"));assertTrue(multiple.getBoolean("hasMultipleSigners"));assertTrue(multiple.isNull("signingHistoryCount"));assertTrue(multiple.isNull("signingHistoryTruncated"));assertTrue(multiple.isNull("signingHistorySha256"));
            synthetic.signingInfo=new android.content.pm.SigningInfo(3,Collections.singletonList(history[0]),null,null);
            JSONObject single=new JSONObject();BuildSystemCollector.putWebViewSigningHistory(single,synthetic);
            assertEquals("ok",single.getString("signingHistoryStatus"));assertFalse(single.getBoolean("hasPastSigningCertificates"));assertEquals(1,single.getInt("signingHistoryCount"));assertFalse(single.getBoolean("signingHistoryTruncated"));
        }
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new BuildSystemCollector("environment").collect(sink);
        JSONObject summary=null;for(int i=0;i<sink.items.length();i++)if(sink.items.getJSONObject(i).getString("key").equals("environment/webview-package-summary"))summary=sink.items.getJSONObject(i);
        assertNotNull(summary);assertEquals("ok",summary.getString("status"));JSONObject value=summary.getJSONObject("value");
        if(!value.getBoolean("available"))return;
        assertTrue(value.has("signingHistoryStatus"));assertTrue(value.has("signingCertificateSha256"));assertTrue(value.has("installerQueryStatus"));
        if(Build.VERSION.SDK_INT>=28&&value.getString("packageQueryStatus").equals("allowed")){
            android.content.pm.SigningInfo info=context.getPackageManager().getPackageInfo(value.getString("packageName"),android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES).signingInfo;
            if(info!=null){assertEquals(info.hasMultipleSigners(),value.getBoolean("hasMultipleSigners"));assertEquals(info.hasPastSigningCertificates(),value.getBoolean("hasPastSigningCertificates"));
                android.content.pm.Signature[] history=info.getSigningCertificateHistory();
                if(history!=null){assertEquals(history.length,value.getInt("signingHistoryCount"));assertEquals(Math.min(16,history.length),value.getJSONArray("signingCertificateHistorySha256").length());for(int i=0;i<Math.min(16,history.length);i++)assertEquals(PackageInputCollector.sha(history[i].toByteArray()),value.getJSONArray("signingCertificateHistorySha256").getString(i));}
            }
        }
        assertFalse(value.toString().contains("derBase64"));
    }
    @Test public void webViewPackageSummaryKeepsScopedAndBoundedEvidence()throws Exception{
        android.content.pm.Signature[] synthetic=new android.content.pm.Signature[18];for(int i=0;i<synthetic.length;i++)synthetic[i]=new android.content.pm.Signature(new byte[]{(byte)i});
        JSONArray hashes=BuildSystemCollector.webViewSignerHashes(synthetic);assertEquals(16,hashes.length());assertEquals(PackageInputCollector.sha(new byte[]{0}),hashes.getString(0));assertEquals(PackageInputCollector.sha(new byte[]{15}),hashes.getString(15));
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new BuildSystemCollector("environment").collect(sink);
        JSONObject summary=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("environment/webview-package-summary")){summary=item;break;}}
        assertNotNull(summary);assertEquals("ok",summary.getString("status"));JSONObject value=summary.getJSONObject("value");
        if(!value.getBoolean("available")){assertTrue(java.util.Arrays.asList("unsupported","no-provider").contains(value.getString("status")));return;}
        assertTrue(value.getString("packageName").length()>0);assertTrue(value.has("selectedVersionCode"));assertTrue(value.has("selectionVersionMatches"));assertTrue(value.has("packageQueryStatus"));assertTrue(value.has("installerQueryStatus"));assertEquals(16,value.getInt("signatureLimit"));
        JSONArray actual=value.getJSONArray("signingCertificateSha256");assertTrue(actual.length()<=16);for(int i=0;i<actual.length();i++)assertTrue(actual.getString(i).matches("[a-f0-9]{64}"));
        if("allowed".equals(value.getString("packageQueryStatus"))){assertFalse(value.isNull("versionCode"));assertTrue(value.has("firstInstallTime"));assertTrue(value.has("targetSdkVersion"));assertTrue(value.has("systemApp"));if(!value.isNull("signatureCount")){assertEquals(Math.min(16,value.getInt("signatureCount")),actual.length());assertEquals(value.getInt("signatureCount")>16,value.getBoolean("signaturesTruncated"));}}
        assertFalse(value.toString().contains("derBase64"));assertFalse(value.toString().contains("sourceDir"));assertFalse(value.toString().contains("dataDir"));
    }
    @Test public void deviceManagementEvidenceIsScopedToCurrentApp()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new SecurityCollector("security").collect(sink);
        JSONObject management=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("security/device-management")){management=item;break;}}
        assertNotNull("应记录设备管理环境条目",management);assertEquals("ok",management.getString("status"));JSONObject value=management.getJSONObject("value");assertTrue(value.has("deviceOwnerForSelf"));assertTrue(value.has("profileOwnerForSelf"));assertTrue(value.has("managedProfile"));assertTrue(value.has("restrictions"));assertFalse(value.has("deviceOwnerPackage"));
    }
    @Test public void securitySummaryKeepsDetailedEvidenceSeparate()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new SecurityCollector("security").collect(sink);
        JSONObject summary=null,self=null,providers=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("security/summary"))summary=item;if(item.getString("key").equals("security/self"))self=item;if(item.getString("key").equals("security/providers-summary"))providers=item;}
        assertNotNull("应生成安全环境摘要",summary);assertNotNull("应保留应用安全上下文",self);assertNotNull("应生成安全提供者摘要",providers);assertEquals("ok",summary.getString("status"));assertEquals("ok",self.getString("status"));assertEquals("ok",providers.getString("status"));JSONObject value=summary.getJSONObject("value");assertTrue(value.has("selfDebuggable"));assertTrue(value.has("appOpsAllowedCount"));assertEquals(10,value.getInt("appOpsCheckedCount"));assertTrue(value.has("keyguardSecure"));assertTrue(value.has("accessibilityServiceCount"));assertTrue(value.has("securityProviderCount"));JSONObject providerValue=providers.getJSONObject("value");assertTrue(providerValue.getInt("providerCount")>0);assertTrue(providerValue.has("serviceTypeCounts"));assertTrue(providerValue.has("serviceNames"));assertFalse(summary.toString().contains("derBase64"));assertFalse(summary.toString().contains("certificateChainBase64"));assertFalse(summary.toString().contains("windowText"));
    }
    @Test public void selinuxSummaryKeepsContextOutOfEvidence()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new SecurityCollector("security").collect(sink);
        JSONObject summary=null,raw=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("security/selinux-summary"))summary=item;if(item.getString("key").equals("security/file/proc/self/attr/current"))raw=item;}
        assertNotNull("应生成 SELinux 环境摘要",summary);assertNotNull("应保留 SELinux 原始上下文条目",raw);assertEquals("ok",summary.getString("status"));JSONObject value=summary.getJSONObject("value");assertTrue(value.has("enforceFilePresent"));assertTrue(value.has("enforcing"));assertTrue(value.has("contextPresent"));assertTrue(value.has("contextLength"));assertTrue(value.has("contextSha256"));assertFalse(value.has("context"));if(value.getBoolean("contextPresent"))assertTrue(value.getString("contextSha256").matches("[0-9a-f]{64}"));
    }
    @Test public void specialAccessEvidenceUsesCurrentAppOps()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new SecurityCollector("security").collect(sink);
        JSONObject access=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("security/special-access")){access=item;break;}}
        assertNotNull("应记录本 App 特殊访问条目",access);assertEquals("ok",access.getString("status"));JSONObject value=access.getJSONObject("value");assertTrue(value.has("available"));assertTrue(value.has("canDrawOverlays"));assertTrue(value.has("canRequestPackageInstalls"));assertTrue(value.has("canRequestPackageInstallsStatus"));assertTrue(value.has("notificationPolicyAccess"));assertTrue(value.has("notificationPolicyAccessStatus"));assertTrue(value.has("allFilesAccess"));assertTrue(value.has("allFilesAccessStatus"));if(value.optBoolean("available")){assertTrue(value.has("appOps"));JSONObject appOps=value.getJSONObject("appOps");assertTrue(appOps.has("android:camera"));assertTrue(appOps.has("android:record_audio"));assertTrue(appOps.has("android:body_sensors"));assertTrue(appOps.has("android:fine_location"));assertTrue(appOps.has("android:coarse_location"));}assertFalse(value.has("otherPackages"));
    }
    @Test public void lockBiometricEvidenceUsesPublicStateOnly()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new SecurityCollector("security").collect(sink);
        JSONObject lock=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("security/lock-biometric")){lock=item;break;}}
        assertNotNull("应记录锁屏与生物识别环境",lock);assertEquals("ok",lock.getString("status"));JSONObject value=lock.getJSONObject("value");assertTrue(value.has("keyguardSecure"));assertTrue(value.has("deviceLocked"));assertTrue(value.has("fingerprintFeature"));assertTrue(value.has("biometricStrongCode"));assertTrue(value.has("biometricStrongStatus"));assertTrue(value.has("biometricWeakCode"));assertTrue(value.has("biometricWeakStatus"));assertTrue(value.has("deviceCredentialCode"));assertTrue(value.has("deviceCredentialStatus"));assertFalse(value.has("biometricTemplate"));
    }
    @Test public void missingKeyguardKeepsUnknownSeparateFromFalse()throws Exception{
        Context missingKeyguard=new ContextWrapper(context){
            @Override public Object getSystemService(String name){
                if(Context.KEYGUARD_SERVICE.equals(name))return null;
                return super.getSystemService(name);
            }
        };
        assertNull(missingKeyguard.getSystemService(KeyguardManager.class));
        assertNotNull("正常测试设备应提供锁屏服务",context.getSystemService(KeyguardManager.class));
        for(Context source:new Context[]{missingKeyguard,context}){
            ItemSink sink=new ItemSink(source,UUID.randomUUID().toString());new SecurityCollector("security").collect(sink);
            JSONObject exported=new JSONObject(Json.obj("items",sink.items).toString());int checked=0;
            for(int i=0;i<exported.getJSONArray("items").length();i++){
                JSONObject item=exported.getJSONArray("items").getJSONObject(i);String key=item.getString("key");
                if(!key.equals("security/summary")&&!key.equals("security/lock-biometric"))continue;
                checked++;assertEquals("其它公开状态仍应正常采集", "ok",item.getString("status"));JSONObject value=item.getJSONObject("value");
                for(String field:new String[]{"keyguardSecure","keyguardLocked","deviceSecure","deviceLocked"}){
                    assertTrue(key+" 应保留 "+field,value.has(field));
                    if(source==missingKeyguard)assertTrue(key+" 缺失锁屏服务时 "+field+" 必须为 null，不能为 false",value.isNull(field));
                    else assertTrue(key+" 正常服务的 "+field+" 应为 boolean",value.get(field) instanceof Boolean);
                }
                assertTrue(value.get("fingerprintFeature") instanceof Boolean);
                if(key.equals("security/lock-biometric"))assertTrue(value.has("biometricStrongStatus"));
                else assertTrue(value.has("securityProviderCount"));
            }
            assertEquals("两个锁屏采集路径均须覆盖",2,checked);
        }
    }
    @Test public void accessibilityEvidenceUsesServiceMetadataOnly()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new SecurityCollector("security").collect(sink);
        JSONObject accessibility=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("security/accessibility")){accessibility=item;break;}}
        assertNotNull("应记录无障碍服务元数据",accessibility);assertEquals("ok",accessibility.getString("status"));JSONObject value=accessibility.getJSONObject("value");assertTrue(value.has("available"));assertTrue(value.has("count"));assertTrue(value.has("services"));JSONArray services=value.getJSONArray("services");for(int i=0;i<services.length();i++){JSONObject service=services.getJSONObject(i);assertFalse(service.has("windowText"));assertFalse(service.has("password"));}
    }
    @Test public void selfIntegrityEvidenceUsesSignatureHashesOnly()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new SecurityCollector("security").collect(sink);
        JSONObject integrity=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("security/self-integrity")){integrity=item;break;}}
        assertNotNull("应记录当前 App 完整性摘要",integrity);assertEquals("ok",integrity.getString("status"));JSONObject value=integrity.getJSONObject("value");assertEquals(context.getPackageName(),value.getString("packageName"));assertTrue(value.has("installer"));assertTrue(value.has("initiatingPackageName"));assertTrue(value.has("originatingPackageName"));assertTrue(value.has("updateOwnerPackageName"));assertTrue(value.has("packageSource"));assertTrue(value.has("packageSourceName"));assertTrue(value.has("packageSourceStatus"));assertTrue(value.has("signingCertificateSha256"));assertTrue(value.has("signingCertificateHistorySha256"));assertTrue(value.has("hasPastSigningCertificates"));assertTrue(value.has("applicationFlagsSummary"));JSONObject flags=value.getJSONObject("applicationFlagsSummary");assertTrue(flags.has("testOnly"));assertTrue(flags.has("usesCleartextTraffic"));assertTrue(flags.has("hardwareAccelerated"));assertTrue(value.has("instantApp"));assertTrue(value.has("packageSuspended"));assertTrue(value.has("packageSuspensionQueryStatus"));assertFalse(integrity.toString().contains("derBase64"));assertFalse(integrity.toString().contains("sourceDir"));
    }
    @Test public void selfRuntimeEvidenceKeepsPublicApiBoundaries()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new SecurityCollector("security").collect(sink);
        JSONObject runtime=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("security/self-runtime")){runtime=item;break;}}
        assertNotNull("应记录应用运行时可分析配置",runtime);assertEquals("ok",runtime.getString("status"));JSONObject value=runtime.getJSONObject("value");assertTrue(value.getBoolean("available"));assertTrue(value.has("profileableByShell"));assertTrue(value.has("profileable"));assertTrue(value.has("virtualPreload"));assertTrue(value.has("gwpAsanMode"));assertTrue(value.has("memtagMode"));assertTrue(value.has("nativeHeapZeroInitialized"));assertTrue(value.has("requestRawExternalStorageAccess"));assertTrue(value.has("attributionsUserVisible"));assertFalse(runtime.toString().contains("debuggerConnected"));
    }
    @Test public void processIdentityEvidenceKeepsSandboxClassificationScoped()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new SecurityCollector("security").collect(sink);
        JSONObject identity=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("security/process-identity-summary")){identity=item;break;}}
        assertNotNull("应记录当前进程身份摘要",identity);assertEquals("ok",identity.getString("status"));JSONObject value=identity.getJSONObject("value");assertTrue(value.getBoolean("available"));assertEquals(context.getPackageName(),value.getString("packageName"));assertTrue(value.has("uid"));assertTrue(value.has("isApplicationUid"));assertTrue(value.has("isIsolatedUid"));assertTrue(value.has("isSdkSandboxUid"));assertTrue(value.has("isIsolatedProcess"));assertTrue(value.has("isSdkSandboxProcess"));assertFalse(identity.toString().contains("otherPackages"));
    }
    @Test public void selfPermissionsEvidenceIsBoundedAndScoped()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new SecurityCollector("security").collect(sink);
        JSONObject permissions=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("security/self-permissions")){permissions=item;break;}}
        assertNotNull("应记录当前 App 权限摘要",permissions);assertEquals("ok",permissions.getString("status"));JSONObject value=permissions.getJSONObject("value");assertTrue(value.getBoolean("available"));assertTrue(value.has("requestedCount"));assertTrue(value.has("grantedCount"));assertTrue(value.has("deniedCount"));assertTrue(value.has("unknownCount"));assertTrue(value.has("dangerousRequestedCount"));assertTrue(value.has("dangerousGrantedCount"));assertTrue(value.has("protectionInfoCount"));assertTrue(value.getInt("returnedCount")<=256);assertTrue(value.getJSONArray("permissions").length()<=256);assertFalse(permissions.toString().contains("otherPackages"));
    }
    @Test public void selfComponentsEvidenceSummarizesCurrentAppOnly()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new SecurityCollector("security").collect(sink);
        JSONObject components=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("security/self-components-summary")){components=item;break;}}
        assertNotNull("应记录当前 App 组件摘要",components);assertEquals("ok",components.getString("status"));JSONObject value=components.getJSONObject("value");assertTrue(value.getBoolean("available"));assertTrue(value.has("activityCount"));assertTrue(value.has("receiverCount"));assertTrue(value.has("serviceCount"));assertTrue(value.has("providerCount"));assertEquals(value.getInt("componentCount"),value.getInt("activityCount")+value.getInt("receiverCount")+value.getInt("serviceCount")+value.getInt("providerCount"));assertTrue(value.has("exportedCount"));assertTrue(value.has("permissionFieldCount"));assertTrue(value.has("directBootAwareCount"));assertTrue(value.has("isolatedServiceCount"));assertTrue(value.has("foregroundServiceTypeCount"));assertFalse(components.toString().contains("sourceDir"));assertFalse(components.toString().contains("otherPackages"));
    }
    @Test public void rootMountEvidenceDecodesFlagsWithoutWriteProbes()throws Exception{
        long all=android.system.OsConstants.ST_RDONLY|android.system.OsConstants.ST_NOSUID|android.system.OsConstants.ST_NODEV|android.system.OsConstants.ST_NOEXEC;
        JSONObject flags=SecurityCollector.rootMountFlags("/synthetic",all);for(String key:new String[]{"readOnly","noSuid","noDev","noExec"})assertTrue(flags.getBoolean(key));
        JSONObject readWrite=SecurityCollector.rootMountFlags("/synthetic",0);for(String key:new String[]{"readOnly","noSuid","noDev","noExec"})assertFalse(readWrite.getBoolean(key));
        JSONObject onlyNoExec=SecurityCollector.rootMountFlags("/synthetic",android.system.OsConstants.ST_NOEXEC);assertTrue(onlyNoExec.getBoolean("noExec"));assertFalse(onlyNoExec.getBoolean("readOnly"));
        String[] flagNames={"readOnly","noSuid","noDev","noExec"};int[] flagValues={android.system.OsConstants.ST_RDONLY,android.system.OsConstants.ST_NOSUID,android.system.OsConstants.ST_NODEV,android.system.OsConstants.ST_NOEXEC};
        for(int i=0;i<flagValues.length;i++){JSONObject single=SecurityCollector.rootMountFlags("/synthetic",flagValues[i]);for(int j=0;j<flagNames.length;j++)assertEquals(i==j,single.getBoolean(flagNames[j]));}
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new SecurityCollector("security").collect(sink);
        JSONObject summary=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("security/root-mount-summary")){summary=item;break;}}
        assertNotNull(summary);assertEquals("ok",summary.getString("status"));JSONObject value=summary.getJSONObject("value");assertEquals(7,value.getInt("pathChecks"));assertEquals(7,value.getInt("pathLimit"));assertFalse(value.getBoolean("writeProbeAttempted"));assertTrue(value.getBoolean("followSymlinks"));assertTrue(value.getBoolean("evidenceOnly"));
        assertEquals(7,value.getInt("knownCount")+value.getInt("unknownCount"));assertEquals(value.getInt("knownCount"),value.getInt("readOnlyCount")+value.getInt("readWriteFlagCount"));assertEquals(value.getInt("unknownCount"),value.getInt("deniedCount")+value.getInt("missingCount")+value.getInt("unresolvedCount")+value.getInt("errorCount"));
        JSONArray entries=value.getJSONArray("entries");assertEquals(7,entries.length());Set<String> paths=new HashSet<>();int known=0,ro=0;Map<String,Integer> unknown=new HashMap<>();
        for(int i=0;i<entries.length();i++){JSONObject entry=entries.getJSONObject(i);assertTrue(paths.add(entry.getString("path")));String status=entry.getString("status");if(status.equals("ok")){known++;if(entry.getBoolean("readOnly"))ro++;assertTrue(entry.has("flags"));assertFalse(entry.isNull("noExec"));}else{assertTrue(Arrays.asList("missing","denied","unresolved","error").contains(status));unknown.put(status,unknown.getOrDefault(status,0)+1);assertTrue(entry.isNull("flags"));assertTrue(entry.isNull("readOnly"));assertTrue(entry.getInt("errno")!=0);}}
        assertEquals(known,value.getInt("knownCount"));assertEquals(ro,value.getInt("readOnlyCount"));for(String status:new String[]{"denied","missing","unresolved","error"})assertEquals((int)unknown.getOrDefault(status,0),value.getInt(status+"Count"));
        assertEquals(new HashSet<>(Arrays.asList("/system","/system/bin","/system/sbin","/system/xbin","/vendor/bin","/sbin","/etc")),paths);assertFalse(value.has("isRooted"));
    }
    @Test public void rootPathEvidenceSeparatesMissingDeniedAndUnresolved()throws Exception{
        assertEquals("present",SecurityCollector.rootPathStatus(Json.obj("ok",true,"errno",0)));
        assertEquals("missing",SecurityCollector.rootPathStatus(Json.obj("ok",false,"errno",2)));
        for(int errno:new int[]{1,13})assertEquals("denied",SecurityCollector.rootPathStatus(Json.obj("ok",false,"errno",errno)));
        for(int errno:new int[]{20,40})assertEquals("unresolved",SecurityCollector.rootPathStatus(Json.obj("ok",false,"errno",errno)));
        assertEquals("error",SecurityCollector.rootPathStatus(Json.obj("ok",false,"errno",5)));
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new SecurityCollector("security").collect(sink);
        JSONObject summary=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("security/root-path-summary")){summary=item;break;}}
        assertNotNull(summary);assertEquals("ok",summary.getString("status"));assertEquals("jni",summary.getJSONObject("source").getString("kind"));JSONObject value=summary.getJSONObject("value");
        assertEquals(32,value.getInt("pathChecks"));assertEquals(32,value.getInt("pathLimit"));assertFalse(value.getBoolean("binaryExecutionAttempted"));assertFalse(value.getBoolean("followParentSymlinks"));assertTrue(value.getBoolean("evidenceOnly"));
        assertEquals(32,value.getInt("presentCount")+value.getInt("missingCount")+value.getInt("unknownCount"));assertEquals(value.getInt("unknownCount"),value.getInt("deniedCount")+value.getInt("unresolvedCount")+value.getInt("errorCount"));
        JSONArray entries=value.getJSONArray("entries");assertEquals(32,entries.length());java.util.Set<String> paths=new java.util.HashSet<>();java.util.Map<String,Integer> counts=new java.util.HashMap<>();
        for(int i=0;i<entries.length();i++){JSONObject entry=entries.getJSONObject(i);assertTrue(paths.add(entry.getString("path")));String status=entry.getString("status");assertTrue(java.util.Arrays.asList("present","missing","denied","unresolved","error").contains(status));counts.put(status,counts.getOrDefault(status,0)+1);assertTrue(entry.has("errno"));assertFalse(entry.has("linkBase64"));}
        for(String status:new String[]{"present","missing","denied","unresolved","error"})assertEquals((int)counts.getOrDefault(status,0),value.getInt(status+"Count"));
        assertTrue(paths.contains("/system_ext/bin/su"));assertTrue(paths.contains("/system/xbin/busybox"));assertTrue(paths.contains("/data/adb"));assertFalse(value.has("isRooted"));
    }
    @Test public void trustStoreSummaryBoundsCertificateMaterial()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new SecurityCollector("security").collect(sink);
        JSONObject summary=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("security/trust-store-summary")){summary=item;break;}}
        assertNotNull("应记录 CA 信任库摘要",summary);assertEquals("ok",summary.getString("status"));JSONObject value=summary.getJSONObject("value");assertTrue(value.getBoolean("available"));assertTrue(value.has("certificateCount"));assertTrue(value.has("userCertificateCount"));assertTrue(value.has("systemCertificateCount"));assertTrue(value.has("hasUserCertificates"));assertTrue(value.has("hashedCertificateCount"));assertTrue(value.has("uniqueSha256Count"));assertTrue(value.has("certificateSetSha256"));assertTrue(value.has("certificateReadErrorCount"));assertTrue(value.getInt("hashLimit")<=4096);assertFalse(summary.toString().contains("derBase64"));
    }
    @Test public void riskSettingsEvidenceIsStructuredAndExcludesAndroidId()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new BuildSystemCollector("environment").collect(sink);
        JSONObject settings=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("environment/risk-settings")){settings=item;break;}}
        assertNotNull("应记录系统风控设置摘要",settings);assertEquals("ok",settings.getString("status"));JSONObject value=settings.getJSONObject("value");assertTrue(value.has("adbEnabled"));assertTrue(value.has("developmentSettingsEnabled"));assertTrue(value.has("mockLocation"));assertFalse(value.has("androidId"));
    }
    @Test public void systemSettingsEvidenceUsesFixedPublicKeys()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new BuildSystemCollector("environment").collect(sink);
        JSONObject settings=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("environment/system-settings")){settings=item;break;}}
        assertNotNull("应记录显示与动画设置摘要",settings);assertEquals("ok",settings.getString("status"));JSONObject value=settings.getJSONObject("value");assertTrue(value.has("available"));assertTrue(value.has("status"));JSONObject values=value.getJSONObject("settings");for(String key:new String[]{"screen_brightness","screen_brightness_mode","screen_off_timeout","accelerometer_rotation","user_rotation","window_animation_scale","transition_animation_scale","animator_duration_scale"})assertTrue(values.has(key));
    }
    @Test public void internationalizationVersionsKeepRawStringsAndLocaleEvidence()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new BuildSystemCollector("environment").collect(sink);
        Map<String,JSONObject> items=new HashMap<>();for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);items.put(item.getString("key"),item);}
        assertTrue("原语言摘要必须保留",items.containsKey("environment/locale-summary"));assertTrue("原语言配置必须保留",items.containsKey("environment/locales"));
        String[] keys={"environment/icu-version","environment/unicode-version","environment/tzdata-version","environment/cldr-version"};
        String[] expected={android.icu.util.VersionInfo.ICU_VERSION.toString(),android.icu.lang.UCharacter.getUnicodeVersion().toString(),android.icu.util.TimeZone.getTZDataVersion(),Build.VERSION.SDK_INT>=28?android.icu.util.LocaleData.getCLDRVersion().toString():null};
        JSONArray parsed=new JSONArray(sink.items.toString());
        for(int i=0;i<keys.length;i++){
            JSONObject item=items.get(keys[i]);assertNotNull(item);
            if(expected[i]==null){assertEquals("unsupported",item.getString("status"));assertEquals("null",item.getString("dataType"));assertTrue(item.isNull("value"));continue;}
            assertEquals("ok",item.getString("status"));assertEquals("string",item.getString("dataType"));assertEquals(expected[i],item.getString("value"));assertFalse(item.getString("value").isEmpty());
            boolean found=false;for(int j=0;j<parsed.length();j++)if(parsed.getJSONObject(j).getString("key").equals(keys[i])){assertEquals(expected[i],parsed.getJSONObject(j).getString("value"));found=true;}assertTrue(found);
        }
    }
    @Test public void localeSummaryKeepsRawLocaleEvidenceSeparate()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new BuildSystemCollector("environment").collect(sink);
        JSONObject summary=null,raw=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("environment/locale-summary"))summary=item;if(item.getString("key").equals("environment/locales"))raw=item;}
        assertNotNull("应生成语言与时区摘要",summary);assertNotNull("应保留语言与时区原始条目",raw);assertEquals("ok",summary.getString("status"));JSONObject value=summary.getJSONObject("value");assertTrue(value.has("localeTags"));assertTrue(value.has("localeCount"));assertTrue(value.has("timezoneId"));assertTrue(value.has("is24HourFormat"));assertTrue(value.has("applicationLocaleAccess"));assertTrue(value.has("applicationLocaleTags"));assertFalse(summary.toString().contains("configuration"));
    }
    @Test public void ringtoneSourceKeepsSupplierSignalBounded()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new BuildSystemCollector("environment").collect(sink);
        JSONObject item=null;for(int i=0;i<sink.items.length();i++){JSONObject candidate=sink.items.getJSONObject(i);if(candidate.getString("key").equals("environment/ringtone-source")){item=candidate;break;}}
        assertNotNull("应生成铃声来源条目",item);assertEquals("ok",item.getString("status"));JSONObject value=item.getJSONObject("value");
        assertTrue(value.getBoolean("available"));assertTrue(value.has("status"));assertTrue(value.has("uri"));assertTrue(value.has("uriPresent"));assertTrue(value.has("uriLength"));assertTrue(value.has("uriSha256"));assertTrue(value.has("scheme"));assertTrue(value.has("authority"));
        if(value.getBoolean("uriPresent")){assertTrue(value.getInt("uriLength")>0);assertTrue(value.getInt("uriLength")<=2048);assertTrue(value.getString("uriSha256").matches("[0-9a-f]{64}"));}else{assertTrue(value.isNull("uriSha256"));}
    }
    @Test public void gsfIdSummaryKeepsProviderAndRawValueBoundary()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new BuildSystemCollector("environment").collect(sink);
        JSONObject item=null;for(int i=0;i<sink.items.length();i++){JSONObject candidate=sink.items.getJSONObject(i);if(candidate.getString("key").equals("environment/gsf-id-summary")){item=candidate;break;}}
        assertNotNull("应生成 GSF ID 摘要条目",item);assertTrue(item.getString("status").equals("ok")||item.getString("status").equals("denied")||item.getString("status").equals("error"));
        if("ok".equals(item.getString("status"))){JSONObject value=item.getJSONObject("value");assertTrue(value.has("available"));assertTrue(value.has("status"));assertTrue(value.has("providerPackage"));assertEquals("com.google.android.gsf",value.getString("providerPackage"));assertTrue(value.has("idPresent"));assertTrue(value.has("idLength"));assertTrue(value.has("idSha256"));assertTrue(value.has("normalization"));if(value.getBoolean("idPresent")){assertTrue(value.getInt("idLength")>0);assertTrue(value.getInt("idLength")<=16);assertTrue(value.getString("idSha256").matches("[0-9a-f]{64}"));}else assertTrue(value.isNull("idSha256"));assertFalse(value.has("id"));assertFalse(value.has("rawValue"));}
    }
    private long int64(JSONObject value,String key)throws Exception{Object raw=value.get(key);return raw instanceof JSONObject?Long.parseLong(((JSONObject)raw).getString("value")):value.getLong(key);}
    @Test public void clockSummaryKeepsRawClockEvidenceSeparate()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new BuildSystemCollector("environment").collect(sink);
        JSONObject summary=null,raw=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("environment/clock-summary"))summary=item;if(item.getString("key").equals("environment/clocks"))raw=item;}
        assertNotNull("应生成系统时钟摘要",summary);assertNotNull("应保留原始时钟快照",raw);assertEquals("ok",summary.getString("status"));JSONObject value=summary.getJSONObject("value");assertTrue(value.has("elapsedRealtimeMillis"));assertTrue(value.has("uptimeMillis"));assertTrue(value.has("wallClockMillis"));assertTrue(value.has("bootTimeMillis"));assertTrue(value.has("sleepMillis"));assertTrue(int64(value,"elapsedRealtimeMillis")>=int64(value,"uptimeMillis"));assertTrue(int64(value,"sleepMillis")>=0);
    }
    @Test public void androidIdSummaryKeepsRawIdentifierSeparate()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new BuildSystemCollector("environment").collect(sink);
        JSONObject summary=null,raw=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("environment/android-id-summary"))summary=item;if(item.getString("key").equals("environment/secure/android_id"))raw=item;}
        assertNotNull("应生成 Android ID 摘要",summary);assertNotNull("应保留 Android ID 原始条目",raw);assertEquals("ok",summary.getString("status"));JSONObject value=summary.getJSONObject("value");assertTrue(value.getBoolean("available"));assertTrue(value.has("status"));assertTrue(value.has("scope"));assertTrue(value.has("androidIdPresent"));if(value.getBoolean("androidIdPresent")){assertTrue(value.getInt("androidIdLength")>0);assertTrue(value.getString("androidIdSha256").matches("[0-9a-f]{64}"));Object rawValue=raw.opt("value");if(rawValue instanceof String)assertFalse(value.toString().contains((String)rawValue));}
    }
    @Test public void deviceStateEvidenceUsesPublicStateOnly()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new BuildSystemCollector("environment").collect(sink);
        JSONObject state=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("environment/device-state")){state=item;break;}}
        assertNotNull("应记录设备初始化与运行状态",state);assertEquals("ok",state.getString("status"));JSONObject value=state.getJSONObject("value");assertTrue(value.has("deviceProvisioned"));assertTrue(value.has("userSetupComplete"));assertTrue(value.has("bootCount"));assertTrue(value.has("lowRamDevice"));assertTrue(value.has("userUnlocked"));assertTrue(value.has("userRunning"));assertFalse(state.toString().toLowerCase(java.util.Locale.ROOT).contains("latitude"));assertFalse(state.toString().toLowerCase(java.util.Locale.ROOT).contains("serial"));
    }
    @Test public void memorySummaryKeepsRawSnapshotSeparate()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new BuildSystemCollector("environment").collect(sink);
        JSONObject summary=null,raw=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("environment/memory-summary"))summary=item;if(item.getString("key").equals("environment/memory"))raw=item;}
        assertNotNull("应生成内存环境摘要",summary);assertNotNull("应保留内存原始快照",raw);assertEquals("ok",summary.getString("status"));JSONObject value=summary.getJSONObject("value");assertTrue(value.getBoolean("available"));assertTrue(value.has("totalMemBytes"));assertTrue(value.has("memoryClassMb"));assertTrue(value.has("largeMemoryClassMb"));assertTrue(value.has("runtimeMaxMemoryBytes"));assertTrue(value.has("heapUsedBytes"));assertTrue(value.has("runtimeProcessorCount"));
    }
    @Test public void userProfileSummaryKeepsIdentityOutOfEvidence()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new BuildSystemCollector("environment").collect(sink);
        JSONObject summary=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("environment/user-profiles")){summary=item;break;}}
        assertNotNull("应记录用户与工作资料摘要",summary);assertEquals("ok",summary.getString("status"));JSONObject value=summary.getJSONObject("value");assertTrue(value.has("available"));assertTrue(value.has("profileCount"));assertTrue(value.has("managedProfile"));assertTrue(value.getInt("profileCount")>=0);assertFalse(summary.toString().contains("UserHandle"));assertFalse(summary.toString().toLowerCase(java.util.Locale.ROOT).contains("account"));
    }
    @Test public void appStandbyEvidenceKeepsApiBoundary()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new BuildSystemCollector("environment").collect(sink);
        JSONObject standby=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("environment/app-standby")){standby=item;break;}}
        assertNotNull("应记录当前 App 待机分桶",standby);assertEquals("ok",standby.getString("status"));JSONObject value=standby.getJSONObject("value");assertTrue(value.has("available"));assertTrue(value.has("status"));assertTrue(value.has("bucket"));assertTrue(value.has("bucketName"));
    }
    @Test public void processExitEvidenceIsBoundedAndMetadataOnly()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new BuildSystemCollector("environment").collect(sink);
        JSONObject exits=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("environment/process-exits")){exits=item;break;}}
        assertNotNull("应记录进程退出历史",exits);assertEquals("ok",exits.getString("status"));JSONObject value=exits.getJSONObject("value");assertTrue(value.has("available"));assertTrue(value.has("count"));assertTrue(value.getInt("count")<=32);assertTrue(value.has("reasons"));assertTrue(value.has("statuses"));assertTrue(value.has("entries"));JSONArray entries=value.getJSONArray("entries");for(int i=0;i<entries.length();i++){JSONObject entry=entries.getJSONObject(i);assertFalse(entry.has("description"));assertFalse(entry.has("traceInputStream"));}
    }
    @Test public void lowPowerStandbyEvidenceKeepsVersionBoundaries()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new BuildSystemCollector("environment").collect(sink);
        JSONObject standby=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("environment/low-power-standby")){standby=item;break;}}
        assertNotNull("应记录低功耗待机与重启能力",standby);assertEquals("ok",standby.getString("status"));JSONObject value=standby.getJSONObject("value");assertTrue(value.has("available"));assertTrue(value.has("status"));assertTrue(value.has("lowPowerStandbyEnabled"));assertTrue(value.has("lowPowerStandbyExempt"));assertTrue(value.has("rebootingUserspaceSupported"));
    }
    @Test public void webViewUserAgentKeepsLengthMetadata()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new BuildSystemCollector("environment").collect(sink);
        JSONObject userAgent=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("environment/webview-user-agent")){userAgent=item;break;}}
        assertNotNull("应记录 WebView 默认 UA 条目",userAgent);assertEquals("ok",userAgent.getString("status"));JSONObject value=userAgent.getJSONObject("value");assertTrue(value.getString("userAgent").length()>0);assertEquals(value.getString("userAgent").length(),value.getInt("length"));
    }
    @Test public void powerThermalEvidenceUsesPublicSnapshot()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new BuildSystemCollector("environment").collect(sink);
        JSONObject power=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("environment/power-thermal")){power=item;break;}}
        assertNotNull("应记录电源与热状态",power);assertEquals("ok",power.getString("status"));JSONObject value=power.getJSONObject("value");assertTrue(value.has("powerSaveMode"));assertTrue(value.has("interactive"));assertTrue(value.has("ignoresBatteryOptimizations"));assertTrue(value.has("batteryOptimizationQueryStatus"));assertFalse(value.has("thermalControl"));
    }
    @Test public void locationEvidenceUsesProviderMetadataOnly()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new BuildSystemCollector("environment").collect(sink);
        JSONObject location=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("environment/location")){location=item;break;}}
        assertNotNull("应记录定位环境摘要",location);assertEquals("ok",location.getString("status"));JSONObject value=location.getJSONObject("value");assertTrue(value.has("available"));assertTrue(value.has("providerCount"));assertTrue(value.has("providers"));if(Build.VERSION.SDK_INT>=31){assertTrue(value.has("gnssAntennaCount"));assertTrue(value.has("gnssAntennaCarrierFrequenciesMHz"));assertTrue(value.getJSONArray("gnssAntennaCarrierFrequenciesMHz").length()<=64);}assertFalse(location.toString().contains("latitude"));assertFalse(location.toString().contains("longitude"));assertFalse(location.toString().contains("lastKnown"));
    }
    @Test public void sensorSummaryKeepsFullListSeparate()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new HardwareCollector("sensors").collect(sink);
        JSONObject summary=null,list=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("sensors/summary"))summary=item;if(item.getString("key").equals("sensors/list"))list=item;}
        assertNotNull("应生成传感器能力摘要",summary);assertNotNull("应保留传感器完整清单",list);assertEquals("ok",summary.getString("status"));assertEquals("ok",list.getString("status"));JSONObject value=summary.getJSONObject("value");assertTrue(value.has("count"));assertTrue(value.has("typeCounts"));assertTrue(value.has("vendors"));assertTrue(value.getJSONArray("vendors").length()<=128);assertTrue(value.has("vendorsTruncated"));assertEquals(value.getInt("count"),list.getJSONArray("value").length());
    }
    @Test public void batterySummaryKeepsRawBatteryEvidenceSeparate()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new HardwareCollector("battery").collect(sink);
        JSONObject summary=null,broadcast=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("battery/summary"))summary=item;if(item.getString("key").equals("battery/broadcast"))broadcast=item;}
        assertNotNull("应生成电池环境摘要",summary);assertNotNull("应保留电池广播快照",broadcast);assertEquals("ok",summary.getString("status"));JSONObject value=summary.getJSONObject("value");assertTrue(value.has("available"));assertTrue(value.has("percentage"));assertTrue(value.has("charging"));assertTrue(value.has("temperatureC"));assertTrue(value.has("present"));
    }
    @Test public void batteryCycleCountKeepsApiAndMissingValueBoundary()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new HardwareCollector("battery").collect(sink);
        JSONObject cycle=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("battery/cycle-count")){cycle=item;break;}}
        assertNotNull("应记录电池循环次数",cycle);assertEquals("ok",cycle.getString("status"));JSONObject value=cycle.getJSONObject("value");assertTrue(value.has("available"));assertTrue(value.has("status"));assertTrue(value.has("cycleCount"));if(Build.VERSION.SDK_INT<34){assertEquals("unsupported",value.getString("status"));assertTrue(value.isNull("cycleCount"));}
    }
    @Test public void simSummaryKeepsIdentifiersOutOfSummary()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new NetworkCollector("sim").collect(sink);
        JSONObject summary=null,euicc=null,radio=null,service=null,signal=null,imei=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("sim/summary"))summary=item;if(item.getString("key").equals("sim/euicc-summary"))euicc=item;if(item.getString("key").equals("sim/radio-summary"))radio=item;if(item.getString("key").equals("sim/service-summary"))service=item;if(item.getString("key").equals("sim/signal-summary"))signal=item;if(item.getString("key").equals("sim/imei"))imei=item;}
        assertNotNull("应生成 SIM 与电话摘要",summary);assertNotNull("应生成 eSIM 能力摘要",euicc);assertNotNull("应生成电话射频摘要",radio);assertNotNull("应生成电话注册服务摘要",service);assertNotNull("应生成电话信号摘要",signal);assertNotNull("应保留 IMEI 原始条目状态",imei);assertEquals("ok",summary.getString("status"));String euiccStatus=euicc.getString("status");assertTrue("eSIM 读取应明确记录边界: "+euiccStatus,euiccStatus.equals("ok")||euiccStatus.equals("denied")||euiccStatus.equals("unsupported")||euiccStatus.equals("unavailable")||euiccStatus.equals("error"));JSONObject euiccValue=euicc.getJSONObject("value");assertTrue(euiccValue.has("managerPresent"));assertTrue(euiccValue.has("enabled"));assertTrue(euiccValue.has("euiccInfoPresent"));assertTrue(euiccValue.has("euiccOsVersion"));assertTrue(euiccValue.has("availableMemoryBytes"));assertFalse(euicc.toString().toLowerCase(java.util.Locale.ROOT).contains("eid"));assertEquals("ok",radio.getString("status"));assertEquals("ok",service.getString("status"));String signalStatus=signal.getString("status");assertTrue("信号读取应明确记录边界: "+signalStatus,signalStatus.equals("ok")||signalStatus.equals("denied")||signalStatus.equals("unsupported")||signalStatus.equals("unavailable")||signalStatus.equals("error"));JSONObject value=summary.getJSONObject("value");assertTrue(value.has("phoneType"));assertTrue(value.has("simStates"));assertTrue(value.has("subscriptionAccess"));JSONObject radioValue=radio.getJSONObject("value");assertTrue(radioValue.has("activeModemCount"));assertTrue(radioValue.has("supportedModemCount"));assertTrue(radioValue.has("supportedRadioAccessFamily"));assertTrue(radioValue.has("dataCapable"));JSONObject serviceValue=service.getJSONObject("value");assertTrue(serviceValue.has("serviceState"));assertTrue(serviceValue.has("registrationCount"));assertTrue(serviceValue.has("domainCounts"));assertTrue(serviceValue.has("cellBandwidths"));if(signalStatus.equals("ok")){JSONObject signalValue=signal.getJSONObject("value");assertTrue(signalValue.has("signalLevel"));assertTrue(signalValue.has("cellCount"));assertTrue(signalValue.has("typeCounts"));assertTrue(signalValue.has("levelCounts"));}assertFalse(summary.toString().toLowerCase(java.util.Locale.ROOT).contains("imei"));assertFalse(summary.toString().toLowerCase(java.util.Locale.ROOT).contains("imsi"));assertFalse(summary.toString().toLowerCase(java.util.Locale.ROOT).contains("icc"));assertFalse(radio.toString().toLowerCase(java.util.Locale.ROOT).contains("imei"));assertFalse(radio.toString().toLowerCase(java.util.Locale.ROOT).contains("imsi"));String serviceJson=service.toString().toLowerCase(java.util.Locale.ROOT);assertFalse(serviceJson.contains("cellidentity"));assertFalse(serviceJson.contains("\"number\""));assertFalse(signal.toString().toLowerCase(java.util.Locale.ROOT).contains("cellidentity"));
    }
}
