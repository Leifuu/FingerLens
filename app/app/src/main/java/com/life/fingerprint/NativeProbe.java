package com.life.fingerprint;

public final class NativeProbe {
    static { System.loadLibrary("fingerprint"); }
    private NativeProbe() {}
    public static native String readFile(String path, int limit);
    public static native String inspectPath(String path);
    public static native String listDirectory(String path, int limit);
    public static native String properties();
    public static native String kernel();
    public static native String graphics();
}
