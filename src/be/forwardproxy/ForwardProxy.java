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

public class ForwardProxy {
    private final static String APP_VERSION = "ForwardProxy V0.8.280221";
    private final static String UNDERLINE =   "========================";

    public static int threadCount;
    
    // exit statuses
    public final static int MISSING_ARGUMENTS = 1;
    public final static int INVALID_PORT = 2;
    public final static int PROXY_SERVER_CREATION_FAILED = 3;
    public final static int HTTPS_NOT_SUPPORTED = 4;
    public final static int REQUEST_DISPATCH_FAILED = 5;
    public final static int CANNOT_START_FFMPEG = 6;
    public final static int MALFORMED_URL = 7;
    
    // stream buffer size
    public final static int IO_BUFFER_SIZE_KB = 256;
    public final static int IO_BUFFER_SIZE = IO_BUFFER_SIZE_KB * 1024;

    private class RequestHandler extends Thread {
        private final Socket socket;
        private final ProxyURL proxyUrl;
        private Socket toSocket;
        private InputStream fromIS, toIS;
        private OutputStream fromOS, toOS;
        private final long threadId= this.getId();
        
        public RequestHandler(Socket socket, ProxyURL proxyUrl) {
            this.socket = socket;
            this.proxyUrl = proxyUrl;
        }

        private void closeConnections() {
            try { fromOS.close(); } catch (Exception ex) {};
            try { toIS.close(); } catch (Exception ex) {};
            try { toOS.close(); } catch (Exception ex) {};
            try { fromIS.close(); } catch (Exception ex) {};
            try { socket.close(); } catch (Exception ex) {};
            try { toSocket.close(); } catch (Exception ex) {};

            Util.log(threadId, --threadCount, "Stop thread for " + proxyUrl);
        }

        public void run() {
            Util.log(threadId, ++threadCount, "Start thread for " + proxyUrl);
            // get client inputstream
            try {
                fromIS = socket.getInputStream();
            } catch (IOException ex) {
                Util.log(threadId, threadCount, "Failed to connect to Client Input Stream: "
                        + ex.getMessage());
                closeConnections();
                return;
            }

            // Connect to music service/server
            try {
                toSocket = new Socket(proxyUrl.getHost(), proxyUrl.getPort());
            } catch (IOException ex) {
                Util.log(threadId, threadCount, "Failed to connect to music service: " 
                        + ex.getMessage());
                closeConnections();
                return;
            }

            // get outputstreams
            try {
                fromOS = socket.getOutputStream();
                toOS = toSocket.getOutputStream();
            } catch (IOException ex) {
                Util.log(threadId, threadCount, "Failed to connect to Output Streams: "
                        + ex.getMessage());
                closeConnections();
                return;
            }

            // get client inputstream
            try {
                toIS = toSocket.getInputStream();
            } catch (IOException ex) {
                Util.log(threadId, threadCount, "Failed to connect to Server Input Stream: "
                        + ex.getMessage());
                closeConnections();
                return;
            }

            // read request from streamer
            byte[] inputBytes = new byte[IO_BUFFER_SIZE]; 
            int bytesRead=0;
            try {
                bytesRead = fromIS.read(inputBytes);
            } catch (IOException ex) {
                Util.log(threadId, threadCount, "Cannot read incoming request: " + ex.getMessage());
                closeConnections();
                return;
            }

            // start decoder when "Range: bytes=0-" is in the request
            boolean needDecoding = false;
            if(Util.indexOf(inputBytes, 0, bytesRead, "bytes=0-")!=-1) 
                needDecoding = true;
            
            // create request and send it to the music service
            Util.replace(inputBytes, 0, bytesRead, "/", proxyUrl.getPath());
            if(Util.DEBUG) Util.deb(threadId, threadCount, "Req: [" + new String(inputBytes, 0, bytesRead+proxyUrl.getPath().length()-1) +"]");
            try {
                toOS.write(inputBytes, 0, bytesRead+proxyUrl.getPath().length()-1);
            } catch (IOException ex) {
                Util.log(threadId, threadCount, "Cannot send request to server: " + ex.getMessage());
                closeConnections();
                return;
            }

            // read response from music service
            try {
                bytesRead=toIS.read(inputBytes);
                if(Util.DEBUG) Util.deb(threadId, threadCount, "bytesRead: " + bytesRead);
            } catch (IOException ex) {
                Util.log(threadId, threadCount, "Cannot read server response: " + ex.getMessage());
                closeConnections();
                return;
            }
            
            // limit response to HTTP content
            int offset=Util.indexOf(inputBytes, 0, bytesRead, "\r\n\r\n");

            // Replace Content-Type info
            if(Util.replace(inputBytes, 0, offset, "audio/ogg", "audio/wav")==-1)
                if(Util.replace(inputBytes, 0, offset, "application/ogg", "audio/wav")!=-1)
                    bytesRead-=6;
            if(Util.DEBUG) Util.deb(threadId, threadCount, "Resp: [" + new String(inputBytes, 0, offset) +"]");
            offset+=4; // add the \r\n pairs again

            // if decode request, pass on to decoder
            if(needDecoding) {
                // bytesRead contains the total of bytes read last time
                // offset points to the first byte after the http response; this
                // is the start of the stream
                new OggDecoder(this.getId(), proxyUrl.getUrlString()).
                        decode(fromOS, inputBytes, offset);
            } else {
                // Write till sreamer closes socket. 
                offset=bytesRead;
                try {
                    while((bytesRead=toIS.read(inputBytes, offset, IO_BUFFER_SIZE-offset))!=-1) {
                        offset+=bytesRead;
                        if(offset==IO_BUFFER_SIZE) {
                            try {
                                fromOS.write(inputBytes);
                                if(Util.DEBUG) Util.deb(threadId, threadCount, "Sent buffer to streamer");
                            } catch (IOException ex) { break; }
                            offset=0;
                        }
                    } 
                } catch (IOException ex) { 
                    Util.log(threadId, threadCount, "Cannot read server response: " + ex.getMessage());
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
            byte[] bytes = new byte[IO_BUFFER_SIZE];
            OggDecoder dec = new OggDecoder(-1);
            dec.decode(fos, bytes);
            fos.close();
        } catch (Exception ex) {
            Util.log("Fout! " + ex);
        }
        return 0;
    }*/
    
    public int start(String[] args) {
        Util.DEBUG=true;
        
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
        Util.log("I/O Buffer size: " + ForwardProxy.IO_BUFFER_SIZE_KB+ " KB");
        Util.log("Forward URL: " + fwdURL);
        
        // start proxyserver
        return createForwardProxyServer(forwardProxyPort, fwdURL);
    }

    
    public static void main(String[] args) {
        System.exit(new ForwardProxy().start(args));
    }
}
