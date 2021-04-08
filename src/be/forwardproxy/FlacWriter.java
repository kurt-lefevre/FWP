package be.forwardproxy;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketException;

public class FlacWriter {
    private static final ProxyLog logger=ProxyLog.getInstance();
    private final int IO_BUF_SIZE;
    private final long threadId;
    private InputStream is;
    private final OutputStream os;
    
    public FlacWriter(long threadId, int ioBufferSize, OutputStream os) {
        this.IO_BUF_SIZE = ioBufferSize;
        this.threadId = threadId;
        this.os = os;
    }
  
    public void decode(byte[] inputBytes, int streamOffset) {
        try {
            //is = new URL("http://mscp3.live-streams.nl:8340/jazz-flac.flac").openStream();
            // is = new URL("http://secure.live-streams.nl/flac.flac").openStream();
            is = new FileInputStream("sara.flac");
        } catch (Exception ex) {
            logger.log(threadId, "FlacWriter: Can't open FLAC file: " + ex.getMessage());
            return;
        }
        
        int bytesRead=0;
        try {
            while((bytesRead=is.read(inputBytes, streamOffset, IO_BUF_SIZE-streamOffset))!=-1) {
                streamOffset += bytesRead;
                if(streamOffset==IO_BUF_SIZE) {
                    try {
                        os.write(inputBytes);
                        if(ProxyLog.DEBUG) logger.deb(threadId, "FlacWriter: Wrote buffer");
                    } catch (SocketException ex) {
                        if(ProxyLog.DEBUG) logger.deb(threadId, "FlacWriter: Stream stopped: "
                            + ex.getMessage());
                        break;
                    }
                }
                streamOffset=0;   
            }
        } catch (IOException ex) {  
            logger.log(threadId, "FlacWriter: Can't read flac file: " + ex.getMessage());
        }
    }
}
