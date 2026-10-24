package com.life.fingerprint;

import android.content.*;
import android.net.*;
import android.net.wifi.*;
import android.os.Build;
import android.telephony.*;
import android.bluetooth.*;
import org.json.*;
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
        s.read("network","network/connectivity","连接与链路","ConnectivityManager.getAllNetworks",()->{JSONArray a=new JSONArray();Network active=c.getActiveNetwork();for(Network n:c.getAllNetworks()){NetworkCapabilities cap=c.getNetworkCapabilities(n);LinkProperties link=c.getLinkProperties(n);JSONObject o=Json.obj("handle",n.getNetworkHandle(),"active",n.equals(active),"capabilitiesRaw",cap==null?null:cap.toString());if(cap!=null){JSONArray transports=new JSONArray();for(int type=0;type<=10;type++)if(cap.hasTransport(type))transports.put(type);Json.put(o,"transports",transports);Json.put(o,"downstreamKbps",cap.getLinkDownstreamBandwidthKbps());Json.put(o,"upstreamKbps",cap.getLinkUpstreamBandwidthKbps());}if(link!=null){JSONArray addresses=new JSONArray(),dns=new JSONArray(),routes=new JSONArray();for(LinkAddress x:link.getLinkAddresses())addresses.put(Json.obj("address",x.getAddress().getHostAddress(),"prefixLength",x.getPrefixLength(),"flags",x.getFlags(),"scope",x.getScope()));for(InetAddress x:link.getDnsServers())dns.put(x.getHostAddress());for(RouteInfo x:link.getRoutes())routes.put(Json.obj("destination",x.getDestination().toString(),"gateway",x.getGateway()==null?null:x.getGateway().getHostAddress(),"interface",x.getInterface(),"default",x.isDefaultRoute()));Json.put(o,"link",Json.obj("interfaceName",link.getInterfaceName(),"domains",link.getDomains(),"mtu",Build.VERSION.SDK_INT>=29?link.getMtu():null,"addresses",addresses,"dns",dns,"routes",routes,"httpProxy",link.getHttpProxy()==null?null:link.getHttpProxy().toString(),"raw",link.toString()));}a.put(o);}return a;});
        s.read("network","network/interfaces","网络接口","NetworkInterface.getNetworkInterfaces",()->{JSONArray a=new JSONArray();Enumeration<NetworkInterface> all=NetworkInterface.getNetworkInterfaces();if(all!=null)while(all.hasMoreElements()){NetworkInterface n=all.nextElement();JSONArray addresses=new JSONArray();for(InterfaceAddress x:n.getInterfaceAddresses())addresses.put(Json.obj("address",x.getAddress().getHostAddress(),"prefixLength",x.getNetworkPrefixLength(),"broadcast",x.getBroadcast()==null?null:x.getBroadcast().getHostAddress()));byte[] mac=null;String error=null;try{mac=n.getHardwareAddress();}catch(Exception e){error=e.toString();}a.put(Json.obj("name",n.getName(),"displayName",n.getDisplayName(),"index",n.getIndex(),"up",n.isUp(),"loopback",n.isLoopback(),"virtual",n.isVirtual(),"pointToPoint",n.isPointToPoint(),"mtu",n.getMTU(),"hardwareAddressBase64",mac==null?null:android.util.Base64.encodeToString(mac,android.util.Base64.NO_WRAP),"hardwareAddressError",error,"addresses",addresses));}return a;});
        WifiManager wifi=s.context.getApplicationContext().getSystemService(WifiManager.class);
        s.read("network","network/wifi","当前 Wi-Fi","WifiManager.getConnectionInfo",()->{WifiInfo i=wifi.getConnectionInfo();JSONObject o=Json.obj("state",wifi.getWifiState(),"ssid",i.getSSID(),"bssid",i.getBSSID(),"macAddress",i.getMacAddress(),"rssi",i.getRssi(),"frequency",i.getFrequency(),"linkSpeed",i.getLinkSpeed(),"networkId",i.getNetworkId(),"ipAddress",i.getIpAddress(),"supplicantState",i.getSupplicantState().name(),"raw",i.toString());if(Build.VERSION.SDK_INT>=29){Json.put(o,"txLinkSpeedMbps",i.getTxLinkSpeedMbps());Json.put(o,"rxLinkSpeedMbps",i.getRxLinkSpeedMbps());}return o;});
        s.read("network","network/wifi-cached-scan","系统已有 Wi-Fi 缓存（不发起扫描）","WifiManager.getScanResults",()->{JSONArray a=new JSONArray();for(ScanResult i:wifi.getScanResults())a.put(Json.obj("ssid",i.SSID,"bssid",i.BSSID,"capabilities",i.capabilities,"frequency",i.frequency,"level",i.level,"timestamp",i.timestamp,"channelWidth",i.channelWidth,"centerFreq0",i.centerFreq0,"centerFreq1",i.centerFreq1));return a;});
        s.read("network","network/proxy","默认代理","ConnectivityManager.getDefaultProxy",()->{ProxyInfo p=c.getDefaultProxy();return p==null?null:Json.obj("host",p.getHost(),"port",p.getPort(),"exclusionList",p.getExclusionList(),"pacFileUrl",p.getPacFileUrl().toString());});
        s.read("network","network/bluetooth","蓝牙及已配对设备","BluetoothManager / BluetoothAdapter.getBondedDevices",()->{BluetoothManager m=s.context.getSystemService(BluetoothManager.class);BluetoothAdapter b=m==null?null:m.getAdapter();if(b==null)return null;JSONArray devices=new JSONArray();for(BluetoothDevice d:b.getBondedDevices())devices.put(Json.obj("address",d.getAddress(),"name",d.getName(),"type",d.getType(),"bondState",d.getBondState()));return Json.obj("name",b.getName(),"address",b.getAddress(),"state",b.getState(),"pairedDevices",devices);});
        for(String p:new String[]{"/proc/net/route","/proc/net/ipv6_route","/proc/net/if_inet6","/proc/net/arp","/proc/net/dev"})s.file("network","network/file"+p,p);
    }
}
