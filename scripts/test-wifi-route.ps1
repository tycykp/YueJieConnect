param([string]$OutputDirectory = 'build/wifi-route-validation')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root $OutputDirectory
$classes = Join-Path $out 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
# Host-only Android boundaries; exercise the production route implementation.
$fixtures = @{
    'android/net/Network.java' = 'package android.net; public final class Network {}'
    'android/net/NetworkCapabilities.java' = 'package android.net; public final class NetworkCapabilities { public static final int TRANSPORT_WIFI=1,NET_CAPABILITY_INTERNET=12; public boolean hasTransport(int type){return type==TRANSPORT_WIFI;} }'
    'android/net/NetworkRequest.java' = 'package android.net; public final class NetworkRequest { public boolean internet; public static final class Builder { private final NetworkRequest value=new NetworkRequest(); public Builder addTransportType(int t){return this;} public Builder addCapability(int c){if(c==12)value.internet=true;return this;} public NetworkRequest build(){return value;} } }'
    'android/util/Log.java' = 'package android.util; public final class Log { public static int i(String tag,String msg){return 0;} }'
    'android/net/ConnectivityManager.java' = @'
package android.net;
public final class ConnectivityManager {
    public Network first=new Network(),bound;
    public NetworkCallback callback;
    public int requests,unregisters;
    public boolean rejectBind,unavailable;
    public NetworkCapabilities getNetworkCapabilities(Network n){return new NetworkCapabilities();}
    public boolean bindProcessToNetwork(Network n){if(n!=null&&rejectBind)return false;bound=n;return true;}
    public void requestNetwork(NetworkRequest request,NetworkCallback cb,int timeout){
        if(request.internet)throw new AssertionError("LAN must not require Internet");
        requests++;callback=cb;if(unavailable)cb.onUnavailable();else cb.onAvailable(first);
    }
    public void unregisterNetworkCallback(NetworkCallback cb){unregisters++;}
    public Network[] getAllNetworks(){return new Network[]{first};}
    public Network getActiveNetwork(){return first;}
    public static class NetworkCallback {
        public void onAvailable(Network n){} public void onUnavailable(){} public void onLost(Network n){}
    }
}
'@
    'io/github/yuejie/connect/WifiRouteCheck.java' = @'
package io.github.yuejie.connect;
import android.net.*;
public final class WifiRouteCheck {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        ConnectivityManager cm=new ConnectivityManager();WifiRoute route=new WifiRoute(cm);
        check(route.acquire()&&cm.bound==cm.first,"initial LAN binding");
        check(route.acquire()&&cm.requests==1,"reuse existing request");
        ConnectivityManager.NetworkCallback old=cm.callback;
        Network replacement=new Network();old.onAvailable(replacement);
        check(cm.bound==replacement,"replacement network must rebind process");
        old.onLost(cm.first);check(cm.bound==replacement,"late old-network loss must not clear replacement");
        old.onLost(replacement);check(cm.bound==null,"lost network must clear process binding");
        old.onAvailable(replacement);check(cm.bound==replacement,"available callback restores route");
        route.release();check(cm.bound==null&&cm.unregisters==1,"release removes callback and binding");
        old.onAvailable(cm.first);check(cm.bound==null,"released callback must not resurrect binding");
        check(route.acquire(),"reacquire after release");
        old.onLost(cm.first);old.onAvailable(replacement);
        check(cm.bound==cm.first,"old request must not alter new binding");route.release();
        cm.rejectBind=true;check(route.acquire()&&cm.bound==null,"OEM binding rejection uses system route");
        route.release();cm.rejectBind=false;cm.unavailable=true;
        check(route.acquire()&&cm.bound==null,"unavailable request uses existing system Wi-Fi");route.release();
        System.out.println("WifiRoute: 12 lifecycle and route regression checks passed");
    }
}
'@
}
$sources = @()
foreach ($item in $fixtures.GetEnumerator()) {
    $file = Join-Path (Join-Path $out 'fixtures') $item.Key
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $file) | Out-Null
    [IO.File]::WriteAllText($file, $item.Value, [Text.UTF8Encoding]::new($false))
    $sources += $file
}
$sources += Join-Path $root 'android/module/src/main/java/io/github/yuejie/connect/WifiRoute.java'
$java = 'C:/Program Files/Android/Android Studio/jbr/bin'
& "$java/javac.exe" --release 17 -encoding UTF-8 -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Wi-Fi route fixture compilation failed' }
& "$java/java.exe" -cp $classes io.github.yuejie.connect.WifiRouteCheck
if ($LASTEXITCODE -ne 0) { throw 'Wi-Fi route regression failed' }
