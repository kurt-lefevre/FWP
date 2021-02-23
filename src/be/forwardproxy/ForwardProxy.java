/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

// AAC: http://mscp3.live-streams.nl:8340/jazz-high.aac
// MP3: http://mscp3.live-streams.nl:8340/jazz-low.mp3
// FLAC: http://stream.radioparadise.com/flac
// FLAC: http://mscp2.live-streams.nl:8100/flac.flac
// FLAC: http://mscp3.live-streams.nl:8340/jazz-flac.flac  // NAIM


package be.forwardproxy;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URL;

public class ForwardProxy {
    private final static String APP_VERSION = "ForwardProxy V0.5.220221";
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
    public final static int REQUEST_DISPATCH_FAILED = 11;
    public final static int MALFORMED_URL = 12;
    public final static int CANNOT_START_FFMPEG = 13;
    
    // stream & request buffer size
    public final static int READ_BUFFER_SIZE = 4096;
    
    private class RequestHandler extends Thread {
        private Socket socket;
        private String host, path;
        private int port;
        
        public RequestHandler(Socket socket, String host, String path, int port) {
            this.socket = socket;
            this.host = host;
            this.path = path;
            this.port = port;
        }
        
        public void run() {
            Util.log("[" + this.getId() + "]: Starting thread. Thread count: " +  ++threadCount);
            OggDecoder oggDecoder = new OggDecoder(this.getId());

            // get client inputstream
            InputStream fromIS=null;
            try {
                fromIS = socket.getInputStream();
            } catch (IOException ex) {
                Util.log("[" + this.getId() + "]: Failed to connect to Client Input Stream: "
                        + ex.getMessage());
                System.exit(IS_FAILED);
            }

            // Connect to music service/server
            Socket toSocket = null;
            try {
                toSocket = new Socket(host, port);
            } catch (IOException ex) {
                Util.log("[" + this.getId() + "]: Failed to connect to music service: " 
                        + ex.getMessage());
                System.exit(SERVER_CONNECTION_FAILED);
            }

            // get outputstreams
            OutputStream fromOS=null, toOS=null;
            try {
                fromOS = socket.getOutputStream();
                toOS = toSocket.getOutputStream();
            } catch (IOException ex) {
                Util.log("[" + this.getId() + "]: " + "Failed to connect to Output Streams: "
                        + ex.getMessage());
                System.exit(OS_FAILED);
            }

            // get client inputstream
            InputStream toIS=null;
            try {
                toIS = toSocket.getInputStream();
            } catch (IOException ex) {
                Util.log("[" + this.getId() + "]: Failed to connect to Server Input Stream: "
                        + ex.getMessage());
                System.exit(IS_FAILED);
            }

            // read request from client
            byte[] inputBytes = new byte[READ_BUFFER_SIZE]; 
            int offset, index;
            try {
                int bytesRead;
                byte[] reqArr;
                while((bytesRead = fromIS.read(inputBytes))!=-1) {
                    // create request and send it to the music server/service
                    reqArr = Util.replace(inputBytes, "/", path);
                    // String request = "GET " + path + new String(inputBytes, 5, bytesRead-5); 
                    Util.log("[" + this.getId() + "]: " + "Org Req received: [" + new String(inputBytes, 0, bytesRead) + "]");

                    try {
                        toOS.write(reqArr, 0, bytesRead+path.length()-1);
                    } catch (IOException ex) {
                        Util.log("[" + this.getId() + "]: Cannot send request to server: " + ex.getMessage());
                        System.exit(CANNOT_SEND_REQUEST);
                    }
                    
                    // read response
                    offset=0;
                    try {
                        while((bytesRead=toIS.read(inputBytes, offset, READ_BUFFER_SIZE-offset))!=-1) {
//                            Util.log("[" + this.getId() + "]: Resp: [" + new String(inputBytes, offset, bytesRead) +"]");

                            index=Util.indexOf(inputBytes, offset, offset+bytesRead, "OggS");
                            if(index!=-1) {
                                // Util.log("[" + this.getId() + "]: OggS found. Index1: " + index);
                                offset = index;
                                oggDecoder.decode(fromOS, inputBytes, offset);
                                break;
                            } else {
                                offset += bytesRead;
                                if(offset==READ_BUFFER_SIZE) {
                                    try {
                                        fromOS.write(inputBytes);
                                    } catch (SocketException ex) { //ex.printStackTrace();
                                        break;
                                    }
                                    offset=0;
                                    Util.log("[" + this.getId() + "]: Wrote full buffer");
                                }
                            }
                        }
                    } catch (IOException ex) {
                        Util.log("[" + this.getId() + "]: Cannot read server response: "
                                + ex.getMessage());
                        System.exit(CANNOT_READ_SERVER_RESPONSE);
                    }
                }
            } catch (IOException ex) {
                Util.log("[" + this.getId() + "]: Cannot read incoming request: "
                        + ex.getMessage());
                System.exit(CANNOT_READ_REQUEST);
            } finally {
                //Logger.log("[" + this.getId() + "]: Closing connections");
                oggDecoder.stop();
                try { fromOS.close(); } catch (Exception ex) {};
                try { toIS.close(); } catch (Exception ex) {};
                try { toOS.close(); } catch (Exception ex) {};
                try { fromIS.close(); } catch (Exception ex) {};
                try { socket.close(); } catch (Exception ex) {};
                try { toSocket.close(); } catch (Exception ex) {};
    
                Util.log("[" + this.getId() + "]: Stopping thread. Thread count: " + --threadCount);
            }
        }
    }

