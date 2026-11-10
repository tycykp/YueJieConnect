package android.telecom;
public class Connection {
    public static final int STATE_INITIALIZING = 0, STATE_NEW = 1, STATE_RINGING = 2, STATE_DIALING = 3,
            STATE_ACTIVE = 4, STATE_HOLDING = 5, STATE_DISCONNECTED = 6, CAPABILITY_HOLD = 1;
    public int state = STATE_RINGING, video, capabilities = CAPABILITY_HOLD, actions;
    public int getState() { return state; }
    public android.net.Uri address;
    public int presentation = 1;
    public android.net.Uri getAddress() { return address; }
    public int getAddressPresentation() { return presentation; }
    public int getVideoState() { return video; }
    public int getConnectionCapabilities() { return capabilities; }
    public void onAnswer(int videoState) { actions++; }
    public void onReject() { actions++; }
    public void onDisconnect() { actions++; }
    public void onHold() { actions++; }
    public void onUnhold() { actions++; }
}
