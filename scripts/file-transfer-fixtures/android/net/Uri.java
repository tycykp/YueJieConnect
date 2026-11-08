package android.net;
public final class Uri {
    private final java.net.URI value;
    private Uri(String text) { value = java.net.URI.create(text); }
    public static Uri parse(String text) { return new Uri(text); }
    public String getScheme() { return value.getScheme(); }
    public String getAuthority() { return value.getAuthority(); }
    public String getPath() { return value.getPath(); }
    public String toString() { return value.toString(); }
    public boolean equals(Object other) { return other instanceof Uri uri && value.equals(uri.value); }
    public int hashCode() { return value.hashCode(); }
}
