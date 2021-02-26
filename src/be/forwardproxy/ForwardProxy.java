// AAC: http://mscp3.live-streams.nl:8340/jazz-high.aac
// MP3: http://mscp3.live-streams.nl:8340/jazz-low.mp3
// FLAC: http://stream.radioparadise.com/flac
// FLAC: http://mscp2.live-streams.nl:8100/flac.flac // HiOline
// FLAC: http://mscp3.live-streams.nl:8340/jazz-flac.flac  // NAIM
// FLAC: http://icecast3.streamserver24.com:18800/motherearth  // 24 bit
// FLAC: http://thecheese.ddns.net:8004/stream // 16 bit
// OCI: http://158.101.168.33:9500/

package be.forwardproxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ForwardProxy {
    private final static String APP_VERSION = "ForwardProxy V0.6.260221";
    private final static String UNDERLINE =   "========================";

    private int threadCount;
    
    // exit statuses
    public final static int MISSING_ARGUMENTS = 1;
    public final static int INVALID_PORT = 2;
    public final static int PROXY_SERVER_CREATION_FAILED = 3;
    public final static int SERVER_CONNECTION_FAILED = 4;
    public final static int IS_FAILED = 5;
    public final static int OS_FAILED = 6;
    public final static int HTTPS_NOT_SUPPORTED = 7;
    public final static int CANNOT_READ_REQUEST = 8;
    public final static int CANNOT_SEND_REQUEST = 9;
    public final static int CANNOT_READ_SERVER_RESPONSE = 10;
    public final static int CANNOT_SEND_SERVER_RESPONSE = 11;
    public final static int REQUEST_DISPATCH_FAILED = 12;
    public final static int MALFORMED_URL = 13;
    public final static int CANNOT_START_FFMPEG = 14;
    
    // stream buffer size
    public final static int READ_BUFFER_SIZE = 4096;
    public final static int SOCKET_TIMEOUT_MS = 5000;
    final static boolean DEBUG = true;

    private class RequestHandler extends Thread {
        private final Socket socket;
        private final ProxyURL proxyUrl;
        private Socket toSocket;
        private InputStream fromIS, toIS;
        private OutputStream fromOS, toOS;
        
        public RequestHandler(Socket socket, ProxyURL proxyUrl) {
            this.socket = socket;
            this.proxyUrl = proxyUrl;
        }

        private void closeConnections() {
            try { fromOS.close(); } catch (Exception ex) {};
            try { toIS.close(); } catch (Exception ex) {};
            try { toOS.close(); } catch (Exception ex) {};
            try { fromIS.close(); } catch (Exception ex) {};
            //try { socket.close(); } catch (Exception ex) {};
            try { toSocket.close(); } catch (Exception ex) {};

            Util.log("[" + this.getId() + "]: Stop thread. Count: " + --threadCount);
        }

        public void run() {
            Util.log("[" + this.getId() + "]: Start thread. Count: " +  ++threadCount);
            // get client inputstream
            try {
                //socket.setSoTimeout(SOCKET_TIMEOUT_MS);
                fromIS = socket.getInputStream();
            } catch (IOException ex) {
                Util.log("[" + this.getId() + "]: Failed to connect to Client Input Stream: "
                        + ex.getMessage());
                closeConnections();
                return;
            }

            // Connect to music service/server
            try {
                toSocket = new Socket(proxyUrl.getHost(), proxyUrl.getPort());
                //toSocket.setSoTimeout(SOCKET_TIMEOUT_MS);
            } catch (IOException ex) {
                Util.log("[" + this.getId() + "]: Failed to connect to music service: " 
                        + ex.getMessage());
                closeConnections();
                return;
            }

            // get outputstreams
            try {
                fromOS = socket.getOutputStream();
                toOS = toSocket.getOutputStream();
            } catch (IOException ex) {
                Util.log("[" + this.getId() + "]: " + "Failed to connect to Output Streams: "
                        + ex.getMessage());
                closeConnections();
                return;
            }

            // get client inputstream
            try {
                toIS = toSocket.getInputStream();
            } catch (IOException ex) {
                Util.log("[" + this.getId() + "]: Failed to connect to Server Input Stream: "
                        + ex.getMessage());
                closeConnections();
                return;
            }

            // read request from streamer
            byte[] inputBytes = new byte[READ_BUFFER_SIZE]; 
            int bytesRead=0;
            try {
                bytesRead = fromIS.read(inputBytes);
            } catch (IOException ex) {
                Util.log("[" + this.getId() + "]: Cannot read incoming request: " + ex.getMessage());
                closeConnections();
                return;
            }
            
            // create request and send it to the music service
            Util.replace(inputBytes, 0, bytesRead, "/", proxyUrl.getPath());
            if(DEBUG) Util.log("[" + this.getId() + "]: Req: [" + 
                new String(inputBytes, 0, bytesRead+proxyUrl.getPath().length()-1) +"]");
            try {
                toOS.write(inputBytes, 0, bytesRead+proxyUrl.getPath().length()-1);
            } catch (IOException ex) {
                Util.log("[" + this.getId() + "]: Cannot send request to server: " + ex.getMessage());
                closeConnections();
                return;
            }

            // read response from music service
            try {
                bytesRead=toIS.read(inputBytes);
                if(DEBUG) Util.log("[" + this.getId() + "]: bytesRead: " + bytesRead);
            } catch (IOException ex) {
                Util.log("[" + this.getId() + "]: Cannot read server response: "
                        + ex.getMessage());
                closeConnections();
                return;
            }

            // limit response to HTTP content
            bytesRead=Util.indexOf(inputBytes, 0, bytesRead, "\r\n\r\n") + 4;

            // Replace Content-Type info
            boolean needDecoding = true;
            if(Util.replace(inputBytes, 0, bytesRead, "audio/ogg", "audio/wav")==-1)
                if(Util.replace(inputBytes, 0, bytesRead, "application/ogg", "audio/wav")!=-1)
                    bytesRead-=6;
                else needDecoding = false;
            if(DEBUG) Util.log("[" + this.getId() + "]: Resp: [" + new String(inputBytes, 0, bytesRead) +"]");

            // write response to streamer
/*            try {
                fromOS.write(inputBytes, 0, bytesRead);
            } catch (IOException ex) { //ex.printStackTrace();
                Util.log("[" + this.getId() + "]: Cannot send server response: "
                        + ex.getMessage());
                closeConnections();
                return;
            }*/

            // if decode request, pass on to decoder
            if(needDecoding) {
                new OggDecoder(this.getId(), proxyUrl.getUrlString()).
                        decode(fromOS, inputBytes, bytesRead);
            } else {
                // Write till sreamer closes socket. 
                int offset=bytesRead=0;
                try {
                    while((bytesRead=toIS.read(inputBytes, offset, READ_BUFFER_SIZE-offset))!=-1) {
                        offset += bytesRead;
                        if(offset==READ_BUFFER_SIZE) {
                            try {
                                fromOS.write(inputBytes);
                                if(DEBUG) Util.log("[" + this.getId() + "]: Wrote buffer");
                            } catch (IOException ex) { break; }
                            offset=0;
                        }
                    }
                } catch (IOException ex) { 
                    Util.log("[" + this.getId() + "]: Cannot read server response: "
                        + ex.getMessage());
                }
            }

            // clean up resources
            closeConnections();
        }
    }

    private int createForwardProxyServer(int forwardProxyPort, String fwdURL) {
        // parse forward URL
        ProxyURL proxyUrl = new ProxyURL(fwdURL);
        
        // Create the Server Socket for the Proxy
        ServerSocket forwardProxyServerSocket ;
        try {
            forwardProxyServerSocket = new ServerSocket(forwardProxyPort);
        } catch (IOException ex) {
            Util.log("Failed to create ForwardProxy on port [" + forwardProxyPort + "]: "
                    + ex.getMessage());
            return PROXY_SERVER_CREATION_FAILED;
        }
        
        Util.log("Listening...");
        while(true) {
            try {
                new RequestHandler(forwardProxyServerSocket.accept(), proxyUrl).start();
            } catch (IOException ex) {
                Util.log("Failed to dispatch request: " + ex.getMessage());
                return REQUEST_DISPATCH_FAILED;
            }
        }
    }

    /*public int start2(String[] args) {
        try {
            FileOutputStream fos = new FileOutputStream("fons.flac");
            byte[] bytes = new byte[READ_BUFFER_SIZE];
            OggDecoder dec = new OggDecoder(-1);
            dec.decode(fos, bytes);
            fos.close();
        } catch (Exception ex) {
            Util.log("Fout! " + ex);
        }
        return 0;
    }*/
    
    public int start(String[] args) {
        // Check # arguments
        if(args.length != 2) {
            System.err.println(APP_VERSION + "\n" + UNDERLINE +
                "\nUSAGE:\n  ForwardProxy {port} {to URL}");
            return MISSING_ARGUMENTS;
        }
        
        int forwardProxyPort; // listening port for ForwardProxy server
        String fwdURL;  // URL to forward incoming requests to

        // parse port
        try {
            forwardProxyPort=Integer.parseInt(args[0]);
        } catch(NumberFormatException e) {
            System.err.println(APP_VERSION + "\n" + UNDERLINE +
                "\nCan't parse port [" + args[0] + "]");
            return INVALID_PORT;
        }
        
        // check for https
        fwdURL = args[1];
        if(fwdURL.toLowerCase().contains("https:/")) {
            System.err.println(APP_VERSION + "\n" + UNDERLINE +
                "\nHTTPS URLs are not supported");
            return HTTPS_NOT_SUPPORTED;
        }
        
        // Arguments parsed successfully
        Util.log(APP_VERSION);
        Util.log(UNDERLINE);
        Util.log("ForwardProxy port: " + forwardProxyPort);
        Util.log("Forward URL: " + fwdURL);
        
        // start proxyserver
        return createForwardProxyServer(forwardProxyPort, fwdURL);
    }

    
    public static void main(String[] args) {
        System.exit(new ForwardProxy().start(args));
    }
}
