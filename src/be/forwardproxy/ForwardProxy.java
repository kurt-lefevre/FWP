/*
    Version      | Comment
    -------------+--------------------------------------------------------------
    1.0.060321   | Initial release
    1.1.070321   | Added searchpath in showInfo()
    1.2.070321   | Improved debugging of incoming requests
    1.3.070321   | Sorted radio stations when displaying them
    1.4.080321   | Added StaleThreadMonitor + Health monitor has dedicated port
    1.5.090321   | Added "log" property in config file to enable/disable logging
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.stream.Stream;

public class ForwardProxy {
    private final static String APP_VERSION = "ForwardProxy V1.5.090321";
    private final static String UNDERLINE =   "========================";

    private final ProxyLog logger = ProxyLog.getInstance();
    private int forwardProxyPort, monitorPort;  
    private int ioBufferSize = IO_BUFFER_SIZE_KB * 1024;
    private HashMap<String, ProxyURL> radioList;
    private ArrayList<ProxyURL> radioListSorted;
    private long bootTime;
    private String bootTimeStr;
    private boolean log;
    
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
    
    public static Comparator<ProxyURL> FRIENDLY_NAME_COMPARATOR = new Comparator<ProxyURL>() {         
        @Override         
        public int compare(ProxyURL proxyUrl1, ProxyURL proxyUrl2) { 
            return proxyUrl1.getFriendlyName().compareTo(proxyUrl2.getFriendlyName());
        }
    };

    private class MonitorHandler extends Thread {
        private final Socket socket;
        private final long threadId= this.getId();
        private InputStream is;
        private OutputStream os;

        public MonitorHandler(Socket socket) {
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
        
        public void run() {
            logger.adjustThreadCount(1);
            logger.log(threadId, "MonitorHandler: Start");
            // get  inputstream
            try {
                socket.setSoTimeout(SOCKET_TIMEOUT_MS);
                is = socket.getInputStream();
            } catch (IOException ex) {
                logger.log(threadId, "MonitorHandler: Failed to connect to Input Stream: "
                        + ex.getMessage());
                closeConnections();
                return;
            }

            // get  outputstream
            try {
                os = socket.getOutputStream();
            } catch (IOException ex) {
                logger.log(threadId, "MonitorHandler: Failed to connect to Output Stream: "
                        + ex.getMessage());
                closeConnections();
                return;
            }
            
            // read request from streamer
            byte[] inputBytes = new byte[ioBufferSize]; 
            int bytesRead=0;
            try {
                bytesRead = is.read(inputBytes);
            } catch (IOException ex) {
                logger.log(threadId, "MonitorHandler: Cannot read request: " + ex.getMessage());
                closeConnections();
                return;
            }
            if(ProxyLog.DEBUG) logger.deb(threadId, "MonitorHandler: Req: [" + new String(inputBytes, 0, bytesRead)+"]");
            
            // check for special health request
            if(indexOf(inputBytes, 0, bytesRead, "fwdhealth")!=-1) {
                if(ProxyLog.DEBUG) logger.deb(threadId, "MonitorHandler: Health request");
                try {
                    if(indexOf(inputBytes, 0, bytesRead, "GET /favicon.ico")!=-1 ||
                            indexOf(inputBytes, 0, bytesRead, "GET /apple")!=-1)
                        os.write("HTTP/1.1 200 OK\r\n\r\n".getBytes());
                    else {
                        logger.log(threadId, "MonitorHandler: Health request");
                        os.write(showInfo());
                    }
                } catch (IOException ex) {
                    logger.log(threadId, "MonitorHandler: Failed to send health response: "
                            + ex.getMessage());
                }
            } else {
                // ProxyLog.log(threadId, threadCount, "Evil request");
                try {
                    os.write("HTTP/1.1 404\r\n\r\n".getBytes());
                } catch (IOException ex) {
                    logger.log(threadId, "MonitorHandler: Failed to send 404 response: "
                            + ex.getMessage());
                }
                
            }
            
            closeConnections();
        }

        private void closeConnections() {
            try { socket.close(); } catch (Exception ex) {};
            try { is.close(); } catch (Exception ex) {};
            try { os.close(); } catch (Exception ex) {};

            logger.adjustThreadCount(-1);
            logger.log(threadId, "MonitorHandler: Stop");
        }
    }
    
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
            logger.log(threadId, "RequestHandler: Stop");
        }

        public void run() {
            logger.adjustThreadCount(1);
            logger.log(threadId, "RequestHandler: Start");
            // get client inputstream
            try {
                socket.setSoTimeout(SOCKET_TIMEOUT_MS);
                fromIS = socket.getInputStream();
            } catch (IOException ex) {
                logger.log(threadId, "RequestHandler: Failed to connect to Client Input Stream: "
                        + ex.getMessage());
                closeConnections();
                return;
            }

            // get client outputstream
            try {
                fromOS = socket.getOutputStream();
            } catch (IOException ex) {
                logger.log(threadId, "RequestHandler: Failed to connect to Client Output Stream: "
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
                logger.log(threadId, "RequestHandler: Cannot read request: " + ex.getMessage());
                closeConnections();
                return;
            }
            if(ProxyLog.DEBUG) logger.deb(threadId, "RequestHandler: Req: [" + new String(inputBytes, 0, bytesRead)+"]");

            // check for valid request before continuing
            // if request contains no "Icy-MetaData: 1", reject it
            if(indexOf(inputBytes, 0, bytesRead, "Icy-MetaData: 1")==-1) {
                // ProxyLog.log(threadId, threadCount, "Evil request");
                try {
                    fromOS.write("HTTP/1.1 404\r\n\r\n".getBytes());
                } catch (IOException ex) {
                    logger.log(threadId, "RequestHandler: Failed to send 404 response to client: "
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
                logger.log(threadId, "RequestHandler: Invalid search path: [" + searchPath + "]");
                closeConnections();
                return;
            }
           
            // Connect to music service/server
            try {
                toSocket = new Socket(proxyUrl.getHost(), proxyUrl.getPort());
                toSocket.setSoTimeout(SOCKET_TIMEOUT_MS);
            } catch (IOException ex) {
                logger.log(threadId, "RequestHandler: Failed to connect to music service [" 
                        + proxyUrl.getUrlString() + "]: " + ex.getMessage());
                closeConnections();
                return;
            }
            logger.log(threadId, "RequestHandler: Connected to " + proxyUrl.getFriendlyName());

            // get server outputstream
            try {
                toOS = toSocket.getOutputStream();
            } catch (IOException ex) {
                logger.log(threadId, "RequestHandler: Failed to connect to Server Output Streams: "
                        + ex.getMessage());
                closeConnections();
                return;
            }

            // get server inputstream
            try {
                toIS = toSocket.getInputStream();
            } catch (IOException ex) {
                logger.log(threadId, "RequestHandler: Failed to connect to Server Input Stream: "
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
            if(ProxyLog.DEBUG) logger.deb(threadId, "RequestHandler: Req to send: [" + new String(inputBytes, 0, bytesRead)+"]");
            try {
                toOS.write(inputBytes, 0, bytesRead+proxyUrl.getPath().length()-1);
            } catch (IOException ex) {
                logger.log(threadId, "RequestHandler: Cannot send request to server: " + ex.getMessage());
                closeConnections();
                return;
            }

            // read response from music service
            try {
                bytesRead=toIS.read(inputBytes);
                if(ProxyLog.DEBUG) logger.deb(threadId, "RequestHandler: bytesRead: " + bytesRead);
            } catch (IOException ex) {
                logger.log(threadId, "RequestHandler: Cannot read server response: " + ex.getMessage());
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
            if(ProxyLog.DEBUG) logger.deb(threadId, "RequestHandler: Resp: [" + new String(inputBytes, 0, offset) +"]");

            // if decode request, pass on to decoder
            if(needDecoding) {
                // We don't need those anymore
                try { toSocket.close(); } catch (Exception ex) {};
                try { toIS.close(); } catch (Exception ex) {};
                try { toOS.close(); } catch (Exception ex) {};

                // bytesRead contains the total of bytes read last time
                // offset points to the first byte after the http response; this
                // is the start of the stream
                new OggDecoder(this.getId(), proxyUrl, ioBufferSize, fromOS).
                        decode(inputBytes, offset);
            } else {
                // Write till sreamer closes socket. 
                offset=bytesRead;
                try {
                    while((bytesRead=toIS.read(inputBytes, offset, ioBufferSize-offset))!=-1) {
                        offset+=bytesRead;
                        if(offset==ioBufferSize) {
                            try {
                                fromOS.write(inputBytes);
                                if(ProxyLog.DEBUG) logger.deb(threadId, "RequestHandler: Sent buffer to streamer");
                            } catch (IOException ex) { break; }
                            offset=0;
                        }
                    } 
                } catch (IOException ex) { 
                    logger.log(threadId, "RequestHandler: Cannot read server response: " + ex.getMessage());
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
        for(ProxyURL station : radioListSorted) {
            sb.append("  ").append(station.getFriendlyName()).append(" (").
                    append(station.getSearchPath()).append(")\n");
        }
        sb.append("\nKurt Lefevre (linkedin.com/in/lefevrekurt)");
            
        String msg = "HTTP/1.1 200 OK\r\nContent-Type: text/plain;charset=UTF-8\r\nContent-Length: "
                            + sb.length() + "\r\n\r\n" + sb.toString();
        return msg.getBytes();
    }
    
    private int createForwardProxyServer() {
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

    
    private int createMonitor() {
        bootTime = System.currentTimeMillis();
        SimpleDateFormat calFormatter = new SimpleDateFormat("dd-MM-yyyy 'at' HH:mm:ss");
        bootTimeStr = calFormatter.format(new Date(bootTime));
        
        // Create the Server Socket for the Proxy
        ServerSocket monitorSocket ;
        try {
            monitorSocket = new ServerSocket(monitorPort);
        } catch (IOException ex) {
            logger.log("Failed to bind Monitor to port " + monitorPort + ": "
                    + ex.getMessage());
            return PROXY_SERVER_CREATION_FAILED;
        }
        
        logger.log("Monitor started on port " + monitorPort);
        while(true) {
            try {
                new MonitorHandler(monitorSocket.accept()).start();
            } catch (IOException ex) {
                logger.log("Failed to dispatch request: " + ex.getMessage());
                return REQUEST_DISPATCH_FAILED;
            }
        }
    }
    
    private int postValidation() {
        // post checks
        if(forwardProxyPort==0) {
            logger.log("Missing PROXY_PORT in configuration file");
            return MISSING_PORT;
        }
        
        if(monitorPort==0) {
            logger.log("Missing MONITOR_PORT in configuration file");
            return MISSING_PORT;
        }
        
        if(monitorPort==forwardProxyPort) {
            logger.log("MONITOR_PORT and PROXY_PORT cannot be the same port");
            return INVALID_PORT;
        }
        
        // display radio station list
        if(radioList.isEmpty()) {
            logger.log("No stations defined");
            return NO_STATIONS_DEFINED;
        }
    
        // Create sorted station list
        radioListSorted = new ArrayList<ProxyURL>();
        for (ProxyURL station : radioList.values()) 
            radioListSorted.add(station);
        Collections.sort(radioListSorted, FRIENDLY_NAME_COMPARATOR);

        return SUCCESS;
    }
    
    private void dispStartup() {
        logger.log("Logfile size: " + logger.getLogfileSize() + " kB");
        logger.log("I/O buffer size: " + ioBufferSize/1024 + " kB");
        logger.log("");
        logger.log("Stations");
        logger.log("--------");
        for(ProxyURL station : radioListSorted) {
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
                case "proxy_port":
                    try {
                        forwardProxyPort=Integer.parseInt(value);
                    } catch(NumberFormatException e) {
                        logger.log("Can't parse PROXY_PORT [" + value + "]");
                        return INVALID_PORT;
                    }
                    break;
                case "monitor_port":
                    try {
                        monitorPort=Integer.parseInt(value);
                    } catch(NumberFormatException e) {
                        logger.log("Can't parse MONITOR_PORT [" + value + "]");
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
                case "log":
                    try {
                        log=Boolean.parseBoolean(value);
                    } catch(NumberFormatException e) {
                        logger.log("Can't convert LOG [" + value + "]. Assuming false");
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
                    logger.log("Don't understand [" + line + "]");
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
        if(log) logger.initializeLogger("ForwardProxy_");
        
        // do post check validation of values
        retVal = postValidation();
        if(retVal!=SUCCESS) return retVal;

        // Arguments parsed successfully
        dispStartup();
        
        // Start proxy server
        Thread proxyServerThread = new Thread(){
            @Override
            public void run() {
                createForwardProxyServer();
            }
        };
        proxyServerThread.start();        

        // Start Monitor
        Thread monitorThread = new Thread(){
            @Override
            public void run() {
                createMonitor();
            }
        };
        monitorThread.start();      
        
        // wait till proxyServerThread dies
        try {
            proxyServerThread.join();
        } catch (InterruptedException ex) {}

        return SUCCESS;
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
