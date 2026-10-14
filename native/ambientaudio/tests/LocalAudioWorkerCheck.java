import com.ambient.tv.ui.player.audio.LocalAudioDecoder;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Exercises bounded queues, EOS drain, seek generation, and worker teardown. */
public final class LocalAudioWorkerCheck {
    public static void main(String[] args) throws Exception {
        byte[] input=Files.readAllBytes(Paths.get(args[0]));
        Format format=new Format.Builder().setSampleMimeType("audio/eac3").setChannelCount(6).setSampleRate(48000).build();
        LocalAudioDecoder decoder=new LocalAudioDecoder("eac3",format,6,true);
        try {
            for(int round=0;round<3;round++) {
                // Start a new stream after EOS, then seek while its packets are in flight.
                decoder.flush();
                // Leave old packets in flight to test generation cancellation, not just EOS reset.
                for (int queued=0; queued<8; queued++) {
                    DecoderInputBuffer stale=decoder.dequeueInputBuffer();
                    if (stale==null) break;
                    stale.ensureSpaceForWrite(2560); stale.data.put(input,0,2560); stale.flip();
                    stale.timeUs=0; decoder.queueInputBuffer(stale);
                }
                decoder.flush();
                long start=5000000L+round*1000000;
                decoder.setOutputStartTimeUs(start);
                int sent=0, received=0; boolean ended=false;
                long deadline=System.nanoTime()+10000000000L;
                while(!ended && System.nanoTime()<deadline) {
                    DecoderInputBuffer buffer=sent<=10?decoder.dequeueInputBuffer():null;
                    if(buffer!=null) {
                        if(sent==10) buffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
                        else { buffer.ensureSpaceForWrite(2560); buffer.data.put(input,sent*2560,2560); buffer.flip(); }
                        buffer.timeUs=start+sent*32000; decoder.queueInputBuffer(buffer); sent++;
                    }
                    SimpleDecoderOutputBuffer output=decoder.dequeueOutputBuffer();
                    if(output!=null) {
                        if(output.isEndOfStream()) ended=true;
                        else {
                            if(output.timeUs!=start+received*32000 || output.data.remaining()!=2560)
                                throw new AssertionError("Stale timestamp or invalid access unit: "+output.timeUs);
                            received++;
                        }
                        output.release();
                    } else Thread.sleep(1);
                }
                if(!ended || received!=10) throw new AssertionError("Worker lost frames: "+received);
                System.out.println("PASS worker seek/EOS round="+round+" frames="+received);
            }
        } finally { decoder.release(); }
    }
}
