import com.ambient.tv.ui.player.audio.LocalAudioBridge;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Runs via app_process on Android; input is 10 EAC3 5.1 frames at 640 kbit/s. */
public final class LocalAudioNativeCheck {
    public static void main(String[] args) throws Exception {
        if (!LocalAudioBridge.AVAILABLE || !LocalAudioBridge.supports("eac3")) throw new AssertionError("Codec unavailable");
        byte[] input=Files.readAllBytes(Paths.get(args[0]));
        String codec=args.length>1?"truehd":"eac3";
        int[] sizes=args.length>1?Files.readAllLines(Paths.get(args[1])).stream().filter(line -> !line.isEmpty()).mapToInt(Integer::parseInt).toArray():new int[]{2560,2560,2560,2560,2560,2560,2560,2560,2560,2560};
        for (boolean ac3 : new boolean[]{true,false,true}) {
            long h=LocalAudioBridge.create(codec,new byte[0],ac3?6:2,ac3);
            if(h==0) throw new AssertionError("Create failed");
            int frames=0, bytes=0; long previous=Long.MIN_VALUE;
            try {
                int offset=0;
                for(int i=0;i<=sizes.length;i++) {
                    ByteBuffer buffer=null;
                    if(i<sizes.length) { buffer=ByteBuffer.allocateDirect(sizes[i]); buffer.put(input,offset,sizes[i]).flip(); offset+=sizes[i]; }
                    int status=LocalAudioBridge.send(h,buffer,i<sizes.length?sizes[i]:0,5000000+i*32000);
                    if(status<0) throw new AssertionError("Decode failed: "+status);
                    byte[] output;
                    while((output=LocalAudioBridge.receive(h))!=null) {
                        long pts=LocalAudioBridge.timestamp(h);
                        if(pts<5000000 || pts<=previous) throw new AssertionError("Bad timestamp: "+pts);
                        previous=pts;
                        if(ac3 && (output.length!=2560 || output[0]!=11 || output[1]!=119)) throw new AssertionError("Invalid AC3 access unit");
                        frames++; bytes+=output.length;
                    }
                }
                if(ac3 && frames!=10) throw new AssertionError("Lost AC3 frames: "+frames);
                if(!ac3 && bytes!=10*1536*2*2) throw new AssertionError("Lost PCM samples: "+bytes);
                System.out.println("PASS "+codec+" -> "+(ac3?"AC3 5.1":"PCM stereo")+" frames="+frames+" bytes="+bytes);
            } finally { LocalAudioBridge.destroy(h); }
        }
    }
}
