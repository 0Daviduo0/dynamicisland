package dev.android.dynamicisland.island.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class QuickShareStateTest {
    private static String state(String receiveStatus, String user, int progress, long bytes, long total) {
        return "ReceivingDialogState(fetchStatus=Fetched, requestAccept=RequestAccept(id=42, deviceId=abc, commandId=1, receiveStatus="
                + receiveStatus + ", senderDeviceCategory=PHONE, acceptResult=NONE, progressHide=false, totalFileCount=3, errorCode=0, "
                + "fileName=IMG_0001.jpg, progress=" + progress + ", downloadFileCount=1, shareContentType=IMAGE, previewUris=[], "
                + "text=null, representFileName=null, deviceName=Galaxy S25, senderName=Anna, downloadBytes=" + bytes
                + ", totalFileSize=" + total + ", cancel=false), userAcceptResult=" + user + ", previewStatus=DONE, deviceLocked=false, invalidSetupPreview=false)";
    }

    @Test
    public void readsTheRequest() {
        QuickShareState s = QuickShareState.parse(state("WAIT_FOR_ACCEPT", "NONE", 0, 0, 0));
        assertEquals(42, s.id);
        assertEquals("Anna", s.sender);
        assertEquals("IMG_0001.jpg", s.fileName);
        assertNull(s.text);
        assertEquals(3, s.fileCount);
        assertEquals(QuickShareState.Phase.REQUEST, s.phase());
    }

    @Test
    public void followsTheTransfer() {
        QuickShareState s = QuickShareState.parse(state("TRANSFER_STARTED", "ACCEPT", 10, 250, 1000));
        assertEquals(QuickShareState.Phase.TRANSFER, s.phase());
        assertEquals(.25f, s.progress, 1e-4);
        QuickShareState percent = QuickShareState.parse(state("TRANSFER_STARTED", "ACCEPT", 60, 0, 0));
        assertEquals(.6f, percent.progress, 1e-4);
        assertEquals(QuickShareState.Phase.TRANSFER, QuickShareState.parse(state("WAIT_FOR_ACCEPT", "ACCEPT", 0, 0, 0)).phase());
    }

    @Test
    public void endings() {
        assertEquals(QuickShareState.Phase.DONE, QuickShareState.parse(state("TRANSFER_FINISHED", "ACCEPT", 100, 0, 0)).phase());
        assertEquals(QuickShareState.Phase.DECLINED, QuickShareState.parse(state("WAIT_FOR_ACCEPT", "DECLINE", 0, 0, 0)).phase());
        assertEquals(QuickShareState.Phase.FAILED, QuickShareState.parse(state("TRANSFER_CANCELED", "ACCEPT", 0, 0, 0)).phase());
    }

    @Test
    public void noSessionNoState() {
        assertNull(QuickShareState.parse("ReceivingDialogState(fetchStatus=NotFetched)"));
        assertNull(QuickShareState.parse(state("NONE", "NONE", 0, 0, 0).replace("id=42", "id=-1")));
        assertNull(QuickShareState.parse(null));
    }
}
