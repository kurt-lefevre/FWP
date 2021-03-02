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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Iterator;
import java.util.stream.Stream;

public class ForwardProxy {
    private final static String APP_VERSION = "ForwardProxy V0.9.010321";
    private final static String UNDERLINE =   "========================";

    public static int threadCount;
    private int forwardProxyPort;  // listening port for ForwardProxy server
    private int logfileSizeKb = Util.LOGFILE_SIZE_KB;
    private int ioBufferSize = IO_BUFFER_SIZE_KB * 1024;
    private HashMap<String, ProxyURL> radioList;
    
    // exit statuses
    public final static int SUCCESS = 0;
    public final static int MISSING_ARGUMENTS = 1;
    public final static int INVALID_PORT = 2;
    public final static int PROXY_SERVER_CREATION_FAILED = 3;
    public final static int HTTPS_NOT_SUPPORTED = 4;
    public final static int REQUEST_DISPATCH_FAILED = 5;
    public final static int CANNOT_START_FFMPEG = 6;
    public final static int MALFORMED_URL = 7;
    public final static int CANNOT_READ_CONFIG_FILE=8;
    public final static int CANNOT_PARSE_CONFIG_FILE=9;
    public final static int MISSING_PORT = 10;
    
    public final static int IO_BUFFER_SIZE_KB = 384;
    public final static int SOCKET_TIMEOUT_MS = 5000;
    
    private class RequestHandler extends Thread {
        private final Socket socket;
        private Socket toSocket;
        private InputStream fromIS, toIS;
        private OutputStream fromOS, toOS;
        private ProxyURL proxyUrl;
        private final long threadId= this.getId();
        
        public RequestHandler(Socket socket) {
            this.socket = socket;
        }

        private void closeConnections() {
            try { toSocket.close(); } catch (Exception ex) {};
            try { toIS.close(); } catch (Exception ex) {};
            try { toOS.close(); } catch (Exception ex) {};
            try { socket.close(); } catch (Exception ex) {};
            try { fromIS.close(); } catch (Exception ex) {};
            try { fromOS.close(); } catch (Exception ex) {};

            Util.log(threadId, --threadCount, "Stop thread");
        }

