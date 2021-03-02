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
        if(!DEBUG) return;
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
        sBuf.append(dateFormatter.format(new Date())).append('[').append(threadId).
                append(':').append(threadCount).append("] ").append(logInfo);
        String msg = sBuf.toString();
        System.out.println(msg);
        if(logger!=null) logger.log(Level.INFO, msg);
    }

    public static int indexOf(byte[] byteArr, String searchStr) {
        return indexOf(byteArr, 0, byteArr.length, searchStr);        
    }

    public static int indexOf(byte[] byteArr, int start, int end, String searchStr) {
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

        
    public static int replace(byte[] byteArr, String fromStr, String toStr) {
        return replace(byteArr, 0, byteArr.length, fromStr, toStr);
    }
    
    public static int replace(byte[] byteArr, int start, int end, String fromStr, String toStr) {
        int index;
        if((index=indexOf(byteArr, start, end, fromStr))==-1) return -1;
        
        // fromStr was found
        int fromLen=fromStr.length();
        byte[] toStrArr = toStr.getBytes();

        // copy remaining bytes form source to array
        System.arraycopy(byteArr, index+fromLen, byteArr, 
                index+toStrArr.length, end-fromLen-index);

        // copy toArr to array
        System.arraycopy(toStrArr, 0, byteArr, index, toStrArr.length);
        
        return index;
    }

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
