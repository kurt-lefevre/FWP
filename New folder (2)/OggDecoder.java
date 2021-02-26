package be.forwardproxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketException;

public class OggDecoder {
    private final long threadId;

    public OggDecoder(long threadId) {
        this.threadId = threadId;
    }
    
    public void decode(OutputStream os, byte[] inputBytes, int offset) {
//        ProcessBuilder pb = new ProcessBuilder("ffmpeg" , "-i", "http://mscp3.live-streams.nl:8340/jazz-flac.flac", 
//                    "-f", "wav", "-map_metadata", "0",  "-id3v2_version", "3", "-");
        ProcessBuilder pb = new ProcessBuilder("ffmpeg" , "-i", "http://thecheese.ddns.net:8004/stream", 
                    "-f", "wav", "-");
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p=null;
        try {         
            p = pb.start();
        } catch (IOException ex) {
            Util.log("[" + threadId + "]: Cannot start ffmpeg process: " + ex.getMessage());
            System.exit(ForwardProxy.CANNOT_START_FFMPEG);
        }

        Util.log("[" + threadId + "]: Start transcoding");
        int bytesRead;
        InputStream pIS = p.getInputStream();
        try {
            while((bytesRead=pIS.read(inputBytes, offset, ForwardProxy.READ_BUFFER_SIZE-offset))!=-1) {
                offset += bytesRead;
                if(offset==ForwardProxy.READ_BUFFER_SIZE) {
                    int endOfResp = Util.indexOf(inputBytes, 0, bytesRead, "\r\n\r\n");
                    if(endOfResp!=-1) Util.log("[" + threadId + "]: Resp: [" +  new String(inputBytes, 0, endOfResp) +"]");
                    try {
                        os.write(inputBytes);
                        // Util.log("[" + threadId + "]: Decoder wrote buffer");
                    } catch (SocketException ex) {  // ex.printStackTrace();
                        break;
                    }
                    offset=0;
                }
            }
        } catch (IOException ex) {
            Util.log("[" + threadId + "]: Cannot read server response: "
                    + ex.getMessage());
            System.exit(ForwardProxy.CANNOT_READ_SERVER_RESPONSE);
        }

        // Stop process
        p.destroy();
        Util.log("[" + threadId + "]: Stop transcoding");
    }
}
