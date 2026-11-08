package android.content;
import android.net.Uri;
public final class ClipData {
    public static final class Item {
        private final Uri uri;
        Item(Uri uri) { this.uri = uri; }
        public Uri getUri() { return uri; }
    }
    private final Item[] items;
    public ClipData(Uri... uris) { items = java.util.Arrays.stream(uris).map(Item::new).toArray(Item[]::new); }
    public int getItemCount() { return items.length; }
    public Item getItemAt(int at) { return items[at]; }
}
