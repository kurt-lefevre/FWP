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
        
        if(bytesPerSec<1024)
            return String.format(" (%.1f B/S)", bytesPerSec);

        if(bytesPerSec>=1024 && bytesPerSec<1048576)
            return String.format(" (%.1f KB/S)", bytesPerSec/1024);

        return String.format(" (%.1f MB/S)", bytesPerSec/1048576);
    }
    
    public void decode(OutputStream os, byte[] inputBytes, int streamOffset) {
        ProcessBuilder pb = new ProcessBuilder("ffmpeg" , "-f", "flac", "-i", proxyUrl.getUrlString(), 
            "-f", "wav", "-map_metadata", "0",  "-id3v2_version", "3", "-");
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
                        if(Util.DEBUG) Util.deb(threadId, ForwardProxy.threadCount, "OggDecoder: Stream stopped");
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
