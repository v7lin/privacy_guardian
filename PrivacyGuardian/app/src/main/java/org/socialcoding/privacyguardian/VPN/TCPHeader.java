package org.socialcoding.privacyguardian.VPN;

/**
 * Created by 신승수 on 2016-10-25.
 */
public class TCPHeader extends TransportHeader {
    private long sequenceNumber;
    private long ackNumber;

    TCPHeader(byte[] packet , int ihl){
        this.ihl = ihl;
        this.length = packet.length - ihl;
        this.headerLength = ((int)((packet[ihl+12] & 0xf0) >> 4)) * 4;
        this.payloadLength = length - headerLength;

        header = new byte[headerLength];
        payload = new byte[length - headerLength];

        for(int i = 0; i < headerLength; i++)
            header[i] = packet[ihl+i];

        for (int i = 0; i < payloadLength; i++)
            payload[i] = packet[ihl + headerLength + i];

        sPort = ((header[0] & 0xff) << 8) | (header[1] & 0xff);
        dPort = ((header[2] & 0xff) << 8) | (header[3] & 0xff);
        sequenceNumber = (((header[4] &0xff) << 24) | ((header[5] &0xff) << 16) | ((header[6] &0xff) << 8) | (header[7] &0xff)) & 0x00000000ffffffff;
        ackNumber = (((header[8] &0xff) << 24) | ((header[9] &0xff) << 16) | ((header[10] &0xff) << 8) | (header[11] &0xff)) & 0x00000000ffffffff;
    }

    long getSequenceNumber(){
        return sequenceNumber;
    }
    void setSequenceNumber(long sequenceNumber){
        this.sequenceNumber = sequenceNumber;
        header[4]= (byte)(sequenceNumber >> 24);
        header[5]= (byte)(sequenceNumber >> 16);
        header[6]= (byte)(sequenceNumber >> 8);
        header[7]= (byte)(sequenceNumber);
    }

    boolean getSyn(){
        boolean ret = false;

        if (((header[13] & 0x2) >> 1) == 1)
            ret = true;

        return ret;
    }

    boolean getFin() {
        boolean ret = false;

        if (((header[13] & 0x1) >> 1) == 1)
            ret = true;
        return ret;
    }

    boolean getAck(){
        boolean ret = false;

        if (((header[13] & 0x10) >> 4) == 1)
            ret = true;

        return ret;
    }

    long getAckNumber() { return ackNumber;}
    void setAckNumber(long ackNum) {
        ackNumber = ackNum;
        header[8] = (byte) (ackNum >> 24);
        header[9] = (byte) (ackNum >> 16);
        header[10] = (byte) (ackNum >> 8);
        header[11] = (byte) (ackNum);
    }
}
