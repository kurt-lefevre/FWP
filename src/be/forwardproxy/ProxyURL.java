package be.forwardproxy;

import java.net.MalformedURLException;
import java.net.URL;

public class ProxyURL {
    private final String host;
    private final String path;
    private int port;
    private boolean active;
    private final int decodeScriptId;
    private final boolean https;
    private final String urlString;
    private final String friendlyName;
    private final String searchPath;
    private final String protocol;

    public ProxyURL(String urlString, String friendlyName, String searchPath, int decodeScriptId) {
        this.urlString = urlString;
        this.friendlyName=friendlyName;
        this.searchPath=searchPath;
        this.decodeScriptId=decodeScriptId;

        URL url=null;
        try {
            url = new java.net.URL(this.urlString);
        } catch (MalformedURLException ex) {
            ProxyLog.getInstance().log("ProxyURL: URL is malformed [" + this.urlString + "]: " + ex.getMessage());
            System.exit(ForwardProxy.MALFORMED_URL);
        }

        https = url.getProtocol().equals("https");

        // Set default port
        port=url.getPort();
        if(port == -1) {
            if(https) port=443;
            else port = 80;
        }

        host=url.getHost();
        path=url.getPath();
        protocol=url.getProtocol();
    }

    public void setActive() {
        active=true;
    }

    public void setInActive() {
        active=false;
    }
    
    public char getActive() {
        if(active) return '*';
        else return ' ';
    }
    
    public int getDecodeScriptId() {
        return decodeScriptId;
    }

    public boolean isHttps() {
        return https;
    }

    public String getSearchPath() {
        return searchPath;
    }
    
    public String getFriendlyName() {
        return friendlyName;
    }

    public String getProtocol() {
        return protocol;
    }
   
    public String getHost() {
        return host;
    }

    public String getPath() {
        return path;
    }

    public int getPort() {
        return port;
    }

    public String getUrlString() {
        return urlString;
    }
}
