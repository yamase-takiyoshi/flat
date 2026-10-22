package com.nittakeiba.x96offline;
import android.app.Activity;
import android.content.Intent;
import android.os.*;
import android.graphics.Color;
import android.widget.*;
public class MainActivity extends Activity {
 Handler h=new Handler(Looper.getMainLooper());
 TextView state;
 Runnable check=new Runnable(){public void run(){
  state.setText("状態："+TranslateService.status+"\n翻訳先：英語 → 日本語\n"+
   (TranslateService.ready?"モデル取得済み":"モデル未準備")+"\n端末内：127.0.0.1:8788");
  h.postDelayed(this,1500);
 }};
 @Override public void onCreate(Bundle b){
  super.onCreate(b);
  LinearLayout root=new LinearLayout(this);
  root.setPadding(30,30,30,30);root.setOrientation(1);
  root.setBackgroundColor(Color.rgb(242,247,255));
  TextView title=new TextView(this);title.setText("X96オフライン英日翻訳");
  title.setTextSize(25);root.addView(title);
  state=new TextView(this);state.setTextSize(19);state.setPadding(0,30,0,30);root.addView(state);
  Button start=new Button(this);start.setText("▶ 翻訳開始・初回モデル取得");root.addView(start);
  start.setOnClickListener(v->{Intent i=new Intent(this,TranslateService.class);
   if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);});
  Button stop=new Button(this);stop.setText("■ 翻訳停止");root.addView(stop);
  stop.setOnClickListener(v->stopService(new Intent(this,TranslateService.class)));
  TextView info=new TextView(this);
  info.setText("モデルの初回取得だけ通信が必要です。\n準備完了になったら翻訳時はオフラインです。\nX96で8777番サーバーも起動してください。");
  info.setTextSize(16);info.setPadding(0,25,0,0);root.addView(info);
  setContentView(root);
 }
 @Override protected void onResume(){super.onResume();h.post(check);}
 @Override protected void onPause(){h.removeCallbacks(check);super.onPause();}
}
