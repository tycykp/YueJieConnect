package io.github.yuejie.connect;

/** Keep fixed diagnostics visible even on OEM builds that suppress application logcat. */
final class ScreenCastLog {
    static volatile io.github.libxposed.api.XposedModule module;
    static void i(String tag,String message){write(android.util.Log.INFO,tag,message,null);}
    static void w(String tag,String message){write(android.util.Log.WARN,tag,message,null);}
    static void e(String tag,String message,Throwable error){write(android.util.Log.ERROR,tag,message,error);}
    private static void write(int level,String tag,String message,Throwable error){
        android.util.Log.println(level,tag,message+(error==null?"":" "+android.util.Log.getStackTraceString(error)));
        var current=module;if(current!=null)current.log(level,tag,message+(error==null?"":" "+android.util.Log.getStackTraceString(error)));
    }
}
