package com.life.fingerprint;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

public final class ReportStore {
    public static final int MAX_BYTES=64*1024*1024;
    private ReportStore(){}
    public static File exports(Context c){File root=c.getExternalFilesDir(null);if(root==null)root=c.getFilesDir();File dir=new File(root,"exports");if(!dir.exists()&&!dir.mkdirs())throw new IllegalStateException("无法创建导出目录");return dir;}
    public static File part(Context c,String session,String module){if(!session.matches("[a-f0-9-]{36}")||!module.matches("[a-z]+"))throw new IllegalArgumentException("非法内部路径");File dir=new File(c.getFilesDir(),"parts/"+session);if(!dir.exists()&&!dir.mkdirs())throw new IllegalStateException("无法创建工作目录");return new File(dir,module+".json");}
    public static void write(File file,String text)throws IOException {
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_BYTES)throw new IOException("指纹超过64MiB上限");AtomicFile atomic=new AtomicFile(file);FileOutputStream out=null;try{out=atomic.startWrite();out.write(bytes);atomic.finishWrite(out);}catch(IOException e){atomic.failWrite(out);throw e;}
    }
    public static String read(File file)throws IOException {if(file.length()>MAX_BYTES)throw new IOException("文件过大");try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>MAX_BYTES)throw new IOException("文件过大");out.write(buf,0,n);}return new String(out.toByteArray(),StandardCharsets.UTF_8);}}
    public static File gzip(File source)throws IOException {File target=new File(source.getParentFile(),source.getName()+".gz");AtomicFile atomic=new AtomicFile(target);FileOutputStream raw=null;try{raw=atomic.startWrite();GZIPOutputStream zip=new GZIPOutputStream(raw);try(InputStream in=new FileInputStream(source)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)zip.write(b,0,n);}zip.finish();zip.flush();atomic.finishWrite(raw);return target;}catch(IOException e){atomic.failWrite(raw);throw e;}}
    public static String sha256(File source)throws Exception {java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(source)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return hex.toString();}
}
