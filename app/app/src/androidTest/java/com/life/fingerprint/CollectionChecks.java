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
    @Test public void localeSummaryKeepsRawLocaleEvidenceSeparate()throws Exception{
        ItemSink sink=new ItemSink(context,UUID.randomUUID().toString());new BuildSystemCollector("environment").collect(sink);
        JSONObject summary=null,raw=null;for(int i=0;i<sink.items.length();i++){JSONObject item=sink.items.getJSONObject(i);if(item.getString("key").equals("environment/locale-summary"))summary=item;if(item.getString("key").equals("environment/locales"))raw=item;}
        assertNotNull("应生成语言与时区摘要",summary);assertNotNull("应保留语言与时区原始条目",raw);assertEquals("ok",summary.getString("status"));JSONObject value=summary.getJSONObject("value");assertTrue(value.has("localeTags"));assertTrue(value.has("localeCount"));assertTrue(value.has("timezoneId"));assertTrue(value.has("is24HourFormat"));assertTrue(value.has("applicationLocaleAccess"));assertTrue(value.has("applicationLocaleTags"));assertFalse(summary.toString().contains("configuration"));
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
