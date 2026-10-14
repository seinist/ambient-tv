package com.ambient.tv.ui.player.audio;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.Decoder;
import androidx.media3.decoder.DecoderException;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import java.util.ArrayDeque;

/** Bounded worker queues; one encoded access unit per output for AudioSink accounting. */
@UnstableApi
public final class LocalAudioDecoder implements Decoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException> {
    private final ArrayDeque<DecoderInputBuffer> free = new ArrayDeque<>();
    private final ArrayDeque<DecoderInputBuffer> pending = new ArrayDeque<>();
    private final ArrayDeque<SimpleDecoderOutputBuffer> ready = new ArrayDeque<>();
    private final Thread worker;
    private final String codec;
    private final byte[] extra;
    public final Format outputFormat;
    public final Format inputFormat;
    public Runnable onReleased = () -> {};
    private final int channels;
    private final boolean ac3;
    private boolean released;
    private long generation, startTimeUs = Long.MIN_VALUE;
    private DecoderInputBuffer dequeued;
    private DecoderException failure;

    public LocalAudioDecoder(String codec, Format input, int channels, boolean ac3) {
        this.inputFormat=input;
        this.codec=codec; this.channels=channels; this.ac3=ac3;
        extra=input.initializationData.isEmpty()?new byte[0]:input.initializationData.get(0);
        outputFormat=new Format.Builder().setSampleMimeType(ac3?"audio/ac3":"audio/raw")
            .setSampleRate(48000).setChannelCount(channels).setPcmEncoding(C.ENCODING_PCM_16BIT).build();
        // TrueHD packets can contain only 40 samples. Keep more than one renderer tick
        // queued even for those packets; eight buffers cover less than 7 ms at 48 kHz.
        for (int i=0;i<32;i++) free.add(new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT));
        worker=new Thread(this::run,"LocalAudioCodec"); worker.start();
    }
    @Override public String getName() { return ac3?"Local FFmpeg AC3":"Local FFmpeg PCM"; }
    @Override public synchronized void setOutputStartTimeUs(long time) { startTimeUs=time; }
    private void check() throws DecoderException { if(failure!=null) throw failure; }
    @Override public synchronized DecoderInputBuffer dequeueInputBuffer() throws DecoderException {
        check(); if (dequeued!=null) throw new IllegalStateException("Input already dequeued");
        dequeued=free.poll(); return dequeued;
    }
    @Override public synchronized void queueInputBuffer(DecoderInputBuffer input) throws DecoderException {
        check(); if(input!=dequeued) throw new IllegalStateException("Foreign input");
        dequeued=null; pending.add(input); notifyAll();
    }
    @Override public synchronized SimpleDecoderOutputBuffer dequeueOutputBuffer() throws DecoderException {
        check(); SimpleDecoderOutputBuffer output=ready.poll(); notifyAll(); return output;
    }
    @Override public synchronized void flush() {
        generation++; startTimeUs=Long.MIN_VALUE;
        if(dequeued!=null) { dequeued.clear(); free.add(dequeued); dequeued=null; }
        while(!pending.isEmpty()) { DecoderInputBuffer input=pending.remove(); input.clear(); free.add(input); }
        ready.clear(); notifyAll();
    }
    @Override public void release() {
        synchronized(this) { released=true; notifyAll(); }
        boolean interrupted=false;
        for (;;) { try { worker.join(); break; } catch(InterruptedException e) { interrupted=true; } }
        if(interrupted) Thread.currentThread().interrupt();
        onReleased.run();
    }
    private synchronized void emit(byte[] bytes,long time,boolean eos,long epoch) throws InterruptedException {
        while(!released && generation==epoch && ready.size()>=16) wait();
        if(released || generation!=epoch) return;
        if(!eos && time<startTimeUs) return;
        SimpleDecoderOutputBuffer output=new SimpleDecoderOutputBuffer(buffer -> {});
        if(eos) output.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
        else { output.init(time,bytes.length).put(bytes); output.data.flip(); }
        ready.add(output);
    }
    private void run() {
        long nativeHandle=0, nativeGeneration=-1;
        try {
            for (;;) {
                DecoderInputBuffer input; long epoch;
                synchronized(this) {
                    while(!released && pending.isEmpty()) wait();
                    if(released) return;
                    input=pending.remove(); epoch=generation;
                }
                if(nativeGeneration!=epoch) {
                    if(nativeHandle!=0) LocalAudioBridge.destroy(nativeHandle);
                    nativeHandle=LocalAudioBridge.create(codec,extra,channels,ac3);
                    if(nativeHandle==0) throw new DecoderException("Local audio codec initialization failed");
                    nativeGeneration=epoch;
                }
                boolean eos=input.isEndOfStream();
                int status=LocalAudioBridge.send(nativeHandle,eos?null:input.data,
                    eos?0:input.data.remaining(),input.timeUs);
                if(status<0) throw new DecoderException("Local audio conversion failed: "+status);
                byte[] packet;
                while((packet=LocalAudioBridge.receive(nativeHandle))!=null)
                    emit(packet,LocalAudioBridge.timestamp(nativeHandle),false,epoch);
                if(eos) emit(null,0,true,epoch);
                synchronized(this) { input.clear(); free.add(input); }
            }
        } catch(Exception e) {
            synchronized(this) { failure=e instanceof DecoderException?(DecoderException)e:new DecoderException("Local audio worker failed",e); }
        } finally { if(nativeHandle!=0) LocalAudioBridge.destroy(nativeHandle); }
    }
}
