package be.forwardproxy;

import java.net.MalformedURLException;
import java.net.URL;

public class ProxyURL {
    private String host;
    private String path;
    private int port;
    private final String urlString;

    public ProxyURL(String urlString) {
        this.urlString = urlString.strip();

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
