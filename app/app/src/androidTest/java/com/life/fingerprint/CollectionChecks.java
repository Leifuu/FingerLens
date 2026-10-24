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
    @Test public void storageSummaryKeepsRawVolumesSeparate()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new BuildSystemCollector("storage").collect(sink);
        JSONObject summary=null,volumes=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("storage/summary"))summary=item;if(item.getString("key").equals("storage/volumes"))volumes=item;}
        assertNotNull("应生成存储卷摘要",summary);assertNotNull("应保留存储卷原始清单",volumes);assertEquals("ok",summary.getString("status"));assertEquals("ok",volumes.getString("status"));JSONObject value=summary.getJSONObject("value");assertEquals(value.getInt("volumeCount"),volumes.getJSONArray("value").length());assertTrue(value.has("mountedCount"));assertTrue(value.has("stateCounts"));
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
    @Test public void widevineSignalNeverExportsRawUniqueId()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new HardwareCollector("hardware").collect(sink);
        JSONObject drm=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("hardware/drm/widevine")){drm=item;break;}}
        assertNotNull("应记录 Widevine 支持状态",drm);if("ok".equals(drm.getString("status"))){JSONObject value=drm.getJSONObject("value");assertFalse(value.toString().contains("deviceUniqueIdBase64"));if(value.optJSONObject("deviceUniqueId")!=null)assertFalse(value.getJSONObject("deviceUniqueId").optBoolean("rawExported",true));}
    }
    @Test public void usbEvidenceUsesPublicDescriptorsOnly()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new HardwareCollector("hardware").collect(sink);
        JSONObject usb=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("hardware/usb")){usb=item;break;}}
        assertNotNull("应记录 USB 公开设备清单",usb);assertEquals("ok",usb.getString("status"));JSONObject value=usb.getJSONObject("value");assertTrue(value.has("count"));assertTrue(value.getInt("count")<=256);assertTrue(value.has("devices"));assertFalse(usb.toString().contains("serialNumber"));
    }
    @Test public void nfcEvidenceUsesPublicAdapterState()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new HardwareCollector("hardware").collect(sink);
        JSONObject nfc=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("hardware/nfc")){nfc=item;break;}}
        assertNotNull("应记录 NFC 能力",nfc);assertEquals("ok",nfc.getString("status"));JSONObject value=nfc.getJSONObject("value");assertTrue(value.has("available"));assertFalse(nfc.toString().contains("tagId"));if(Build.VERSION.SDK_INT>=29){assertTrue(value.has("secureNfcSupported"));assertTrue(value.has("secureNfcEnabled"));}if(Build.VERSION.SDK_INT>=34)assertTrue(value.has("antennaInfoAvailable"));
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
    @Test public void cameraSummaryKeepsPerCameraEvidence()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new HardwareCollector("hardware").collect(sink);
        JSONObject summary=null;int cameraItems=0;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);String key=item.getString("key");if(key.equals("hardware/camera-summary"))summary=item;if(key.startsWith("hardware/camera/")&&!key.equals("hardware/camera-summary"))cameraItems++;}
        assertNotNull("应生成摄像头能力摘要",summary);assertEquals("ok",summary.getString("status"));JSONObject value=summary.getJSONObject("value");assertTrue(value.has("count"));assertEquals(value.getInt("count"),cameraItems);assertTrue(value.getJSONArray("cameraIds").length()<=64);assertTrue(value.has("cameraIdsTruncated"));assertTrue(value.has("lensFacingCounts"));assertTrue(value.has("hardwareLevelCounts"));
    }
    @Test public void audioSummaryKeepsFullListSeparate()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new HardwareCollector("hardware").collect(sink);
        JSONObject summary=null,list=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("hardware/audio-summary"))summary=item;if(item.getString("key").equals("hardware/audio"))list=item;}
        assertNotNull("应生成音频能力摘要",summary);assertNotNull("应保留音频设备完整清单",list);assertEquals("ok",summary.getString("status"));assertEquals("ok",list.getString("status"));JSONObject value=summary.getJSONObject("value");assertEquals(value.getInt("count"),list.getJSONArray("value").length());assertTrue(value.getJSONArray("productNames").length()<=128);assertTrue(value.has("productNamesTruncated"));assertTrue(value.has("sampleRates"));assertTrue(value.has("encodings"));
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
    @Test public void wifiSummaryKeepsScanPermissionBoundaryExplicit()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new NetworkCollector("network").collect(sink);
        JSONObject summary=null,raw=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("network/wifi-summary"))summary=item;if(item.getString("key").equals("network/wifi-cached-scan"))raw=item;}
        assertNotNull("应生成 Wi-Fi 能力摘要",summary);assertNotNull("应保留 Wi-Fi 缓存原始条目",raw);assertEquals("ok",summary.getString("status"));JSONObject value=summary.getJSONObject("value");assertTrue(value.has("connectionAccess"));assertTrue(value.has("scanAccess"));assertTrue(value.has("cachedScanCount"));assertTrue(value.getJSONArray("frequencies").length()<=64);assertTrue(value.has("frequenciesTruncated"));
    }
    @Test public void connectivitySummaryKeepsRawNetworksSeparate()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new NetworkCollector("network").collect(sink);
        JSONObject summary=null,raw=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("network/connectivity-summary"))summary=item;if(item.getString("key").equals("network/connectivity"))raw=item;}
        assertNotNull("应生成网络集合摘要",summary);assertNotNull("应保留网络连接原始清单",raw);assertEquals("ok",summary.getString("status"));assertEquals("ok",raw.getString("status"));JSONObject value=summary.getJSONObject("value");assertTrue(value.has("networkCount"));assertTrue(value.has("activePresent"));assertTrue(value.has("capabilitiesAvailableCount"));assertTrue(value.has("transportCounts"));
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
    @Test public void deviceManagementEvidenceIsScopedToCurrentApp()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new SecurityCollector("security").collect(sink);
        JSONObject management=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("security/device-management")){management=item;break;}}
        assertNotNull("应记录设备管理环境条目",management);assertEquals("ok",management.getString("status"));JSONObject value=management.getJSONObject("value");assertTrue(value.has("deviceOwnerForSelf"));assertTrue(value.has("profileOwnerForSelf"));assertTrue(value.has("managedProfile"));assertTrue(value.has("restrictions"));assertFalse(value.has("deviceOwnerPackage"));
    }
    @Test public void securitySummaryKeepsDetailedEvidenceSeparate()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new SecurityCollector("security").collect(sink);
        JSONObject summary=null,self=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("security/summary"))summary=item;if(item.getString("key").equals("security/self"))self=item;}
        assertNotNull("应生成安全环境摘要",summary);assertNotNull("应保留应用安全上下文",self);assertEquals("ok",summary.getString("status"));assertEquals("ok",self.getString("status"));JSONObject value=summary.getJSONObject("value");assertTrue(value.has("selfDebuggable"));assertTrue(value.has("appOpsAllowedCount"));assertTrue(value.has("keyguardSecure"));assertTrue(value.has("accessibilityServiceCount"));assertTrue(value.has("securityProviderCount"));assertFalse(summary.toString().contains("derBase64"));assertFalse(summary.toString().contains("certificateChainBase64"));assertFalse(summary.toString().contains("windowText"));
    }
    @Test public void specialAccessEvidenceUsesCurrentAppOps()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new SecurityCollector("security").collect(sink);
        JSONObject access=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("security/special-access")){access=item;break;}}
        assertNotNull("应记录本 App 特殊访问条目",access);assertEquals("ok",access.getString("status"));JSONObject value=access.getJSONObject("value");assertTrue(value.has("available"));assertTrue(value.has("canDrawOverlays"));assertTrue(value.has("canRequestPackageInstalls"));assertTrue(value.has("canRequestPackageInstallsStatus"));assertTrue(value.has("notificationPolicyAccess"));assertTrue(value.has("notificationPolicyAccessStatus"));assertTrue(value.has("allFilesAccess"));assertTrue(value.has("allFilesAccessStatus"));if(value.optBoolean("available"))assertTrue(value.has("appOps"));assertFalse(value.has("otherPackages"));
    }
    @Test public void lockBiometricEvidenceUsesPublicStateOnly()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new SecurityCollector("security").collect(sink);
        JSONObject lock=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("security/lock-biometric")){lock=item;break;}}
        assertNotNull("应记录锁屏与生物识别环境",lock);assertEquals("ok",lock.getString("status"));JSONObject value=lock.getJSONObject("value");assertTrue(value.has("keyguardSecure"));assertTrue(value.has("deviceLocked"));assertTrue(value.has("fingerprintFeature"));assertTrue(value.has("biometricStrongCode"));assertTrue(value.has("biometricStrongStatus"));assertTrue(value.has("biometricWeakCode"));assertTrue(value.has("biometricWeakStatus"));assertTrue(value.has("deviceCredentialCode"));assertTrue(value.has("deviceCredentialStatus"));assertFalse(value.has("biometricTemplate"));
    }
    @Test public void accessibilityEvidenceUsesServiceMetadataOnly()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new SecurityCollector("security").collect(sink);
        JSONObject accessibility=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("security/accessibility")){accessibility=item;break;}}
        assertNotNull("应记录无障碍服务元数据",accessibility);assertEquals("ok",accessibility.getString("status"));JSONObject value=accessibility.getJSONObject("value");assertTrue(value.has("available"));assertTrue(value.has("count"));assertTrue(value.has("services"));JSONArray services=value.getJSONArray("services");for(int i=0;i<services.length();i++){JSONObject service=services.getJSONObject(i);assertFalse(service.has("windowText"));assertFalse(service.has("password"));}
    }
    @Test public void selfIntegrityEvidenceUsesSignatureHashesOnly()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new SecurityCollector("security").collect(sink);
        JSONObject integrity=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("security/self-integrity")){integrity=item;break;}}
        assertNotNull("应记录当前 App 完整性摘要",integrity);assertEquals("ok",integrity.getString("status"));JSONObject value=integrity.getJSONObject("value");assertEquals(context.getPackageName(),value.getString("packageName"));assertTrue(value.has("signingCertificateSha256"));assertTrue(value.has("signingCertificateHistorySha256"));assertTrue(value.has("hasPastSigningCertificates"));assertTrue(value.has("instantApp"));assertTrue(value.has("packageSuspended"));assertTrue(value.has("packageSuspensionQueryStatus"));assertFalse(integrity.toString().contains("derBase64"));assertFalse(integrity.toString().contains("sourceDir"));
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
    @Test public void deviceStateEvidenceUsesPublicStateOnly()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new BuildSystemCollector("environment").collect(sink);
        JSONObject state=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("environment/device-state")){state=item;break;}}
        assertNotNull("应记录设备初始化与运行状态",state);assertEquals("ok",state.getString("status"));JSONObject value=state.getJSONObject("value");assertTrue(value.has("deviceProvisioned"));assertTrue(value.has("userSetupComplete"));assertTrue(value.has("bootCount"));assertTrue(value.has("lowRamDevice"));assertTrue(value.has("userUnlocked"));assertTrue(value.has("userRunning"));assertFalse(state.toString().toLowerCase(java.util.Locale.ROOT).contains("latitude"));assertFalse(state.toString().toLowerCase(java.util.Locale.ROOT).contains("serial"));
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
    @Test public void simSummaryKeepsIdentifiersOutOfSummary()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new NetworkCollector("sim").collect(sink);
        JSONObject summary=null,imei=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("sim/summary"))summary=item;if(item.getString("key").equals("sim/imei"))imei=item;}
        assertNotNull("应生成 SIM 与电话摘要",summary);assertNotNull("应保留 IMEI 原始条目状态",imei);assertEquals("ok",summary.getString("status"));JSONObject value=summary.getJSONObject("value");assertTrue(value.has("phoneType"));assertTrue(value.has("simStates"));assertTrue(value.has("subscriptionAccess"));assertFalse(summary.toString().toLowerCase(java.util.Locale.ROOT).contains("imei"));assertFalse(summary.toString().toLowerCase(java.util.Locale.ROOT).contains("imsi"));assertFalse(summary.toString().toLowerCase(java.util.Locale.ROOT).contains("icc"));
    }
}
