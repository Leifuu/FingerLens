package com.life.fingerprint;

import android.content.*;
import android.net.*;
import android.net.wifi.*;
import android.os.Build;
import android.telephony.*;
import android.bluetooth.*;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.util.*;

public final class NetworkCollector implements Collector {
    private final String module;
    public NetworkCollector(String module){this.module=module;}
    public void collect(ItemSink s){if(module.equals("sim"))sim(s);else network(s);}
    // ItemSink.read 统一捕获 SecurityException；特权标识只尝试普通 App API，不申请特权。
    @android.annotation.SuppressLint("MissingPermission")
    private void sim(ItemSink s){
        TelephonyManager t=s.context.getSystemService(TelephonyManager.class);
        s.read("sim","sim/summary","SIM 与电话环境摘要","TelephonyManager / SubscriptionManager",()->simSummary(s.context));
        if(t==null)return;
        s.read("sim","sim/phoneType","电话类型","TelephonyManager.getPhoneType",t::getPhoneType);
        s.read("sim","sim/count","电话数量","TelephonyManager.getPhoneCount",t::getPhoneCount);
        s.read("sim","sim/state","SIM 状态","TelephonyManager.getSimState",t::getSimState);
        s.read("sim","sim/operator","SIM 运营商编码","TelephonyManager.getSimOperator",t::getSimOperator);
        s.read("sim","sim/operatorName","SIM 运营商名称","TelephonyManager.getSimOperatorName",t::getSimOperatorName);
        s.read("sim","sim/country","SIM 国家","TelephonyManager.getSimCountryIso",t::getSimCountryIso);
        s.read("sim","sim/networkOperator","网络运营商编码","TelephonyManager.getNetworkOperator",t::getNetworkOperator);
        s.read("sim","sim/networkName","网络运营商名称","TelephonyManager.getNetworkOperatorName",t::getNetworkOperatorName);
        s.read("sim","sim/networkCountry","网络国家","TelephonyManager.getNetworkCountryIso",t::getNetworkCountryIso);
        s.read("sim","sim/dataNetworkType","数据网络类型","TelephonyManager.getDataNetworkType",t::getDataNetworkType);
        s.read("sim","sim/roaming","漫游状态","TelephonyManager.isNetworkRoaming",t::isNetworkRoaming);
        s.read("sim","sim/dataEnabled","移动数据开关","TelephonyManager.isDataEnabled",()->{if(Build.VERSION.SDK_INT<26)throw new UnsupportedOperationException("requires API 26");return t.isDataEnabled();});
        s.read("sim","sim/imei","IMEI（可能受限）","TelephonyManager.getImei/getDeviceId",()->Build.VERSION.SDK_INT>=26?t.getImei():t.getDeviceId());
        s.read("sim","sim/imsi","IMSI（可能受限）","TelephonyManager.getSubscriberId",t::getSubscriberId);
        s.read("sim","sim/serial","SIM 序列号（可能受限）","TelephonyManager.getSimSerialNumber",t::getSimSerialNumber);
        s.read("sim","sim/line1","号码（可能受限）","TelephonyManager.getLine1Number",t::getLine1Number);
        s.read("sim","sim/subscriptions","活动订阅","SubscriptionManager.getActiveSubscriptionInfoList",()->{SubscriptionManager m=s.context.getSystemService(SubscriptionManager.class);java.util.List<SubscriptionInfo> list=m.getActiveSubscriptionInfoList();if(list==null)return null;JSONArray a=new JSONArray();for(SubscriptionInfo i:list){JSONObject o=Json.obj("subscriptionId",i.getSubscriptionId(),"simSlotIndex",i.getSimSlotIndex(),"carrierName",String.valueOf(i.getCarrierName()),"displayName",String.valueOf(i.getDisplayName()),"countryIso",i.getCountryIso(),"iccId",i.getIccId(),"number",i.getNumber(),"dataRoaming",i.getDataRoaming());if(Build.VERSION.SDK_INT>=29){Json.put(o,"mcc",i.getMccString());Json.put(o,"mnc",i.getMncString());Json.put(o,"embedded",i.isEmbedded());}a.put(o);}return a;});
        int count=1;try{count=Math.min(16,Math.max(1,t.getPhoneCount()));}catch(Exception ignored){}for(int i=0;i<count;i++){final int slot=i;s.read("sim","sim/slot/"+i,"SIM 槽位 "+i,"TelephonyManager.getSimState(slot)",()->{if(Build.VERSION.SDK_INT<26)throw new UnsupportedOperationException("requires API 26");return t.getSimState(slot);});}
    }
    // 业务块：只汇总电话/SIM 环境元数据，不复制 IMEI、IMSI、ICCID 或号码；原始 sim/* 条目继续保留。
    @android.annotation.SuppressLint("MissingPermission")
    private static JSONObject simSummary(Context context)throws Exception{
        TelephonyManager t=context.getSystemService(TelephonyManager.class);if(t==null)return Json.obj("available",false,"simStates",new JSONArray(),"subscriptionAccess","unavailable");
        Object phoneCount=simRead(t::getPhoneCount);JSONArray simStates=new JSONArray();int slots=phoneCount instanceof Integer?Math.min(16,Math.max(1,(Integer)phoneCount)):1;for(int i=0;i<slots;i++){final int slot=i;simStates.put(simRead(()->Build.VERSION.SDK_INT>=26?t.getSimState(slot):null));}
        JSONObject value=Json.obj("available",true,"phoneType",simRead(t::getPhoneType),"phoneCount",phoneCount,"simState",simRead(t::getSimState),"simStates",simStates,"simOperator",simRead(t::getSimOperator),"simOperatorName",simRead(t::getSimOperatorName),"simCountry",simRead(t::getSimCountryIso),"networkOperator",simRead(t::getNetworkOperator),"networkName",simRead(t::getNetworkOperatorName),"networkCountry",simRead(t::getNetworkCountryIso),"dataNetworkType",simRead(t::getDataNetworkType),"roaming",simRead(t::isNetworkRoaming),"dataEnabled",Build.VERSION.SDK_INT>=26?simRead(t::isDataEnabled):null);
        SubscriptionManager manager=context.getSystemService(SubscriptionManager.class);if(manager==null){Json.put(value,"subscriptionAccess","unavailable");Json.put(value,"subscriptionCount",null);Json.put(value,"simSlotIndexes",new JSONArray());Json.put(value,"carrierNames",new JSONArray());Json.put(value,"mccs",new JSONArray());Json.put(value,"mncs",new JSONArray());Json.put(value,"embeddedCount",null);return value;}
        try{List<SubscriptionInfo> list=manager.getActiveSubscriptionInfoList();JSONArray simSlotIndexes=new JSONArray(),carrierNames=new JSONArray(),mccs=new JSONArray(),mncs=new JSONArray();int embeddedCount=0;if(list!=null)for(SubscriptionInfo info:list){if(simSlotIndexes.length()>=16)break;simSlotIndexes.put(info.getSimSlotIndex());CharSequence carrier=info.getCarrierName();if(carrier!=null)carrierNames.put(carrier.toString());if(Build.VERSION.SDK_INT>=29){String mcc=info.getMccString(),mnc=info.getMncString();if(mcc!=null)mccs.put(mcc);if(mnc!=null)mncs.put(mnc);if(info.isEmbedded())embeddedCount++;}}Json.put(value,"subscriptionAccess",list==null?"empty":"allowed");Json.put(value,"subscriptionCount",list==null?null:list.size());Json.put(value,"simSlotIndexes",simSlotIndexes);Json.put(value,"carrierNames",carrierNames);Json.put(value,"mccs",mccs);Json.put(value,"mncs",mncs);Json.put(value,"embeddedCount",Build.VERSION.SDK_INT>=29?embeddedCount:null);
        }catch(SecurityException e){Json.put(value,"subscriptionAccess","denied");Json.put(value,"subscriptionCount",null);Json.put(value,"simSlotIndexes",new JSONArray());Json.put(value,"carrierNames",new JSONArray());Json.put(value,"mccs",new JSONArray());Json.put(value,"mncs",new JSONArray());Json.put(value,"embeddedCount",null);}return value;
    }
    private interface SimRead{Object get()throws Exception;}
    private static Object simRead(SimRead read){try{return read.get();}catch(Exception|LinkageError ignored){return null;}}
    // 权限拒绝由 ItemSink 转成 denied；缓存只读，不主动扫描。
    @android.annotation.SuppressLint("MissingPermission")
    private void network(ItemSink s){
        ConnectivityManager c=s.context.getSystemService(ConnectivityManager.class);
        // 业务块：记录公开网络能力，供风控人工复核；不把 VPN、代理或未验证链路直接判定为欺诈。
        s.read("network","network/risk-signals","活动链路风控信号","ConnectivityManager.getActiveNetwork / NetworkCapabilities",()->{
            Network active=c.getActiveNetwork();
            if(active==null)return Json.obj("present",false);
            NetworkCapabilities cap=c.getNetworkCapabilities(active);
            if(cap==null)return Json.obj("present",true,"capabilitiesAvailable",false,"networkHandle",active.getNetworkHandle());
            LinkProperties link=c.getLinkProperties(active);
            JSONArray transports=new JSONArray();
            for(int type=0;type<=6;type++)if(cap.hasTransport(type))transports.put(type);
            return Json.obj("present",true,"capabilitiesAvailable",true,"networkHandle",active.getNetworkHandle(),
                    "transports",transports,"vpn",cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                    "validated",cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    "captivePortal",cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
                    "notVpn",cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
                    "notMetered",cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                    "notRoaming",cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING),
                    "notRestricted",cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED),
                    "trusted",cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED),
                    "foreground",cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_FOREGROUND),
                    "metered",c.isActiveNetworkMetered(),"interfaceName",link==null?null:link.getInterfaceName(),
                    "httpProxy",link==null||link.getHttpProxy()==null?null:link.getHttpProxy().toString(),
                    "capabilitiesRaw",cap.toString());
        });
        s.read("network","network/connectivity-summary","网络集合摘要","ConnectivityManager.getAllNetworks / NetworkCapabilities",()->connectivitySummary(c));
        s.read("network","network/connectivity","连接与链路","ConnectivityManager.getAllNetworks",()->{JSONArray a=new JSONArray();Network active=c.getActiveNetwork();for(Network n:c.getAllNetworks()){NetworkCapabilities cap=c.getNetworkCapabilities(n);LinkProperties link=c.getLinkProperties(n);JSONObject o=Json.obj("handle",n.getNetworkHandle(),"active",n.equals(active),"capabilitiesRaw",cap==null?null:cap.toString());if(cap!=null){JSONArray transports=new JSONArray();for(int type=0;type<=10;type++)if(cap.hasTransport(type))transports.put(type);Json.put(o,"transports",transports);Json.put(o,"downstreamKbps",cap.getLinkDownstreamBandwidthKbps());Json.put(o,"upstreamKbps",cap.getLinkUpstreamBandwidthKbps());}if(link!=null){JSONArray addresses=new JSONArray(),dns=new JSONArray(),routes=new JSONArray();for(LinkAddress x:link.getLinkAddresses())addresses.put(Json.obj("address",x.getAddress().getHostAddress(),"prefixLength",x.getPrefixLength(),"flags",x.getFlags(),"scope",x.getScope()));for(InetAddress x:link.getDnsServers())dns.put(x.getHostAddress());for(RouteInfo x:link.getRoutes())routes.put(Json.obj("destination",x.getDestination().toString(),"gateway",x.getGateway()==null?null:x.getGateway().getHostAddress(),"interface",x.getInterface(),"default",x.isDefaultRoute()));Json.put(o,"link",Json.obj("interfaceName",link.getInterfaceName(),"domains",link.getDomains(),"mtu",Build.VERSION.SDK_INT>=29?link.getMtu():null,"addresses",addresses,"dns",dns,"routes",routes,"httpProxy",link.getHttpProxy()==null?null:link.getHttpProxy().toString(),"raw",link.toString()));}a.put(o);}return a;});
        s.read("network","network/interfaces","网络接口","NetworkInterface.getNetworkInterfaces",()->{JSONArray a=new JSONArray();Enumeration<NetworkInterface> all=NetworkInterface.getNetworkInterfaces();if(all!=null)while(all.hasMoreElements()){NetworkInterface n=all.nextElement();JSONArray addresses=new JSONArray();for(InterfaceAddress x:n.getInterfaceAddresses())addresses.put(Json.obj("address",x.getAddress().getHostAddress(),"prefixLength",x.getNetworkPrefixLength(),"broadcast",x.getBroadcast()==null?null:x.getBroadcast().getHostAddress()));byte[] mac=null;String error=null;try{mac=n.getHardwareAddress();}catch(Exception e){error=e.toString();}a.put(Json.obj("name",n.getName(),"displayName",n.getDisplayName(),"index",n.getIndex(),"up",n.isUp(),"loopback",n.isLoopback(),"virtual",n.isVirtual(),"pointToPoint",n.isPointToPoint(),"mtu",n.getMTU(),"hardwareAddressBase64",mac==null?null:android.util.Base64.encodeToString(mac,android.util.Base64.NO_WRAP),"hardwareAddressError",error,"addresses",addresses));}return a;});
        s.read("network","network/interface-summary","网络接口摘要","NetworkInterface.getNetworkInterfaces",()->interfaceSummary());
        WifiManager wifi=s.context.getApplicationContext().getSystemService(WifiManager.class);
        s.read("network","network/wifi","当前 Wi-Fi","WifiManager.getConnectionInfo",()->{WifiInfo i=wifi.getConnectionInfo();JSONObject o=Json.obj("state",wifi.getWifiState(),"ssid",i.getSSID(),"bssid",i.getBSSID(),"macAddress",i.getMacAddress(),"rssi",i.getRssi(),"frequency",i.getFrequency(),"linkSpeed",i.getLinkSpeed(),"networkId",i.getNetworkId(),"ipAddress",i.getIpAddress(),"supplicantState",i.getSupplicantState().name(),"raw",i.toString());if(Build.VERSION.SDK_INT>=29){Json.put(o,"txLinkSpeedMbps",i.getTxLinkSpeedMbps());Json.put(o,"rxLinkSpeedMbps",i.getRxLinkSpeedMbps());}return o;});
        s.read("network","network/wifi-cached-scan","系统已有 Wi-Fi 缓存（不发起扫描）","WifiManager.getScanResults",()->{JSONArray a=new JSONArray();for(ScanResult i:wifi.getScanResults())a.put(Json.obj("ssid",i.SSID,"bssid",i.BSSID,"capabilities",i.capabilities,"frequency",i.frequency,"level",i.level,"timestamp",i.timestamp,"channelWidth",i.channelWidth,"centerFreq0",i.centerFreq0,"centerFreq1",i.centerFreq1));return a;});
        s.read("network","network/wifi-summary","Wi-Fi 能力摘要","WifiManager.getConnectionInfo / getScanResults",()->wifiSummary(wifi));
        s.read("network","network/wifi-capabilities","Wi-Fi 链路能力","WifiInfo capability APIs",()->wifiCapabilities(wifi));
        s.read("network","network/proxy-summary","代理环境摘要","ConnectivityManager.getDefaultProxy",()->proxySummary(c));
        s.read("network","network/proxy","默认代理","ConnectivityManager.getDefaultProxy",()->{ProxyInfo p=c.getDefaultProxy();return p==null?null:Json.obj("host",p.getHost(),"port",p.getPort(),"exclusionList",p.getExclusionList(),"pacFileUrl",p.getPacFileUrl()==null?null:p.getPacFileUrl().toString());});
        s.read("network","network/bluetooth","蓝牙及已配对设备","BluetoothManager / BluetoothAdapter.getBondedDevices",()->{BluetoothManager m=s.context.getSystemService(BluetoothManager.class);BluetoothAdapter b=m==null?null:m.getAdapter();if(b==null)return null;JSONArray devices=new JSONArray();for(BluetoothDevice d:b.getBondedDevices())devices.put(Json.obj("address",d.getAddress(),"name",d.getName(),"type",d.getType(),"bondState",d.getBondState()));return Json.obj("name",b.getName(),"address",b.getAddress(),"state",b.getState(),"pairedDevices",devices);});
        s.read("network","network/bluetooth-summary","蓝牙能力摘要","BluetoothManager / BluetoothAdapter",()->bluetoothSummary(s.context));
        s.read("network","network/bluetooth-capabilities","蓝牙 LE 能力","BluetoothAdapter LE capability APIs",()->bluetoothCapabilities(s.context));
        for(String p:new String[]{"/proc/net/route","/proc/net/ipv6_route","/proc/net/if_inet6","/proc/net/arp","/proc/net/dev"})s.file("network","network/file"+p,p);
    }
    // 业务块：只汇总接口数量、地址族和受控名称前缀，不复制接口名、地址或 MAC；network/interfaces 继续保留。
    private static JSONObject interfaceSummary()throws Exception{
        Enumeration<NetworkInterface> all=NetworkInterface.getNetworkInterfaces();if(all==null)return Json.obj("available",false,"status","unavailable","interfaceCount",null,"upCount",null,"downCount",null,"loopbackCount",null,"virtualCount",null,"pointToPointCount",null,"hardwareAddressPresentCount",null,"addressCount",null,"ipv4Count",null,"ipv6Count",null,"mtuMin",null,"mtuMax",null,"prefixCounts",new JSONObject());
        JSONObject prefixes=new JSONObject();int interfaces=0,up=0,down=0,loopback=0,virtual=0,pointToPoint=0,hardware=0,addresses=0,ipv4=0,ipv6=0,mtuMin=Integer.MAX_VALUE,mtuMax=Integer.MIN_VALUE;
        while(all.hasMoreElements()){NetworkInterface network=all.nextElement();interfaces++;boolean isUp=network.isUp();if(isUp)up++;else down++;if(network.isLoopback())loopback++;if(network.isVirtual())virtual++;if(network.isPointToPoint())pointToPoint++;if(network.getHardwareAddress()!=null)hardware++;int mtu=network.getMTU();if(mtu>0){mtuMin=Math.min(mtuMin,mtu);mtuMax=Math.max(mtuMax,mtu);}for(InterfaceAddress address:network.getInterfaceAddresses()){InetAddress value=address.getAddress();if(value==null)continue;addresses++;if(value instanceof Inet4Address)ipv4++;else if(value instanceof Inet6Address)ipv6++;}count(prefixes,interfacePrefix(network.getName()));}
        return Json.obj("available",true,"status","ok","interfaceCount",interfaces,"upCount",up,"downCount",down,"loopbackCount",loopback,"virtualCount",virtual,"pointToPointCount",pointToPoint,"hardwareAddressPresentCount",hardware,"addressCount",addresses,"ipv4Count",ipv4,"ipv6Count",ipv6,"mtuMin",mtuMin==Integer.MAX_VALUE?null:mtuMin,"mtuMax",mtuMax==Integer.MIN_VALUE?null:mtuMax,"prefixCounts",prefixes);
    }
    private static String interfacePrefix(String name){String lower=name==null?"":name.toLowerCase(Locale.ROOT);if(lower.equals("lo")||lower.startsWith("lo"))return "loopback";if(lower.startsWith("wlan"))return "wifi";if(lower.startsWith("rmnet")||lower.startsWith("ccmni")||lower.startsWith("radio"))return "cellular";if(lower.startsWith("eth"))return "ethernet";if(lower.startsWith("tun")||lower.startsWith("tap")||lower.startsWith("ppp"))return "vpn_or_tunnel";if(lower.startsWith("usb"))return "usb";if(lower.startsWith("dummy")||lower.startsWith("veth")||lower.startsWith("br"))return "virtual";return "other";}
    // 业务块：汇总所有公开网络能力，不复制地址、DNS、路由或原始字符串；network/connectivity 继续保留。
    private static JSONObject connectivitySummary(ConnectivityManager manager){
        if(manager==null)return Json.obj("available",false,"networkCount",0,"transportCounts",new JSONObject());
        Network[] networks;try{networks=manager.getAllNetworks();}catch(Exception e){return Json.obj("available",false,"access","error","networkCount",null,"transportCounts",new JSONObject());}if(networks==null)networks=new Network[0];
        Network active;try{active=manager.getActiveNetwork();}catch(Exception e){active=null;}JSONObject transports=new JSONObject();int capabilitiesAvailableCount=0,linkPropertiesAvailableCount=0,validatedCount=0,vpnCount=0,notMeteredCount=0,activeIndex=-1;
        for(int i=0;i<networks.length;i++){Network network=networks[i];if(active!=null&&active.equals(network))activeIndex=i;NetworkCapabilities cap;try{cap=manager.getNetworkCapabilities(network);}catch(Exception e){cap=null;}if(cap!=null){capabilitiesAvailableCount++;for(int type=0;type<=10;type++)if(cap.hasTransport(type))Json.put(transports,String.valueOf(type),transports.optInt(String.valueOf(type),0)+1);if(cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))validatedCount++;if(cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN))vpnCount++;if(cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))notMeteredCount++;}try{if(manager.getLinkProperties(network)!=null)linkPropertiesAvailableCount++;}catch(Exception ignored){}}
        return Json.obj("available",true,"networkCount",networks.length,"activePresent",active!=null,"activeIndex",activeIndex,"activeNetworkHandle",active==null?null:active.getNetworkHandle(),"capabilitiesAvailableCount",capabilitiesAvailableCount,"linkPropertiesAvailableCount",linkPropertiesAvailableCount,"validatedCount",validatedCount,"vpnCount",vpnCount,"notMeteredCount",notMeteredCount,"transportCounts",transports);
    }
    // 业务块：代理摘要只保留可比较元数据，不把主机、排除列表或 PAC 地址复制到摘要；network/proxy 继续保留原始条目。
    private static JSONObject proxySummary(ConnectivityManager manager)throws Exception{
        JSONObject value=Json.obj("available",manager!=null,"status",manager==null?"unavailable":"none","configured",false,"hostPresent",false,"hostLength",null,"hostSha256",null,"port",null,"exclusionCount",0,"pacFilePresent",false,"pacScheme",null);
        if(manager==null)return value;
        ProxyInfo proxy=manager.getDefaultProxy();
        if(proxy==null)return value;
        String host=proxy.getHost();
        String pac=proxy.getPacFileUrl()==null?null:proxy.getPacFileUrl().toString();
        String[] exclusions=proxy.getExclusionList();
        Json.put(value,"status","ok");
        Json.put(value,"configured",true);
        Json.put(value,"hostPresent",host!=null&&!host.isEmpty());
        Json.put(value,"hostLength",host==null?null:host.length());
        Json.put(value,"hostSha256",host==null||host.isEmpty()?null:PackageInputCollector.sha(host.getBytes(StandardCharsets.UTF_8)));
        Json.put(value,"port",proxy.getPort());
        Json.put(value,"exclusionCount",exclusions==null?0:Math.min(256,exclusions.length));
        boolean pacPresent=pac!=null&&!pac.isEmpty();
        Json.put(value,"pacFilePresent",pacPresent);
        Json.put(value,"pacScheme",pacPresent&&proxy.getPacFileUrl()!=null?proxy.getPacFileUrl().getScheme():null);
        return value;
    }
    // 业务块：公开蓝牙能力只保留计数与存在性；权限不足时返回结构化状态，不伪造已配对设备。
    private static JSONObject bluetoothSummary(Context context){
        BluetoothManager manager=context.getSystemService(BluetoothManager.class);if(manager==null)return Json.obj("available",false,"access","unavailable","bondedCount",0,"bondedTypeCounts",new JSONObject(),"bondedClassCounts",new JSONObject());BluetoothAdapter adapter=manager.getAdapter();if(adapter==null)return Json.obj("available",false,"access","unavailable","bondedCount",0,"bondedTypeCounts",new JSONObject(),"bondedClassCounts",new JSONObject());
        int state;try{state=adapter.getState();}catch(Exception e){state=-1;}boolean namePresent=false,addressPresent=false;try{namePresent=adapter.getName()!=null&&!adapter.getName().isEmpty();}catch(SecurityException ignored){}try{addressPresent=adapter.getAddress()!=null&&!adapter.getAddress().isEmpty();}catch(SecurityException ignored){}
        Set<BluetoothDevice> bonded;try{bonded=adapter.getBondedDevices();}catch(SecurityException e){return Json.obj("available",true,"access","denied","adapterState",state,"adapterNamePresent",namePresent,"adapterAddressPresent",addressPresent,"bondedCount",null,"bondedNameCount",null,"bondedAddressCount",null,"bondedTypeCounts",new JSONObject(),"bondedClassCounts",new JSONObject());}
        JSONObject typeCounts=new JSONObject(),classCounts=new JSONObject();int nameCount=0,addressCount=0;for(BluetoothDevice device:bonded){try{if(device.getName()!=null&&!device.getName().isEmpty())nameCount++;}catch(SecurityException ignored){}try{if(device.getAddress()!=null&&!device.getAddress().isEmpty())addressCount++;}catch(SecurityException ignored){}count(typeCounts,String.valueOf(device.getType()));try{BluetoothClass clazz=device.getBluetoothClass();if(clazz!=null)count(classCounts,String.valueOf(clazz.getDeviceClass()));}catch(SecurityException ignored){}}
        return Json.obj("available",true,"access","allowed","adapterState",state,"adapterNamePresent",namePresent,"adapterAddressPresent",addressPresent,"bondedCount",bonded.size(),"bondedNameCount",nameCount,"bondedAddressCount",addressCount,"bondedTypeCounts",typeCounts,"bondedClassCounts",classCounts);
    }
    // 业务块：只查询蓝牙适配器公开 LE 能力，不读取设备地址、名称或广播内容。
    private static JSONObject bluetoothCapabilities(Context context){
        if(Build.VERSION.SDK_INT<26)return Json.obj("available",false,"access","unsupported","adapterState",null,"le2MPhySupported",null,"leCodedPhySupported",null,"leExtendedAdvertisingSupported",null,"leMaximumAdvertisingDataLength",null);
        BluetoothManager manager=context.getSystemService(BluetoothManager.class);BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
        if(adapter==null)return Json.obj("available",false,"access","unavailable","adapterState",null,"le2MPhySupported",null,"leCodedPhySupported",null,"leExtendedAdvertisingSupported",null,"leMaximumAdvertisingDataLength",null);
        try{return Json.obj("available",true,"access","allowed","adapterState",adapter.getState(),"le2MPhySupported",adapter.isLe2MPhySupported(),"leCodedPhySupported",adapter.isLeCodedPhySupported(),"leExtendedAdvertisingSupported",adapter.isLeExtendedAdvertisingSupported(),"leMaximumAdvertisingDataLength",adapter.getLeMaximumAdvertisingDataLength());}
        catch(SecurityException e){return Json.obj("available",true,"access","denied","adapterState",null,"le2MPhySupported",null,"leCodedPhySupported",null,"leExtendedAdvertisingSupported",null,"leMaximumAdvertisingDataLength",null);}
        catch(RuntimeException e){return Json.obj("available",true,"access","error","adapterState",null,"le2MPhySupported",null,"leCodedPhySupported",null,"leExtendedAdvertisingSupported",null,"leMaximumAdvertisingDataLength",null,"error",e.getClass().getName());}
    }
    private static void count(JSONObject counts,String key){Json.put(counts,key,counts.optInt(key,0)+1);}
    // 业务块：Wi-Fi 摘要只记录连接/频段元数据，不导出 SSID、BSSID 或主动发起扫描。
    private static JSONObject wifiSummary(WifiManager wifi){
        if(wifi==null)return Json.obj("available",false,"connectionAccess","unavailable","scanAccess","unavailable","cachedScanCount",null,"frequencies",new JSONArray());
        int wifiState;try{wifiState=wifi.getWifiState();}catch(Exception e){wifiState=-1;}JSONObject out=Json.obj("available",true,"wifiState",wifiState,"connectionAccess","unknown","scanAccess","unknown","connectionInfoAvailable",false,"ssidPresent",false,"bssidPresent",false,"macAddressPresent",false,"frequency",null,"rssi",null,"linkSpeed",null,"cachedScanCount",null,"ssidPresentCount",null,"bssidPresentCount",null,"band2_4GHzCount",null,"band5GHzCount",null,"band6GHzCount",null,"frequencies",new JSONArray(),"frequenciesTruncated",false);
        try{WifiInfo info=wifi.getConnectionInfo();if(info!=null){Json.put(out,"connectionAccess","allowed");Json.put(out,"connectionInfoAvailable",true);String ssid=info.getSSID(),bssid=info.getBSSID(),mac=info.getMacAddress();Json.put(out,"ssidPresent",ssid!=null&&!ssid.isEmpty()&&!"<unknown ssid>".equalsIgnoreCase(ssid));Json.put(out,"bssidPresent",bssid!=null&&!bssid.isEmpty());Json.put(out,"macAddressPresent",mac!=null&&!mac.isEmpty());Json.put(out,"frequency",info.getFrequency());Json.put(out,"rssi",info.getRssi());Json.put(out,"linkSpeed",info.getLinkSpeed());}}catch(SecurityException e){Json.put(out,"connectionAccess","denied");}
        try{List<ScanResult> scans=wifi.getScanResults();if(scans==null)scans=Collections.emptyList();TreeSet<Integer> frequencies=new TreeSet<>();int ssidCount=0,bssidCount=0,band24=0,band5=0,band6=0;for(ScanResult scan:scans){if(scan.SSID!=null&&!scan.SSID.isEmpty())ssidCount++;if(scan.BSSID!=null&&!scan.BSSID.isEmpty())bssidCount++;frequencies.add(scan.frequency);if(scan.frequency>=2400&&scan.frequency<2500)band24++;else if(scan.frequency>=4900&&scan.frequency<5925)band5++;else if(scan.frequency>=5925)band6++;}JSONArray values=new JSONArray();for(Integer frequency:frequencies)if(values.length()<64)values.put(frequency);Json.put(out,"scanAccess","allowed");Json.put(out,"cachedScanCount",scans.size());Json.put(out,"ssidPresentCount",ssidCount);Json.put(out,"bssidPresentCount",bssidCount);Json.put(out,"band2_4GHzCount",band24);Json.put(out,"band5GHzCount",band5);Json.put(out,"band6GHzCount",band6);Json.put(out,"frequencies",values);Json.put(out,"frequenciesTruncated",frequencies.size()>64);}catch(SecurityException e){Json.put(out,"scanAccess","denied");}
        return out;
    }
    // 业务块：记录公开 Wi-Fi 链路能力；MLD 地址只保留存在性，不导出原文。
    private static JSONObject wifiCapabilities(WifiManager wifi){
        if(wifi==null)return Json.obj("available",false,"access","unavailable","wifiStandard",null,"maxSupportedRxLinkSpeedMbps",null,"maxSupportedTxLinkSpeedMbps",null,"apMldMacPresent",null,"apMloLinkId",null,"affiliatedMloLinkCount",null);
        JSONObject out=Json.obj("available",true,"access","unknown","wifiStandard",null,"maxSupportedRxLinkSpeedMbps",null,"maxSupportedTxLinkSpeedMbps",null,"apMldMacPresent",null,"apMloLinkId",null,"affiliatedMloLinkCount",null);
        try{
            WifiInfo info=wifi.getConnectionInfo();
            if(info==null){Json.put(out,"access","empty");return out;}
            Json.put(out,"access","allowed");
            if(Build.VERSION.SDK_INT>=30){Json.put(out,"wifiStandard",info.getWifiStandard());Json.put(out,"maxSupportedRxLinkSpeedMbps",info.getMaxSupportedRxLinkSpeedMbps());Json.put(out,"maxSupportedTxLinkSpeedMbps",info.getMaxSupportedTxLinkSpeedMbps());}
            if(Build.VERSION.SDK_INT>=33){Json.put(out,"apMldMacPresent",info.getApMldMacAddress()!=null);Json.put(out,"apMloLinkId",info.getApMloLinkId());List<?> links=info.getAffiliatedMloLinks();Json.put(out,"affiliatedMloLinkCount",links==null?null:Math.min(16,links.size()));}
        }catch(SecurityException e){Json.put(out,"access","denied");}
        catch(RuntimeException e){Json.put(out,"access","error");Json.put(out,"error",e.getClass().getName());}
        return out;
    }
}
