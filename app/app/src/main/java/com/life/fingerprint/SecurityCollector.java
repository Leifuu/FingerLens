package com.life.fingerprint;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.app.admin.DevicePolicyManager;
import android.app.AppOpsManager;
import android.app.KeyguardManager;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.view.accessibility.AccessibilityManager;
import android.os.*;
import android.provider.Settings;
import android.security.keystore.*;
import android.util.Base64;
import org.json.*;
import java.security.*;
import java.security.cert.Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.*;

public final class SecurityCollector implements Collector {
    private final String module;
    public SecurityCollector(String module){this.module=module;}
    // getSerial 的拒绝也是证据；ItemSink 负责捕获，绝不申请系统签名权限。
    @android.annotation.SuppressLint("MissingPermission")
    public void collect(ItemSink s)throws Exception {
        if(module.equals("attestation")){attestation(s);return;}
        if(module.equals("virtualization")){virtualization(s);return;}
        s.read("security","security/summary","安全环境摘要","Debug / DevicePolicyManager / AppOpsManager / KeyguardManager",()->securitySummary(s.context));
        s.read("security","security/selinux-summary","SELinux 环境摘要","/sys/fs/selinux/enforce / /proc/self/attr/current",SecurityCollector::selinuxSummary);
        s.ok("security","security/self","应用安全上下文","android-api","Process / ApplicationInfo / Debug",Json.obj("uid",android.os.Process.myUid(),"pid",android.os.Process.myPid(),"debuggable",(s.context.getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0,"debuggerConnected",Debug.isDebuggerConnected(),"waitingForDebugger",Debug.waitingForDebugger()));
        s.read("security","security/self-runtime","应用运行时可分析配置","ApplicationInfo public runtime flags",()->selfRuntimeSummary(s.context));
        s.read("security","security/self-permissions","当前 App 运行时权限摘要","PackageManager.GET_PERMISSIONS",()->selfPermissions(s.context));
        s.read("security","security/self-integrity","当前 App 签名与安装环境","PackageManager.getPackageInfo / getInstallSourceInfo",()->selfIntegrity(s.context));
        s.read("security","security/device-management","设备管理与用户环境","DevicePolicyManager / UserManager",()->managementEnvironment(s.context));
        s.read("security","security/special-access","本 App 特殊访问状态","AppOpsManager / PackageManager / NotificationManager / Environment / Settings",()->specialAccess(s.context));
        s.read("security","security/lock-biometric","锁屏与生物识别环境","KeyguardManager / BiometricManager / PackageManager.hasSystemFeature",()->lockBiometricEnvironment(s.context));
        s.read("security","security/accessibility","启用的无障碍服务元数据","AccessibilityManager.getEnabledAccessibilityServiceList",()->accessibilityEnvironment(s.context));
        for(String p:new String[]{"/proc/self/attr/current","/proc/self/status","/sys/fs/selinux/enforce","/sys/fs/selinux/policyvers","/sys/fs/selinux/checkreqprot","/proc/cmdline","/proc/bootconfig","/system/etc/selinux/plat_sepolicy.cil","/vendor/etc/selinux/vendor_sepolicy.cil"})s.file("security","security/file"+p,p);
        s.read("security","security/buildSerial","Build 序列号（可能受限）","Build.getSerial",()->Build.VERSION.SDK_INT>=26?Build.getSerial():Build.SERIAL);
        s.read("security","security/providers","安全提供者","Security.getProviders",()->{JSONArray a=new JSONArray();for(Provider p:Security.getProviders()){JSONArray services=new JSONArray();for(Provider.Service v:p.getServices())services.put(Json.obj("type",v.getType(),"algorithm",v.getAlgorithm(),"className",v.getClassName()));a.put(Json.obj("name",p.getName(),"info",p.getInfo(),"version",p.getVersion(),"services",services));}return a;});
        s.read("security","security/providers-summary","安全提供者摘要","Security.getProviders",SecurityCollector::providerSummary);
        s.read("security","security/trust-store","当前 App 可见 CA 信任库","KeyStore AndroidCAStore",()->{KeyStore store=KeyStore.getInstance("AndroidCAStore");store.load(null);JSONArray a=new JSONArray();Enumeration<String> names=store.aliases();int count=0;while(names.hasMoreElements()){String name=names.nextElement();if(++count>4096)throw new IllegalStateException("CA 数量超过 4096");Certificate cert=store.getCertificate(name);a.put(Json.obj("alias",name,"derBase64",cert==null?null:Base64.encodeToString(cert.getEncoded(),Base64.NO_WRAP),"sha256",cert==null?null:PackageInputCollector.sha(cert.getEncoded())));}return a;});
    }
    // 业务块：记录当前 App 的公开分析/内存加固配置，不执行调试、分析或内存修改操作。
    private static JSONObject selfRuntimeSummary(Context context){
        ApplicationInfo application=context.getApplicationInfo();
        if(application==null)return Json.obj("available",false,"status","unavailable","profileableByShell",null,"profileable",null,"virtualPreload",null,"gwpAsanMode",null,"memtagMode",null,"nativeHeapZeroInitialized",null,"requestRawExternalStorageAccess",null,"attributionsUserVisible",null);
        return Json.obj("available",true,"status","ok",
                "profileableByShell",Build.VERSION.SDK_INT>=29?application.isProfileableByShell():null,
                "profileable",Build.VERSION.SDK_INT>=31?application.isProfileable():null,
                "virtualPreload",Build.VERSION.SDK_INT>=27?application.isVirtualPreload():null,
                "gwpAsanMode",Build.VERSION.SDK_INT>=30?application.getGwpAsanMode():null,
                "memtagMode",Build.VERSION.SDK_INT>=31?application.getMemtagMode():null,
                "nativeHeapZeroInitialized",Build.VERSION.SDK_INT>=31?application.getNativeHeapZeroInitialized():null,
                "requestRawExternalStorageAccess",Build.VERSION.SDK_INT>=31?application.getRequestRawExternalStorageAccess():null,
                "attributionsUserVisible",Build.VERSION.SDK_INT>=31?application.areAttributionsUserVisible():null);
    }
    // 业务块：汇总当前 App 的声明权限与授权状态，限制返回规模，不申请权限也不查询其它包。
    private static JSONObject selfPermissions(Context context)throws Exception{
        PackageManager packages=context.getPackageManager();
        if(packages==null)return Json.obj("available",false,"status","unavailable","requestedCount",null,"grantedCount",null,"deniedCount",null,"unknownCount",null,"dangerousRequestedCount",null,"dangerousGrantedCount",null,"protectionInfoCount",null,"returnedCount",0,"truncated",null,"permissions",new JSONArray());
        PackageInfo info=packages.getPackageInfo(context.getPackageName(),PackageManager.GET_PERMISSIONS);
        String[] names=info.requestedPermissions;int[] flags=info.requestedPermissionsFlags;int requested=names==null?0:names.length,granted=0,denied=0,unknown=0,dangerousRequested=0,dangerousGranted=0,protectionInfo=0;JSONArray values=new JSONArray();int limit=Math.min(256,requested);
        for(int i=0;i<requested;i++){
            String name=names[i];if(name==null)continue;
            Boolean isGranted=flags!=null&&i<flags.length?((flags[i]&PackageInfo.REQUESTED_PERMISSION_GRANTED)!=0):null;
            if(isGranted==null)unknown++;else if(isGranted)granted++;else denied++;
            JSONObject permission=Json.obj("name",name,"granted",isGranted,"protectionLevel",null,"protectionBase",null,"dangerous",null);
            try{PermissionInfo details=packages.getPermissionInfo(name,0);int level=details.protectionLevel,base=level&PermissionInfo.PROTECTION_MASK_BASE;Json.put(permission,"protectionLevel",level);Json.put(permission,"protectionBase",base);boolean dangerous=base==PermissionInfo.PROTECTION_DANGEROUS;Json.put(permission,"dangerous",dangerous);protectionInfo++;if(dangerous){dangerousRequested++;if(Boolean.TRUE.equals(isGranted))dangerousGranted++;}}catch(PackageManager.NameNotFoundException ignored){}
            if(i<limit)values.put(permission);
        }
        return Json.obj("available",true,"status","ok","requestedCount",requested,"grantedCount",granted,"deniedCount",denied,"unknownCount",unknown,"dangerousRequestedCount",dangerousRequested,"dangerousGrantedCount",dangerousGranted,"protectionInfoCount",protectionInfo,"returnedCount",values.length(),"truncated",requested>limit,"permissions",values);
    }
    // 业务块：汇总安全边界状态，不展开证书、序列号、窗口内容或其它用户/包信息；原始 security/* 条目继续保留。
    private static JSONObject securitySummary(Context context){
        ApplicationInfo application=context.getApplicationInfo();DevicePolicyManager policy=context.getSystemService(DevicePolicyManager.class);UserManager users=context.getSystemService(UserManager.class);AppOpsManager ops=context.getSystemService(AppOpsManager.class);KeyguardManager keyguard=context.getSystemService(KeyguardManager.class);PackageManager packages=context.getPackageManager();
        String packageName=context.getPackageName();String[] appOpNames=checkedAppOps();int appOpsAllowed=0;for(String name:appOpNames)if(ops!=null&&ops.checkOpNoThrow(name,android.os.Process.myUid(),packageName)==AppOpsManager.MODE_ALLOWED)appOpsAllowed++;
        int accessibilityCount=0;try{AccessibilityManager accessibility=context.getSystemService(AccessibilityManager.class);List<AccessibilityServiceInfo> services=accessibility==null?null:accessibility.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);accessibilityCount=services==null?0:services.size();}catch(Exception ignored){}
        int providerCount=Security.getProviders()==null?0:Security.getProviders().length;
        return Json.obj("selfDebuggable",application!=null&&(application.flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0,"debuggerConnected",Debug.isDebuggerConnected(),"waitingForDebugger",Debug.waitingForDebugger(),"deviceOwnerForSelf",policy!=null&&policy.isDeviceOwnerApp(packageName),"profileOwnerForSelf",policy!=null&&policy.isProfileOwnerApp(packageName),"managedProfile",Build.VERSION.SDK_INT>=30&&users!=null&&users.isManagedProfile(),"userUnlocked",Build.VERSION.SDK_INT<24||users==null||users.isUserUnlocked(),"specialAccessAvailable",ops!=null,"appOpsCheckedCount",ops==null?0:appOpNames.length,"appOpsAllowedCount",appOpsAllowed,"canDrawOverlays",Settings.canDrawOverlays(context),"keyguardSecure",keyguard!=null&&keyguard.isKeyguardSecure(),"keyguardLocked",keyguard!=null&&keyguard.isKeyguardLocked(),"deviceSecure",Build.VERSION.SDK_INT<23||keyguard!=null&&keyguard.isDeviceSecure(),"deviceLocked",Build.VERSION.SDK_INT<23||keyguard!=null&&keyguard.isDeviceLocked(),"fingerprintFeature",packages.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT),"faceFeature",Build.VERSION.SDK_INT>=29&&packages.hasSystemFeature(PackageManager.FEATURE_FACE),"irisFeature",Build.VERSION.SDK_INT>=29&&packages.hasSystemFeature(PackageManager.FEATURE_IRIS),"accessibilityServiceCount",accessibilityCount,"securityProviderCount",providerCount);
    }
    // 业务块：限制安全提供者摘要的体积，完整 provider/service 条目继续保留供复核。
    private static JSONObject providerSummary()throws Exception{
        Provider[] providers=Security.getProviders();if(providers==null)return Json.obj("available",false,"status","unavailable","providerCount",null,"serviceCount",null,"providers",new JSONArray(),"providerNamesTruncated",false,"serviceTypeCounts",new JSONObject(),"serviceNames",new JSONArray(),"serviceNamesTruncated",false);
        JSONArray values=new JSONArray();JSONObject typeCounts=new JSONObject();TreeSet<String> serviceNames=new TreeSet<>();int serviceCount=0;boolean providerLimit=false,serviceLimit=false;
        for(Provider provider:providers){if(provider==null)continue;Set<Provider.Service> services=provider.getServices();JSONObject providerTypes=new JSONObject();if(values.length()<64)for(Provider.Service service:services){String type=service.getType(),name=type+":"+service.getAlgorithm();Json.put(providerTypes,type,providerTypes.optInt(type,0)+1);Json.put(typeCounts,type,typeCounts.optInt(type,0)+1);serviceCount++;if(serviceNames.size()<512)serviceNames.add(name);else serviceLimit=true;}else{providerLimit=true;serviceCount+=services.size();for(Provider.Service service:services){String type=service.getType();Json.put(typeCounts,type,typeCounts.optInt(type,0)+1);if(serviceNames.size()<512)serviceNames.add(type+":"+service.getAlgorithm());else serviceLimit=true;}}
            if(values.length()<64){String info=provider.getInfo();values.put(Json.obj("name",provider.getName(),"version",provider.getVersion(),"infoLength",info==null?null:info.length(),"infoSha256",info==null?null:PackageInputCollector.sha(info.getBytes(java.nio.charset.StandardCharsets.UTF_8)),"serviceCount",services.size(),"serviceTypeCounts",providerTypes));}
        }
        JSONArray names=new JSONArray();for(String name:serviceNames)names.put(name);
        return Json.obj("available",true,"status","ok","providerCount",providers.length,"serviceCount",serviceCount,"providers",values,"providerNamesTruncated",providerLimit,"serviceTypeCounts",typeCounts,"serviceNames",names,"serviceNamesTruncated",serviceLimit);
    }
    // 业务块：只保留 SELinux enforcing 状态和当前上下文的存在性/哈希；原始安全文件继续单独保留。
    private static JSONObject selinuxSummary(){
        JSONObject out=Json.obj("available",true,"status","unavailable","enforceFilePresent",false,"enforcing",null,"enforceValue",null,"contextPresent",false,"contextLength",null,"contextSha256",null);
        String enforce=readTextFile("/sys/fs/selinux/enforce",32),context=readTextFile("/proc/self/attr/current",4096);boolean enforceOk=false,contextOk=false;
        if(enforce!=null){String value=enforce.trim();if("0".equals(value)||"1".equals(value)){enforceOk=true;Json.put(out,"enforceFilePresent",true);Json.put(out,"enforceValue",value);Json.put(out,"enforcing","1".equals(value));}}
        if(context!=null){String value=context.trim();if(!value.isEmpty()){contextOk=true;Json.put(out,"contextPresent",true);Json.put(out,"contextLength",value.length());try{Json.put(out,"contextSha256",PackageInputCollector.sha(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception ignored){}}}
        Json.put(out,"status",enforceOk&&contextOk?"ok":enforceOk||contextOk?"partial":"unavailable");return out;
    }
    private static String readTextFile(String path,int limit){try{JSONObject raw=new JSONObject(NativeProbe.readFile(path,limit));if(!raw.optBoolean("ok"))return null;Object value=raw.has("base64")?Json.bytes(raw.getString("base64")):null;return value instanceof String?(String)value:null;}catch(Exception ignored){return null;}}
    // 业务块：只查询当前用户和本 App 的管理状态，避免枚举其它管理员或用户身份。
    private static JSONObject managementEnvironment(Context context){
        DevicePolicyManager policy=context.getSystemService(DevicePolicyManager.class);
        UserManager users=context.getSystemService(UserManager.class);
        String packageName=context.getPackageName();
        JSONObject restrictions=new JSONObject();
        String[] names={UserManager.DISALLOW_DEBUGGING_FEATURES,UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,UserManager.DISALLOW_CONFIG_LOCATION,UserManager.DISALLOW_CONFIG_WIFI,UserManager.DISALLOW_CONFIG_BLUETOOTH,UserManager.DISALLOW_ADD_USER,UserManager.DISALLOW_FACTORY_RESET};
        for(String name:names)Json.put(restrictions,name,users!=null&&users.hasUserRestriction(name));
        return Json.obj("deviceOwnerForSelf",policy!=null&&policy.isDeviceOwnerApp(packageName),
                "profileOwnerForSelf",policy!=null&&policy.isProfileOwnerApp(packageName),
                "managedProfile",Build.VERSION.SDK_INT>=30&&users!=null&&users.isManagedProfile(),
                "userUnlocked",Build.VERSION.SDK_INT<24||users==null||users.isUserUnlocked(),
                "storageEncryptionStatus",policy==null?null:policy.getStorageEncryptionStatus(),
                "restrictions",restrictions);
    }
    // 业务块：只查询本 App UID 的 AppOps，不调用 noteOp、不申请授权，也不枚举其它包。
    private static JSONObject specialAccess(Context context){
        AppOpsManager ops=context.getSystemService(AppOpsManager.class);
        android.app.NotificationManager notifications=context.getSystemService(android.app.NotificationManager.class);
        PackageManager packages=context.getPackageManager();
        Boolean canRequestPackageInstalls=null,notificationPolicyAccess=null,allFilesAccess=null;
        String canRequestPackageInstallsStatus=Build.VERSION.SDK_INT<26?"unsupported":"allowed";
        String notificationPolicyAccessStatus=Build.VERSION.SDK_INT<23?"unsupported":"allowed";
        String allFilesAccessStatus=Build.VERSION.SDK_INT<30?"unsupported":"allowed";
        if(Build.VERSION.SDK_INT>=26)try{canRequestPackageInstalls=packages.canRequestPackageInstalls();canRequestPackageInstallsStatus=canRequestPackageInstalls?"allowed":"denied";}catch(SecurityException e){canRequestPackageInstallsStatus="denied";}
        if(Build.VERSION.SDK_INT>=23)try{if(notifications==null){notificationPolicyAccessStatus="unavailable";}else{notificationPolicyAccess=notifications.isNotificationPolicyAccessGranted();notificationPolicyAccessStatus=notificationPolicyAccess?"allowed":"denied";}}catch(SecurityException e){notificationPolicyAccessStatus="denied";}
        if(Build.VERSION.SDK_INT>=30)try{allFilesAccess=Environment.isExternalStorageManager();allFilesAccessStatus=allFilesAccess?"allowed":"denied";}catch(SecurityException e){allFilesAccessStatus="denied";}
        JSONObject out=Json.obj("available",ops!=null,"canDrawOverlays",Settings.canDrawOverlays(context),
                "canRequestPackageInstalls",canRequestPackageInstalls,
                "canRequestPackageInstallsStatus",canRequestPackageInstallsStatus,
                "notificationPolicyAccess",notificationPolicyAccess,
                "notificationPolicyAccessStatus",notificationPolicyAccessStatus,
                "allFilesAccess",allFilesAccess,
                "allFilesAccessStatus",allFilesAccessStatus);
        if(ops==null)return out;
        JSONObject appOps=new JSONObject();
        ArrayList<String> names=new ArrayList<>(Arrays.asList(checkedAppOps()));
        if(Build.VERSION.SDK_INT>=26)names.add("android:request_install_packages");
        if(Build.VERSION.SDK_INT>=30)names.add("android:manage_external_storage");
        for(String name:names){int mode=ops.checkOpNoThrow(name,android.os.Process.myUid(),context.getPackageName());Json.put(appOps,name,Json.obj("mode",mode,"allowed",mode==AppOpsManager.MODE_ALLOWED));}
        Json.put(out,"appOps",appOps);
        return out;
    }
    private static String[] checkedAppOps(){return new String[]{AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,AppOpsManager.OPSTR_GET_USAGE_STATS,AppOpsManager.OPSTR_PICTURE_IN_PICTURE,AppOpsManager.OPSTR_MOCK_LOCATION,AppOpsManager.OPSTR_WRITE_SETTINGS,AppOpsManager.OPSTR_CAMERA,AppOpsManager.OPSTR_RECORD_AUDIO,AppOpsManager.OPSTR_BODY_SENSORS,AppOpsManager.OPSTR_FINE_LOCATION,AppOpsManager.OPSTR_COARSE_LOCATION};}
    // 业务块：只记录锁屏安全状态与硬件能力声明，不读取生物特征、模板或用户身份。
    private static JSONObject lockBiometricEnvironment(Context context){
        KeyguardManager keyguard=context.getSystemService(KeyguardManager.class);
        PackageManager packages=context.getPackageManager();
        JSONObject out=Json.obj("keyguardSecure",keyguard!=null&&keyguard.isKeyguardSecure(),
                "keyguardLocked",keyguard!=null&&keyguard.isKeyguardLocked(),
                "deviceSecure",Build.VERSION.SDK_INT<23||keyguard!=null&&keyguard.isDeviceSecure(),
                "deviceLocked",Build.VERSION.SDK_INT<23||keyguard!=null&&keyguard.isDeviceLocked(),
                "fingerprintFeature",packages.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT),
                "faceFeature",Build.VERSION.SDK_INT>=29&&packages.hasSystemFeature(PackageManager.FEATURE_FACE),
                "irisFeature",Build.VERSION.SDK_INT>=29&&packages.hasSystemFeature(PackageManager.FEATURE_IRIS),
                "biometricManagerAvailable",false,
                "biometricStrongCode",null,"biometricStrongStatus","unsupported",
                "biometricWeakCode",null,"biometricWeakStatus","unsupported",
                "deviceCredentialCode",null,"deviceCredentialStatus","unsupported");
        if(Build.VERSION.SDK_INT<30)return out;
        android.hardware.biometrics.BiometricManager biometric=context.getSystemService(android.hardware.biometrics.BiometricManager.class);
        if(biometric==null){Json.put(out,"biometricStrongStatus","unavailable");Json.put(out,"biometricWeakStatus","unavailable");Json.put(out,"deviceCredentialStatus","unavailable");return out;}
        Json.put(out,"biometricManagerAvailable",true);
        putBiometricResult(out,biometric,"biometricStrong",android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG);
        putBiometricResult(out,biometric,"biometricWeak",android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK);
        putBiometricResult(out,biometric,"deviceCredential",android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL);
        return out;
    }
    private static void putBiometricResult(JSONObject out,android.hardware.biometrics.BiometricManager biometric,String prefix,int authenticators){
        try{
            int code=biometric.canAuthenticate(authenticators);Json.put(out,prefix+"Code",code);Json.put(out,prefix+"Status",biometricStatus(code));
        }catch(SecurityException e){Json.put(out,prefix+"Code",null);Json.put(out,prefix+"Status","denied");}
    }
    private static String biometricStatus(int code){
        if(code==android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS)return "success";
        if(code==android.hardware.biometrics.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED)return "none_enrolled";
        if(code==android.hardware.biometrics.BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE)return "no_hardware";
        if(code==android.hardware.biometrics.BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE)return "hardware_unavailable";
        if(code==android.hardware.biometrics.BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED)return "security_update_required";
        return "error_"+code;
    }
    // 业务块：只记录无障碍服务的组件与能力位，不读取窗口内容、文本或用户操作。
    private static JSONObject accessibilityEnvironment(Context context){
        AccessibilityManager manager=context.getSystemService(AccessibilityManager.class);
        if(manager==null)return Json.obj("available",false,"count",0,"services",new JSONArray());
        List<AccessibilityServiceInfo> list=manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        if(list==null)return Json.obj("available",true,"count",0,"services",new JSONArray());
        if(list.size()>256)throw new IllegalStateException("无障碍服务超过 256");
        ArrayList<AccessibilityServiceInfo> sorted=new ArrayList<>(list);
        sorted.sort(Comparator.comparing(SecurityCollector::accessibilityComponent,String.CASE_INSENSITIVE_ORDER));
        JSONArray services=new JSONArray();
        for(AccessibilityServiceInfo info:sorted){
            ResolveInfo resolved=info.getResolveInfo();
            ServiceInfo service=resolved==null?null:resolved.serviceInfo;
            String packageName=service==null?null:service.packageName;
            String serviceName=service==null?null:service.name;
            int capabilities=info.getCapabilities();
            services.put(Json.obj("packageName",packageName,"serviceName",serviceName,
                    "feedbackType",info.feedbackType,"eventTypes",info.eventTypes,"capabilities",capabilities,
                    "canRetrieveWindowContent",(capabilities&AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT)!=0,
                    "canRequestTouchExploration",(capabilities&AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION)!=0,
                    "canRequestFilterKeyEvents",(capabilities&AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS)!=0,
                    "canPerformGestures",Build.VERSION.SDK_INT>=24&&(capabilities&AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES)!=0));
        }
        return Json.obj("available",true,"count",services.length(),"services",services);
    }
    private static String accessibilityComponent(AccessibilityServiceInfo info){
        ResolveInfo resolved=info.getResolveInfo();
        ServiceInfo service=resolved==null?null:resolved.serviceInfo;
        return service==null?"":service.packageName+"/"+service.name;
    }
    // 业务块：只查询当前 App 自身的签名摘要与安装来源，不枚举其它包、不导出证书 DER。
    private static JSONObject selfIntegrity(Context context)throws Exception{
        PackageManager pm=context.getPackageManager();
        String packageName=context.getPackageName();
        int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
        PackageInfo info=pm.getPackageInfo(packageName,flags);
        android.content.pm.Signature[] signatures=Build.VERSION.SDK_INT>=28&&info.signingInfo!=null?info.signingInfo.getApkContentsSigners():info.signatures;
        JSONArray hashes=new JSONArray(),history=new JSONArray();Boolean hasPast=null;
        if(signatures!=null)for(android.content.pm.Signature signature:signatures)hashes.put(PackageInputCollector.sha(signature.toByteArray()));
        if(Build.VERSION.SDK_INT>=28&&info.signingInfo!=null){android.content.pm.Signature[] signingHistory=info.signingInfo.getSigningCertificateHistory();if(signingHistory!=null)for(android.content.pm.Signature signature:signingHistory)history.put(PackageInputCollector.sha(signature.toByteArray()));hasPast=info.signingInfo.hasPastSigningCertificates();}
        android.content.pm.InstallSourceInfo installSource=null;
        String installer=null,initiatingPackageName=null,originatingPackageName=null,updateOwnerPackageName=null,packageSourceName=null,packageSourceStatus=Build.VERSION.SDK_INT<33?"unsupported":"unavailable";
        Integer packageSource=null;
        if(Build.VERSION.SDK_INT>=30){
            installSource=pm.getInstallSourceInfo(packageName);
            if(installSource!=null){
                installer=installSource.getInstallingPackageName();
                initiatingPackageName=installSource.getInitiatingPackageName();
                originatingPackageName=installSource.getOriginatingPackageName();
                if(Build.VERSION.SDK_INT>=33){packageSource=installSource.getPackageSource();packageSourceName=packageSourceName(packageSource);packageSourceStatus="ok";}
                if(Build.VERSION.SDK_INT>=34)updateOwnerPackageName=installSource.getUpdateOwnerPackageName();
            }
        }else installer=pm.getInstallerPackageName(packageName);
        ApplicationInfo application=info.applicationInfo;
        Boolean instantApp=Build.VERSION.SDK_INT>=26?pm.isInstantApp():null,packageSuspended=null;
        String packageSuspensionQueryStatus=Build.VERSION.SDK_INT<24?"unsupported":"allowed";
        if(Build.VERSION.SDK_INT>=24)try{packageSuspended=pm.isPackageSuspended();}catch(SecurityException e){packageSuspensionQueryStatus="denied";}
        return Json.obj("packageName",packageName,"versionName",info.versionName,
                "versionCode",Build.VERSION.SDK_INT>=28?info.getLongVersionCode():(long)info.versionCode,
                "installer",installer,"initiatingPackageName",initiatingPackageName,"originatingPackageName",originatingPackageName,"updateOwnerPackageName",updateOwnerPackageName,
                "packageSource",packageSource,"packageSourceName",packageSourceName,"packageSourceStatus",packageSourceStatus,
                "signingCertificateSha256",hashes,"signingCertificateHistorySha256",history,"hasPastSigningCertificates",hasPast,
                "enabled",application==null?null:application.enabled,
                "applicationFlags",application==null?null:application.flags,
                "debuggable",application!=null&&(application.flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0,
                "hasCode",application!=null&&(application.flags&ApplicationInfo.FLAG_HAS_CODE)!=0,
                "instantApp",instantApp,"packageSuspended",packageSuspended,"packageSuspensionQueryStatus",packageSuspensionQueryStatus);
    }
    // 业务块：把公开安装来源常量转换为稳定名称；未知常量保留数值，避免伪造来源结论。
    private static String packageSourceName(Integer source){
        if(source==null)return null;
        if(source==android.content.pm.PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED)return "unspecified";
        if(source==android.content.pm.PackageInstaller.PACKAGE_SOURCE_STORE)return "store";
        if(source==android.content.pm.PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)return "local_file";
        if(source==android.content.pm.PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)return "downloaded_file";
        if(source==android.content.pm.PackageInstaller.PACKAGE_SOURCE_OTHER)return "other";
        return "code_"+source;
    }
    public static JSONObject key(String alias,boolean attest,boolean strong)throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        try{
            byte[] challenge=new byte[32];new SecureRandom().nextBytes(challenge);
            KeyGenParameterSpec.Builder spec=new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_VERIFY).setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256);
            if(attest)spec.setAttestationChallenge(challenge);if(strong){if(Build.VERSION.SDK_INT<28)throw new UnsupportedOperationException("StrongBox requires API 28");spec.setIsStrongBoxBacked(true);}
            KeyPairGenerator generator=KeyPairGenerator.getInstance("EC","AndroidKeyStore");generator.initialize(spec.build());KeyPair pair=generator.generateKeyPair();
            KeyInfo info=KeyFactory.getInstance("EC","AndroidKeyStore").getKeySpec(pair.getPrivate(),KeyInfo.class);
            Signature signer=Signature.getInstance("SHA256withECDSA");signer.initSign(pair.getPrivate());signer.update(challenge);byte[] signature=signer.sign();
            JSONArray chain=new JSONArray();Certificate[] certificates=store.getCertificateChain(alias);if(certificates!=null)for(Certificate cert:certificates)chain.put(Base64.encodeToString(cert.getEncoded(),Base64.NO_WRAP));
            JSONObject out=Json.obj("requestedAttestation",attest,"requestedStrongBox",strong,"algorithm","SHA256withECDSA","challengeBase64",Base64.encodeToString(challenge,Base64.NO_WRAP),"signatureBase64",Base64.encodeToString(signature,Base64.NO_WRAP),"publicKeyBase64",Base64.encodeToString(pair.getPublic().getEncoded(),Base64.NO_WRAP),"certificateChainBase64",chain,"insideSecureHardware",info.isInsideSecureHardware());
            if(Build.VERSION.SDK_INT>=31)Json.put(out,"securityLevel",info.getSecurityLevel());return out;
        }finally{if(store.containsAlias(alias))store.deleteEntry(alias);}
    }
    public static int cleanupOwnedAliases()throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);ArrayList<String> names=new ArrayList<>();Enumeration<String> aliases=store.aliases();while(aliases.hasMoreElements()){String name=aliases.nextElement();if(name.startsWith("fp_collect_"))names.add(name);}for(String name:names)store.deleteEntry(name);return names.size();
    }
    private void attestation(ItemSink s){
        s.read("attestation","attestation/cleanup-before","回收本工具上次中断的临时密钥","AndroidKeyStore.deleteEntry(fp_collect_* only)",SecurityCollector::cleanupOwnedAliases);
        s.read("attestation","attestation/play-integrity-environment","Play Integrity 本地运行环境（非 verdict）","PackageManager.getPackageInfo",()->integrityEnvironment(s.context));
        s.read("attestation","attestation/tee","临时密钥与挑战认证","AndroidKeyStore.setAttestationChallenge",()->key("fp_collect_"+s.session+"_tee",true,false));
        s.read("attestation","attestation/plain","无认证挑战的密钥安全级别","AndroidKeyStore / KeyInfo",()->key("fp_collect_"+s.session+"_plain",false,false));
        if(Build.VERSION.SDK_INT>=28&&s.context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE))s.read("attestation","attestation/strongbox","StrongBox 实际生成与认证","setIsStrongBoxBacked(true)",()->key("fp_collect_"+s.session+"_strongbox",true,true));
        else s.add("attestation","attestation/strongbox","StrongBox 未声明","android-api","FEATURE_STRONGBOX_KEYSTORE","unsupported",null,null,null);
        s.add("attestation","attestation/play-integrity","Play Integrity 未接入","collector","离线采集配置","skipped",null,null,null);
        s.read("attestation","attestation/cleanup-after","本次临时密钥清理","AndroidKeyStore.deleteEntry(fp_collect_* only)",SecurityCollector::cleanupOwnedAliases);
    }
    // 业务块：只记录 Play Integrity 依赖包的本地可见元数据，不把包存在当作远程 verdict。
    private static JSONObject integrityEnvironment(Context context)throws Exception{
        PackageManager pm=context.getPackageManager();
        JSONObject out=Json.obj("verdictAvailable",false,"conclusion","仅为本地环境证据，不等于 Play Integrity verdict");
        Json.put(out,"playServices",packageEnvironment(pm,"com.google.android.gms"));
        Json.put(out,"playStore",packageEnvironment(pm,"com.android.vending"));
        return out;
    }
    private static JSONObject packageEnvironment(PackageManager pm,String packageName)throws Exception{
        try{
            PackageInfo info=pm.getPackageInfo(packageName,Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES);
            JSONArray hashes=new JSONArray();
            android.content.pm.Signature[] signatures=Build.VERSION.SDK_INT>=28&&info.signingInfo!=null?info.signingInfo.getApkContentsSigners():info.signatures;
            if(signatures!=null)for(android.content.pm.Signature signature:signatures)hashes.put(PackageInputCollector.sha(signature.toByteArray()));
            String installer=null;if(Build.VERSION.SDK_INT>=30)installer=pm.getInstallSourceInfo(packageName).getInstallingPackageName();else installer=pm.getInstallerPackageName(packageName);
            ApplicationInfo app=info.applicationInfo;
            return Json.obj("present",true,"packageName",packageName,"versionName",info.versionName,"versionCode",Build.VERSION.SDK_INT>=28?info.getLongVersionCode():(long)info.versionCode,"enabled",app==null?null:app.enabled,"applicationFlags",app==null?null:app.flags,"installer",installer,"signingCertificateSha256",hashes);
        }catch(PackageManager.NameNotFoundException e){return Json.obj("present",false,"packageName",packageName,"status","not-found");}
    }
    private void virtualization(ItemSink s)throws Exception {
        String[] paths={"/dev/qemu_pipe","/dev/socket/qemud","/system/lib/libc_malloc_debug_qemu.so","/sys/qemu_trace","/system/bin/qemu-props","/system/bin/su","/system/xbin/su","/sbin/su","/vendor/bin/su","/data/adb","/proc/vz","/proc/bc","/dev/kvm"};
        JSONArray presentPaths=new JSONArray(), pathErrors=new JSONArray();
        for(String path:paths){JSONObject stat=new JSONObject(NativeProbe.inspectPath(path));s.ok("virtualization","virtualization/path"+path,path,"jni","openat(O_NOFOLLOW) + fstatat(AT_SYMLINK_NOFOLLOW)，只观察不执行",stat);if(stat.optBoolean("ok"))presentPaths.put(path);else if(stat.has("errno"))pathErrors.put(Json.obj("path",path,"errno",stat.optInt("errno")));}
        JSONObject build=Json.obj("fingerprint",Build.FINGERPRINT,"model",Build.MODEL,"manufacturer",Build.MANUFACTURER,"brand",Build.BRAND,"device",Build.DEVICE,"product",Build.PRODUCT,"hardware",Build.HARDWARE,"board",Build.BOARD,"supportedAbis",Build.SUPPORTED_ABIS);
        s.ok("virtualization","virtualization/build-evidence","虚拟化规则输入","android-api","Build public fields",build);
        String all=build.toString().toLowerCase(Locale.ROOT);JSONArray matches=new JSONArray();for(String marker:new String[]{"generic","goldfish","ranchu","vbox","qemu","emulator","test-keys"})if(all.contains(marker))matches.put(marker);
        s.ok("virtualization","virtualization/build-markers","公开字符串规则线索","rule","build-markers@1",Json.obj("matchedMarkers",matches,"conclusion","仅为可复核字符串线索，不能据此确定真机、云机或第三方风控结论"));
        JSONObject item=s.items.optJSONObject(s.items.length()-1);Json.put(item,"rule",Json.obj("version","1","evidenceKeys",new String[]{"virtualization/build-evidence"}));
        // 业务块：把公开规则输入压缩成可读摘要，原始逐路径项目仍完整保留。
        s.ok("virtualization","virtualization/risk-summary","虚拟化与提权线索摘要","rule","rootbeer/talsec-signal-summary@1",Json.obj(
                "pathChecks",paths.length,"presentPaths",presentPaths,"pathErrors",pathErrors,
                "matchedBuildMarkers",matches,"testKeys",all.contains("test-keys"),
                "evidenceOnly",true,"conclusion","仅汇总公开证据，不自动判定 root、模拟器、云机或欺诈"));
    }
}
