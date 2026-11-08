package android.content;
import java.io.File;
public final class Context {
    private final File cache;
    public Context(File cache) { this.cache = cache; }
    public String getPackageName() { return "com.xiaomi.mirror"; }
    public ClassLoader getClassLoader() { return getClass().getClassLoader(); }
    public File getCacheDir() { return cache; }
    public ContentResolver getContentResolver() { return new ContentResolver(); }
}
