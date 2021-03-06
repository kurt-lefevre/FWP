/*
    Version      | Comment
    -------------+--------------------------------------------------------------
    1.0.060321   | Initial release
    1.1.070321   | Added searchpath in showInfo()
    -------------+--------------------------------------------------------------
*/

package be.forwardproxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.stream.Stream;

public class ForwardProxy {
    private final static String APP_VERSION = "ForwardProxy V1.1.070321";
    private final static String UNDERLINE =   "========================";

    private final ProxyLog logger = ProxyLog.getInstance();
    private int forwardProxyPort;  // listening port for ForwardProxy server
    private int ioBufferSize = IO_BUFFER_SIZE_KB * 1024;
    private HashMap<String, ProxyURL> radioList;
    private long bootTime;
    private String bootTimeStr;
    
    // exit statuses
    public final static int SUCCESS = 0;
    public final static int MISSING_ARGUMENTS = 1;
    public final static int INVALID_PORT = 2;
    public final static int PROXY_SERVER_CREATION_FAILED = 3;
    public final static int HTTPS_NOT_SUPPORTED = 4;
    public final static int REQUEST_DISPATCH_FAILED = 5;
    public final static int CANNOT_START_DECODE_SCRIPT = 6;
    public final static int MALFORMED_URL = 7;
    public final static int CANNOT_READ_CONFIG_FILE=8;
    public final static int CANNOT_PARSE_CONFIG_FILE=9;
    public final static int MISSING_PORT = 10;
    public final static int NO_STATIONS_DEFINED = 11;
    
    public final static int IO_BUFFER_SIZE_KB = 8;
    public final static int SOCKET_TIMEOUT_MS = 1000;
    
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

        private int indexOf(byte[] byteArr, int start, int end, String searchStr) {
            byte[] searchArr=searchStr.getBytes();
            int lastPos=end - searchArr.length;

            if(lastPos<0) return -1; // search string is longer than array

            int y;
            for(int x=start; x<=lastPos; x++) {
                for(y=0; y<searchArr.length; y++)
                    if(byteArr[x]==searchArr[y]) x++;
                    else break;
                if(y==searchArr.length) return x-y;
            }

            return -1;
        }

        private int replace(byte[] byteArr, int start, int end, String fromStr, String toStr) {
            int index;
            if((index=indexOf(byteArr, start, end, fromStr))==-1) return -1;

            // fromStr was found
            int fromLen=fromStr.length();
            byte[] toStrArr = toStr.getBytes();

            // arraycopy​(Object src, int srcPos, Object dest, int destPos, int length)
            
            // copy remaining bytes form source to array
            System.arraycopy(byteArr, index+fromLen, byteArr, 
                    index+toStrArr.length, end-fromLen-index);

            // copy toArr to array
            System.arraycopy(toStrArr, 0, byteArr, index, toStrArr.length);

            return index;
        }
        
        private void closeConnections() {
            try { toSocket.close(); } catch (Exception ex) {};
            try { toIS.close(); } catch (Exception ex) {};
            try { toOS.close(); } catch (Exception ex) {};
            try { socket.close(); } catch (Exception ex) {};
            try { fromIS.close(); } catch (Exception ex) {};
            try { fromOS.close(); } catch (Exception ex) {};

            logger.adjustThreadCount(-1);
            logger.log(threadId, "Stop thread");
        }

