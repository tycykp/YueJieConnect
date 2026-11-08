package android.telecom;
public class ConnectionService {
    public final java.util.List<Connection> live = new java.util.ArrayList<>();
    public java.util.Collection<Connection> getAllConnections() { return java.util.List.copyOf(live); }
}
