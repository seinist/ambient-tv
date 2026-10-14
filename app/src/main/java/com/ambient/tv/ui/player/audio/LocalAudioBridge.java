package com.ambient.tv.ui.player.audio;

import androidx.annotation.Keep;
import java.nio.ByteBuffer;

/** Packet-only FFmpeg bridge. Never opens a URL or touches the video stream. */
@Keep
public final class LocalAudioBridge {
    public static final boolean AVAILABLE;
    static {
        boolean loaded;
        try { System.loadLibrary("ambientaudio"); loaded = true; }
        catch (LinkageError error) { loaded = false; }
        AVAILABLE = loaded;
    }
    public static native boolean supports(String codec);
    public static native long create(String codec, byte[] extra, int channels, boolean ac3);
    public static native int send(long handle, ByteBuffer data, int size, long timeUs);
    public static native byte[] receive(long handle);
    public static native long timestamp(long handle);
    public static native void destroy(long handle);
    private LocalAudioBridge() {}
}
