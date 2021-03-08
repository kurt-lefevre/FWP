/*¨
  16 bit: ffmpeg -nostats -loglevel 0 -f ogg -i $1 -f wav -
  16/24 bit: wget -qO- $1|flac -d --totally-silent --ogg -c -|ffmpeg -nostats -loglevel 0 -f wav -i - -c:a copy -f wav -
  See: https://stackoverflow.com/questions/56138370/problems-piping-ffmpeg-to-flac-encoder

  URLs:
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
    private final ProxyURL proxyUrl;
    private final int ioBufferSize;
    private final ProxyLog logger=ProxyLog.getInstance();
    private long totalBytes;
    private final long threadId;
    private OutputStream os;

    private class StaleThreadMonitor extends Thread {
        private static final int CHECK_INTERVAL_MS=60000;
        private boolean loop=true;
        private long bytesProcessed;

        public void exit() {
            loop=false;
            this.interrupt();
        }
        
        public void run() {
            logger.adjustThreadCount(1);
            if(ProxyLog.DEBUG) logger.deb(threadId, "StaleThreadMonitor: Start");
            while(loop) {
                bytesProcessed = totalBytes;

                // wait
                try { Thread.sleep(CHECK_INTERVAL_MS); } 
                catch (InterruptedException ex) {
                    if(ProxyLog.DEBUG) logger.deb(threadId, "StaleThreadMonitor: Terminated during sleep");
                    break;
                }
                
                // check if thread is stale
                if(ProxyLog.DEBUG) logger.deb(threadId, "StaleThreadMonitor: bytesProcessed: " +
                        bytesProcessed + " - totalBytes: " + totalBytes);
                if(totalBytes==bytesProcessed) {
                    logger.log(threadId, "StaleThreadMonitor: Terminated stale thread");
                    try {
                        os.close();
                    } catch (IOException ex) {
                        logger.log(threadId, "StaleThreadMonitor: Failed to close stream:" 
                                + ex.getMessage());
                    }
                    break;
                }
            }
            
            logger.adjustThreadCount(-1);
            if(ProxyLog.DEBUG) logger.deb(threadId, "StaleThreadMonitor: Stop");
        }
    }
    
    public OggDecoder(long threadId, ProxyURL proxyUrl, int ioBufferSize, OutputStream os) {
        this.proxyUrl=proxyUrl;
        this.threadId=threadId;
        this.ioBufferSize=ioBufferSize;
        this.os=os;
        logger.adjustDecoderCount(1);
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
    
    public void decode(byte[] inputBytes, int streamOffset) {
//      ProcessBuilder pb = new ProcessBuilder("ffmpeg" , "-f", "ogg", "-i", proxyUrl.getUrlString(), "-f", "wav", "-map_metadata", "0",  "-id3v2_version", "3", "-");
        ProcessBuilder pb = new ProcessBuilder("./decode.sh", proxyUrl.getUrlString());
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p=null;
        try {         
            p = pb.start();
        } catch (IOException ex) {
            logger.log(threadId, "OggDecoder: Cannot start decode script: " + ex.getMessage());
            System.exit(ForwardProxy.CANNOT_START_DECODE_SCRIPT);
        }

        // start stale detector for this thread
        StaleThreadMonitor staleThreadMonitor = new StaleThreadMonitor();
        staleThreadMonitor.start();
        
        logger.log(threadId, "OggDecoder: Start for " + proxyUrl.getFriendlyName());
        InputStream pIS = p.getInputStream();
        long startTime = System.nanoTime();
        long totalTime=0;
        int bytesRead=0;
        try {
            while((bytesRead=pIS.read(inputBytes, streamOffset, ioBufferSize-streamOffset))!=-1) {
                streamOffset += bytesRead;
                if(streamOffset==ioBufferSize) {
                    try {
                        os.write(inputBytes);
                        totalBytes += ioBufferSize;
                        if(ProxyLog.DEBUG) logger.deb(threadId, "OggDecoder: Sent buffer to streamer");
                    } catch (SocketException ex) {  //ex.printStackTrace();
                        totalTime=System.nanoTime() - startTime;
                        if(ProxyLog.DEBUG) logger.deb(threadId, "OggDecoder: Stream stopped: "
                            + ex.getMessage());
                        break;
                    }
                    streamOffset=0;   
                }
            }
        } catch (Exception ex) {
            logger.log(threadId, "OggDecoder: Cannot read server response: " + ex.getMessage());
        }

        // Cleanup
        staleThreadMonitor.exit();
        p.destroy();
        logger.adjustDecoderCount(-1);
        logger.log(threadId, "OggDecoder: Stop for " + proxyUrl.getFriendlyName() + 
                formatTransferRate(totalBytes, totalTime));
    }
}