    private int createForwardProxyServer(int forwardProxyPort, String fwdURL) {
        // parse forward URL
        URL url=null;
        try {
            url = new URL(fwdURL);
        } catch (MalformedURLException ex) {
            Util.log("URL is malformed [" + fwdURL + "]: " + ex.getMessage());
            System.exit(MALFORMED_URL);
        }

        // Set default port
        int port=url.getPort();
        if(port == -1) port = 80;
        
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
                new RequestHandler(forwardProxyServerSocket.accept(), 
                    url.getHost(), url.getPath(), port).start();
            } catch (IOException ex) {
                Util.log("Failed to dispatch request: " + ex.getMessage());
                return REQUEST_DISPATCH_FAILED;
            }
        }
    }

    public int start2(String[] args) {
        try {
            FileOutputStream fos = new FileOutputStream("fons.flac");
            byte[] bytes = new byte[READ_BUFFER_SIZE];
            OggDecoder dec = new OggDecoder(-1);
            dec.decode(fos, bytes, 0);
            fos.close();
        } catch (Exception ex) {
            Util.log("Fout! " + ex);
        }
        
        return 0;
    }

    /*public int start3(String[] args) {
        byte[] ar1="1234567890".getBytes();
        int len = ar1.length;
        System.out.println("Ind: " + Util.indexOf(ar1, 0, len, "456"));
        System.out.println("Ind: " + Util.indexOf(ar1, 2, len, "456"));
        System.out.println("Ind: " + Util.indexOf(ar1, 3, 5, "456"));
        System.out.println("Ind: " + Util.indexOf(ar1, 2, 6, "456"));
        System.out.println("Ind: " + Util.indexOf(ar1, 0, len, "1234567890"));
        System.out.println("Ind: " + Util.indexOf(ar1, 0, len, "12345678901"));
        System.out.println("Ind: " + Util.indexOf(ar1, 0, len, "129"));
        System.out.println("Ind: " + Util.indexOf(ar1, 0, len, "90"));
        System.out.println("Ind: " + Util.indexOf(ar1, 7, len, "90"));
        
        return 0;
    }*/
    
/*    public int start4(String[] args) {
        byte[] ar1="1234567890".getBytes();
        System.out.println("Replace: " + new String(Util.replace(ar1, "345", "ZER")));
        System.out.println("Replace: " + new String(Util.replace(ar1, "345", "ABCD")));
        System.out.println("Replace: " + new String(Util.replace(ar1, "90", "ABC")));
        System.out.println("Replace: " + new String(Util.replace(ar1, "234", "AB")));
        System.out.println("Replace: " + new String(Util.replace(ar1, "kurt", "AB")));
        System.out.println("Replace: " + new String(Util.replace(ar1, "kurtkurtkurt", "AB")));
        System.out.println("Remove: " + new String(Util.remove(ar1, 3, 2)));
        
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
