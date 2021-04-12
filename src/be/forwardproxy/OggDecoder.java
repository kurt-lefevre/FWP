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
    private final int IO_BUFFER_SIZE;
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

        public void killDecodeProcess() {
            ProcessBuilder pk = new ProcessBuilder(APPLICATION_DIR + KILL_SCRIPT, Long.toString(pId));
            try {
                pk.start();
            } catch (Exception ex) {
                logger.log(threadId, "StaleThreadMonitor: Failed to run process kill script "
                    + APPLICATION_DIR + KILL_SCRIPT + ": " + ex.getMessage()); 
            }
        }

        public void run() {
            if(ProxyLog.DEBUG) logger.deb(threadId, "StaleThreadMonitor: Start");
            logger.incThreadCount();
            while(loop) {
                bytesProcessed = totalBytes;

                // wait
                try { Thread.sleep(CHECK_INTERVAL_MS); } 
                catch (InterruptedException ex) {
                    if(ProxyLog.DEBUG) logger.deb(threadId, "StaleThreadMonitor: Terminated during wait");
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
                    killDecodeProcess();
                    
                    logger.log(threadId, "StaleThreadMonitor: Terminated stale thread");
                    break;
                }
            }
            
            logger.decThreadCount();
            if(ProxyLog.DEBUG) logger.deb(threadId, "StaleThreadMonitor: Stop");
        }
    }
    
    public OggDecoder(long threadId, ProxyURL proxyUrl, int ioBufferSize, OutputStream os) {
        this.proxyUrl=proxyUrl;
        this.threadId=threadId;
        this.IO_BUFFER_SIZE=ioBufferSize;
        this.os=os;
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
        
        logger.incDecoderCount();
        logger.log(threadId, "OggDecoder: Start for " + proxyUrl.getFriendlyName());
        proxyUrl.incClientCount();
        InputStream pIS = p.getInputStream();
        long startTime = System.nanoTime();
        long totalTime=0;
        int bytesRead=0;
        try {
            while((bytesRead=pIS.read(inputBytes, streamOffset, IO_BUFFER_SIZE-streamOffset))!=-1) {
                streamOffset += bytesRead;
                if(streamOffset==IO_BUFFER_SIZE) {
                    try {
                        os.write(inputBytes);
                        totalBytes += IO_BUFFER_SIZE;
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
        staleThreadMonitor.killDecodeProcess();
        staleThreadMonitor.exit();
        //p.destroy();
        proxyUrl.decClientCount();
        logger.decDecoderCount();
        logger.log(threadId, "OggDecoder: Stop for " + proxyUrl.getFriendlyName() + 
                formatTransferRate(totalBytes, totalTime));
    }
}
