package be.forwardproxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketException;

// ffmpeg -i - -f flac -

public class OggDecoder {
    private Process p;
    private long threadId;

    public OggDecoder(long threadId) {
        //ProcessBuilder pb = new ProcessBuilder("ffmpeg" , "-i", "-", "-f", "flac", "-");
//        ProcessBuilder pb = new ProcessBuilder("ffmpeg" , "-i", "http://mscp2.live-streams.nl:8100/flac.flac", "-f", "flac", "-");
        ProcessBuilder pb = new ProcessBuilder("ffmpeg" , "-i", "http://mscp3.live-streams.nl:8340/jazz-flac.flac", "-f", "flac", "-");
//        ProcessBuilder pb = new ProcessBuilder("ffmpeg" , "-i", "http://mscp3.live-streams.nl:8340/jazz-flac.flac", "-f", "wav", "-");
//        ProcessBuilder pb = new ProcessBuilder("ffmpeg" , "-i", "http://mscp3.live-streams.nl:8340/jazz-flac.flac", "-f", "mp3", "-");
        this.threadId = threadId;
        try {
            p = pb.start();
        } catch (IOException ex) {
            Util.log("[" + threadId + "]: Cannot start ffmpeg: " + ex.getMessage());
            System.exit(ForwardProxy.CANNOT_START_FFMPEG);
        }
        Util.log("[" + threadId + "]: Started ffmpeg sub process");
    }

    public void stop() {
        p.destroy();
    }
    
    public void decode(OutputStream os, byte[] inputBytes, int offset) {
        Util.log("[" + threadId + "]: Starting transcoding");
        // Util.log("[" + threadId + "]: offset: " + offset);
        int bytesRead;
        InputStream pIS = p.getInputStream();
        byte[] respArr;
        try {
            while((bytesRead=pIS.read(inputBytes, offset, ForwardProxy.READ_BUFFER_SIZE-offset))!=-1) {
                // Util.log("[" + threadId + "]: Decoder bytesRead: " + bytesRead);

                offset += bytesRead;
                if(offset==ForwardProxy.READ_BUFFER_SIZE) {
                    respArr = Util.replace(inputBytes, "/ogg", "/flac");

                    int endOfResp = Util.indexOf(respArr, "\r\n\r\n");
                    if(endOfResp!=-1) Util.log("[" + threadId + "]: Resp: [" + 
                            new String(respArr, 0, endOfResp) +"]");
                    try {
                        os.write(respArr);
                        Util.log("[" + threadId + "]: Decoder wrote buffer");
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
    }
}
