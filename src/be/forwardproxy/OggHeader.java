package be.forwardproxy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// Parses the OggHeader. Getters are defined for useful information 
public class OggHeader {
    private static final byte[] OGGS = {'O', 'g', 'g', 'S'}; 
    private static final byte[] FLAC = {'f', 'L', 'a', 'C'}; 
    private byte[] inputBytes;
    private int streamOffset;
    private int dataSize;

    public void setData(byte[] inputBytes, int streamOffset) {
        this.inputBytes = inputBytes;
        this.streamOffset = streamOffset;
        dataSize=-1;
   }

    public boolean isContinuedPacket() {
        return (inputBytes[streamOffset+5] & 1)==1;
    }

    public boolean isBeginOfStream() {
        return (inputBytes[streamOffset+5] & 2)==2;
    }

    public boolean isEndOfStream() {
        return (inputBytes[streamOffset+5] & 4)==4;
    }

    public boolean isFlac() {
        return ByteBuffer.wrap(FLAC).equals(ByteBuffer.wrap(inputBytes, getDataStartPos()+9, 4));

    }

    public boolean isOggStream() {
        return ByteBuffer.wrap(OGGS).equals(ByteBuffer.wrap(inputBytes, streamOffset, 4));
    }

    public int getSegmentCount() {
        return (int)(inputBytes[streamOffset+26] & 0xff);
    }

    public int getFlacStartPos() {
        return getDataStartPos()+9;
    }
    
    public byte getVersion() {
        return inputBytes[streamOffset+4];
    }

    // = Ogg header + segment table
    public int getHeaderSize() {
        return getSegmentCount() + 27;
    }

    public int getPageNr() {
        return ByteBuffer.wrap(inputBytes, streamOffset+18, 4).
                order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xffffffff;
    }

    // = Ogg header + segment table + data 
    public int getPageSize() {
        return getHeaderSize() + getDataSize();
    }

    public int getDataSize() {
        if(dataSize!=-1) return dataSize;

        int segmentCount = getSegmentCount();
        dataSize=0;
        for(int t=0; t<segmentCount; t++) 
            dataSize+=(int)(inputBytes[streamOffset+27+t] & 0xff);

        return dataSize;
    }

    public int getDataStartPos() {
        return streamOffset + 27 + getSegmentCount();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("OggS: ").append(isOggStream()).
            append("\nPage nr: ").append(getPageNr()).
            append("\nVersion: ").append(getVersion()).
            append("\nBegin of stream: ").append(isBeginOfStream()).
            append("\nContinued packet: ").append(isContinuedPacket()).
            append("\nEnd of stream: ").append(isEndOfStream()).
            append("\nSegments: ").append(getSegmentCount()).
            append("\nHeader size: ").append(getHeaderSize()).
            append("\nData size: ").append(getDataSize()).
            append("\nPage size: ").append(getPageSize());

        return sb.toString();
    }
}
