package com.life.fingerprint;

public final class Collectors {
    public static final String[] MODULES={"build","packages","input","display","binder","kernel","storage","hardware","sensors","battery","sim","network","environment","security","attestation","virtualization","directories"};
    public static final String[] NAMES={"Build 与属性","应用清单","输入设备","屏幕显示","Binder 服务","内核与进程","分区与存储","硬件与图形","传感器","电池","SIM 与电话","网络","语言与环境","安全与完整性","密钥认证","虚拟化线索","目录清单"};
    private Collectors(){}
    public static Collector get(String module){switch(module){case "packages":case "input":case "display":return new PackageInputCollector(module);case "hardware":case "sensors":case "battery":return new HardwareCollector(module);case "sim":case "network":return new NetworkCollector(module);case "security":case "attestation":case "virtualization":return new SecurityCollector(module);case "directories":return new DirectoryCollector();case "build":case "binder":case "kernel":case "storage":case "environment":return new BuildSystemCollector(module);default:throw new IllegalArgumentException("Unknown module");}}
    public static int timeout(String module){return module.equals("directories")?60000:module.equals("attestation")?30000:15000;}
}