        public void run() {
            Util.log(threadId, ++threadCount, "Start thread");
            // get client inputstream
            try {
                socket.setSoTimeout(SOCKET_TIMEOUT_MS);
                fromIS = socket.getInputStream();
            } catch (IOException ex) {
                Util.log(threadId, threadCount, "Failed to connect to Client Input Stream: "
                        + ex.getMessage());
                closeConnections();
                return;
            }

            // read request from streamer
            byte[] inputBytes = new byte[ioBufferSize]; 
            int bytesRead=0;
            try {
                bytesRead = fromIS.read(inputBytes);
            } catch (IOException ex) {
                Util.log(threadId, threadCount, "Cannot read incoming request: " + ex.getMessage());
                closeConnections();
                return;
            }

            // check for valid request before continuing
            if(Util.indexOf(inputBytes, 0, bytesRead, "Icy-MetaData: 1")==-1) {
                Util.log(threadId, threadCount, "Bad request");
                closeConnections();
                return;
            }

            // get searchPath from request
            int startIndex = Util.indexOf(inputBytes, 0, bytesRead, "/");
            int endIndex = Util.indexOf(inputBytes, 0, bytesRead, " HTTP");
            String searchPath=new String(inputBytes, startIndex, endIndex-4);
            proxyUrl = radioList.get(searchPath.toLowerCase());
            if(proxyUrl==null) {
                Util.log(threadId, threadCount, "Invalid search path: [" + searchPath + "]");
                closeConnections();
                return;
            }
           
            // Connect to music service/server
            try {
                toSocket = new Socket(proxyUrl.getHost(), proxyUrl.getPort());
                toSocket.setSoTimeout(SOCKET_TIMEOUT_MS);
            } catch (IOException ex) {
                Util.log(threadId, threadCount, "Failed to connect to music service [" 
                        + proxyUrl.getUrlString() + "]: " + ex.getMessage());
                closeConnections();
                return;
            }
            Util.log(threadId, threadCount, "Connected to " + proxyUrl.getFriendlyName());

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

            // start decoder when "Range: bytes=0-" is in the request
            boolean needDecoding = false;
            if(Util.indexOf(inputBytes, 0, bytesRead, "bytes=0-")!=-1) 
                needDecoding = true;
            
            // create request and send it to the music service
            Util.replace(inputBytes, 0, bytesRead, searchPath, proxyUrl.getPath());
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
                // We don't need those anymore
                try { toSocket.close(); } catch (Exception ex) {};
                try { toIS.close(); } catch (Exception ex) {};
                try { toOS.close(); } catch (Exception ex) {};

                // bytesRead contains the total of bytes read last time
                // offset points to the first byte after the http response; this
                // is the start of the stream
                new OggDecoder(this.getId(), proxyUrl.getUrlString(), ioBufferSize).
                        decode(fromOS, inputBytes, offset);
            } else {
                // Write till sreamer closes socket. 
                offset=bytesRead;
                try {
                    while((bytesRead=toIS.read(inputBytes, offset, ioBufferSize-offset))!=-1) {
                        offset+=bytesRead;
                        if(offset==ioBufferSize) {
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

    
    
    private int createForwardProxyServer(int forwardProxyPort) {
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
                new RequestHandler(forwardProxyServerSocket.accept()).start();
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
    

    private int readConfiguration(String configFile ) {
        Path path = Paths.get(configFile);
        Stream<String> fileLines = null;
        try {
            fileLines = Files.lines(path);
            Util.log("Read " + configFile);
        } catch (IOException ex) {
            Util.log("Can't read [" + configFile + "]");
            return CANNOT_READ_CONFIG_FILE;
        }
        radioList = new HashMap<>();
        Iterator<String> fileLinesIt = fileLines.iterator();
        String line;
        String[] oneLine;
        String key, value, forwardUrl, friendlyName;
        ProxyURL proxyUrl;
        while(fileLinesIt.hasNext()) {
            line = fileLinesIt.next();

            // skip comment & blanc lines
            if(line.isBlank() || line.charAt(0)=='#') continue;

            //split one line by = 
            oneLine = line.split("=");
            if(oneLine.length!=2) {
                Util.log("Can't parse [" + line + "]");
                return CANNOT_READ_CONFIG_FILE;
            }
            
            key = oneLine[0].trim();
            value = oneLine[1].trim();
            
            // parse line
            switch(key.toLowerCase()) {
                case "port":
                    try {
                        forwardProxyPort=Integer.parseInt(value);
                    } catch(NumberFormatException e) {
                        Util.log("Can't parse port [" + value + "]");
                        return INVALID_PORT;
                    }
                    break;
                case "logfile_size":
                    try {
                        logfileSizeKb=Integer.parseInt(value);
                    } catch(NumberFormatException e) {
                        Util.log("Can't convert LOGFILE_SIZE [" + value + "]. Assuming " +
                                logfileSizeKb + " KB");
                    }
                    break;
                case "io_buffer_size_kb":
                    try {
                        ioBufferSize=Integer.parseInt(value) * 1024;
                    } catch(NumberFormatException e) {
                        Util.log("Can't convert IO_BUFFER_SIZE_KB [" + value + "]. Assuming " +
                                IO_BUFFER_SIZE_KB + " KB");
                    }
                    break;
                case "debug":
                    try {
                        Util.DEBUG=Boolean.parseBoolean(value);
                    } catch(NumberFormatException e) {
                        Util.log("Can't convert DEBUG [" + value + "]. Assuming false");
                    }
                    break;
                default:
                    // station
                    oneLine = value.split(",");
                    if(oneLine.length!=2) {
                        Util.log("Can't parse [" + value + "]");
                        break;
                    }

                    friendlyName = oneLine[0].trim();
                    forwardUrl = oneLine[1].trim();
                    proxyUrl = new ProxyURL(forwardUrl, friendlyName);
                    if(!proxyUrl.getProtocol().equals("http")) {
                        Util.log("HTTPS URLs are not supported: [" + 
                                proxyUrl.getUrlString() + "]");
                    } else {
                        radioList.put(key.toLowerCase(), proxyUrl);
                        Util.log("Found " + friendlyName);
                    }
                    break;
            }
        }
        try{ fileLines.close(); } catch(Exception e) {}
        
        // post checks
        if(forwardProxyPort==0) {
            Util.log("Missing PORT in configuration file");
            return MISSING_PORT;
        }
        
        return SUCCESS;
    }

    
    public int start(String[] args) {
        // Check # arguments
        if(args.length != 1) {
            System.err.println(APP_VERSION + "\n" + UNDERLINE +
                "\nUSAGE:\n  ForwardProxy {configuration file}");
            return MISSING_ARGUMENTS;
        }

        Util.log(APP_VERSION);
        Util.log(UNDERLINE);
        
        // process configuration
        int retVal=readConfiguration(args[0]);
        if(retVal!=SUCCESS) return retVal;
        
        // init logging 
        Util.initializeLogger("ForwardProxy_", logfileSizeKb);

        // Arguments parsed successfully
        Util.log("ForwardProxy port: " + forwardProxyPort);
        Util.log("Logfile size: " + logfileSizeKb + " KB");
        Util.log("I/O Buffer size: " + ioBufferSize/1024 + " KB");
        
        // start proxyserver
        return createForwardProxyServer(forwardProxyPort);
    }

    
    public static void main(String[] args) {
        System.exit(new ForwardProxy().start(args));
    }
}
