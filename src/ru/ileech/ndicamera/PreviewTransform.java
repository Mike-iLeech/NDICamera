package ru.ileech.ndicamera;

import android.opengl.Matrix;

/** Camera SurfaceTexture already contains the producer's sensor rotation and mirroring. */
final class PreviewTransform {
 static boolean swapsAxes(float[] producer){return Math.abs(producer[4])>Math.abs(producer[5]);}
 static float aspect(float[] producer,int width,int height,int displayRotation){
  boolean swap=swapsAxes(producer)^((displayRotation&1)!=0);
  return swap?height/(float)width:width/(float)height;
 }
 static float[] matrix(float[] producer,int width,int height,int rotation,int targetWidth,int targetHeight,boolean readback,float digitalZoom){
  float source=aspect(producer,width,height,rotation),target=targetWidth/(float)targetHeight;
  float sx=target<source?target/source:1,sy=target>source?source/target:1;
  float[] extra=new float[16],result=new float[16];Matrix.setIdentityM(extra,0);Matrix.translateM(extra,0,.5f,.5f,0);
  // Rotate from the display's coordinates to the producer's natural orientation only.
  Matrix.rotateM(extra,0,-rotation*90,0,0,1);Matrix.scaleM(extra,0,sx/digitalZoom,sy/digitalZoom,1);
  if(readback)Matrix.scaleM(extra,0,1,-1,1);Matrix.translateM(extra,0,-.5f,-.5f,0);Matrix.multiplyMM(result,0,producer,0,extra,0);return result;
 }
 static float[] point(float[] transform,float x,float y){float[] input={x,1-y,0,1},result=new float[4];Matrix.multiplyMV(result,0,transform,0,input,0);return new float[]{Math.max(0,Math.min(1,result[0])),Math.max(0,Math.min(1,result[1]))};}
}
