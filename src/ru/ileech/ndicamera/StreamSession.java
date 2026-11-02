package ru.ileech.ndicamera;

import android.content.Context;
import android.media.*;
import android.net.TrafficStats;
import android.net.wifi.WifiManager;
import android.os.*;
import java.nio.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded off-GL video queue. Sender destruction happens after both producers stop. */
final class StreamSession {
 static volatile boolean running;
 volatile boolean muted;
 volatile float dbfs=-90;
 volatile String error="";
 volatile int receivers;
 volatile double mbps=-1;
 volatile long startedAt;
 private final ExecutorService video=Executors.newSingleThreadExecutor(r->new Thread(r,"NDI video"));
 private final AtomicBoolean sending=new AtomicBoolean();
 private final Object senderLock=new Object();
 private long sender,bytesBefore,sampleBefore;
 private volatile boolean closed;
 private AudioRecord record;private Thread audio;
 private WifiManager.MulticastLock multicast;private WifiManager.WifiLock wifi;
 private PowerManager.WakeLock wake;
 StreamSession(Context c,String name,boolean microphone,boolean initiallyMuted)throws Exception{
  muted=initiallyMuted;
  try{
   sender=Ndi.create(name);if(sender==0)throw new IllegalStateException("Не удалось создать NDI-источник");
   WifiManager wm=(WifiManager)c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
   if(wm!=null){multicast=wm.createMulticastLock("NDI Camera discovery");multicast.acquire();wifi=wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"NDI Camera stream");wifi.acquire();}
   wake=c.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"ndicamera:stream");wake.acquire();
   if(microphone)openMicrophone();
   startedAt=SystemClock.elapsedRealtime();sampleBefore=startedAt;bytesBefore=TrafficStats.getUidTxBytes(android.os.Process.myUid());running=true;
  }catch(Exception e){close();throw e;}
 }
 boolean reserve(){return !closed&&sending.compareAndSet(false,true);}
 void cancelReservation(){sending.set(false);}
 void frame(ByteBuffer rgba,int w,int h,int fps,long timestamp){
  try{video.execute(()->{try{synchronized(senderLock){if(!closed&&sender!=0){Ndi.frame(sender,rgba,w,h,w*4,fps,timestamp);receivers=Ndi.connections(sender);}}}catch(Throwable e){error="NDI: "+e.getMessage();}finally{sending.set(false);}});}catch(RejectedExecutionException ignored){sending.set(false);}
 }
 void telemetry(){long now=SystemClock.elapsedRealtime(),bytes=TrafficStats.getUidTxBytes(android.os.Process.myUid());if(now-sampleBefore>=1000){mbps=bytes<0||bytesBefore<0?-1:Math.max(0,(bytes-bytesBefore)*8.0/((now-sampleBefore)*1000));sampleBefore=now;bytesBefore=bytes;}}
 synchronized void enableMicrophone(){if(closed)throw new IllegalStateException("Трансляция уже остановлена");if(record==null)try{openMicrophone();}catch(RuntimeException e){if(record!=null){try{record.stop();}catch(Exception ignored){}record.release();record=null;}audio=null;throw e;}}
 private void openMicrophone(){
  int min=AudioRecord.getMinBufferSize(48000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
  if(min<=0)throw new IllegalStateException("Микрофон 48 кГц недоступен");
  record=new AudioRecord(MediaRecorder.AudioSource.MIC,48000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min*2,7680));
  if(record.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("Не удалось открыть микрофон");
  record.startRecording();if(record.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IllegalStateException("Микрофон не запущен");
  audio=new Thread(()->{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);short[] mono=new short[960];ByteBuffer stereo=ByteBuffer.allocateDirect(3840).order(ByteOrder.nativeOrder());long samples=0,anchor=System.nanoTime();try{
   while(!closed){int n=record.read(mono,0,mono.length,AudioRecord.READ_BLOCKING);if(closed)break;if(n<0)throw new IllegalStateException("AudioRecord: "+n);if(n==0)continue;double energy=0;boolean silence=muted;stereo.clear();for(int j=0;j<n;j++){energy+=(double)mono[j]*mono[j];short value=silence?0:mono[j];stereo.putShort(value).putShort(value);}dbfs=silence?-90:(float)Math.max(-90,20*Math.log10(Math.max(1e-9,Math.sqrt(energy/n)/32768)));stereo.rewind();synchronized(senderLock){if(!closed&&sender!=0)Ndi.audio(sender,stereo,n,(anchor+samples*1_000_000_000L/48000)/100);}samples+=n;
   }
  }catch(Throwable e){if(!closed)error="Микрофон: "+e.getMessage();}finally{dbfs=-90;}},"NDI microphone");audio.start();
 }
 void close(){
  closed=true;running=false;
  if(record!=null)try{record.stop();}catch(Exception ignored){}
  if(audio!=null){boolean interrupted=false;while(audio.isAlive())try{audio.join();}catch(InterruptedException e){interrupted=true;}if(interrupted)Thread.currentThread().interrupt();}
  if(record!=null){record.release();record=null;}
  video.shutdown();synchronized(senderLock){if(sender!=0){Ndi.destroy(sender);sender=0;}}
  if(multicast!=null&&multicast.isHeld())multicast.release();if(wifi!=null&&wifi.isHeld())wifi.release();if(wake!=null&&wake.isHeld())wake.release();
 }
}
