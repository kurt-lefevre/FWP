package be.forwardproxy;

import java.net.MalformedURLException;
import java.net.URL;

public class ProxyURL {
    private final String host;
    private final String path;
    private int port;
    private final String urlString;
    private final String friendlyName;
    private final String protocol;

    public ProxyURL(String urlString, String friendlyName) {
        this.urlString = urlString.strip();
        this.friendlyName=friendlyName;

        URL url=null;
        try {
            url = new java.net.URL(this.urlString);
        } catch (MalformedURLException ex) {
            Util.log("URL is malformed [" + this.urlString + "]: " + ex.getMessage());
            System.exit(ForwardProxy.MALFORMED_URL);
        }

        // Set default port
        port=url.getPort();
        if(port == -1) port = 80;

        host=url.getHost();
        path=url.getPath();
        protocol=url.getProtocol();
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

    @Override
    public String toString() {
        return urlString ;
    }
}
