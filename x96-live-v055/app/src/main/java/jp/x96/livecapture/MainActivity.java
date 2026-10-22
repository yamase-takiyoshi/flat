package jp.x96.livecapture;
import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.zip.*;
public class MainActivity extends Activity {
 public static volatile String modelStatus="英語認識モデル：未取得";
 private static final String MODEL_URL="https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip";
 private static final String MODEL_NAME="vosk-model-small-en-us-0.15";
 private final Handler handler=new Handler(Looper.getMainLooper());
 private TextView info;
 private volatile boolean downloading=false;
 private final Runnable ui=new Runnable(){public void run(){
  info.setText(modelStatus+"\n"+
   CaptureService.status+"\n"+
   "端末内認識→8788番の英日翻訳→8777番のiPad");
  handler.postDelayed(this,1200);
 }};
 public static File modelPath(Context ctx){return new File(ctx.getFilesDir(),MODEL_NAME);}
 @Override public void onCreate(Bundle b){
  super.onCreate(b);
  LinearLayout root=new LinearLayout(this);root.setOrientation(1);
  root.setPadding(26,26,26,26);root.setBackgroundColor(Color.rgb(240,247,255));
  TextView title=new TextView(this);title.setText("X96 動画音声 → 日本語\nv0.5.5");
  title.setTextSize(25);root.addView(title);
  info=new TextView(this);info.setTextSize(17);info.setPadding(0,25,0,28);root.addView(info);
  Button model=new Button(this);model.setText("① 英語認識モデルを取得（初回約40MB）");root.addView(model);
  model.setOnClickListener(v->downloadModel());
  Button start=new Button(this);start.setText("② DRMなしの動画音声を取得開始");root.addView(start);
  start.setOnClickListener(v->capturePermission());
  Button stop=new Button(this);stop.setText("■ 音声取得を停止");root.addView(stop);
  stop.setOnClickListener(v->stopService(new Intent(this,CaptureService.class)));
  TextView help=new TextView(this);help.setTextSize(15);help.setPadding(0,18,0,0);
  help.setText("使用前にX96オフライン翻訳アプリを起動し、ML Kit準備完了にします。"+
   "\n録音許可画面で承認し、その後Chromeや動画アプリに切り替えて再生してください。"+
   "\n対応は動画アプリの録音許可によります。録音不可の動画を回避する機能ではありません。"+
   "\niPadは既存の8777番を開いてライブ翻訳をON。");
  root.addView(help);
  ScrollView scroll=new ScrollView(this);scroll.addView(root);setContentView(scroll);
  if(modelPath(this).isDirectory())modelStatus="英語認識モデル：取得済み";
 }
 private void capturePermission(){
  if(!modelPath(this).isDirectory()){modelStatus="最初に①モデル取得を実行してください";return;}
  if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
   requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},12);return;
  }
  MediaProjectionManager m=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
  startActivityForResult(m.createScreenCaptureIntent(),21);
 }
 @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){
  super.onRequestPermissionsResult(r,p,g);
  if(r==12&&g.length>0&&g[0]==PackageManager.PERMISSION_GRANTED)capturePermission();
  else modelStatus="録音許可が必要です";
 }
 @Override protected void onActivityResult(int request,int result,Intent data){
  super.onActivityResult(request,result,data);
  if(request==21&&result==RESULT_OK&&data!=null){
   Intent svc=new Intent(this,CaptureService.class);
   svc.putExtra("result",result);svc.putExtra("projection",data);
   if(Build.VERSION.SDK_INT>=26)startForegroundService(svc);else startService(svc);
  }else if(request==21)modelStatus="画面音声の取得が許可されませんでした";
 }
 private void downloadModel(){
  if(downloading)return;
  if(modelPath(this).isDirectory()){modelStatus="英語認識モデル：取得済み";return;}
  downloading=true;modelStatus="英語認識モデルをダウンロードしています…";
  new Thread(()->{
   File zip=new File(getCacheDir(),"english-model.zip");
   File tmp=new File(getFilesDir(),"model-tmp");
   try{
    HttpURLConnection c=(HttpURLConnection)new URL(MODEL_URL).openConnection();
    c.setConnectTimeout(15000);c.setReadTimeout(30000);
    c.setInstanceFollowRedirects(true);
    if(c.getResponseCode()!=200)throw new IOException("HTTP "+c.getResponseCode());
    try(InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(zip)){
     byte[] buf=new byte[32768];int n;long size=0;
     while((n=in.read(buf))!=-1){size+=n;if(size>100L*1024*1024)throw new IOException("ZIPが大きすぎます");
      out.write(buf,0,n); if(size%(5*1024*1024)<32768)modelStatus="英語モデル取得中 "+(size/1048576)+"MB";}
    }finally{c.disconnect();}
    remove(tmp);if(!tmp.mkdirs())throw new IOException("展開先の作成失敗");
    long total=0;
    try(ZipInputStream zin=new ZipInputStream(new FileInputStream(zip))){
     ZipEntry e;byte[] buf=new byte[16384];
     while((e=zin.getNextEntry())!=null){
      if(!e.getName().startsWith(MODEL_NAME+"/"))continue;
      File out=new File(tmp,e.getName().substring(MODEL_NAME.length()+1));
      String base=tmp.getCanonicalPath()+File.separator;
      if(!out.getCanonicalPath().startsWith(base))throw new IOException("ZIPパスが不正");
      if(e.isDirectory()){out.mkdirs();continue;}
      if(!out.getParentFile().exists()&&!out.getParentFile().mkdirs())throw new IOException("フォルダ作成失敗");
      try(FileOutputStream f=new FileOutputStream(out)){
       int n;while((n=zin.read(buf))!=-1){total+=n;if(total>270L*1024*1024)throw new IOException("展開容量超過");f.write(buf,0,n);}
      }
     }
    }
    if(!new File(tmp,"am/final.mdl").exists()||!new File(tmp,"conf/model.conf").exists())
      throw new IOException("モデル内容が不完全です");
    if(!tmp.renameTo(modelPath(this)))throw new IOException("モデルの保存失敗");
    modelStatus="英語認識モデル：取得済み。②を押してください";
   }catch(Exception e){modelStatus="モデル取得失敗："+e.getMessage();remove(tmp);}
   finally{zip.delete();downloading=false;}
  }).start();
 }
 private static void remove(File f){
  if(f.isDirectory()){File[] children=f.listFiles();if(children!=null)for(File c:children)remove(c);}
  f.delete();
 }
 @Override protected void onResume(){super.onResume();handler.post(ui);}
 @Override protected void onPause(){handler.removeCallbacks(ui);super.onPause();}
}
