package ru.ileech.ndicamera;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.*;
import android.view.Surface;
import java.nio.*;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Composites camera textures twice: preview and clean RGBA output (no UI capture). */
final class CameraRenderer implements GLSurfaceView.Renderer {
 interface Frames {void scopes(int[] rgb,int[] vector);}
 private final GLSurfaceView view;private final CameraController camera;private final Frames listener;
 private final SurfaceTexture[] inputs=new SurfaceTexture[2];private final int[] texture=new int[2];private final boolean[] pending=new boolean[2],valid=new boolean[2];
 private final float[][] transforms={new float[16],new float[16]};
 private final FloatBuffer vertices=ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();
 private final FloatBuffer uv=ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();
 private int program,position,texcoord,sampler,matrix,surfaceWidth,surfaceHeight,fbo,color,outputWidth,outputHeight;private ByteBuffer pixels;private long nextFrame,lastScope;
 volatile StreamSession stream;volatile boolean scopes;volatile int displayRotation;private int glError;
 CameraRenderer(GLSurfaceView view,CameraController camera,Frames listener){this.view=view;this.camera=camera;this.listener=listener;vertices.put(new float[]{-1,-1,1,-1,-1,1,1,1}).position(0);uv.put(new float[]{0,0,1,0,0,1,1,1}).position(0);}
 private int shader(int type,String source){int s=GLES20.glCreateShader(type);GLES20.glShaderSource(s,source);GLES20.glCompileShader(s);int[] ok=new int[1];GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0);if(ok[0]==0)throw new IllegalStateException(GLES20.glGetShaderInfoLog(s));return s;}
 @Override public void onSurfaceCreated(GL10 gl,EGLConfig config){
  String v="attribute vec2 pos;attribute vec2 uv;uniform mat4 transform;varying vec2 tc;void main(){gl_Position=vec4(pos,0.,1.);tc=(transform*vec4(uv,0.,1.)).xy;}";
  String f="#extension GL_OES_EGL_image_external : require\nprecision mediump float;uniform samplerExternalOES camera;varying vec2 tc;void main(){gl_FragColor=texture2D(camera,tc);}";
  int vs=shader(GLES20.GL_VERTEX_SHADER,v),fs=shader(GLES20.GL_FRAGMENT_SHADER,f);program=GLES20.glCreateProgram();GLES20.glAttachShader(program,vs);GLES20.glAttachShader(program,fs);GLES20.glLinkProgram(program);int[] ok=new int[1];GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,ok,0);if(ok[0]==0)throw new IllegalStateException(GLES20.glGetProgramInfoLog(program));GLES20.glDeleteShader(vs);GLES20.glDeleteShader(fs);
  position=GLES20.glGetAttribLocation(program,"pos");texcoord=GLES20.glGetAttribLocation(program,"uv");sampler=GLES20.glGetUniformLocation(program,"camera");matrix=GLES20.glGetUniformLocation(program,"transform");
  GLES20.glGenTextures(2,texture,0);for(int i=0;i<2;i++){GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture[i]);GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);if(inputs[i]!=null)inputs[i].release();inputs[i]=new SurfaceTexture(texture[i]);final int index=i;valid[i]=false;inputs[i].setOnFrameAvailableListener(s->{synchronized(pending){pending[index]=true;}view.requestRender();});}
  fbo=0;color=0;outputWidth=0;outputHeight=0;camera.attach(inputs[0],inputs[1]);
 }
 @Override public void onSurfaceChanged(GL10 gl,int w,int h){surfaceWidth=w;surfaceHeight=h;}
 private int rotation(int index,boolean front){android.hardware.camera2.CameraCharacteristics info=index==1?camera.secondaryInfo:camera.active.info;Integer sensor=info==null?null:info.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION);int d=displayRotation*90;return ((sensor==null?90:sensor)+(front?d:-d)+360)%360;}
 private void draw(int index,int x,int y,int w,int h,boolean flip,boolean front){
  if(!valid[index])return;GLES20.glViewport(x,y,w,h);float[] orient=new float[16],combined=new float[16];Matrix.setIdentityM(orient,0);Matrix.translateM(orient,0,.5f,.5f,0);if(front)Matrix.scaleM(orient,0,-1,1,1);Matrix.rotateM(orient,0,rotation(index,front),0,0,1);
  int sourceW=index==0?camera.width:camera.secondaryWidth,sourceH=index==0?camera.height:camera.secondaryHeight;float frameRatio=(rotation(index,front)%180==0)?sourceW/(float)sourceH:sourceH/(float)sourceW;float target=w/(float)h;if(target>frameRatio)Matrix.scaleM(orient,0,1,frameRatio/target,1);else Matrix.scaleM(orient,0,target/frameRatio,1,1);
  if(flip)Matrix.scaleM(orient,0,1,-1,1);Matrix.translateM(orient,0,-.5f,-.5f,0);Matrix.multiplyMM(combined,0,transforms[index],0,orient,0);
  GLES20.glUseProgram(program);GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture[index]);GLES20.glUniform1i(sampler,0);GLES20.glUniformMatrix4fv(matrix,1,false,combined,0);vertices.position(0);uv.position(0);GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,0,vertices);GLES20.glVertexAttribPointer(texcoord,2,GLES20.GL_FLOAT,false,0,uv);GLES20.glEnableVertexAttribArray(position);GLES20.glEnableVertexAttribArray(texcoord);GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
 }
 private void composite(int w,int h,boolean output){
  GLES20.glClearColor(0,0,0,1);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);draw(0,0,0,w,h,output,camera.mainFront);
  if(camera.pip){float scale=w/(float)Math.max(1,surfaceWidth);int pw=(int)(Math.min(surfaceWidth*.24f,100*view.getResources().getDisplayMetrics().density)*scale),ph=(int)(pw*1.4f),left=(int)(25*view.getResources().getDisplayMetrics().density*scale),bottom=(int)(78*view.getResources().getDisplayMetrics().density*scale);if(bottom+ph>h)bottom=Math.max(0,h-ph-8);if(output)bottom=h-bottom-ph;draw(1,left,bottom,pw,ph,output,!camera.mainFront);}
 }
 private boolean target(int w,int h){
  if(w==outputWidth&&h==outputHeight&&pixels!=null)return true;
  if(color!=0)GLES20.glDeleteTextures(1,new int[]{color},0);if(fbo!=0)GLES20.glDeleteFramebuffers(1,new int[]{fbo},0);int[] id=new int[1];GLES20.glGenTextures(1,id,0);color=id[0];GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,color);GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,w,h,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);GLES20.glGenFramebuffers(1,id,0);fbo=id[0];GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,fbo);GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,GLES20.GL_TEXTURE_2D,color,0);if(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)!=GLES20.GL_FRAMEBUFFER_COMPLETE)return false;pixels=ByteBuffer.allocateDirect(w*h*4);outputWidth=w;outputHeight=h;return true;
 }
 @Override public void onDrawFrame(GL10 gl){
  boolean fresh=false;for(int i=0;i<2;i++){boolean update;synchronized(pending){update=pending[i];pending[i]=false;}if(update){inputs[i].updateTexImage();inputs[i].getTransformMatrix(transforms[i]);valid[i]=true;if(i==0)fresh=true;}}
  GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);composite(surfaceWidth,surfaceHeight,false);StreamSession s=stream;long now=System.nanoTime();boolean scopeDue=scopes&&now-lastScope>250_000_000L;
  if(!fresh||!camera.ready||now+1_000_000L<nextFrame||s==null&&!scopeDue)return;boolean reserved=s!=null&&s.reserve();if(s!=null&&!reserved)return;
  try{
   boolean portrait=surfaceHeight>surfaceWidth;int w=portrait?camera.height:camera.width,h=portrait?camera.width:camera.height;
   if(s==null){float factor=Math.min(1,320f/Math.max(w,h));w=Math.max(2,(int)(w*factor)/2*2);h=Math.max(2,(int)(h*factor)/2*2);}
   if(!target(w,h))throw new IllegalStateException("Не удалось создать видеобуфер OpenGL");GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,fbo);composite(w,h,true);pixels.position(0);GLES20.glReadPixels(0,0,w,h,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixels);glError=GLES20.glGetError();if(glError!=GLES20.GL_NO_ERROR)throw new IllegalStateException("OpenGL error "+glError);
   if(scopeDue){lastScope=now;int[] rgb=new int[768],vector=new int[64*64];int step=Math.max(4,(w*h/12000)*4);for(int i=0;i<w*h*4;i+=step){int r=pixels.get(i)&255,g=pixels.get(i+1)&255,b=pixels.get(i+2)&255;rgb[r]++;rgb[256+g]++;rgb[512+b]++;int u=Math.max(0,Math.min(63,(int)(32+(-.169*r-.331*g+.5*b)/4))),v=Math.max(0,Math.min(63,(int)(32+(.5*r-.419*g-.081*b)/4)));vector[v*64+u]++;}listener.scopes(rgb,vector);}
   if(reserved){pixels.rewind();s.frame(pixels,w,h,camera.fps,now/100);long interval=1_000_000_000L/camera.fps;nextFrame=nextFrame==0||now-nextFrame>interval?now+interval:nextFrame+interval;}
  }catch(Throwable e){if(reserved){s.cancelReservation();s.error=e.getMessage();}}
  finally{GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);}
 }
}
