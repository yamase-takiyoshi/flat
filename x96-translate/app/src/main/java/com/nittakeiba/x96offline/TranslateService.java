package com.nittakeiba.x96offline;
import android.app.*;
import android.content.Intent;
import android.os.*;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.*;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.*;
public class TranslateService extends Service {
 public static volatile String status="停止中";
 public static volatile boolean ready=false;
 private volatile boolean run=false;
 private Translator translator;
 private ServerSocket listener;
 private final ExecutorService pool=Executors.newFixedThreadPool(3);
 private final Object lock=new Object();
 @Override public void onCreate(){
  super.onCreate();run=true;ready=false;status="モデル確認中";
  String channel="x96offline";
  if(Build.VERSION.SDK_INT>=26){
   NotificationChannel ch=new NotificationChannel(channel,"X96オフライン翻訳",NotificationManager.IMPORTANCE_LOW);
   ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
  }
  int flag=PendingIntent.FLAG_UPDATE_CURRENT;
  if(Build.VERSION.SDK_INT>=23)flag|=PendingIntent.FLAG_IMMUTABLE;
  PendingIntent pi=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),flag);
  Notification.Builder nb=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,channel):new Notification.Builder(this);
  startForeground(8788,nb.setSmallIcon(android.R.drawable.stat_notify_sync)
   .setContentTitle("X96オフライン翻訳").setContentText("英語 → 日本語")
   .setContentIntent(pi).setOngoing(true).build());
  TranslatorOptions o=new TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.ENGLISH)
   .setTargetLanguage(TranslateLanguage.JAPANESE).build();
  translator=Translation.getClient(o);
  translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
   .addOnSuccessListener(v->{ready=true;status="準備完了：オフライン翻訳可能";})
   .addOnFailureListener(e->{status="モデル取得失敗："+e.getMessage();});
  new Thread(this::listen).start();
 }
 @Override public IBinder onBind(Intent i){return null;}
 @Override public int onStartCommand(Intent i,int flags,int id){return START_STICKY;}
 private void listen(){
  try(ServerSocket s=new ServerSocket()){
   listener=s;s.setReuseAddress(true);
   s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),8788));
   while(run){
    try{Socket c=s.accept();pool.execute(()->respond(c));}
    catch(Exception e){if(run)status="接続エラー："+e.getMessage();}
   }
  }catch(Exception e){if(run)status="ポート8788エラー："+e.getMessage();}
 }
 private void respond(Socket c){
  try(Socket socket=c){
   socket.setSoTimeout(39000);
   InputStream in=socket.getInputStream();OutputStream out=socket.getOutputStream();
   ByteArrayOutputStream head=new ByteArrayOutputStream();
   int b,matched=0;byte[] sep={13,10,13,10};
   while((b=in.read())!=-1){
    head.write(b);matched=b==sep[matched]?matched+1:(b==13?1:0);
    if(matched==4)break;
    if(head.size()>8192)return;
   }
   if(matched!=4)return;
   String[] lines=head.toString("UTF-8").split("\\r\\n");
   int length=0;
   for(String line:lines){
    if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))
     length=Integer.parseInt(line.substring(15).trim());
   }
   int code=200;JSONObject obj=new JSONObject();
   try {
    if(lines[0].startsWith("GET /health ")){
     obj.put("ok",true);obj.put("ready",ready);obj.put("status",status);
    }else if(lines[0].startsWith("POST /translate ")){
     if(length<1||length>8192)throw new IllegalArgumentException("invalid length");
     byte[] data=new byte[length];int n=0;
     while(n<length){int k=in.read(data,n,length-n);if(k<0)throw new IOException("truncated");n+=k;}
     String english=new JSONObject(new String(data,StandardCharsets.UTF_8)).optString("text","").trim();
     if(english.isEmpty()||english.length()>2000)throw new IllegalArgumentException("invalid text");
     if(!ready){code=503;obj.put("ok",false);obj.put("error",status);}
     else {
      String ja;
      synchronized(lock){ja=Tasks.await(translator.translate(english),32,TimeUnit.SECONDS);}
      obj.put("ok",true);obj.put("ja",ja);
     }
    }else{code=404;obj.put("ok",false);obj.put("error","not found");}
   }catch(Exception e){code=503;obj=new JSONObject();obj.put("ok",false);obj.put("error",String.valueOf(e.getMessage()));}
   byte[] data=obj.toString().getBytes(StandardCharsets.UTF_8);
   String label=code==200?"OK":"Error";
   String headers="HTTP/1.1 "+code+" "+label+"\r\n"+
    "Content-Type: application/json; charset=utf-8\r\n"+
    "Content-Length: "+data.length+"\r\nConnection: close\r\n\r\n";
   out.write(headers.getBytes(StandardCharsets.UTF_8));out.write(data);out.flush();
  }catch(Exception ignored){}
 }
 @Override public void onDestroy(){
  run=false;ready=false;status="停止中";
  try{if(listener!=null)listener.close();}catch(Exception ignored){}
  pool.shutdownNow();if(translator!=null)translator.close();super.onDestroy();
 }
}
