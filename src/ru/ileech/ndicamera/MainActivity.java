package ru.ileech.ndicamera;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.content.res.Configuration;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.display.DisplayManager;
import android.graphics.RectF;
import android.opengl.GLSurfaceView;
import android.os.*;
import android.util.Size;
import android.view.*;
import android.widget.*;
import java.util.*;

public class MainActivity extends Activity implements CameraHud.Actions {
 private CameraController camera;private CameraRenderer renderer;private GLSurfaceView preview;private CameraHud hud;private FrameLayout root;
 private StreamSession stream;private final Handler main=new Handler();private boolean resumed,locked,muted,initial=true,starting;private String error="";private int mode;
 private final DisplayManager.DisplayListener displayListener=new DisplayManager.DisplayListener(){public void onDisplayAdded(int id){}public void onDisplayRemoved(int id){}public void onDisplayChanged(int id){updateRotation();}};
 private void updateRotation(){if(renderer!=null){renderer.displayRotation=getWindowManager().getDefaultDisplay().getRotation();preview.requestRender();}}
 private final Runnable refresh=new Runnable(){public void run(){if(hud==null)return;if(stream!=null){stream.telemetry();if(!stream.error.isEmpty()){String e=stream.error;stop();message(e);}}hud.update(stream!=null,muted,Updates.prefs(MainActivity.this).getBoolean("showBitrate",true),locked,source(),stream);Updates.offer(MainActivity.this);main.postDelayed(this,100);}};
 private String source(){return Updates.prefs(this).getString("deviceName",Build.MODEL+" Camera");}
 @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);getWindow().setStatusBarColor(0xff0f172a);getWindow().setNavigationBarColor(0xff0f172a);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
  if(Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);WindowManager.LayoutParams window=getWindow().getAttributes();window.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;getWindow().setAttributes(window);
  if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO},10);TextView hint=new TextView(this);hint.setText("Для NDI Camera разрешите доступ к камере. Микрофон нужен для звука в потоке.");hint.setTextColor(-1);hint.setBackgroundColor(0xff0f172a);hint.setPadding(32,60,32,32);setContentView(hint);hint.setOnClickListener(v->requestPermissions(new String[]{Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO},10));}else initialize();
 }
 private void initialize(){if(camera!=null)return;try{
  camera=new CameraController(this,new CameraController.Listener(){public void changed(){main.post(()->{if(hud!=null)hud.invalidate();});}public void failure(String message){main.post(()->{stop();error=message;message(message);});}});mode=camera.mainFront?1:0;
  root=new FrameLayout(this);root.setBackgroundColor(0xff0f172a);preview=new GLSurfaceView(this);preview.setEGLContextClientVersion(2);preview.setPreserveEGLContextOnPause(true);renderer=new CameraRenderer(preview,camera,(rgb,vector)->{if(hud!=null)hud.scopes(rgb,vector);});preview.setRenderer(renderer);preview.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);root.addView(preview,new FrameLayout.LayoutParams(-1,-1));hud=new CameraHud(this,camera,this);root.addView(hud,new FrameLayout.LayoutParams(-1,-1));setContentView(root);renderer.displayRotation=getWindowManager().getDefaultDisplay().getRotation();choose(camera.active,false,camera.mainFront,720,30);
  if(resumed){preview.onResume();main.removeCallbacks(refresh);main.post(refresh);}Updates.schedule(this);Updates.check(this,false,null);
 }catch(Exception e){message("Ошибка запуска: "+e.getMessage());}}
 @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==10){if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)initialize();else message("Без разрешения камеры предпросмотр недоступен. Нажмите на экран, чтобы повторить запрос.");}if(request==12){if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)unmute();else message("Без разрешения микрофона звук останется выключен");}if(request==11){if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)start();else message("Микрофон не разрешён. Выключите звук кнопкой MUTE, затем нажмите START.");}}
 @Override protected void onResume(){super.onResume();resumed=true;getSystemService(DisplayManager.class).registerDisplayListener(displayListener,main);updateRotation();if(preview!=null){preview.onResume();camera.resume();main.removeCallbacks(refresh);main.post(refresh);Updates.schedule(this);Updates.check(this,false,null);}}
 @Override protected void onPause(){getSystemService(DisplayManager.class).unregisterDisplayListener(displayListener);resumed=false;main.removeCallbacks(refresh);stop();if(camera!=null){camera.light(false);camera.pause();}setFrontLight(false);if(preview!=null)preview.onPause();super.onPause();}
 @Override protected void onDestroy(){if(camera!=null)camera.close();main.removeCallbacksAndMessages(null);super.onDestroy();}
 @Override public void onConfigurationChanged(Configuration configuration){super.onConfigurationChanged(configuration);if(renderer!=null)renderer.displayRotation=getWindowManager().getDefaultDisplay().getRotation();if(root!=null)root.requestLayout();}
 private void message(String value){Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
 @Override public void action(String id){if(camera==null)return;switch(id){
  case "settings":if(stream!=null){message("Остановите трансляцию перед настройками");break;}startActivity(new Intent(this,SettingsActivity.class));break;
  case "lock":locked=!locked;setRequestedOrientation(locked?ActivityInfo.SCREEN_ORIENTATION_LOCKED:ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);break;
  case "grid":hud.grid=!hud.grid;hud.invalidate();break;
  case "light":if(camera.mainFront){setFrontLight(!hud.frontLight);if(camera.pip)camera.light(false);}else{if(!Boolean.TRUE.equals(camera.active.info.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))){message("Фонарик недоступен для выбранного объектива");break;}camera.light(!camera.torch);}break;
  case "ev":exposureDialog();break;
  case "ae":camera.locks(!camera.aeLock,camera.afLock);break;
  case "af":camera.locks(camera.aeLock,!camera.afLock);break;
  case "mf":manualDialog();break;
  case "scopes":hud.scopes=!hud.scopes;renderer.scopes=hud.scopes;preview.requestRender();break;
  case "hide":hud.hide(true);break;
  case "restore":hud.hide(false);break;
  case "camera":cycle();break;
  case "swap":setFrontLight(false);choose(!camera.mainFront?camera.front.get(0):camera.rear.get(0),true,!camera.mainFront,720,30);break;
  case "quality":if(stream!=null){message("Остановите трансляцию перед изменением качества");break;}qualityDialog();break;
  case "lenses":lensDialog();break;
  case "start":if(stream==null)start();else stop();break;
  case "mute":if(muted){if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},12);break;}unmute();}else{muted=true;if(stream!=null)stream.muted=true;}break;
 }}
 private void start(){if(starting||stream!=null)return;if(!camera.ready){message(error.isEmpty()?"Дождитесь готовности камеры":error);return;}if(source().trim().isEmpty()){message("Укажите название устройства в настройках");return;}
  if(!muted&&checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},11);return;}starting=true;
  try{stream=new StreamSession(this,source(),checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED,muted);renderer.stream=stream;}catch(Exception e){message("Ошибка NDI: "+e.getMessage());}finally{starting=false;}}
 private void unmute(){try{if(stream!=null){stream.enableMicrophone();stream.muted=false;}muted=false;}catch(Exception e){message("Микрофон: "+e.getMessage());}}
 private void stop(){if(renderer!=null)renderer.stream=null;if(stream!=null){stream.close();stream=null;}}
 private void setFrontLight(boolean enabled){if(hud==null)return;hud.frontLight=enabled;WindowManager.LayoutParams p=getWindow().getAttributes();p.screenBrightness=enabled?1f:WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;getWindow().setAttributes(p);hud.invalidate();}
 private void cycle(){int next=(mode+1)%3;if(next==1&&camera.front.isEmpty())next=0;if(next==2&&!camera.concurrent()){message("Одновременная работа камер не поддерживается Camera2 на этом телефоне");next=0;}mode=next;setFrontLight(false);CameraController.Lens lens=mode==1?camera.front.get(0):camera.rear.isEmpty()?camera.front.get(0):camera.rear.get(0);choose(lens,mode==2,mode==1,camera.height,camera.fps);}
 private void choose(CameraController.Lens lens,boolean pip,boolean front,int height,int fps){List<Size> choices=camera.sizes(lens);if(pip)choices.removeIf(s->s.getWidth()>1280||s.getHeight()>720);Size selected=null;int edge=pip?Math.min(720,height):height;for(Size s:choices)if(selected==null||Math.abs(s.getHeight()-edge)<Math.abs(selected.getHeight()-edge))selected=s;if(selected==null){message("Объектив не предоставляет подходящий видеорежим");return;}chooseSize(lens,pip,front,selected,fps);}
 private void chooseSize(CameraController.Lens lens,boolean pip,boolean front,Size selected,int fps){List<Integer> rates=camera.rates(lens,selected,pip);if(rates.isEmpty()){message("Нет совместимого режима 25/30/50/60 FPS");return;}int rate=rates.get(0);for(int value:rates)if(Math.abs(value-fps)<Math.abs(rate-fps))rate=value;camera.configure(lens,pip,front,selected.getWidth(),selected.getHeight(),rate);error="";}
 private void qualityDialog(){List<Size> sizes=camera.sizes(camera.active);if(camera.pip)sizes.removeIf(s->s.getWidth()>1280||s.getHeight()>720);String[] labels=new String[sizes.size()];for(int i=0;i<labels.length;i++)labels[i]=sizes.get(i).getWidth()+" × "+sizes.get(i).getHeight();new AlertDialog.Builder(this).setTitle("Разрешение камеры").setItems(labels,(d,index)->{Size size=sizes.get(index);List<Integer> rates=camera.rates(size);if(rates.isEmpty()){message("Для этого разрешения нет доступных 25/30/50/60 FPS");return;}String[] options=new String[rates.size()];for(int j=0;j<options.length;j++)options[j]=rates.get(j)+" кадров/с";new AlertDialog.Builder(this).setTitle("Частота кадров").setItems(options,(a,n)->chooseSize(camera.active,camera.pip,camera.mainFront,size,rates.get(n))).setNegativeButton("Отмена",null).show();}).setNegativeButton("Отмена",null).show();}
 private void lensDialog(){List<CameraController.Lens> lenses=camera.mainFront?camera.front:camera.rear;List<String> names=new ArrayList<>();int selected=-1;for(int i=0;i<lenses.size();i++){CameraController.Lens lens=lenses.get(i);names.add(lens.label+(lens.available?"":" · недоступен"));if(lens==camera.active)selected=i;}names.add("Зум текущего объектива…");new AlertDialog.Builder(this).setTitle(camera.lensDescription()).setSingleChoiceItems(names.toArray(new String[0]),selected,(d,index)->{if(index==lenses.size()){d.dismiss();slider(camera.active.physical!=null?"Цифровой зум":"Зум камеры",0,1000,Math.round(1000*(camera.zoom-camera.minZoom)/Math.max(.01f,camera.maxZoom-camera.minZoom)),value->zoom(camera.minZoom+value/1000f*(camera.maxZoom-camera.minZoom)));return;}if(!lenses.get(index).available){message("Этот объектив отклонил конфигурацию Camera2 и недоступен");return;}if(camera.pip){message("В PIP используется основная пара камер. Выберите одиночный режим для смены объектива.");return;}d.dismiss();setFrontLight(false);choose(lenses.get(index),false,camera.mainFront,camera.height,camera.fps);}).setNegativeButton("Отмена",null).show();}
 private interface Value {void changed(int value);}
 private void slider(String title,int min,int max,int value,Value callback){LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(28,20,28,20);TextView text=new TextView(this);text.setTextColor(-1);text.setText(title);box.addView(text);SeekBar bar=new SeekBar(this);bar.setMax(max-min);bar.setProgress(value-min);box.addView(bar);bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int v,boolean from){if(from){callback.changed(v+min);text.setText(title+" · "+(title.equals("EV")?String.format(Locale.US,"%.1f",(v+min)/10f):String.valueOf(v+min)));}}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}});new AlertDialog.Builder(this).setTitle(title).setView(box).setPositiveButton("Готово",null).show();}
 private void exposureDialog(){if(!camera.supportsExposure()){message("Экспокоррекция недоступна для этого физического объектива");return;}android.util.Range<Integer> range=camera.active.info.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);android.util.Rational step=camera.active.info.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);if(range==null||step==null||step.floatValue()==0){message("Экспокоррекция недоступна");return;}slider("EV",Math.round(range.getLower()*step.floatValue()*10),Math.round(range.getUpper()*step.floatValue()*10),Math.round(camera.ev*10),value->exposure(value/10f));}
 private void manualDialog(){if(!camera.supportsManual()){message("Объектив не поддерживает ручной фокус");return;}if(camera.manual){camera.manual(false,0);return;}camera.manual(true,0);slider("Ручной фокус · ∞ → ближний",0,100,0,value->camera.manual(true,value/100f));}
 @Override public void zoom(float value){camera.zoom(value);}
 @Override public void exposure(float value){camera.exposure(value);}
 @Override public void focus(float x,float y,boolean hold){
  if(camera.aeLock&&camera.afLock&&!hold)return;float[] point=renderer.focusPoint(x,y);camera.focus(point[0],point[1]);if(hold){boolean ae=camera.supportsAeLock(),af=camera.supportsAf();camera.locks(ae,af);if(!ae||!af)message("Часть блокировок недоступна для выбранного объектива");}}
 @Override public void pipBounds(RectF bounds){if(renderer!=null)renderer.pipBounds=new RectF(bounds);}
}
