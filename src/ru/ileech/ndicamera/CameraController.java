package ru.ileech.ndicamera;

import android.content.*;
import android.graphics.*;
import android.hardware.camera2.*;
import android.hardware.camera2.params.*;
import android.os.*;
import android.util.*;
import android.view.Surface;
import java.util.*;

/** All Camera2 state is confined to the camera handler; UI controls are posted to it. */
final class CameraController {
 interface Listener {void changed();void failure(String message);}
 static final class Lens {
  final String id,physical;final CameraCharacteristics info;final boolean front;String label;float opticalRatio=1;volatile boolean available=true;
  Lens(String id,String physical,CameraCharacteristics c,String label){this.id=id;this.physical=physical;info=c;front=Objects.equals(c.get(CameraCharacteristics.LENS_FACING),CameraCharacteristics.LENS_FACING_FRONT);this.label=label;}
 }
 private final CameraManager manager;private final HandlerThread thread=new HandlerThread("Camera2");final Handler handler;private final Listener listener;
 final List<Lens> rear=new ArrayList<>(),front=new ArrayList<>();
 private final Map<String,CameraCharacteristics> parents=new HashMap<>();
 volatile String state="Ожидание камеры";volatile boolean ready;volatile float zoom=1,minZoom=1,maxZoom=1,ev=0;volatile boolean aeLock,afLock,manual,torch;
 volatile int width=1280,height=720,fps=30;volatile boolean pip,mainFront;volatile Lens active;volatile String reportedPhysical="";private volatile Rect meteringCrop;
 private final CameraDevice[] devices=new CameraDevice[2];private final CameraCaptureSession[] sessions=new CameraCaptureSession[2];private final CaptureRequest.Builder[] requests=new CaptureRequest.Builder[2];
 private Surface[] surfaces;private int generation,opened;private final SurfaceTexture[] textures=new SurfaceTexture[2];private Lens[] lenses;
 private float focusValue;private Rect focusRect;private boolean touchFocus,triggerFocus;
 volatile CameraCharacteristics secondaryInfo;volatile int secondaryWidth=640,secondaryHeight=480;
 CameraController(Context c,Listener listener)throws Exception{this.listener=listener;manager=c.getSystemService(CameraManager.class);thread.start();handler=new Handler(thread.getLooper());discover();}
 private void discover()throws Exception{
  Set<String> publicIds=new HashSet<>(Arrays.asList(manager.getCameraIdList())),physicalAdded=new HashSet<>();
  for(String id:manager.getCameraIdList()){
   CameraCharacteristics c=manager.getCameraCharacteristics(id);parents.put(id,c);Integer facing=c.get(CameraCharacteristics.LENS_FACING);if(facing==null||facing==CameraCharacteristics.LENS_FACING_EXTERNAL)continue;
   String label=(facing==CameraCharacteristics.LENS_FACING_FRONT?"Фронтальная":"Тыловая")+" · ID "+id;
   Lens logical=new Lens(id,null,c,label);(logical.front?front:rear).add(logical);
   if(!logical.front)for(String physical:c.getPhysicalCameraIds())if(!publicIds.contains(physical)&&physicalAdded.add(physical))try{CameraCharacteristics p=manager.getCameraCharacteristics(physical);rear.add(new Lens(id,physical,p,"Объектив "+physical));}catch(CameraAccessException ignored){}
  }
  if(rear.isEmpty()&&front.isEmpty())throw new IllegalStateException("Камера недоступна");rear.sort((a,b)->Boolean.compare(b.physical==null&&b.info.getPhysicalCameraIds().size()>1,a.physical==null&&a.info.getPhysicalCameraIds().size()>1));active=!rear.isEmpty()?rear.get(0):front.get(0);mainFront=active.front;
  float reference=angleScale(active);for(Lens lens:rear){lens.opticalRatio=angleScale(lens)/reference;lens.label=(automatic(lens)?"Авто (система выбирает объектив)":String.format(Locale.US,"≈ %.1f× · объектив %s",lens.opticalRatio,lens.physical==null?lens.id:lens.physical))+" · Camera2 "+lens.id+(lens.physical==null?"":" → "+lens.physical);}updateZoomBounds();
 }
 private static float angleScale(Lens lens){float[] f=lens.info.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);android.util.SizeF size=lens.info.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);return f!=null&&f.length>0&&size!=null&&size.getWidth()>0?f[0]/size.getWidth():1;}
 static boolean automatic(Lens lens){return lens.physical==null&&lens.info.getPhysicalCameraIds().size()>1;}
 private boolean controlAvailable(Lens lens,CaptureRequest.Key<?> key){if(lens.physical==null)return true;List<CaptureRequest.Key<?>> keys=parents.get(lens.id).getAvailablePhysicalCameraRequestKeys();return keys!=null&&keys.contains(key);}
 private <T> void control(CaptureRequest.Builder request,int index,CaptureRequest.Key<T> key,T value){Lens lens=lenses[index];if(lens.physical==null)request.set(key,value);else if(controlAvailable(lens,key))request.setPhysicalCameraKey(key,value,lens.physical);}
 boolean supportsExposure(){return controlAvailable(active,CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION);}
 private Range<Integer>[] fpsRanges(Lens lens){return (lens.physical!=null&&!controlAvailable(lens,CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)?parents.get(lens.id):lens.info).get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);}
 String lensCaption(){Lens lens=active;String id=lens.physical!=null?lens.physical:reportedPhysical.isEmpty()?lens.id:reportedPhysical;return automatic(lens)?"Авто · ID "+id:String.format(Locale.US,"≈ %.1f× · ID %s",lens.opticalRatio,id);}
 String lensDescription(){return active.label+String.format(Locale.US,"; %s зум %.1f×",automatic(active)?"системный":"цифровой",zoom)+(reportedPhysical.isEmpty()?"":"; активная камера "+reportedPhysical);}
 boolean concurrent(){if(Build.VERSION.SDK_INT<30||front.isEmpty()||rear.isEmpty())return false;try{for(Set<String> pair:manager.getConcurrentCameraIds())if(pair.contains(rear.get(0).id)&&pair.contains(front.get(0).id))return true;}catch(CameraAccessException ignored){}return false;}
 List<Size> sizes(Lens lens){StreamConfigurationMap map=lens.info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);List<Size> out=new ArrayList<>();if(map!=null){Size[] values=map.getOutputSizes(SurfaceTexture.class);if(values!=null)for(Size s:values){int edge=Math.min(s.getWidth(),s.getHeight());if(edge>=480&&edge<=2160&&s.getWidth()<=4096)out.add(s);}}out.sort((a,b)->Integer.compare(a.getHeight(),b.getHeight()));return out;}
 List<Integer> rates(Size size){return rates(active,size,pip);}
 List<Integer> rates(Lens lens,Size size,boolean both){List<Integer> values=new ArrayList<>();Range<Integer>[] ranges=fpsRanges(lens);StreamConfigurationMap map=lens.info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);long duration=map==null?0:map.getOutputMinFrameDuration(SurfaceTexture.class,size);for(int n:new int[]{25,30,50,60}){if(both&&n>30)continue;boolean supported=false;if(ranges!=null)for(Range<Integer> r:ranges)if(r.contains(n))supported=true;if(supported&&(duration==0||duration<=1_000_000_000L/n+1000000))values.add(n);}return values;}
 void attach(SurfaceTexture first,SurfaceTexture second){handler.post(()->{textures[0]=first;textures[1]=second;reopen();});}
 void configure(Lens lens,boolean both,boolean frontMain,int w,int h,int rate){handler.post(()->{active=lens;reportedPhysical="";meteringCrop=null;pip=both;mainFront=frontMain;width=w;height=h;fps=rate;torch=false;aeLock=false;afLock=false;manual=false;focusRect=null;touchFocus=false;triggerFocus=false;zoom=1;updateZoomBounds();reopen();});}
 private void updateZoomBounds(){Range<Float> ratio=Build.VERSION.SDK_INT>=30&&active.physical==null?active.info.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE):null;minZoom=ratio==null?1:ratio.getLower();Float max=active.info.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);maxZoom=ratio==null?(max==null?1:max):ratio.getUpper();zoom=Math.max(minZoom,Math.min(maxZoom,zoom));}
 private void closeDevices(){ready=false;for(int i=0;i<2;i++){if(sessions[i]!=null){sessions[i].close();sessions[i]=null;}requests[i]=null;if(devices[i]!=null){devices[i].close();devices[i]=null;}if(surfaces!=null&&surfaces[i]!=null){surfaces[i].release();surfaces[i]=null;}}}
 private void reopen(){
  final int token=++generation;closeDevices();if(textures[0]==null)return;
  try{
   if(pip&&!concurrent())throw new IllegalStateException("Этот телефон не предоставляет одновременные фронтальную и тыловую камеры через Camera2");
   if(pip){lenses=new Lens[]{mainFront?front.get(0):rear.get(0),mainFront?rear.get(0):front.get(0)};active=lenses[0];secondaryInfo=lenses[1].info;if(width>1280||height>720)throw new IllegalStateException("Выберите поддерживаемый размер PIP до 1280 × 720");fps=Math.min(fps,30);updateZoomBounds();}else lenses=new Lens[]{active};
   surfaces=new Surface[2];opened=0;state="Открытие камеры…";listener.changed();
   for(int slot=0;slot<lenses.length;slot++){
    final int index=slot;if(index==1){StreamConfigurationMap map=lenses[1].info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);Size best=null;if(map!=null)for(Size s:map.getOutputSizes(SurfaceTexture.class))if(s.getWidth()<=640&&s.getHeight()<=480&&(best==null||s.getWidth()*s.getHeight()>best.getWidth()*best.getHeight()))best=s;if(best==null)throw new IllegalStateException("Для второй камеры нет видеорежима до 640 × 480");secondaryWidth=best.getWidth();secondaryHeight=best.getHeight();}textures[index].setDefaultBufferSize(index==0?width:secondaryWidth,index==0?height:secondaryHeight);surfaces[index]=new Surface(textures[index]);
    manager.openCamera(lenses[index].id,new CameraDevice.StateCallback(){
     @Override public void onOpened(CameraDevice camera){if(token!=generation){camera.close();return;}devices[index]=camera;if(++opened==lenses.length)try{configureSessions(token);}catch(Exception e){fail(e);}}
     @Override public void onDisconnected(CameraDevice camera){camera.close();if(token==generation)fail(new IllegalStateException("Камера отключена"));}
     @Override public void onError(CameraDevice camera,int code){camera.close();if(token==generation)fail(new IllegalStateException("Camera2 error "+code));}
    },handler);
   }
  }catch(Exception e){fail(e);}
 }
 private SessionConfiguration configuration(int index,int token)throws Exception{
  OutputConfiguration output=new OutputConfiguration(surfaces[index]);if(lenses[index].physical!=null)output.setPhysicalCameraId(lenses[index].physical);
  return new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,Collections.singletonList(output),r->handler.post(r),new CameraCaptureSession.StateCallback(){
   @Override public void onConfigured(CameraCaptureSession s){if(token!=generation){s.close();return;}sessions[index]=s;try{requests[index]=lenses[index].physical==null?devices[index].createCaptureRequest(CameraDevice.TEMPLATE_RECORD):devices[index].createCaptureRequest(CameraDevice.TEMPLATE_RECORD,Collections.singleton(lenses[index].physical));requests[index].addTarget(surfaces[index]);apply(index);ready=sessions[0]!=null&&(!pip||sessions[1]!=null);state=width+" × "+height+" · "+fps+" FPS";listener.changed();}catch(Exception e){fail(e);}}
   @Override public void onConfigureFailed(CameraCaptureSession s){if(token==generation)fail(new IllegalStateException("Камера отклонила выбранные разрешение / FPS. Выберите меньшие значения."));}
  });
 }
 private void configureSessions(int token)throws Exception{
  SessionConfiguration a=configuration(0,token),b=pip?configuration(1,token):null;
  if(pip&&Build.VERSION.SDK_INT>=30){Map<String,SessionConfiguration> configs=new HashMap<>();configs.put(lenses[0].id,a);configs.put(lenses[1].id,b);if(!manager.isConcurrentSessionConfigurationSupported(configs))throw new IllegalStateException("PIP 720p + 480p не поддерживается этой парой камер");}
  devices[0].createCaptureSession(a);if(pip)devices[1].createCaptureSession(b);
 }
 private boolean hasAfMode(CameraCharacteristics c,int mode){int[] modes=c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);if(modes!=null)for(int m:modes)if(m==mode)return true;return false;}
 private void apply(int index)throws Exception{
  CaptureRequest.Builder r=requests[index];if(r==null||sessions[index]==null)return;CameraCharacteristics c=lenses[index].info;
  r.set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO);r.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON);
  Range<Integer>[] ranges=fpsRanges(lenses[index]);Range<Integer> chosen=null;if(ranges!=null)for(Range<Integer> range:ranges)if(range.contains(fps)&&(chosen==null||range.getUpper()-range.getLower()<chosen.getUpper()-chosen.getLower()))chosen=range;if(chosen!=null){if(lenses[index].physical!=null&&controlAvailable(lenses[index],CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE))control(r,index,CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,chosen);else r.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,chosen);}
  boolean primary=index==0;if(Boolean.TRUE.equals(c.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE)))control(r,index,CaptureRequest.CONTROL_AE_LOCK,primary&&aeLock);
  Range<Integer> range=c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);Rational step=c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);if(range!=null&&step!=null&&step.floatValue()>0)control(r,index,CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,range.clamp(Math.round((primary?ev:0)/step.floatValue())));
  Float minFocus=c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);boolean mf=primary&&manual&&minFocus!=null&&minFocus>0;
  int af=mf?CaptureRequest.CONTROL_AF_MODE_OFF:(primary&&(afLock||touchFocus)&&hasAfMode(c,CaptureRequest.CONTROL_AF_MODE_AUTO)?CaptureRequest.CONTROL_AF_MODE_AUTO:hasAfMode(c,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)?CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO:CaptureRequest.CONTROL_AF_MODE_OFF);control(r,index,CaptureRequest.CONTROL_AF_MODE,af);if(mf)control(r,index,CaptureRequest.LENS_FOCUS_DISTANCE,focusValue*minFocus);
  if(primary){
   if(active.physical!=null){
    // The output is pinned to this physical sensor. Do not route a logical zoom request
    // through its parent: digital zoom of this stream is applied in the compositor.
    if(Build.VERSION.SDK_INT>=30)r.set(CaptureRequest.CONTROL_ZOOM_RATIO,1f);
   }else if(Build.VERSION.SDK_INT>=30&&c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)!=null)r.set(CaptureRequest.CONTROL_ZOOM_RATIO,zoom);else{Rect sensor=c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);if(sensor!=null){int w=(int)(sensor.width()/Math.max(1,zoom)),h=(int)(sensor.height()/Math.max(1,zoom));r.set(CaptureRequest.SCALER_CROP_REGION,new Rect(sensor.centerX()-w/2,sensor.centerY()-h/2,sensor.centerX()+w/2,sensor.centerY()+h/2));}}
   if(focusRect!=null){MeteringRectangle[] areas={new MeteringRectangle(focusRect,1000)};Integer maxAf=c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF),maxAe=c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);if(maxAf!=null&&maxAf>0)control(r,index,CaptureRequest.CONTROL_AF_REGIONS,areas);if(maxAe!=null&&maxAe>0)control(r,index,CaptureRequest.CONTROL_AE_REGIONS,areas);}
  }
  boolean flash=Boolean.TRUE.equals(c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))&&!lenses[index].front;r.set(CaptureRequest.FLASH_MODE,torch&&flash?CaptureRequest.FLASH_MODE_TORCH:CaptureRequest.FLASH_MODE_OFF);
  if(primary&&triggerFocus&&!mf&&supportsAf()){triggerFocus=false;control(r,index,CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_AUTO);control(r,index,CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_START);sessions[index].capture(r.build(),null,handler);control(r,index,CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_IDLE);}
  sessions[index].setRepeatingRequest(r.build(),index==0?new CameraCaptureSession.CaptureCallback(){@Override public void onCaptureCompleted(CameraCaptureSession session,CaptureRequest request,TotalCaptureResult result){if(session!=sessions[0])return;String id=result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID);reportedPhysical=active.physical!=null?active.physical:id==null?active.id:id;Rect crop=result.get(CaptureResult.SCALER_CROP_REGION);if(crop!=null)meteringCrop=crop;}}:null,handler);
 }
 private void update(){try{for(int i=0;i<2;i++)apply(i);listener.changed();}catch(Exception e){fail(e);}}
 void zoom(float value){handler.post(()->{zoom=Math.max(minZoom,Math.min(maxZoom,value));update();});}
 void exposure(float value){handler.post(()->{ev=value;update();});}
 void locks(boolean ae,boolean af){handler.post(()->{aeLock=ae;if(af&&!afLock)triggerFocus=true;if(!af&&afLock)touchFocus=false;afLock=af;if(af)manual=false;update();});}
 boolean supportsManual(){Float min=active.info.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);return min!=null&&min>0&&controlAvailable(active,CaptureRequest.LENS_FOCUS_DISTANCE)&&controlAvailable(active,CaptureRequest.CONTROL_AF_MODE);}
 boolean supportsAeLock(){return Boolean.TRUE.equals(active.info.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE))&&controlAvailable(active,CaptureRequest.CONTROL_AE_LOCK);}
 boolean supportsAf(){return hasAfMode(active.info,CaptureRequest.CONTROL_AF_MODE_AUTO)&&controlAvailable(active,CaptureRequest.CONTROL_AF_MODE)&&controlAvailable(active,CaptureRequest.CONTROL_AF_TRIGGER);}
 void manual(boolean on,float value){handler.post(()->{manual=on;focusValue=value;if(on)afLock=false;update();});}
 void light(boolean on){handler.post(()->{torch=on;update();});}
 void focus(float x,float y){handler.post(()->{Rect full=active.info.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);if(full==null)return;Rect sensor=new Rect(active.physical!=null||meteringCrop==null?full:meteringCrop);float ratio=width/(float)height;if(sensor.width()/(float)sensor.height()>ratio){int half=Math.round(sensor.height()*ratio/2);sensor.left=sensor.centerX()-half;sensor.right=sensor.left+2*half;}else{int half=Math.round(sensor.width()/ratio/2);sensor.top=sensor.centerY()-half;sensor.bottom=sensor.top+2*half;}int side=Math.max(16,Math.min(sensor.width(),sensor.height())/12),cx=sensor.left+(int)(x*sensor.width()),cy=sensor.top+(int)(y*sensor.height());int l=Math.max(sensor.left,Math.min(sensor.right-side,cx-side/2)),t=Math.max(sensor.top,Math.min(sensor.bottom-side,cy-side/2));focusRect=new Rect(l,t,l+side,t+side);if(!manual&&!afLock){touchFocus=true;triggerFocus=true;}update();});}
 private void fail(Exception e){String message=e.getMessage()==null?e.toString():e.getMessage();state=message;Lens failed=active;++generation;closeDevices();if(failed.physical!=null&&!rear.isEmpty()){failed.available=false;active=rear.get(0);pip=false;mainFront=false;zoom=1;reportedPhysical="";focusRect=null;updateZoomBounds();List<Size> options=sizes(active);if(!options.isEmpty()){Size fallback=options.get(0);for(Size s:options)if(Math.abs(s.getHeight()-720)<Math.abs(fallback.getHeight()-720))fallback=s;width=fallback.getWidth();height=fallback.getHeight();List<Integer> values=rates(active,fallback,false);fps=values.contains(30)?30:values.isEmpty()?30:values.get(0);handler.post(this::reopen);}}listener.failure(message);}
 void pause(){handler.post(()->{generation++;closeDevices();});}
 void resume(){handler.post(this::reopen);}
 void close(){handler.post(()->{generation++;closeDevices();thread.quitSafely();});}
}
