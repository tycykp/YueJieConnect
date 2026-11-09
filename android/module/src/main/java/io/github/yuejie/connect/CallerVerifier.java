package io.github.yuejie.connect;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class CallerVerifier {
    static org.json.JSONObject inspect(PackageManager pm,String name) {
        org.json.JSONObject out=new org.json.JSONObject();
        try{
            PackageInfo p=pm.getPackageInfo(name,PackageManager.GET_SIGNING_CERTIFICATES);
            out.put("package",name).put("uid",p.applicationInfo.uid).put("version",p.getLongVersionCode()).put("version_name",p.versionName)
                .put("uid_packages",new org.json.JSONArray(pm.getPackagesForUid(p.applicationInfo.uid)));
            if(p.signingInfo!=null){Signature[] signers=p.signingInfo.getApkContentsSigners();out.put("signer_count",signers.length);
                if(signers.length==1)out.put("certificate",java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray())));}
            out.put("verified",verify(pm,p.applicationInfo.uid));
        }catch(Exception error){try{out.put("error",error.getClass().getSimpleName());}catch(org.json.JSONException ignored){}}
        return out;
    }
    record Verified(int source,String packageName) {}
    // Package validation alone does NOT authenticate shared-UID Binder callers. Use only for
    // our own injected process or with VendorProcessProof's OS-bound callback PID.
    static int mirrorUid(PackageManager pm){
        try{
            PackageInfo p=pm.getPackageInfo(CallerPolicy.MIRROR,PackageManager.GET_SIGNING_CERTIFICATES);
            if(p.applicationInfo==null||p.signingInfo==null)return -1;
            Signature[] signers=p.signingInfo.getApkContentsSigners();if(signers.length!=1)return -1;
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray());StringBuilder cert=new StringBuilder();
            for(byte value:hash)cert.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
            return CallerPolicy.mirrorPackage(p.getLongVersionCode(),p.versionName,cert.toString())?p.applicationInfo.uid:-1;
        }catch(PackageManager.NameNotFoundException|NoSuchAlgorithmException|SecurityException invalid){return -1;}
    }
    static int verify(PackageManager pm, int uid) {
        Verified caller=resolve(pm,uid);return caller==null?0:caller.source();
    }
    static Verified resolve(PackageManager pm, int uid) {
        try {
            String[] packages = pm.getPackagesForUid(uid);
            if (packages == null || packages.length != 1) return null;
            String pkg = packages[0];
            if (!CallerPolicy.XIAOMI.equals(pkg) && !CallerPolicy.OPLUS.equals(pkg)
                    && !CallerPolicy.OPLUS_FINDER.equals(pkg) && !CallerPolicy.OPLUS_CAST.equals(pkg) && !CallerPolicy.OPLUS_PAIRING.equals(pkg)) return null;
            PackageInfo p = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
            if (p.applicationInfo == null || p.applicationInfo.uid != uid || p.signingInfo == null) return null;
            Signature[] signers = p.signingInfo.getApkContentsSigners();
            if (signers.length != 1) return null;
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray());
            StringBuilder cert = new StringBuilder();
            for (byte b : hash) cert.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            int source=CallerPolicy.source(pkg,p.getLongVersionCode(),p.versionName,cert.toString(),packages.length);
            return source==0?null:new Verified(source,pkg);
        } catch (PackageManager.NameNotFoundException | NoSuchAlgorithmException | SecurityException e) {
            return null;
        }
    }
}
