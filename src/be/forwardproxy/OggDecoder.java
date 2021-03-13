/*¨
  16 bit: ffmpeg -nostats -loglevel 0 -f ogg -i $1 -f wav -
  16/24 bit: wget -qO- $1|flac -d --totally-silent --ogg -c -|ffmpeg -nostats -loglevel 0 -f wav -i - -c:a copy -f wav -
  See: https://stackoverflow.com/questions/56138370/problems-piping-ffmpeg-to-flac-encoder

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
    private static final ProxyLog logger=ProxyLog.getInstance();
    private static final String APPLICATION_DIR = logger.getApplicationDir();
    private static final String DECODE_SCRIPT = "decode";
    private static final String KILL_SCRIPT = "killprocs.sh";
    private long totalBytes;
    private final long threadId;
    private final OutputStream os;

    private class StaleThreadMonitor extends Thread {
        private static final int CHECK_INTERVAL_MS=60000;
        private boolean loop=true;
        private long bytesProcessed;
        private final long pId;

        public StaleThreadMonitor(long pId) {
            this.pId = pId;
        }

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
                    if(ProxyLog.DEBUG) logger.deb(threadId, "StaleThreadMonitor: Stale thread detected");
                    // Close the stream. Generates an exception that terminates the thread
                    try {
                        os.close();
                    } catch (IOException ex) {
                            logger.log(threadId, "StaleThreadMonitor: Failed to close stream:" 
                                + ex.getMessage());
                    }

                    // Kill the decode process and its decendants
                    ProcessBuilder pk = new ProcessBuilder(APPLICATION_DIR + KILL_SCRIPT, Long.toString(pId));
                    try {
                        pk.start();
                    } catch (Exception ex) {
                        logger.log(threadId, "StaleThreadMonitor: Failed to run process kill script "
                            + APPLICATION_DIR + KILL_SCRIPT + ": " + ex.getMessage()); 
                    }
                    logger.log(threadId, "StaleThreadMonitor: Terminated stale thread");
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
        String decodeScript = APPLICATION_DIR + DECODE_SCRIPT + proxyUrl.getDecodeScriptId()+".sh";
        ProcessBuilder pb = new ProcessBuilder(decodeScript, proxyUrl.getUrlString());
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p=null;
        try {         
            p = pb.start();
        } catch (IOException ex) {
            logger.log(threadId, "OggDecoder: Cannot start decode script " + 
                    decodeScript + ": "+ ex.getMessage());
            System.exit(ForwardProxy.CANNOT_START_DECODE_SCRIPT);
        }

        // start stale detector for this thread
        StaleThreadMonitor staleThreadMonitor = new StaleThreadMonitor(p.pid());
        staleThreadMonitor.start();
        
        logger.log(threadId, "OggDecoder: Start for " + proxyUrl.getFriendlyName());
        proxyUrl.setActive();
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
        proxyUrl.setInActive();
        logger.adjustDecoderCount(-1);
        logger.log(threadId, "OggDecoder: Stop for " + proxyUrl.getFriendlyName() + 
                formatTransferRate(totalBytes, totalTime));
    }
}
