package io.github.qiangjian.connect;

/** DEX-verified DeviceProfileManagerProxy/Service payload, without the shared-channel prefix. */
final class XiaomiDeviceProfileCodec {
    private XiaomiDeviceProfileCodec() {}
    static boolean accepts(byte[] request) {
        return request!=null&&request.length==2&&request[0]==0&&(request[1]==0||request[1]==(byte)0x80);
    }
    static byte[] response(byte[] request,int level,int plugged) {
        if(!accepts(request)||level<0||level>100||
            (plugged!=0&&plugged!=1&&plugged!=2&&plugged!=4&&plugged!=8))
            throw new IllegalArgumentException("Unsupported device profile payload");
        byte[] result=new byte[request[1]==0?2:10];
        result[0]=1;result[1]=request[1];
        if(result.length==10){result[2]=(byte)level;result[3]=(byte)plugged;}
        return result;
    }
}
