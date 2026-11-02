package ru.ileech.ndicamera;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.RectF;
import android.os.*;
import android.view.*;
import java.lang.reflect.*;
import java.util.*;

/** Device-side integration checks; no test hooks are shipped in the application. */
public class SmokeInstrumentation extends Instrumentation {
 private MainActivity activity;private CameraController camera;private CameraHud hud;
 private Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
 private void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
 private void ui(Runnable task){runOnMainSync(task);waitForIdleSync();}
 private void settle()throws Exception{Thread.sleep(350);waitForIdleSync();}
 private void ready()throws Exception{for(int n=0;n<100&&!camera.ready;n++)Thread.sleep(100);require(camera.ready,"Camera2 not ready: "+camera.state);}
 @Override public void onCreate(Bundle args){super.onCreate(args);start();}
 @Override public void onStart(){Bundle result=new Bundle();StringBuilder checks=new StringBuilder();try{
  activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();camera=(CameraController)field(activity,"camera");hud=(CameraHud)field(activity,"hud");require(camera!=null&&hud!=null,"Camera initialization");ready();checks.append("Camera2 preview READY\n");
  ui(()->activity.action("mute"));ui(()->activity.action("start"));settle();StreamSession stream=(StreamSession)field(activity,"stream");require(stream!=null&&StreamSession.running,"NDI START");require(stream.muted,"initial MUTE");
  ui(()->activity.action("mute"));settle();require(!stream.muted&&field(stream,"record")!=null,"UNMUTE starts microphone");checks.append("MUTE -> START -> UNMUTE opens AudioRecord\n");
  ui(()->activity.action("mute"));require(stream.muted,"MUTE while streaming");ui(()->activity.action("start"));require(!StreamSession.running,"STOP tears down sender");checks.append("NDI START/STOP and audio teardown\n");
  StreamSession lazy=new StreamSession(getTargetContext(),"NDI Camera audio regression",false,true);require(field(lazy,"record")==null,"muted session has no AudioRecord");lazy.enableMicrophone();require(field(lazy,"record")!=null,"late AudioRecord creation");lazy.muted=false;settle();require(lazy.error.isEmpty(),"late microphone error: "+lazy.error);lazy.close();checks.append("lazy microphone creation after initially silent session\n");
  ui(()->activity.action("hide"));require(hud.hidden,"HIDE");ui(()->activity.action("restore"));require(!hud.hidden,"HUD restore");ui(()->activity.action("grid"));require(!hud.grid,"grid toggle");ui(()->activity.action("scopes"));require(hud.scopes,"scopes toggle");checks.append("HIDE/restore/grid/scopes\n");
  float previous=camera.ev;ui(()->{long t=SystemClock.uptimeMillis();hud.dispatchTouchEvent(MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,hud.getWidth()*.5f,hud.getHeight()*.5f,0));});Thread.sleep(700);ui(()->{long t=SystemClock.uptimeMillis();hud.dispatchTouchEvent(MotionEvent.obtain(t-700,t,MotionEvent.ACTION_MOVE,hud.getWidth()*.5f,hud.getHeight()*.5f-180,0));hud.dispatchTouchEvent(MotionEvent.obtain(t-700,t+20,MotionEvent.ACTION_UP,hud.getWidth()*.5f,hud.getHeight()*.5f-180,0));});settle();require(camera.ev>previous,"hold/swipe EV");if(camera.supportsAeLock())require(camera.aeLock,"hold locks AE");if(camera.supportsAf())require(camera.afLock,"hold locks AF");checks.append("hold + swipe locks AE/AF and changes EV\n");
  ui(()->camera.zoom(camera.minZoom));settle();float before=camera.zoom;
  ui(()->{long t=SystemClock.uptimeMillis();float cx=hud.getWidth()*.5f,cy=hud.getHeight()*.5f;touch(t,t,MotionEvent.ACTION_DOWN,new float[]{cx-100},new float[]{cy});touch(t,t+20,MotionEvent.ACTION_POINTER_DOWN|(1<<8),new float[]{cx-100,cx+100},new float[]{cy,cy});for(int i=0;i<20;i++)touch(t,t+40+i*20,MotionEvent.ACTION_MOVE,new float[]{cx-100-i*14,cx+100+i*14},new float[]{cy,cy});touch(t,t+460,MotionEvent.ACTION_POINTER_UP|(1<<8),new float[]{cx-366,cx+366},new float[]{cy,cy});touch(t,t+480,MotionEvent.ACTION_UP,new float[]{cx-366},new float[]{cy});});settle();if(camera.maxZoom>camera.minZoom)require(camera.zoom>before,"pinch zoom before="+before+" after="+camera.zoom+" range="+camera.minZoom+".."+camera.maxZoom);checks.append("two-pointer pinch zoom\n");
  ui(()->activity.action("camera"));ready();settle();if(!camera.front.isEmpty())require(camera.mainFront,"front camera switch");ui(()->activity.action("light"));require(hud.frontLight,"front screen light");ui(()->activity.action("light"));require(!hud.frontLight,"front light off");checks.append("front camera + screen flash\n");
  ui(()->activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));for(int n=0;n<50&&hud.getWidth()<hud.getHeight();n++)Thread.sleep(100);require(hud.getWidth()>hud.getHeight(),"landscape layout");settle();checkButtons();checks.append("landscape controls do not overlap\n");
  ui(()->activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));for(int n=0;n<50&&hud.getWidth()>hud.getHeight();n++)Thread.sleep(100);settle();checkButtons();checks.append("portrait controls do not overlap\n");
  ui(()->activity.action("camera"));settle();if(!camera.concurrent())require(!camera.pip,"unsupported PIP rejected");checks.append("PIP capability fallback\n");
  result.putString("checks",checks.toString());result.putString("verdict","PASS");finish(Activity.RESULT_OK,result);
 }catch(Throwable e){result.putString("checks",checks.toString());result.putString("failure",e.toString());finish(Activity.RESULT_CANCELED,result);}}
 private void checkButtons(){ui(()->{List<View> views=new ArrayList<>();for(int i=0;i<hud.getChildCount();i++){View v=hud.getChildAt(i);if(v instanceof android.widget.Button&&v.getVisibility()==View.VISIBLE)views.add(v);}for(int i=0;i<views.size();i++)for(int j=i+1;j<views.size();j++){View a=views.get(i),b=views.get(j);RectF x=new RectF(a.getLeft(),a.getTop(),a.getRight(),a.getBottom()),y=new RectF(b.getLeft(),b.getTop(),b.getRight(),b.getBottom());require(!RectF.intersects(x,y),"buttons overlap: "+a.getContentDescription()+" / "+b.getContentDescription());}});}
 private void touch(long down,long time,int action,float[] x,float[] y){MotionEvent.PointerProperties[] props=new MotionEvent.PointerProperties[x.length];MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[x.length];for(int i=0;i<x.length;i++){props[i]=new MotionEvent.PointerProperties();props[i].id=i;props[i].toolType=MotionEvent.TOOL_TYPE_FINGER;coords[i]=new MotionEvent.PointerCoords();coords[i].x=x[i];coords[i].y=y[i];coords[i].pressure=1;coords[i].size=1;}MotionEvent e=MotionEvent.obtain(down,time,action,x.length,props,coords,0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0);hud.dispatchTouchEvent(e);e.recycle();}
}
