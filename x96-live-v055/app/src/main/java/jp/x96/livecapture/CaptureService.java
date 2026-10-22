package jp.x96.livecapture;
import android.app.*;
import android.content.*;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
public class CaptureService extends Service {
 public static volatile String status="音声取得：停止中";
 private static final String CH="x96-live-055";
 private final ExecutorService jobs=Executors.newSingleThreadExecutor();
 private final ExecutorService requests=Executors.newFixedThreadPool(2);
 private final List<JSONObject> events=Collections.synchronizedList(new ArrayList<>());
 private volatile int lastId=0;
 private volatile boolean running=false;
 private final long session=System.currentTimeMillis();
 private ServerSocket server;
 private MediaProjection projection;
 private AudioRecord recorder;
 private Model model;
 @Override public void onCreate(){
  super.onCreate();
  if(Build.VERSION.SDK_INT>=26){
   NotificationChannel c=new NotificationChannel(CH,"X96ライブ音声翻訳",NotificationManager.IMPORTANCE_LOW);
   ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
  }
 }
 @Override public int onStartCommand(Intent data,int flags,int id){
  if(data==null || running)return START_NOT_STICKY;
  int flag=PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE;
  PendingIntent pi=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),flag);
  Notification.Builder nb=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CH):new Notification.Builder(this);
  Notification notif=nb.setSmallIcon(android.R.drawable.ic_btn_speak_now)
   .setContentTitle("X96 動画音声取得中").setContentText("停止はアプリ画面から")
   .setContentIntent(pi).setOngoing(true).build();
  startForeground(55,notif);
  status="音声取得：準備中";running=true;
  final Intent captured=data;
  new Thread(this::serve,"x96-local-events").start();
  new Thread(()->startCapture(captured),"x96-speech-recognition").start();
  return START_NOT_STICKY;
 }
 @Override public IBinder onBind(Intent i){return null;}
 private void startCapture(Intent intent){
  try{
   model=new Model(MainActivity.modelPath(this).getAbsolutePath());
   status="英語モデル起動成功。画面音声の許可を確認中";
   MediaProjectionManager mgr=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
   projection=mgr.getMediaProjection(intent.getIntExtra("result",0),intent.getParcelableExtra("projection"));
   if(projection==null)throw new IOException("画面音声の許可を受け取れません");
   projection.registerCallback(new MediaProjection.Callback(){
    @Override public void onStop(){status="録音許可が終了しました";stopSelf();}
   },new Handler(Looper.getMainLooper()));
   int rate=16000;
   int min=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
   if(min<0)throw new IOException("PCM16kHz未対応");
   AudioPlaybackCaptureConfiguration config=new AudioPlaybackCaptureConfiguration.Builder(projection)
     .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
     .addMatchingUsage(AudioAttributes.USAGE_GAME).build();
   recorder=new AudioRecord.Builder().setAudioFormat(new AudioFormat.Builder()
       .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
       .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
       .setAudioPlaybackCaptureConfig(config)
       .setBufferSizeInBytes(Math.max(min,16384)).build();
   if(recorder.getState()!=AudioRecord.STATE_INITIALIZED)throw new IOException("AudioRecord初期化失敗");
   recorder.startRecording();
   status="録音中：Chrome等でDRMなし動画を再生してください";
   try(Recognizer rec=new Recognizer(model,(float)rate)){
    byte[] pcm=new byte[4096];int silent=0;
    while(running){
     int n=recorder.read(pcm,0,pcm.length);
     if(n<=0){
      if(n<0){status="音声取得エラー："+n;break;}
      continue;
     }
     long energy=0;for(int k=0;k+1<n;k+=2){
      int sample=(short)((pcm[k]&255)|((pcm[k+1]&255)<<8));energy+=Math.abs(sample);
     }
     if(energy < (n/2)*85L)silent++;else silent=0;
     if(rec.acceptWaveForm(pcm,n)){
      JSONObject result=new JSONObject(rec.getResult());
      String en=result.optString("text","").trim();
      if(!en.isEmpty())sendTranslation(en);
     }
    }
   }
  }catch(Throwable e){status="録音・認識エラー："+e.getMessage();}
  finally{stopSelf();}
 }
 private void sendTranslation(String en){
  jobs.execute(()->{
   String ja="";String err="";
   try{
    HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:8788/translate").openConnection();
    c.setConnectTimeout(3000);c.setReadTimeout(36000);
    c.setRequestMethod("POST");
    c.setRequestProperty("Content-Type","application/json; charset=utf-8");
    c.setDoOutput(true);
    byte[] bytes=new JSONObject().put("text",en).toString().getBytes(StandardCharsets.UTF_8);
    try(OutputStream out=c.getOutputStream()){out.write(bytes);}
    InputStream input=c.getResponseCode()<400?c.getInputStream():c.getErrorStream();
    try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()){
     byte[] buf=new byte[2048];int n;while((n=in.read(buf))!=-1&&out.size()<16384)out.write(buf,0,n);
     JSONObject r=new JSONObject(out.toString("UTF-8"));
     if(r.optBoolean("ok"))ja=r.optString("ja","");else err=r.optString("error","翻訳失敗");
    }finally{c.disconnect();}
   }catch(Exception e){err="ML Kit翻訳（8788）："+e.getMessage();}
   try{
    JSONObject row=new JSONObject();
    row.put("id",++lastId);row.put("en",en);row.put("ja",ja);row.put("error",err);row.put("at",System.currentTimeMillis());
    synchronized(events){events.add(row);while(events.size()>80)events.remove(0);}
    if(!ja.isEmpty())status="翻訳済み "+lastId+"件 / 動画を再生中";
    else status="文字起こし成功・翻訳未完了："+err;
   }catch(Exception ignored){}
  });
 }
 private void serve(){
  try(ServerSocket s=new ServerSocket()){
   server=s;s.setReuseAddress(true);
   s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),8789));
   while(running){
    try{Socket sock=s.accept();requests.execute(()->reply(sock));}
    catch(Exception e){if(running)status="イベント通信エラー："+e.getMessage();}
   }
  }catch(Exception e){if(running)status="8789番の起動失敗："+e.getMessage();}
 }
 private void reply(Socket socket){
  try(Socket s=socket){
   s.setSoTimeout(5000);
   InputStream in=s.getInputStream();
   ByteArrayOutputStream b=new ByteArrayOutputStream();
   int c;while((c=in.read())!=-1&&c!=10&&b.size()<2048)b.write(c);
   String first=b.toString("UTF-8").trim();
   // Drain the remaining HTTP request headers before writing a response.
   // Closing a socket with unread request bytes can reset the connection.
   int prev=10, count=0, lineBytes=0;
   while((c=in.read())!=-1 && count++<8192){
    if(c==10 && lineBytes<=1)break;
    if(c==10)lineBytes=0;
    else lineBytes++;
   }
   JSONObject response=new JSONObject();
   int code=200;
   if(first.startsWith("GET /health")){
    response.put("ok",true).put("running",running).put("status",status)
      .put("session",session).put("last_id",lastId);
   }else if(first.startsWith("GET /events")){
    int after=0;
    int pos=first.indexOf("after=");
    if(pos>=0){String digits=first.substring(pos+6).split("[^0-9]")[0];
     if(!digits.isEmpty())after=Integer.parseInt(digits);}
    JSONArray list=new JSONArray();
    synchronized(events){for(JSONObject o:events)if(o.optInt("id")>after)list.put(o);}
    response.put("ok",true).put("session",session).put("status",status)
      .put("running",running).put("last_id",lastId).put("events",list);
   }else{code=404;response.put("ok",false).put("error","not found");}
   byte[] payload=response.toString().getBytes(StandardCharsets.UTF_8);
   String head="HTTP/1.1 "+code+" OK\r\nContent-Type: application/json; charset=utf-8\r\n"+
      "Cache-Control: no-store\r\nContent-Length: "+payload.length+"\r\nConnection: close\r\n\r\n";
   OutputStream out=s.getOutputStream();
   out.write(head.getBytes(StandardCharsets.UTF_8));out.write(payload);out.flush();
  }catch(Exception ignored){}
 }
 @Override public void onDestroy(){
  running=false;
  try{if(server!=null)server.close();}catch(Exception ignored){}
  try{if(recorder!=null){recorder.stop();recorder.release();}}catch(Exception ignored){}
  try{if(projection!=null)projection.stop();}catch(Exception ignored){}
  jobs.shutdownNow();requests.shutdownNow();
  try{if(model!=null)model.close();}catch(Exception ignored){}
  if(!status.contains("エラー"))status="音声取得：停止中";
  super.onDestroy();
 }
}
