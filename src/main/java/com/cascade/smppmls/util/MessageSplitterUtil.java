package com.cascade.smppmls.util;

import java.util.ArrayList;
import java.util.List;

public class MessageSplitterUtil {

    private static final int MAX_SINGLE_GSM = 160;
    private static final int MAX_MULTIPART_GSM = 153;
    private static final int MAX_SINGLE_UCS2 = 70;
    private static final int MAX_MULTIPART_UCS2 = 66; // 140 - 7 (16-bit ref UDH) = 133 / 2 = 66

    public static class MessagePart {
        public final String text;
        public final int partNo;
        public final int totalParts;
        public final int concatRefNum;
        public final byte[] udh;
        public final String encoding;

        public MessagePart(String text, int partNo, int totalParts, int concatRefNum, byte[] udh, String encoding) {
            this.text = text;
            this.partNo = partNo;
            this.totalParts = totalParts;
            this.concatRefNum = concatRefNum;
            this.udh = udh;
            this.encoding = encoding;
        }
    }

    /**
     * Checks if the given text contains Arabic script characters (including Dari/Pashto).
     */
    public static boolean isArabicScript(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            int codePoint = text.codePointAt(i);
            // Arabic block: U+0600..U+06FF, Arabic Supplement: U+0750..U+077F
            // Arabic Extended-A: U+08A0..U+08FF, Arabic Presentation Forms-A: U+FB50..U+FDFF
            if ((codePoint >= 0x0600 && codePoint <= 0x06FF) ||
                (codePoint >= 0x0750 && codePoint <= 0x077F) ||
                (codePoint >= 0x08A0 && codePoint <= 0x08FF) ||
                (codePoint >= 0xFB50 && codePoint <= 0xFDFF)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Splits a message into parts if necessary.
     * Uses 16-bit reference number (7-byte UDH) for concatenated messages.
     * 
     * @param message Text to split
     * @param forceUcs2 Force UCS2 encoding even if not Arabic script
     * @param concatRefNum A reference number for the parts (1-65535)
     * @return List of MessageParts
     */
    public static List<MessagePart> splitMessage(String message, boolean forceUcs2, int concatRefNum) {
        if (message == null || message.isEmpty()) {
            return List.of(new MessagePart("", 1, 1, 0, null, "GSM7"));
        }

        boolean isUcs2 = forceUcs2 || isArabicScript(message);
        List<MessagePart> parts = new ArrayList<>();
        
        int length = message.length();
        int maxSingle = isUcs2 ? MAX_SINGLE_UCS2 : MAX_SINGLE_GSM;
        
        if (length <= maxSingle) {
            // Single part message, no UDH
            parts.add(new MessagePart(message, 1, 1, 0, null, isUcs2 ? "UCS2" : "GSM7"));
            return parts;
        }

        // Multipart message
        int maxMulti = isUcs2 ? MAX_MULTIPART_UCS2 : MAX_MULTIPART_GSM;
        int totalParts = (int) Math.ceil((double) length / maxMulti);
        
        for (int i = 0; i < totalParts; i++) {
            int start = i * maxMulti;
            int end = Math.min(start + maxMulti, length);
            String chunk = message.substring(start, end);
            
            // Generate 7-byte UDH for 16-bit reference number
            byte[] udh = new byte[7];
            udh[0] = 0x06; // Length of UDL
            udh[1] = 0x08; // Information Element Identifier (0x08 = 16-bit concatenated message reference)
            udh[2] = 0x04; // Information Element Data Length (4 bytes follow)
            udh[3] = (byte) ((concatRefNum >> 8) & 0xFF); // Reference number (High Byte)
            udh[4] = (byte) (concatRefNum & 0xFF);        // Reference number (Low Byte)
            udh[5] = (byte) totalParts; // Maximum number of messages in the concatenated message
            udh[6] = (byte) (i + 1);    // Sequence number of the current message part
            
            parts.add(new MessagePart(chunk, i + 1, totalParts, concatRefNum, udh, isUcs2 ? "UCS2" : "GSM7"));
        }
        
        return parts;
    }
    
    /**
     * Converts hex string back to byte array for UDH string fields.
     */
    public static byte[] hexStringToByteArray(String s) {
        if (s == null || s.isEmpty()) return null;
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                                 + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    /**
     * Converts byte array to hex string for storing UDH.
     */
    public static String bytesToHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}
