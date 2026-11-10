package android.net;
public record Uri(String scheme, String value) {
    public static Uri fromParts(String scheme, String value, String fragment) { return new Uri(scheme, value); }
    public String getScheme() { return scheme; }
    public String getSchemeSpecificPart() { return value; }
}
