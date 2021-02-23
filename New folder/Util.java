package be.forwardproxy;

import java.text.SimpleDateFormat;
import java.util.Date;

public class Util {
    private static final SimpleDateFormat dateFormatter = new SimpleDateFormat("HH:mm:ss ");
    
    public static void log(String logInfo) {
        System.out.println(dateFormatter.format(new Date()) + logInfo);
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

    public static byte[] replace(byte[] byteArr, String fromStr, String toStr) {
        int index;
        if((index=indexOf(byteArr, 0, byteArr.length, fromStr))==-1) return byteArr;
        
        // fromStr was found
        int fromLen=fromStr.length();
        byte[] toArr = toStr.getBytes();
        byte[] newArr = new byte[byteArr.length + toArr.length - fromLen];

        // copy part before index from source to new array
        System.arraycopy(byteArr, 0, newArr, 0, index);

        // copy toArr to new array
        System.arraycopy(toArr, 0, newArr, index, toArr.length);
        
        // copy remaining bytes form source to new array
        System.arraycopy(byteArr, index+fromLen, newArr, 
                index+toArr.length, byteArr.length-fromLen-index);
        
        return newArr;
    }

}
