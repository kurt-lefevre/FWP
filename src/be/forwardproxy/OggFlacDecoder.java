/*
    Ogg transport format
    ====================
    file/stream = 0.* pages
    page = page header + 0.* segments
    segment = 255 bytes, except last segement (0..255 bytes)
    
    page header
    -----------
    byte(s) | Description
    --------+-------------------------------------------
    0-4     | contains the string "OggS"
    26      | number of segments
    27-n    | n = page_segments + value of byte 26

    Ref: https://xiph.org/vorbis/doc/framing.html
         https://xiph.org/flac/ogg_mapping.html
         https://xiph.org/flac/format.html
         https://xiph.org/flac/documentation_tools_metaflac.html
         https://tools.ietf.org/html/rfc3533.html
*/

package be.forwardproxy;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;

public class OggFlacDecoder {
    private static final ProxyLog logger=ProxyLog.getInstance();
    private final int IO_BUF_SIZE;
    private final long threadId;
    private InputStream is;
    private OutputStream os;
    private final OggHeader oggHeader = new OggHeader();
    private byte[] oggPage = new byte[65067]; 
    // 65307 = (255 B/segm * 255 segm) + 255 B (segm table) + 27 B (Ogg header)
    // 65067 = (255 B/segm * 255 segm) + 79 B - 28 B - 9 B
    
    public OggFlacDecoder(long threadId, ProxyURL proxyUrl, int ioBufferSize, OutputStream os, InputStream is) {
        this.IO_BUF_SIZE = ioBufferSize;
        this.threadId = threadId;
        this.is = is;
        this.os = os;
    }
  
    public void decode(byte[] inputBytes, int streamOffset, int bytesRead) {
        FileOutputStream fos=null;
        try {
            //is = new URL("http://mscp3.live-streams.nl:8340/jazz-flac.flac").openStream();
            // is = new URL("http://secure.live-streams.nl/flac.flac").openStream();
            //is = new FileInputStream("jazz-flac.flac");
            //fos = new FileOutputStream("kurt.flac");
        } catch (Exception ex) {
            logger.log(threadId, "OggFlacDecoder: foute boel bij openen stream! " + ex.getMessage());
            System.exit(-1);
        }
        
        
        // hack
        try {
            os.write(inputBytes, streamOffset, bytesRead);
        } catch (Exception ex) {
            logger.log(threadId, "OggFlacDecoder: foute boel! " + ex.getMessage());
            System.exit(-1);
        }
        
        streamOffset=0;
        //int offset=0;
        
        // Read incoming stream
        try {
            // Process Ogg page header
            // Read first page (BOS). Always 79 bytes
            bytesRead=0;
            while((bytesRead+=is.read(oggPage, streamOffset+bytesRead, 79-bytesRead)) < 79); 
            oggHeader.setData(oggPage, streamOffset);
            if(!oggHeader.isBeginOfStream() || !oggHeader.isOggStream()) {
                logger.log(threadId, "OggFlacDecoder: Can't find Begin Of Stream");
                if(ProxyLog.DEBUG) logger.deb(threadId, "OggFlacDecoder: BOS: ["  + oggHeader + "]");
                return;
            }
            // valid first page, check fLaC signature
            if(!oggHeader.isFlac()) {
                logger.log(threadId, "OggFlacDecoder: Can't find fLaC signature");
                if(ProxyLog.DEBUG) logger.deb(threadId, "OggFlacDecoder: BOS: ["  + oggHeader + "]");
                return;
            }
            // valid flac file. Write flac header to OS
/*            try {
                fos.write(oggPage, oggHeader.getFlacStartPos(), 79-oggHeader.getFlacStartPos());
                os.write(oggPage, oggHeader.getFlacStartPos(), 79-oggHeader.getFlacStartPos());
            } catch (IOException ex) {
                logger.log(threadId, "OggFlacDecoder: Can't send FLAC header: " + ex.getMessage());
            }
*/
            // Set start of FLAC stream at pos 0
            int dataLen = 79 - oggHeader.getFlacStartPos();
            System.arraycopy(oggPage, oggHeader.getFlacStartPos(), oggPage, 0, dataLen);
            streamOffset+=dataLen;

            while(bytesRead>-1) {
                // Read fixed header part (27 bytes)
                bytesRead=0;
                while((bytesRead+=is.read(oggPage, streamOffset+bytesRead, 27-bytesRead)) < 27); 
                oggHeader.setData(oggPage, streamOffset);

                // Read segments table
                bytesRead=0;
                while((bytesRead+=is.read(oggPage, streamOffset+bytesRead+27, oggHeader.getSegmentCount()-bytesRead)) 
                        < oggHeader.getSegmentCount());
                oggHeader.setData(oggPage, streamOffset);

                if(ProxyLog.DEBUG) logger.deb(threadId, "OggFlacDecoder: header: ["  + oggHeader + "]");
               
                // Read data
                bytesRead=0;
                while((bytesRead+=is.read(oggPage, streamOffset+bytesRead, oggHeader.getDataSize()-bytesRead)) 
                        < oggHeader.getDataSize());
                streamOffset+=oggHeader.getDataSize();
                
                int startPos=0;
                while(startPos<streamOffset) {
                    try {
//                        fos.write(oggPage, 0, streamOffset);
//                        os.write(oggPage, 0, streamOffset);
//                        fos.write(oggPage, startPos, 8192);
                        os.write(oggPage, startPos, 8192);
                        if(ProxyLog.DEBUG) logger.deb(threadId, "OggFlacDecoder: Wrote buffer: startPos: " + startPos);
                        startPos+=8192;
                    } catch (IOException ex) {
                        logger.log(threadId, "OggFlacDecoder: Cannot send FLAC data1: " + ex.getMessage());
                    }
                }
                startPos-=8192;
                    try {
  //                      fos.write(oggPage, startPos, streamOffset-startPos);
                        os.write(oggPage, startPos, streamOffset-startPos);
                        if(ProxyLog.DEBUG) logger.deb(threadId, "OggFlacDecoder: Wrote buffer: remaining: " + (streamOffset-startPos));
                    } catch (IOException ex) {
                        logger.log(threadId, "OggFlacDecoder: Cannot send FLAC data2: " + ex.getMessage());
                    }
                
                streamOffset=0;
            }
        } catch (IOException ex) {  
            logger.log(threadId, "OggFlacDecoder: Can't read server response: " + ex.getMessage());
        }
    }
}