        public void run() {
            logger.adjustThreadCount(1);
            logger.log(threadId, "Start thread");
            // get client inputstream
            try {
                socket.setSoTimeout(SOCKET_TIMEOUT_MS);
                fromIS = socket.getInputStream();
            } catch (IOException ex) {
                logger.log(threadId, "Failed to connect to Client Input Stream: "
                        + ex.getMessage());
                closeConnections();
                return;
            }

            // get client outputstream
            try {
                fromOS = socket.getOutputStream();
            } catch (IOException ex) {
                logger.log(threadId, "Failed to connect to Client Output Stream: "
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
                logger.log(threadId, "Cannot read incoming request: " + ex.getMessage());
                closeConnections();
                return;
            }
            
            // check for special health request
            if(indexOf(inputBytes, 0, bytesRead, "fwdhealth")!=-1) {
                if(ProxyLog.DEBUG) logger.deb(threadId, "Health Req: [" 
                        + new String(inputBytes, 0, bytesRead) +"]");
                
                try {
                    if(indexOf(inputBytes, 0, bytesRead, "GET /favicon.ico")!=-1 ||
                            indexOf(inputBytes, 0, bytesRead, "GET /apple")!=-1)
                        fromOS.write("HTTP/1.1 200 OK\r\n\r\n".getBytes());
                    else {
                        logger.log(threadId, "Health request");
                        fromOS.write(showInfo());
                    }
                } catch (IOException ex) {
                    logger.log(threadId, "Failed to send health response to client: "
                            + ex.getMessage());
                }
                closeConnections();
                return;
            }

            // check for valid request before continuing
            // if request contains no "Icy-MetaData: 1", reject it
            if(indexOf(inputBytes, 0, bytesRead, "Icy-MetaData: 1")==-1) {
                // ProxyLog.log(threadId, threadCount, "Evil request");
                try {
                    fromOS.write("HTTP/1.1 404\r\n\r\n".getBytes());
                } catch (IOException ex) {
                    logger.log(threadId, "Failed to send 404 response to client: "
                            + ex.getMessage());
                }
                closeConnections();
                return;
            }

            // get searchPath from request. This is the key in the station list
            int startIndex = indexOf(inputBytes, 0, bytesRead, "/");
            int endIndex = indexOf(inputBytes, 0, bytesRead, " HTTP");
            String searchPath=new String(inputBytes, startIndex, endIndex-4);
            if(ProxyLog.DEBUG) logger.deb(threadId, "searchPath: " + searchPath);
            proxyUrl = radioList.get(searchPath.toLowerCase());
            if(proxyUrl==null) {
                logger.log(threadId, "Invalid search path: [" + searchPath + "]");
                closeConnections();
                return;
            }
           
            // Connect to music service/server
            try {
                toSocket = new Socket(proxyUrl.getHost(), proxyUrl.getPort());
                toSocket.setSoTimeout(SOCKET_TIMEOUT_MS);
            } catch (IOException ex) {
                logger.log(threadId, "Failed to connect to music service [" 
                        + proxyUrl.getUrlString() + "]: " + ex.getMessage());
                closeConnections();
                return;
            }
            logger.log(threadId, "Connected to " + proxyUrl.getFriendlyName());

            // get server outputstream
            try {
                toOS = toSocket.getOutputStream();
            } catch (IOException ex) {
                logger.log(threadId, "Failed to connect to Server Output Streams: "
                        + ex.getMessage());
                closeConnections();
                return;
            }

            // get server inputstream
            try {
                toIS = toSocket.getInputStream();
            } catch (IOException ex) {
                logger.log(threadId, "Failed to connect to Server Input Stream: "
                        + ex.getMessage());
                closeConnections();
                return;
            }

            // start decoder when "Range: bytes=0-" is in the request
            boolean needDecoding = false;
            // below is naim specific. It avoid starting multiple time the ffmpeg process
            if(indexOf(inputBytes, 0, bytesRead, "bytes=0-")!=-1) needDecoding = true;
            
            // create request and send it to the music service
            replace(inputBytes, 0, bytesRead, searchPath, proxyUrl.getPath());
            bytesRead = bytesRead + proxyUrl.getPath().length() - searchPath.length();
            if(ProxyLog.DEBUG) logger.deb(threadId, "Req: [" + new String(inputBytes, 0, bytesRead)+"]");
            try {
                toOS.write(inputBytes, 0, bytesRead+proxyUrl.getPath().length()-1);
            } catch (IOException ex) {
                logger.log(threadId, "Cannot send request to server: " + ex.getMessage());
                closeConnections();
                return;
            }

            // read response from music service
            try {
                bytesRead=toIS.read(inputBytes);
                if(ProxyLog.DEBUG) logger.deb(threadId, "bytesRead: " + bytesRead);
            } catch (IOException ex) {
                logger.log(threadId, "Cannot read server response: " + ex.getMessage());
                closeConnections();
                return;
            }
            
            // limit response to HTTP content
            int offset=indexOf(inputBytes, 0, bytesRead, "\r\n\r\n");

            // Replace Content-Type info
            if(replace(inputBytes, 0, offset, "audio/ogg", "audio/wav")==-1)
                if(replace(inputBytes, 0, offset, "application/ogg", "audio/wav")!=-1)
                    bytesRead-=6;
/*            if(replace(inputBytes, 0, offset, "audio/ogg", "audio/wav")!=-1) bytesRead+=0;
            else if(replace(inputBytes, 0, offset, "application/ogg", "audio/wav")!=-1) bytesRead-=6;*/
            offset+=4; // add the \r\n pairs again
            if(ProxyLog.DEBUG) logger.deb(threadId, "Resp: [" + new String(inputBytes, 0, offset) +"]");

            // if decode request, pass on to decoder
            if(needDecoding) {
                // We don't need those anymore
                try { toSocket.close(); } catch (Exception ex) {};
                try { toIS.close(); } catch (Exception ex) {};
                try { toOS.close(); } catch (Exception ex) {};

                // bytesRead contains the total of bytes read last time
                // offset points to the first byte after the http response; this
                // is the start of the stream
                new OggDecoder(this.getId(), proxyUrl, ioBufferSize).
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
                                if(ProxyLog.DEBUG) logger.deb(threadId, "Sent buffer to streamer");
                            } catch (IOException ex) { break; }
                            offset=0;
                        }
                    } 
                } catch (IOException ex) { 
                    logger.log(threadId, "Cannot read server response: " + ex.getMessage());
                }
            }

            // clean up resources
            closeConnections();
        }
    }
    
    private byte[] showInfo() {
        long diff = System.currentTimeMillis() - bootTime;
        StringBuilder sb = new StringBuilder();
        String uptime = String.format("%d days %02d:%02d:%02d", diff/86400000, 
                diff/3600000%24,diff/60000%60, diff/1000%60);
        
        sb.append(APP_VERSION).append('\n').append(UNDERLINE).append("\n\n").
            append("Decoders         ").append(logger.getDecoderCount()).append('\n').
            append("Threads          ").append(logger.getThreadCount()).append('\n').
            append("Up time          ").append(uptime).append('\n').
            append("Boot time        ").append(bootTimeStr).append('\n').
            append("Logfile size     ").append(logger.getLogfileSize()).append(" kB\n").
            append("I/O buffer size  ").append(ioBufferSize/1024).append(" kB\n\n").
            append("Stations\n--------\n");
        for (ProxyURL station : radioList.values()) {
            sb.append("  ").append(station.getFriendlyName()).append(" (").
                    append(station.getSearchPath()).append(")\n");
        }
        sb.append("\nKurt Lefevre (http://linkedin.com/in/lefevrekurt)");
            
        String msg = "HTTP/1.1 200 OK\r\nContent-Type: text/plain;charset=UTF-8\r\nContent-Length: "
                            + sb.length() + "\r\n\r\n" + sb.toString();
        return msg.getBytes();
    }
    
    private int createForwardProxyServer(int forwardProxyPort) {
        bootTime = System.currentTimeMillis();
        SimpleDateFormat calFormatter = new SimpleDateFormat("dd-MM-yyyy 'at' HH:mm:ss");
        bootTimeStr = calFormatter.format(new Date(bootTime));
        
        // Create the Server Socket for the Proxy
        ServerSocket forwardProxyServerSocket ;
        try {
            forwardProxyServerSocket = new ServerSocket(forwardProxyPort);
        } catch (IOException ex) {
            logger.log("Failed to bind ForwardProxy to port " + forwardProxyPort + ": "
                    + ex.getMessage());
            return PROXY_SERVER_CREATION_FAILED;
        }
        
        logger.log("Listening for requests on port " + forwardProxyPort);
        while(true) {
            try {
                new RequestHandler(forwardProxyServerSocket.accept()).start();
            } catch (IOException ex) {
                logger.log("Failed to dispatch request: " + ex.getMessage());
                return REQUEST_DISPATCH_FAILED;
            }
        }
    }

    private int postValidation() {
        // post checks
        if(forwardProxyPort==0) {
            logger.log("Missing PORT in configuration file");
            return MISSING_PORT;
        }
        
        // display radio station list
        if(radioList.isEmpty()) {
            logger.log("No stations defined");
            return NO_STATIONS_DEFINED;
        }
        
        return SUCCESS;
    }
    
    private void dispStartup() {
        logger.log("Logfile size: " + logger.getLogfileSize() + " kB");
        logger.log("I/O buffer size: " + ioBufferSize/1024 + " kB");
        logger.log("");
        logger.log("Stations");
        logger.log("--------");
        for (ProxyURL station : radioList.values()) {
            logger.log("  " + station.getFriendlyName());
        }
        logger.log("");
    }
    
    private int readConfiguration(String configFile ) {
        Path path = Paths.get(configFile);
        Stream<String> fileLines = null;
        try {
            fileLines = Files.lines(path);
            //Util.log("Read " + configFile);
        } catch (IOException ex) {
            logger.log("Can't find [" + configFile + "]");
            return CANNOT_READ_CONFIG_FILE;
        }
        radioList = new HashMap<>();
        Iterator<String> fileLinesIt = fileLines.iterator();
        String[] oneLine;
        String line, key, value, forwardUrl, friendlyName, searchPath;
        ProxyURL proxyUrl;
        while(fileLinesIt.hasNext()) {
            line = fileLinesIt.next();

            // skip comment & blanc lines
            if(line.isBlank() || line.charAt(0)=='#') continue;

            //split one line by = 
            oneLine = line.split("=");
            if(oneLine.length!=2) {
                logger.log("Can't parse [" + line + "]");
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
                        logger.log("Can't parse port [" + value + "]");
                        return INVALID_PORT;
                    }
                    break;
                case "logfile_size_kb":
                    try {
                        logger.setLogfileSize(Integer.parseInt(value));
                    } catch(NumberFormatException e) {
                        logger.log("Can't convert LOGFILE_SIZE [" + value + "]. Assuming " +
                                logger.getLogfileSize() + " KB");
                    }
                    break;
                case "io_buffer_size_kb":
                    try {
                        ioBufferSize=Integer.parseInt(value) * 1024;
                    } catch(NumberFormatException e) {
                        logger.log("Can't convert IO_BUFFER_SIZE_KB [" + value + "]. Assuming " +
                            IO_BUFFER_SIZE_KB + " KB");
                    }
                    break;
                case "debug":
                    try {
                        ProxyLog.DEBUG=Boolean.parseBoolean(value);
                    } catch(NumberFormatException e) {
                        logger.log("Can't convert DEBUG [" + value + "]. Assuming false");
                    }
                    break;
                case "station":
                    // station
                    oneLine = value.split(",");
                    if(oneLine.length!=3) {
                        logger.log("Can't parse line for STATION [" + value + "]");
                        break;
                    }

                    searchPath = oneLine[0].strip();
                    friendlyName = oneLine[1].strip();
                    forwardUrl = oneLine[2].strip();
                    proxyUrl = new ProxyURL(forwardUrl, friendlyName, searchPath);
                    if(!proxyUrl.getProtocol().equals("http")) {
                        logger.log("HTTPS URLs are not supported: [" + 
                                proxyUrl.getUrlString() + "]");
                    } else radioList.put('/' + searchPath.toLowerCase(), proxyUrl);
                    break;
                default:
                    logger.log("Don't understand [" + value + "]");
                    break;
            }
        }
        try{ fileLines.close(); } catch(Exception e) {}
        
        return SUCCESS;
    }

    
    public int start(String[] args) {
        // Check # arguments
        if(args.length != 1) {
            System.err.println(APP_VERSION + "\n" + UNDERLINE +
                "\nUSAGE:\n  ForwardProxy {configuration file}");
            return MISSING_ARGUMENTS;
        }

        logger.log(APP_VERSION);
        logger.log(UNDERLINE);
        
        // process configuration
        int retVal=readConfiguration(args[0]);
        if(retVal!=SUCCESS) return retVal;
        
        // init logging 
        logger.initializeLogger("ForwardProxy_");
        
        // do post check validation of values
        retVal = postValidation();
        if(retVal!=SUCCESS) return retVal;

        // Arguments parsed successfully
        dispStartup();
        
        // start proxyserver
        return createForwardProxyServer(forwardProxyPort);
    }

/*    public int start2(String[] args) {
        try {
            ProxyLog.DEBUG=true;
            ProxyURL proxyUrl = new ProxyURL("http://secure.live-streams.nl/flac.flac", "Intense Radio");
//            ProxyURL proxyUrl = new ProxyURL("http://mscp3.live-streams.nl:8250/class-flac.flac", "Naim Classic");
            FileOutputStream fos = new FileOutputStream("fons.out");
            byte[] bytes = new byte[4096];
            OggDecoder dec = new OggDecoder(-1, proxyUrl, 4096);
            dec.decode(fos, bytes, 0);
            fos.close();
        } catch (Exception ex) {
            ProxyLog.log("Fout! " + ex);
        }
        return 0;
    }*/
    
    public static void main(String[] args) {
        System.exit(new ForwardProxy().start(args));
    }
}
