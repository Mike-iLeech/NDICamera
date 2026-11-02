package ru.ileech.ndicamera;

import android.app.*;
import android.app.job.*;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

final class Updates {
 static final String REPO="Mike-iLeech/NDICamera";
 static final String MANIFEST="https://github.com/"+REPO+"/releases/latest/download/update.json";
 static final int JOB=1401;
 static final long CHECK_INTERVAL_MS=24*60*60*1000L;
 static final AtomicBoolean busy=new AtomicBoolean();
 static volatile String status="Проверка ещё не выполнялась";
 private static long offeredVersion=-1;
 private static final Handler main=new Handler(Looper.getMainLooper());
 static android.content.SharedPreferences prefs(Context c){return c.getSharedPreferences("settings",0);}
 static File apk(Context c){return new File(c.getFilesDir(),"update.apk");}
 static void schedule(Context c){
  JobScheduler scheduler=c.getSystemService(JobScheduler.class);
  if(!prefs(c).getBoolean("autoUpdate",true)){scheduler.cancel(JOB);return;}
  JobInfo existing=scheduler.getPendingJob(JOB);
  if(existing==null||existing.getIntervalMillis()!=CHECK_INTERVAL_MS)scheduler.schedule(new JobInfo.Builder(JOB,new ComponentName(c,UpdateJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(CHECK_INTERVAL_MS).setPersisted(true).build());
 }
 static final class Request { volatile boolean canceled; volatile HttpURLConnection connection; void cancel(){canceled=true;HttpURLConnection c=connection;if(c!=null)c.disconnect();} }
 static Request check(Context context,boolean manual,Runnable done){
  Request request=new Request();
  Context c=context.getApplicationContext();
  if(!manual&&(!prefs(c).getBoolean("autoUpdate",true)||System.currentTimeMillis()-prefs(c).getLong("lastUpdateCheck",0)<CHECK_INTERVAL_MS)){if(done!=null)main.post(()->{if(!request.canceled)done.run();});return request;}
  if(!busy.compareAndSet(false,true)){if(done!=null)main.post(()->{if(!request.canceled)done.run();});return request;}
  if(manual)offeredVersion=-1;
  prefs(c).edit().putLong("lastUpdateCheck",System.currentTimeMillis()).apply();
  status="Проверка GitHub Releases…";
  new Thread(()->{
   File temp=new File(c.getFilesDir(),"update.apk.part");
   try{
    JSONObject manifest;
    try(InputStream input=open(MANIFEST,request);ByteArrayOutputStream output=new ByteArrayOutputStream()){
     byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))!=-1){if(output.size()+n>65536)throw new IOException("Слишком большой манифест");output.write(buffer,0,n);}manifest=new JSONObject(output.toString("UTF-8"));
    }
    long installed=c.getPackageManager().getPackageInfo(c.getPackageName(),0).getLongVersionCode();
    long version=manifest.getLong("versionCode");
    if(version<=installed){status="Установлена последняя версия";return;}
    if(apk(c).isFile()&&valid(c,apk(c),version)){status="Обновление готово к установке";notifyReady(c);return;}
    String url=manifest.getString("apkUrl"),digest=manifest.getString("sha256");
    URI uri=new URI(url);
    if(!"https".equals(uri.getScheme())||!"github.com".equals(uri.getHost())||!uri.getPath().startsWith("/"+REPO+"/releases/download/")||!digest.matches("[a-fA-F0-9]{64}"))throw new IOException("Некорректный релиз");
    status="Загрузка обновления…";
    MessageDigest sha=MessageDigest.getInstance("SHA-256");long size=0;
    try(InputStream input=open(url,request);OutputStream output=new FileOutputStream(temp)){
     byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1){
      if(request.canceled)throw new IOException("Проверка отложена");
      if(!manual&&!prefs(c).getBoolean("autoUpdate",true))throw new IOException("Автообновление выключено");
      size+=n;if(size>100*1024*1024)throw new IOException("APK больше 100 МБ");sha.update(buffer,0,n);output.write(buffer,0,n);
     }
    }
    if(!hex(sha.digest()).equalsIgnoreCase(digest)||!valid(c,temp,version))throw new IOException("Не совпадает подпись, версия или SHA-256 APK");
    if(!temp.renameTo(apk(c)))throw new IOException("Не удалось сохранить обновление");
    status="Обновление готово к установке";notifyReady(c);
   }catch(Exception e){temp.delete();status=request.canceled?"Проверка отложена":"Не удалось обновить: "+e.getMessage();if(request.canceled)prefs(c).edit().putLong("lastUpdateCheck",0).apply();}
   finally{request.connection=null;busy.set(false);if(done!=null)main.post(()->{if(!request.canceled)done.run();});}
  },"GitHub release updater").start();
  return request;
 }
 private static InputStream open(String address,Request request)throws Exception{
  URL url=new URL(address);
  for(int redirect=0;redirect<6;redirect++){
   if(request.canceled)throw new IOException("Проверка отложена");
   String host=url.getHost();
   if(!"https".equals(url.getProtocol())||!(host.equals("github.com")||host.equals("release-assets.githubusercontent.com")||host.equals("objects.githubusercontent.com")))throw new IOException("Недопустимый адрес обновления");
   HttpURLConnection connection=(HttpURLConnection)url.openConnection();connection.setConnectTimeout(15000);connection.setReadTimeout(20000);connection.setInstanceFollowRedirects(false);connection.setRequestProperty("User-Agent","NDICamera-Android");
   request.connection=connection;if(request.canceled){connection.disconnect();throw new IOException("Проверка отложена");}
   int code=connection.getResponseCode();
   if(code>=300&&code<400){String location=connection.getHeaderField("Location");connection.disconnect();if(location==null)throw new IOException("Нет адреса загрузки");url=new URL(url,location);continue;}
   if(code!=200){connection.disconnect();throw new IOException("GitHub HTTP "+code);}
   return new FilterInputStream(connection.getInputStream()){@Override public void close()throws IOException{try{super.close();}finally{connection.disconnect();}}};
  }
  throw new IOException("Слишком много перенаправлений");
 }
 static boolean valid(Context c,File file,long expected){
  try{
   PackageManager pm=c.getPackageManager();PackageInfo candidate=pm.getPackageArchiveInfo(file.getAbsolutePath(),PackageManager.GET_SIGNING_CERTIFICATES),installed=pm.getPackageInfo(c.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES);
   if(candidate==null||!c.getPackageName().equals(candidate.packageName)||candidate.getLongVersionCode()<=installed.getLongVersionCode()||(expected>0&&candidate.getLongVersionCode()!=expected)||candidate.signingInfo==null)return false;
   return signers(candidate).equals(signers(installed));
  }catch(Exception e){return false;}
 }
 private static Set<String> signers(PackageInfo info)throws Exception{Set<String> result=new HashSet<>();for(Signature s:info.signingInfo.getApkContentsSigners())result.add(hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray())));return result;}
 private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.US,"%02x",b&255));return s.toString();}
 private static void notifyReady(Context c){
  NotificationManager nm=c.getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("updates","Обновления приложения",NotificationManager.IMPORTANCE_DEFAULT));
  PendingIntent open=PendingIntent.getActivity(c,14,new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  try{nm.notify(14,new Notification.Builder(c,"updates").setSmallIcon(ru.ileech.ndicamera.R.drawable.ic_launcher).setContentTitle("Доступно обновление NDI Camera").setContentText("Нажмите, чтобы установить").setContentIntent(open).setAutoCancel(true).build());}catch(SecurityException ignored){}
 }
 static void offer(Activity a){
  if(a.isFinishing()||a.isDestroyed()||StreamSession.running||busy.get())return;
  File file=apk(a);if(!file.isFile())return;
  try{
   PackageInfo info=a.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(),0);if(info==null||info.getLongVersionCode()==offeredVersion)return;
   long version=info.getLongVersionCode();if(!valid(a,file,version)){file.delete();return;}
   if(prefs(a).getBoolean("pendingInstall",false)&&a.getPackageManager().canRequestPackageInstalls()){prefs(a).edit().putBoolean("pendingInstall",false).apply();offeredVersion=version;install(a);return;}
   offeredVersion=version;
   new AlertDialog.Builder(a).setTitle("Обновление "+info.versionName).setMessage("APK загружен и проверен. Установить обновление?")
    .setNegativeButton("Позже",null).setPositiveButton("Установить",(d,w)->install(a)).show();
  }catch(Exception ignored){}
 }
 private static void install(Activity a){
  if(!valid(a,apk(a),0)){Toast.makeText(a,"APK не прошёл проверку",Toast.LENGTH_LONG).show();return;}
  if(!a.getPackageManager().canRequestPackageInstalls()){
   prefs(a).edit().putBoolean("pendingInstall",true).apply();offeredVersion=-1;
   a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+a.getPackageName())));return;
  }
  a.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://"+a.getPackageName()+".updates/apk"),"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
 }
}
