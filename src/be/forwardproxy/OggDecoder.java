/*¨
  16 bit: ffmpeg -nostats -loglevel 0 -f ogg -i $1 -f wav -
  16/24 bit: wget -qO- $1|flac -d --totally-silent --ogg -c -|ffmpeg -nostats -loglevel 0 -f wav -i - -c:a copy -f wav -

  Intense radio: http://secure.live-streams.nl/flac.flac
  See: https://stackoverflow.com/questions/56138370/problems-piping-ffmpeg-to-flac-encoder

  URLs:
  AAC: http://mscp3.live-streams.nl:8340/jazz-high.aac
  MP3: http://mscp3.live-streams.nl:8340/jazz-low.mp3
  FLAC: http://stream.radioparadise.com/flac
  FLAC: http://mscp2.live-streams.nl:8100/flac.flac // HiOline
  FLAC: http://mscp3.live-streams.nl:8340/jazz-flac.flac  // NAIM
  FLAC: http://icecast3.streamserver24.com:18800/motherearth  // 24 bit
  FLAC: http://thecheese.ddns.net:8004/stream // 16 bit
  FLAC: http://secure.live-streams.nl/flac.flac // 24 bit
  OCI: http://158.101.168.33:9500/
  https://github.com/ymnk/jorbis to decode ogg
*/



package be.forwardproxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketException;

public class OggDecoder {
    private final long threadId;
    private final ProxyURL proxyUrl;
    private final int ioBufferSize;

    public OggDecoder(long threadId, ProxyURL proxyUrl, int ioBufferSize) {
        this.threadId = threadId;
        this.proxyUrl=proxyUrl;
        this.ioBufferSize=ioBufferSize;
    }

    private String formatTransferRate(long bytes, long time) {
        double bytesPerSec = bytes * 1000000000/(double)time;
        
        // put most likely option first
        if(bytesPerSec>=1024 && bytesPerSec<1048576)
            return String.format(" (%.1f kB/s)", bytesPerSec/1024);

        if(bytesPerSec<1024)
            return String.format(" (%.1f B/s)", bytesPerSec);

        return String.format(" (%.1f mB/s)", bytesPerSec/1048576);
    }
    
    public void decode(OutputStream os, byte[] inputBytes, int streamOffset) {
//      ProcessBuilder pb = new ProcessBuilder("ffmpeg" , "-f", "ogg", "-i", proxyUrl.getUrlString(), "-f", "wav", "-map_metadata", "0",  "-id3v2_version", "3", "-");

        ProcessBuilder pb = new ProcessBuilder("./decode.sh", proxyUrl.getUrlString());
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p=null;
        try {         
            p = pb.start();
        } catch (IOException ex) {
            Util.log(threadId, ForwardProxy.threadCount, "OggDecoder: Cannot start ffmpeg process: " + ex.getMessage());
            System.exit(ForwardProxy.CANNOT_START_FFMPEG);
        }
        
        int bytesRead;
        Util.log(threadId, ForwardProxy.threadCount, "Start OggDecoder for " + proxyUrl.getFriendlyName());
        InputStream pIS = p.getInputStream();

        long totalBytes=0;
        long startTime = System.nanoTime();
        long totalTime=0;
        try {
            while((bytesRead=pIS.read(inputBytes, streamOffset, ioBufferSize-streamOffset))!=-1) {
                streamOffset += bytesRead;
                if(streamOffset==ioBufferSize) {
                    try {
                        os.write(inputBytes);
                        totalBytes += ioBufferSize;
                        if(Util.DEBUG) Util.deb(threadId, ForwardProxy.threadCount, "OggDecoder: Sent buffer to streamer");
                    } catch (SocketException ex) {  //ex.printStackTrace();
                        totalTime=System.nanoTime() - startTime;
                        if(Util.DEBUG) Util.deb(threadId, ForwardProxy.threadCount, "OggDecoder: Stream stopped: "
                            + ex.getMessage());
                        break;
                    }
                    streamOffset=0;   
                }
            }
        } catch (Exception ex) {
            Util.log(threadId, ForwardProxy.threadCount, "OggDecoder: Cannot read server response: " + ex.getMessage());
        }

        // Stop process
        p.destroy();
        Util.log(threadId, ForwardProxy.threadCount, "Stop OggDecoder for " + 
                proxyUrl.getFriendlyName() + formatTransferRate(totalBytes, totalTime));
    }
}
