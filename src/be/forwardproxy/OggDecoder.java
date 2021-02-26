package be.forwardproxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketException;

public class OggDecoder {
    private final long threadId;
    private final String forwardUrl;

    public OggDecoder(long threadId, String forwardUrl) {
        this.threadId = threadId;
        this.forwardUrl=forwardUrl;
    }
    
    public void decode(OutputStream os, byte[] inputBytes, int offset) {
        ProcessBuilder pb = new ProcessBuilder("ffmpeg" , "-i", forwardUrl, 
            "-f", "wav", "-map_metadata", "0",  "-id3v2_version", "3", "-");
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p=null;
        try {         
            p = pb.start();
        } catch (IOException ex) {
            Util.log("[" + threadId + "]: Cannot start ffmpeg process: " + ex.getMessage());
            System.exit(ForwardProxy.CANNOT_START_FFMPEG);
        }

        Util.log("[" + threadId + "]: Start transcoding " + forwardUrl);
        int bytesRead;
        InputStream pIS = p.getInputStream();
        try {
            while((bytesRead=pIS.read(inputBytes, offset, ForwardProxy.READ_BUFFER_SIZE-offset))!=-1) {
                offset += bytesRead;
                if(offset==ForwardProxy.READ_BUFFER_SIZE) {
                    try {
                        os.write(inputBytes);
                        if(ForwardProxy.DEBUG) Util.log("[" + threadId + "]: Decoder wrote buffer");
                    } catch (SocketException ex) {  //ex.printStackTrace();
                        break;
                    }
                    offset=0;   
                }
            }
        } catch (Exception ex) {
            Util.log("[" + threadId + "]: Decoder: Cannot read server response: " + ex.getMessage());
        }

        // Stop process
        p.destroy();
        Util.log("[" + threadId + "]: Stop transcoding " + forwardUrl);
    }
}
