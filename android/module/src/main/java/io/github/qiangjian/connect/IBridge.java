package io.github.qiangjian.connect;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/** Single-method Binder protocol, versioned descriptor; no vendor Binder transactions reused. */
interface IBridge extends IInterface {
    String transactJson(String command) throws RemoteException;
    abstract class Stub extends Binder implements IBridge {
        private static final String DESCRIPTOR="io.github.qiangjian.connect.IBridge.v1";
        private static final int CALL=IBinder.FIRST_CALL_TRANSACTION;
        Stub() { attachInterface(this,DESCRIPTOR); }
        static IBridge asInterface(IBinder binder) {
            IInterface local=binder.queryLocalInterface(DESCRIPTOR);
            if(local instanceof IBridge) return (IBridge)local;
            return new IBridge() {
                @Override public IBinder asBinder() { return binder; }
                @Override public String transactJson(String command) throws RemoteException {
                    Parcel data=Parcel.obtain(), reply=Parcel.obtain();
                    try {
                        data.writeInterfaceToken(DESCRIPTOR);data.writeString(command);
                        if(!binder.transact(CALL,data,reply,0)) throw new RemoteException("Unsupported bridge protocol");
                        reply.readException();return reply.readString();
                    } finally { reply.recycle();data.recycle(); }
                }
            };
        }
        @Override public IBinder asBinder() { return this; }
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) throws RemoteException {
            if(code==INTERFACE_TRANSACTION) { if(reply!=null) reply.writeString(DESCRIPTOR);return true; }
            if(code==CALL) {
                data.enforceInterface(DESCRIPTOR);
                if(data.dataAvail()>32768) throw new SecurityException("Oversized command");
                String command=data.readString();
                if(data.dataAvail()!=0 || reply==null) throw new SecurityException("Invalid transaction");
                String result=transactJson(command);reply.writeNoException();reply.writeString(result);return true;
            }
            return super.onTransact(code,data,reply,flags);
        }
    }
}
