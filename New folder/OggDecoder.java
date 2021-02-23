package be.forwardproxy;

// ffmpeg -i jazz.ogg -f flac jazz-conc.flac

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;

// ffmpeg -i - -f flac -
// Naim FLAC: http://mscp3.live-streams.nl:8340/jazz-flac.flac
//  ffmpeg -i http://mscp3.live-streams.nl:8340/jazz-flac.flac  -f

public class OggDecoder {
    private Process p;
    private long threadId;
    

    public OggDecoder(long threadId) {
        //ProcessBuilder pb = new ProcessBuilder("ffmpeg" , "-i", "-", "-f", "flac", "-");
        this.threadId = threadId;
        ProcessBuilder pb = new ProcessBuilder("ffmpeg" , "-i", "http://mscp3.live-streams.nl:8340/jazz-flac.flac", "-f", "flac", "-");
        try {
            p = pb.start();
        } catch (IOException ex) {
            Util.log("It went wrong 1... " + ex.getMessage());
            ex.printStackTrace();
        }
        Util.log("[" + threadId + "]: Started ffmpeg sub process");
    }
    
    public void decode(InputStream is, OutputStream os, byte[] inputBytes, int offset) {
        Util.log("[" + threadId + "]: Starting transcoding");
        Util.log("[" + threadId + "]: offset: " + offset);
        int bytesRead;
        OutputStream pOS = p.getOutputStream();
        InputStream pIS = p.getInputStream();
        try {
            //FileOutputStream fos = new FileOutputStream("kurt"+threadId+".flac");
            while((bytesRead=pIS.read(inputBytes, offset, ForwardProxy.READ_BUFFER_SIZE-offset))!=-1) {
//            while((bytesRead=is.read(inputBytes, offset, ForwardProxy.READ_BUFFER_SIZE-offset))!=-1) {
                Util.log("[" + threadId + "]: decoder bytesRead: " + bytesRead);
                // Util.log("[" + threadId + "]: offset2: " + offset);

                offset += bytesRead;
                if(offset>=ForwardProxy.READ_BUFFER_SIZE) {
                    try {
                        byte[] respArr = Util.replace(inputBytes, "audio/oggkurt", "audio/flac");
                        os.write(respArr);
                        //fos.write(respArr);            
                        String response = new String(respArr);
                        //response = response.replace("Type: audio/ogg", "Type: audio/flac");
                        if(response.toLowerCase().startsWith("http/")) 
                                Util.log("[" + threadId + "]: Resp: " + response);

                        Util.log("[" + threadId+ "]: decoder wrote full buffer " + respArr.length);
                    } catch (SocketException ex) {  // ex.printStackTrace();
                        break;
                    }
                    offset=0;
                    //Logger.log("[" + threadId + "]: Wrote full buffer");
                }
            }
        } catch (IOException ex) {
            Util.log("[" + threadId + "]: Cannot read server response: "
                    + ex.getMessage());
            System.exit(ForwardProxy.CANNOT_READ_SERVER_RESPONSE);
        } finally {
            p.destroy();
            Util.log("[" + threadId + "]: Stopped ffmpeg sub process");
        }
    }
}
