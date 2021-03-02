package be.forwardproxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketException;

public class OggDecoder {
    private final long threadId;
    private final String forwardUrl;
    private final int ioBufferSize;

    public OggDecoder(long threadId, String forwardUrl, int ioBufferSize) {
        this.threadId = threadId;
        this.forwardUrl=forwardUrl;
        this.ioBufferSize=ioBufferSize;
    }

    public void decode(OutputStream os, byte[] inputBytes, int streamOffset) {
        ProcessBuilder pb = new ProcessBuilder("ffmpeg" , "-f", "flac", "-i", forwardUrl, 
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
        Util.log(threadId, ForwardProxy.threadCount, "OggDecoder: Start");
        InputStream pIS = p.getInputStream();
        try {
            while((bytesRead=pIS.read(inputBytes, streamOffset, ioBufferSize-streamOffset))!=-1) {
                streamOffset += bytesRead;
                if(streamOffset==ioBufferSize) {
                    try {
                        os.write(inputBytes);
                        if(Util.DEBUG) Util.deb(threadId, ForwardProxy.threadCount, "OggDecoder: Sent buffer to streamer");
                    } catch (SocketException ex) {  //ex.printStackTrace();
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
        Util.log(threadId, ForwardProxy.threadCount, "OggDecoder: Stop");
    }
}
