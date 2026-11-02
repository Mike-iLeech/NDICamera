#include <jni.h>
#include <Processing.NDI.Lib.h>
#include <cstdint>
extern "C" JNIEXPORT jlong JNICALL Java_ru_ileech_ndicamera_Ndi_create(JNIEnv* e,jclass,jstring name) {
 if(!NDIlib_initialize()) return 0;
 const char* n=e->GetStringUTFChars(name,nullptr);
 NDIlib_send_create_t c; c.p_ndi_name=n; c.clock_video=false; c.clock_audio=false;
 auto s=NDIlib_send_create(&c); e->ReleaseStringUTFChars(name,n); return reinterpret_cast<jlong>(s);
}
extern "C" JNIEXPORT void JNICALL Java_ru_ileech_ndicamera_Ndi_frame(JNIEnv* e,jclass,jlong sender,jobject pixels,jint w,jint h,jint stride,jint fps,jlong timecode) {
 auto p=static_cast<uint8_t*>(e->GetDirectBufferAddress(pixels));
 if(!sender || !p || w<=0 || h<=0 || stride<w*4 || e->GetDirectBufferCapacity(pixels)<(jlong)stride*(h-1)+w*4) return;
 NDIlib_video_frame_v2_t f; f.xres=w; f.yres=h; f.FourCC=NDIlib_FourCC_type_RGBX;
 f.frame_rate_N=fps; f.frame_rate_D=1; f.picture_aspect_ratio=(float)w/h;
 f.frame_format_type=NDIlib_frame_format_type_progressive; f.timecode=timecode;
 f.p_data=p; f.line_stride_in_bytes=stride;
 NDIlib_send_send_video_v2(reinterpret_cast<NDIlib_send_instance_t>(sender),&f);
}
extern "C" JNIEXPORT void JNICALL Java_ru_ileech_ndicamera_Ndi_audio(JNIEnv* e,jclass,jlong sender,jobject pcm,jint samples,jlong timecode) {
 auto p=static_cast<int16_t*>(e->GetDirectBufferAddress(pcm));
 if(!sender || !p || samples<=0 || e->GetDirectBufferCapacity(pcm)<(jlong)samples*2*2) return;
 NDIlib_audio_frame_interleaved_16s_t f;
 f.sample_rate=48000; f.no_channels=2; f.no_samples=samples;
 f.timecode=timecode; f.reference_level=0; f.p_data=p;
 NDIlib_util_send_send_audio_interleaved_16s(reinterpret_cast<NDIlib_send_instance_t>(sender),&f);
}
extern "C" JNIEXPORT jint JNICALL Java_ru_ileech_ndicamera_Ndi_connections(JNIEnv*,jclass,jlong s) {return s?NDIlib_send_get_no_connections(reinterpret_cast<NDIlib_send_instance_t>(s),0):0;}
extern "C" JNIEXPORT void JNICALL Java_ru_ileech_ndicamera_Ndi_destroy(JNIEnv*,jclass,jlong s) {if(s)NDIlib_send_destroy(reinterpret_cast<NDIlib_send_instance_t>(s));}
