package io.github.qiangjian.connect;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Set;

/** The OEM page may select a discovered LAN endpoint, never an arbitrary socket or grant. */
final class NativePairingPolicy {
    private static final Set<String> OPERATIONS=Set.of("device_profile","battery_status",
            "clipboard_write","clipboard_rich_write","file_transfer","app_relay");
    static boolean caller(String pkg){return CallerPolicy.XIAOMI.equals(pkg)||CallerPolicy.OPLUS.equals(pkg)||CallerPolicy.OPLUS_PAIRING.equals(pkg);}
    static boolean idle(String phase){return Set.of("stopped","disconnected","listening").contains(phase);}
    static boolean endpoint(InetAddress address,int port){
        return address instanceof Inet4Address&&port>0&&port<=65535
                &&(address.isSiteLocalAddress()||address.isLinkLocalAddress())&&!address.isLoopbackAddress();
    }
    static InetAddress lanAddress(java.util.List<InetAddress> addresses,int port){return addresses.stream().filter(address->endpoint(address,port)).findFirst().orElse(null);}
    static boolean grants(Set<String> operations){return operations.contains("device_profile")&&OPERATIONS.containsAll(operations);}
    static boolean session(String session){return session!=null&&session.matches("[0-9a-f]{64}");}
    static String alias(int source,String key){
        if((source!=1&&source!=2)||!session(key))throw new IllegalArgumentException("invalid_peer");
        try{var hash=java.security.MessageDigest.getInstance("SHA-256");
            hash.update("qiangjian/projection/v1\0".getBytes(java.nio.charset.StandardCharsets.UTF_8));hash.update((byte)source);
            for(int i=0;i<64;i+=2)hash.update((byte)Integer.parseInt(key.substring(i,i+2),16));
            return "qj:"+java.util.HexFormat.of().formatHex(hash.digest());
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
