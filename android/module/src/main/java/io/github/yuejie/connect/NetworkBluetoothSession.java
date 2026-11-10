package io.github.yuejie.connect;

import android.Manifest;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;
import org.json.JSONObject;

/** A Bluetooth carrier for the ordinary Noise XX bridge. Uses the same bilateral confirmation UI. */
final class NetworkBluetoothSession implements AutoCloseable {
    private final NetworkShareBluetoothBearer bearer;
    private final Context context;
    private volatile NetworkShareBluetoothBearer.Link link;
    private volatile String phase="stopped",error="";
    private boolean initiator;
    interface BeforeAttach { void accept(android.os.ParcelFileDescriptor fd) throws Exception; }
    private final java.util.concurrent.CompletableFuture<Void> listeningReady = new java.util.concurrent.CompletableFuture<>();
    NetworkBluetoothSession(Context context){
        this(context, connected -> {});
    }
    NetworkBluetoothSession(Context context, BeforeAttach beforeAttach){
        this.context=context;
        bearer=new NetworkShareBluetoothBearer(context,connected->{
            link=connected;
            try (var fd = connected.duplicateDuplex()) {
                android.util.Log.i("YueJieNetwork","bluetooth_carrier_connected initiator="+initiator);
                beforeAttach.accept(fd);
                if(!NativeCore.attachBluetooth(fd.detachFd(),initiator))
                    throw new IllegalStateException("bluetooth_attach_failed");
                phase="connected";
                connected.termination().thenAccept(reason->{phase="disconnected";error=reason;});
            }catch(Exception failure){phase="failed";error="bluetooth_attach_failed";
                android.util.Log.w("YueJieNetwork","bluetooth_attach_failed "+failure.getClass().getSimpleName());connected.close();}
        });
    }
    @android.annotation.SuppressLint("MissingPermission")
    JSONObject start(JSONObject command)throws Exception {
        if(context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)
            throw new IllegalStateException("bluetooth_connect_permission_denied");
        var adapter=context.getSystemService(BluetoothManager.class).getAdapter();
        if(adapter==null||!adapter.isEnabled())throw new IllegalStateException("bluetooth_disabled");
        initiator="connect".equals(command.optString("mode"));
        if(initiator){
            String address=command.getString("address");
            var device=adapter.getBondedDevices().stream().filter(d->d.getAddress().equals(address)).findFirst()
                .orElseThrow(()->new IllegalStateException("bluetooth_bond_required"));
            phase="connecting";
            bearer.connect(device).whenComplete((connected,failure)->{if(failure!=null){phase="failed";error="bluetooth_connect_failed";
                android.util.Log.w("YueJieNetwork","bluetooth_connect_failed "+failure.getClass().getSimpleName());}});
        }else{
            if(!"listen".equals(command.optString("mode")))throw new IllegalArgumentException("invalid_mode");
            phase="listening";
            bearer.listen().whenComplete((info,failure)->{if(failure!=null){phase="failed";error="bluetooth_listen_failed";listeningReady.completeExceptionally(failure);}
                else listeningReady.complete(null);});
        }
        return status();
    }
    JSONObject status()throws org.json.JSONException {return new JSONObject().put("status","ok").put("phase",phase).put("error",error);}
    boolean pending(){return "connecting".equals(phase)||"listening".equals(phase);}
    void awaitListening() throws Exception { listeningReady.get(5,java.util.concurrent.TimeUnit.SECONDS); }
    @Override public void close(){phase="stopped";bearer.close();}
}
