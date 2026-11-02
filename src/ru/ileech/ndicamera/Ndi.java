package ru.ileech.ndicamera;
import java.nio.ByteBuffer;
final class Ndi {
 static { System.loadLibrary("ndicast"); }
 static native long create(String name);
 static native void frame(long sender, ByteBuffer pixels, int width, int height, int stride, int fps, long timecode);
 static native void audio(long sender, ByteBuffer pcm, int samples, long timecode);
 static native int connections(long sender);
 static native void destroy(long sender);
}
