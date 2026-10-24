package com.life.fingerprint;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends AppCompatActivity {
    private CollectionController controller;
    private TextView status,permissions;
    private ProgressBar progress;
    private Button start,cancel,export,zip,share;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private File pendingExport;
    private final Runnable refresh=new Runnable(){public void run(){if(!status.getText().toString().equals(controller.text))status.setText(controller.text);if(progress.getProgress()!=controller.progress)progress.setProgress(controller.progress);start.setEnabled(!controller.running);cancel.setEnabled(controller.running);boolean ready=controller.reportFile!=null&&!controller.running;export.setEnabled(ready);zip.setEnabled(ready);share.setEnabled(ready);ui.postDelayed(this,500);}};
    @Override public void onCreate(Bundle saved){super.onCreate(saved);controller=CollectionController.get(this);buildUi();}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView label(String text,int size,int color){TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setTextColor(color);v.setPadding(0,dp(8),0,dp(8));return v;}
    private Button button(LinearLayout root,String title,View.OnClickListener click){Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setMinHeight(dp(48));root.addView(b,new LinearLayout.LayoutParams(-1,-2));b.setOnClickListener(click);return b;}
    private void buildUi(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(Color.rgb(12,19,29));LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(24),dp(20),dp(24),dp(32));scroll.addView(root);
        TextView eyebrow=label("DEVICE EVIDENCE / 01",12,Color.rgb(83,207,199));root.addView(eyebrow);root.addView(label("设备指纹采集台",28,Color.WHITE));root.addView(label("普通 App 视角 · 单次快照 · 原值保留\n仅在本地保存，不主动联网。",14,Color.rgb(177,190,206)));
        root.addView(new OrientationButton(this),new LinearLayout.LayoutParams(-1,-2));
        permissions=label("",13,Color.rgb(177,190,206));root.addView(permissions);
        button(root,"1. 准备采集权限",v->requestPermissions());button(root,"2. 授予所有文件访问（可选）",v->{try{if(Build.VERSION.SDK_INT>=30)startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:"+getPackageName())));else Toast.makeText(this,"当前系统使用存储运行时权限",Toast.LENGTH_SHORT).show();}catch(Exception e){Toast.makeText(this,"当前系统不提供该授权页面",Toast.LENGTH_LONG).show();}});
        start=button(root,"开始采集",v->controller.start());cancel=button(root,"取消采集并保留已完成结果",v->controller.cancel());
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(false);progress.setMax(100);root.addView(progress,new LinearLayout.LayoutParams(-1,dp(14)));
        status=label("",14,Color.rgb(218,228,239));status.setTextIsSelectable(true);root.addView(status);
        export=button(root,"导出原始 JSON",v->chooseExport(false));zip=button(root,"导出 gzip 压缩副本",v->chooseExport(true));share=button(root,"分享原始 JSON",v->{File f=controller.reportFile;if(f==null)return;Uri uri=FileProvider.getUriForFile(this,getPackageName()+".files",f);Intent send=new Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(send,"分享指纹"));});
        root.addView(label("受限、空值与不支持会分别记录。\n目录只列元数据，不读取用户文件正文。\n检测线索不等同于第三方 App 风控结论。",12,Color.rgb(143,160,181)));setContentView(scroll);
    }
    private void requestPermissions(){ArrayList<String> missing=new ArrayList<>();for(String p:CollectionController.requestedPermissions())if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED)missing.add(p);if(missing.isEmpty()){Toast.makeText(this,"普通权限已准备",Toast.LENGTH_SHORT).show();return;}requestPermissions(missing.toArray(new String[0]),10);}
    private void chooseExport(boolean compressed){File source=controller.reportFile;if(source==null)return;io.execute(()->{try{File f=compressed?ReportStore.gzip(source):source;runOnUiThread(()->{pendingExport=f;Intent create=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(compressed?"application/gzip":"application/json").putExtra(Intent.EXTRA_TITLE,f.getName());startActivityForResult(create,20);});}catch(Exception e){runOnUiThread(()->Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show());}});}
    @Override protected void onActivityResult(int request,int result,Intent intent){super.onActivityResult(request,result,intent);if(request!=20||result!=RESULT_OK||intent==null||intent.getData()==null||pendingExport==null)return;File source=pendingExport;Uri uri=intent.getData();io.execute(()->{try(InputStream in=new FileInputStream(source);OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("无法打开目标文件");byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);runOnUiThread(()->Toast.makeText(this,"导出完成",Toast.LENGTH_SHORT).show());}catch(Exception e){runOnUiThread(()->Toast.makeText(this,"导出失败："+e.getMessage(),Toast.LENGTH_LONG).show());}});}
    @Override protected void onResume(){super.onResume();int granted=0;String[] requested=CollectionController.requestedPermissions();for(String p:requested)if(checkSelfPermission(p)==PackageManager.PERMISSION_GRANTED)granted++;permissions.setText("普通权限 "+granted+" / "+requested.length+" · 所有文件访问 "+(Build.VERSION.SDK_INT>=30?(Environment.isExternalStorageManager()?"已授权":"未授权"):"系统不适用"));ui.post(refresh);}
    @Override protected void onPause(){ui.removeCallbacks(refresh);super.onPause();}
    @Override protected void onDestroy(){io.shutdown();super.onDestroy();}
}
