package be.forwardproxy;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public class Util {
    public static boolean DEBUG = false;

    public final static int LOGFILE_SIZE_KB = 1024;
    public final static int LOGFILE_COUNT = 2;
    private static final SimpleDateFormat dateFormatter = new SimpleDateFormat("dd/MM HH:mm:ss ");
    private static StringBuffer sBuf = new StringBuffer();
    private static Logger logger;
    
    private static class LogFileFormatter extends java.util.logging.Formatter {
        @Override
        public String format(LogRecord record) {
            return record.getMessage() + "\n";
        }
    }
    
    public static void initializeLogger(String logfileName, int logfileSizeKb){
        if(logger!=null) return; // we do it only once
        logger = Logger.getLogger(Util.class.getName());

        // add own handler
        FileHandler handler=null;
        try {
            handler = new FileHandler(logfileName + "%g.log",
                    logfileSizeKb * 1024, LOGFILE_COUNT, true);
        } catch (Exception ex) {
            log("Failed to initialize logger");
        }
        handler.setFormatter(new LogFileFormatter());
        logger.addHandler(handler);
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.INFO);
    }
    
    
    public static void deb(long threadId, int threadCount, String logInfo) {
        // if(!DEBUG) return;
        sBuf.setLength(0);
        sBuf.append(dateFormatter.format(new Date())).append('[').append(threadId).
                append(':').append(threadCount).append("] ").append(logInfo);
        System.out.println(sBuf);
    }

    public static void log(String logInfo) {
        log(-1, -1, logInfo);
    }
    
    synchronized public static void log(long threadId, int threadCount, String logInfo) {
        sBuf.setLength(0);
        sBuf.append(dateFormatter.format(new Date()));
        if(threadId!=-1) 
            sBuf.append('[').append(threadId).append("]:[").append(threadCount).append("] ");
        sBuf.append(logInfo);
        String msg = sBuf.toString();
        System.out.println(msg);
        if(logger!=null) logger.log(Level.INFO, msg);
    }

    /*public static int indexOf(byte[] byteArr, String searchStr) {
        return indexOf(byteArr, 0, byteArr.length, searchStr);        
    }*/

/*    public static byte[] remove(byte[] byteArr, int start, int len) {
        byte[] newArr = new byte[byteArr.length];

        // copy part before start from source to new array
        System.arraycopy(byteArr, 0, newArr, 0, start);

        
        // copy remaining bytes form source to new array
        System.arraycopy(byteArr, start+len, newArr, start, byteArr.length-start-len);
        
        return newArr;
    }
*/    
}
