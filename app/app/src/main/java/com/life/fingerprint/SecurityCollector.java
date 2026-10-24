package com.life.fingerprint;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.*;
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
        s.ok("security","security/self","应用安全上下文","android-api","Process / ApplicationInfo / Debug",Json.obj("uid",android.os.Process.myUid(),"pid",android.os.Process.myPid(),"debuggable",(s.context.getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0,"debuggerConnected",Debug.isDebuggerConnected(),"waitingForDebugger",Debug.waitingForDebugger()));
        for(String p:new String[]{"/proc/self/attr/current","/proc/self/status","/sys/fs/selinux/enforce","/sys/fs/selinux/policyvers","/sys/fs/selinux/checkreqprot","/proc/cmdline","/proc/bootconfig","/system/etc/selinux/plat_sepolicy.cil","/vendor/etc/selinux/vendor_sepolicy.cil"})s.file("security","security/file"+p,p);
        s.read("security","security/buildSerial","Build 序列号（可能受限）","Build.getSerial",()->Build.VERSION.SDK_INT>=26?Build.getSerial():Build.SERIAL);
        s.read("security","security/providers","安全提供者","Security.getProviders",()->{JSONArray a=new JSONArray();for(Provider p:Security.getProviders()){JSONArray services=new JSONArray();for(Provider.Service v:p.getServices())services.put(Json.obj("type",v.getType(),"algorithm",v.getAlgorithm(),"className",v.getClassName()));a.put(Json.obj("name",p.getName(),"info",p.getInfo(),"version",p.getVersion(),"services",services));}return a;});
        s.read("security","security/trust-store","当前 App 可见 CA 信任库","KeyStore AndroidCAStore",()->{KeyStore store=KeyStore.getInstance("AndroidCAStore");store.load(null);JSONArray a=new JSONArray();Enumeration<String> names=store.aliases();int count=0;while(names.hasMoreElements()){String name=names.nextElement();if(++count>4096)throw new IllegalStateException("CA 数量超过 4096");Certificate cert=store.getCertificate(name);a.put(Json.obj("alias",name,"derBase64",cert==null?null:Base64.encodeToString(cert.getEncoded(),Base64.NO_WRAP),"sha256",cert==null?null:PackageInputCollector.sha(cert.getEncoded())));}return a;});
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
