package io.github.yuejie.connect;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Holds the selected Wi-Fi network for the lifetime of this bridge, never vendor processes. */
final class WifiRoute {
    private final ConnectivityManager manager;
    private volatile ConnectivityManager.NetworkCallback callback;
    private volatile Network selected;
    WifiRoute(ConnectivityManager manager){this.manager=manager;}
    synchronized boolean acquire(){
        Network existing=selected;
        if(existing!=null&&manager.getNetworkCapabilities(existing)!=null&&manager.bindProcessToNetwork(existing))return true;
        release();
        CountDownLatch available=new CountDownLatch(1);
        ConnectivityManager.NetworkCallback requested=new ConnectivityManager.NetworkCallback(){
            @Override public void onAvailable(Network network){
                if(callback!=this)return;
                selected=network;available.countDown();
            }
            @Override public void onUnavailable(){available.countDown();}
            @Override public void onLost(Network network){
                if(callback==this&&network.equals(selected)){
                    selected=null;
                    android.util.Log.i("YueJieBridge","wifi_network_lost");
                }
            }
        };
        callback=requested;
        try{
            manager.requestNetwork(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),requested,4000);
            if(!available.await(4500,TimeUnit.MILLISECONDS)||selected==null)return systemDefault("wifi_request_unavailable");
            if(!manager.bindProcessToNetwork(selected))return systemDefault("wifi_bind_rejected");
            android.util.Log.i("YueJieBridge","route=wifi_retained");return true;
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();release();return false;}
        catch(RuntimeException failure){return systemDefault("wifi_request_"+failure.getClass().getSimpleName());}
    }
    private boolean systemDefault(String reason){
        // Some OEM/VPN policies reject explicit network binding while the system's default
        // route permits the selected LAN peer. Preserve that policy; never change global routing.
        release();
        boolean wifi=false;
        for(Network network:manager.getAllNetworks()){
            NetworkCapabilities capabilities=manager.getNetworkCapabilities(network);
            if(capabilities!=null&&capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))wifi=true;
        }
        boolean available=wifi&&manager.getActiveNetwork()!=null;
        android.util.Log.i("YueJieBridge","route="+(available?"system_default":"unavailable")+" reason="+reason);
        return available;
    }
    synchronized void release(){
        var previous=callback;callback=null;selected=null;
        if(previous!=null)try{manager.unregisterNetworkCallback(previous);}catch(IllegalArgumentException ignored){}
        manager.bindProcessToNetwork(null);
    }
}
