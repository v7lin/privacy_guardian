package org.socialcoding.privacyguardian.VPN;

import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SocketChannel;

/**
 * Created by user on 2016-08-14.
 */
public class Vpn extends VpnService {
    private static final String TAG = "VpnServiceTest";
    private Thread mThread;
    private ParcelFileDescriptor mInterface;
    Builder builder = new Builder();
    private Context mContext = null;
    private static int TIMING = 1000;
    private int MAX_BYTES = 2048;
    private int UDP_OFFSET = 8;

    @Override
    public int onStartCommand(final Intent intent, int flags, int startId) {
        mContext = getApplicationContext();
        mThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    mInterface = builder.setSession("MyVPNService")
                         .addAddress("192.168.0.1", 24)
                         .addDnsServer("8.8.8.8")
                         .addRoute("0.0.0.0", 0).establish();
                    FileInputStream in = new FileInputStream(mInterface.getFileDescriptor());
                    FileOutputStream out = new FileOutputStream(mInterface.getFileDescriptor());

                    SocketChannel socketChannel = SocketChannel.open();
                    protect(socketChannel.socket());                                       //protection
                    SocketManager socketManager = new SocketManager();

                    int length;                                                        //length of packet.
                    ByteBuffer packet = ByteBuffer.allocate(MAX_BYTES);

                    while (true) {
                        // Send the message to the client applications, if any.
                        while (socketManager.isMessage()) {
                            System.out.println("Found the Message");
                            byte[] msg = socketManager.getMessage();
                            System.out.println("Msg Length: " + msg.length);
                            out.write(msg);
                            System.out.println("Send the message to TUN");
                        }

                        // Read the message from the TUN
                        length = in.read(packet.array());
                        if (length > 0) {
                            packet.limit(length);
                            byte[] tmpPacket = new byte[length];
                            System.arraycopy(packet.array(), 0, tmpPacket, 0, length);
                            packet.clear();
                            int ihl = getIhl(tmpPacket);
                            System.out.println("IP Header Length: " + ihl);

                            // Parse the IP Header
                            IPHeader ipHeader = new IPHeader(tmpPacket, ihl);
                            int protocol = ipHeader.getProtocol();

                            // Parse the transport layer
                            if (protocol == 6) {
                                System.out.println("This is TCP packet.");
                                TCPHeader tcpHeader = new TCPHeader(tmpPacket, ihl);
                                processTCPPacket(in, out, socketManager, ipHeader, tcpHeader);
                            } else if (protocol == 17){
                                System.out.println("This is UDP packet.");
                                System.out.println("Packet Length: " + tmpPacket.length);
                                System.out.println("IP Header Length: " + ihl);
                                UDPHeader udpHeader = new UDPHeader(tmpPacket, ihl);
                                processUDPPacket(socketManager, ipHeader, udpHeader);
                            } else if (protocol == 1) {
                                System.out.println("This is ICMP packet.");
                                ICMPHeader icmpHeader = new ICMPHeader(tmpPacket, ihl);
                                System.out.println("ICMP Type: " + icmpHeader.getType());
                                System.out.println("ICMP Code: " + icmpHeader.getCode());
                                processICMPPacket(out, ipHeader, icmpHeader);
                            } else {
                                System.out.println("This is another protocol: " + protocol);
                            }
                        }
                        Thread.sleep(TIMING);
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    try {
                        if (mInterface != null) {
                            mInterface.close();
                            mInterface = null;
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }, "MyVpnRunnable");

        mThread.start();
        return START_STICKY;
    }

    private int getIhl(byte[] packet) {
        return (packet[0] & 0xf) * 4;
    }

    private void processICMPPacket(FileOutputStream out, IPHeader ipHeader, ICMPHeader icmpHeader) {

    }

    private void processTCPPacket(FileInputStream in, FileOutputStream out, SocketManager sm, IPHeader ipHeader, TCPHeader tcpHeader) {
        if (tcpHeader.getSyn()) {
            // Add the TCP Socket in the SocketManager
            try {
                SocketChannel channel = SocketChannel.open();
                protect(channel.socket());
                sm.addTCPSocket(channel, ipHeader, tcpHeader);
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else if (tcpHeader.getFin()) {
            // Delete the TCP Socket in the SocketManager
            sm.delSocket(true, ipHeader.getSourceIP(), tcpHeader.getSourcePort());
        } else {
            // Send the Message
            System.out.println("Send the TCP message from " + ipHeader.getSourceIP() + ":" + tcpHeader.getSourcePort() + " to " + ipHeader.getDestIP() + ":" + tcpHeader.getDestPort());
            System.out.println("TCP Message Size: " + tcpHeader.getPayloadLength());
            sm.sendMessage(true, ipHeader.getSourceIP(), tcpHeader.getSourcePort(), tcpHeader.getPayload());
        }
    }

    private void processUDPPacket(SocketManager sm, IPHeader ipHeader, UDPHeader udpHeader) {
        // Send the Message
        System.out.println("Send the UDP message from " + ipHeader.getSourceIP() + ":" + udpHeader.getSourcePort() + " to " + ipHeader.getDestIP() + ":" + udpHeader.getDestPort());
        System.out.println("UDP Message Size: " + udpHeader.getPayloadLength());

        try {
            DatagramChannel channel = DatagramChannel.open();
            protect(channel.socket());
            if (!sm.checkSocket(false, ipHeader.getSourceIP(), udpHeader.getSourcePort()))
                sm.addUDPSocket(channel, ipHeader, udpHeader);
            sm.sendMessage(false, ipHeader.getSourceIP(), udpHeader.getSourcePort(), udpHeader.getPayload());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void processTCPHandshake(FileInputStream in, FileOutputStream out, IPHeader ipHeader, TCPHeader tcpHeader) {
        String sourceIP = ipHeader.getSourceIP();
        String destIP = ipHeader.getDestIP();
        int sPort = tcpHeader.getSourcePort();
        int dPort = tcpHeader.getDestPort();
        int headerLength = tcpHeader.getHeaderLength();
        long seqNum = tcpHeader.getSequenceNumber();
        long ackNum = tcpHeader.getAckNumber();

        byte[] outPacket = null;

        if (tcpHeader.getSyn()) {
            System.out.println("SYN packet found to " + destIP + ":" + dPort);
            outPacket = changeDestSrc(tcpHeader, ipHeader, null, sourceIP, destIP, sPort, dPort, seqNum, ackNum, "syn");
        } else if (tcpHeader.getFin()) {
            System.out.println("FIN packet found to " + destIP + ":" + dPort);
            outPacket = changeDestSrc(tcpHeader, ipHeader, null, sourceIP, destIP, sPort, dPort, seqNum, ackNum, "fin");
        }

        try {
            out.write(outPacket);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void processFINHandshake(FileInputStream in, FileOutputStream out, IPHeader ipHeader, TCPHeader tcpHeader) {
        String sourceIP = ipHeader.getSourceIP();
        String destIP = ipHeader.getDestIP();
        int sPort = tcpHeader.getSourcePort();
        int dPort = tcpHeader.getDestPort();
        int headerLength = tcpHeader.getHeaderLength();
        long seqNum = tcpHeader.getSequenceNumber();
        long ackNum = tcpHeader.getAckNumber();

        System.out.println("FIN packet found to " + destIP + ":" + dPort);

        byte[] outPacket = changeDestSrc(tcpHeader, ipHeader, null, sourceIP, destIP, sPort, dPort, seqNum ,ackNum, "fin");
        try {
            out.write(outPacket);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private long makingSeqnum(){
        return (long)(Math.random() * Integer.MAX_VALUE) + 1;
    }
    private int makingChecksum(byte[] header){               //make checksum.
        int length = header.length;
        if(length ==0){
            return 1;
        }
        int answer=0;
        for(int i=0;i<length ;i+=2){
            int tmp = header[i];
            if(i+1>=length) {               //odd
                answer = answer+(int) ((tmp&0xff)<<8);
            }
            else {
                int tmp2 = header[i+1];
                answer = answer+(int)(((tmp&0xff) << 8)|(tmp2&0xff));
            }
        }
        answer=((answer&0xffff)+ (answer>>16));
        return ~answer;
    }

    public byte[] changeDestSrc(TCPHeader tHeader, IPHeader ipHeader, byte[] payload , String sourceIP, String destIP, int sPort, int dPort, long seqNum, long ackNum, String state){
        ipHeader.setDestIP(sourceIP);
        ipHeader.setSourceIP(destIP);
        //change IPs.
        tHeader.setDestPort(sPort);
        tHeader.setSourcePort(dPort);

        tHeader.setAckNumber(seqNum+1);
        long newSeqNum=0;
        switch(state){
            case "syn":
                newSeqNum = makingSeqnum();
                break;
            case "fin":
                newSeqNum = ackNum;
                break;
            default:
                Log.d(TAG,"???what state");
        }
        tHeader.setSequenceNumber(newSeqNum);
        int offset = tHeader.getHeaderLength();
        byte[] ipH  = ipHeader.getHeader();
        byte[] tHeaderReader = tHeader.getHeader();

        ipH[10]=(byte)0x00;
        ipH[11]=(byte)0x00;                   // make checksum to 0.
        tHeaderReader[16]=(byte)0x00;
        tHeaderReader[17]=(byte)0x00;                                                    //make checksums to 0.
        tHeaderReader[12] = (byte)((tHeaderReader[12])&0xf1);                               //reserved
        if(state.compareTo("syn")==0)
            tHeaderReader[13] = (byte) 0x12;                                                 //make to syn ack
        else if(state.compareTo("fin")==0)
            tHeaderReader[13] = (byte) 0x11;

        int payload_l = 0;
        if(payload != null){
            payload_l = payload.length;
        }
        byte[] pseudoTCP = new byte[tHeaderReader.length + 12+ payload_l];                         //Pseudo + TCP header.
        System.arraycopy(tHeaderReader,0 ,pseudoTCP,12,tHeaderReader.length);
        System.arraycopy(ipH ,12,pseudoTCP,0 ,8);

        pseudoTCP[8] = (byte)0;                                                        //reserved
        pseudoTCP[9] = (byte)6;
        pseudoTCP[10] = (byte) (((payload_l+offset)&0xff00)>>8);
        pseudoTCP[11] = (byte) ((payload_l+offset)&0x00ff);

        int ipChecksum = makingChecksum(ipH);
        int tcpChecksum =  makingChecksum(pseudoTCP);
        ipH[10] = (byte)((ipChecksum & 0xff00)>>8);
        ipH[11]  = (byte)(ipChecksum & 0x00ff);

        pseudoTCP[12+16] = (byte)((tcpChecksum&0xff00)>>8);
        pseudoTCP[12+17] = (byte)(tcpChecksum&0x00ff);
        byte[] outpacket = new byte[20+offset+payload_l];
        System.arraycopy(ipH,0,outpacket,0,20);
        System.arraycopy(pseudoTCP,12,outpacket,20,offset+payload_l);

        return outpacket;
    }
    @Override
    public void onDestroy() {
        if (mThread != null) {
            mThread.interrupt();
        }
        super.onDestroy();
        Log.d(TAG,"die");
    }

    public byte[] hexToByteArray(String hex) {
        if (hex == null || hex.length() == 0) {
            return null;
        }
        byte[] ba = new byte[hex.length() / 2];
        for (int i = 0; i < ba.length; i++) {
            ba[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return ba;
    }

    public String byteArrayToHex(byte[] ba) {
        if (ba == null || ba.length == 0) {
            return null;
        }

        StringBuffer sb = new StringBuffer(ba.length * 2);
        String hexNumber;
        for (int x = 0; x < ba.length; x++) {
            hexNumber = "0" + Integer.toHexString(0xff & ba[x]);

            sb.append(hexNumber.substring(hexNumber.length() - 2));
        }
        return sb.toString();
    }

    public String hexToIp(String addr){                     //hex to IP addr form.
        String ip = "";
        for(int i =0;i<addr.length();i=i+2){
            ip = ip+Integer.valueOf(addr.substring(i,i+2),16)+".";
        }
        return ip;
    }
}
