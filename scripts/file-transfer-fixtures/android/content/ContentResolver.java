package android.content;
import android.net.Uri;
import java.io.*;
import java.util.*;
public final class ContentResolver {
    public static final Map<Uri, byte[]> DATA = new HashMap<>();
    public InputStream openInputStream(Uri uri) throws FileNotFoundException {
        byte[] bytes = DATA.get(uri);
        if (bytes == null) throw new FileNotFoundException("synthetic_missing_source");
        return new ByteArrayInputStream(bytes);
    }
}
