package be.forwardproxy;

import java.text.SimpleDateFormat;
import java.util.Date;

public class Util {
    private static final SimpleDateFormat dateFormatter = new SimpleDateFormat("dd/MM HH:mm:ss ");
    private static StringBuffer sBuf = new StringBuffer();
    public static boolean DEBUG = false;
    
    public static void deb(long threadId, int threadCount, String logInfo) {
        if(!DEBUG) return;
        log(threadId, threadCount, logInfo);
    }

    synchronized public static void log(String logInfo) {
        sBuf.setLength(0);
        sBuf.append(dateFormatter.format(new Date())).append(logInfo);
        System.out.println(sBuf);
    }
    
    synchronized public static void log(long threadId, int threadCount, String logInfo) {
        sBuf.setLength(0);
        sBuf.append(dateFormatter.format(new Date())).append('[').append(threadId).
                append(':').append(threadCount).append("] ").append(logInfo);
        System.out.println(sBuf);
    }

    public static int indexOf(byte[] byteArr, String searchStr) {
        return indexOf(byteArr, 0, byteArr.length, searchStr);        
    }

    public static int indexOf(byte[] byteArr, int start, int end, String searchStr) {
        byte[] searchArr=searchStr.getBytes();
        int lastPos=end - searchArr.length;
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
