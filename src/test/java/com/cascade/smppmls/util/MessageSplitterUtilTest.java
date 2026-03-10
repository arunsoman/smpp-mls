package com.cascade.smppmls.util;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MessageSplitterUtilTest {

    @Test
    void testIsArabicScript() {
        // English
        assertFalse(MessageSplitterUtil.isArabicScript("Hello World 123"));
        assertFalse(MessageSplitterUtil.isArabicScript(""));
        assertFalse(MessageSplitterUtil.isArabicScript(null));

        // Dari / Pashto (Arabic script)
        assertTrue(MessageSplitterUtil.isArabicScript("سلام دنیا")); // Dari/Persian
        assertTrue(MessageSplitterUtil.isArabicScript("سلام نړۍ")); // Pashto
    }

    @Test
    void testSplitMessageSingleGsm() {
        String msg = "Hello World".repeat(5); // 55 chars
        List<MessageSplitterUtil.MessagePart> parts = MessageSplitterUtil.splitMessage(msg, false, 100);
        
        assertEquals(1, parts.size());
        assertEquals(msg, parts.get(0).text);
        assertNull(parts.get(0).udh);
        assertEquals("GSM7", parts.get(0).encoding);
        assertEquals(1, parts.get(0).partNo);
        assertEquals(1, parts.get(0).totalParts);
    }

    @Test
    void testSplitMessageMultipartGsm() {
        String msg = "Hello World ".repeat(20); // 240 chars
        List<MessageSplitterUtil.MessagePart> parts = MessageSplitterUtil.splitMessage(msg, false, 999);
        
        assertEquals(2, parts.size());
        assertEquals("GSM7", parts.get(0).encoding);
        assertEquals("GSM7", parts.get(1).encoding);
        
        // 1st part should be 153 chars
        assertEquals(153, parts.get(0).text.length());
        assertEquals(1, parts.get(0).partNo);
        assertEquals(2, parts.get(0).totalParts);
        assertNotNull(parts.get(0).udh);
        assertEquals(7, parts.get(0).udh.length);
        assertEquals(999, parts.get(0).concatRefNum);
        
        // 2nd part should be the rest (240 - 153 = 87 chars)
        assertEquals(87, parts.get(1).text.length());
        assertEquals(2, parts.get(1).partNo);
        assertEquals(2, parts.get(1).totalParts);
        assertEquals(999, parts.get(1).concatRefNum);
        assertEquals((byte)2, parts.get(1).udh[6]); // part num
    }

    @Test
    void testSplitMessageSingleUcs2() {
        String msg = "سلام دنیا"; // 9 chars
        List<MessageSplitterUtil.MessagePart> parts = MessageSplitterUtil.splitMessage(msg, false, 200);
        
        assertEquals(1, parts.size());
        assertEquals(msg, parts.get(0).text);
        assertNull(parts.get(0).udh);
        assertEquals("UCS2", parts.get(0).encoding);
    }
    
    @Test
    void testSplitMessageMultipartUcs2() {
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < 20; i++) {
            sb.append("سلام دنیا "); // 10 chars per loop -> 200 chars total
        }
        String msg = sb.toString();
        
        List<MessageSplitterUtil.MessagePart> parts = MessageSplitterUtil.splitMessage(msg, false, 300);
        
        // 200 chars / 66 chars max per part = 4 parts
        assertEquals(4, parts.size());
        
        assertEquals("UCS2", parts.get(0).encoding);
        assertEquals(66, parts.get(0).text.length());
        assertEquals(1, parts.get(0).partNo);
        assertEquals(4, parts.get(0).totalParts);
        assertNotNull(parts.get(0).udh);
        
        // Check UDH exactly
        byte[] udh = parts.get(0).udh;
        assertEquals(0x06, udh[0]);
        assertEquals(0x08, udh[1]);
        assertEquals(0x04, udh[2]);
        assertEquals((byte)((300 >> 8) & 0xFF), udh[3]); // ref hi
        assertEquals((byte)(300 & 0xFF), udh[4]); // ref lo
        assertEquals(4, udh[5]); // max parts
        assertEquals(1, udh[6]); // current part
        
        assertEquals(66, parts.get(1).text.length());
        assertEquals((byte)2, parts.get(1).udh[6]);
        
        assertEquals(66, parts.get(2).text.length());
        assertEquals((byte)3, parts.get(2).udh[6]);
        
        assertEquals(2, parts.get(3).text.length()); // 200 - (66 * 3) = 2
        assertEquals((byte)4, parts.get(3).udh[6]);
    }
}
