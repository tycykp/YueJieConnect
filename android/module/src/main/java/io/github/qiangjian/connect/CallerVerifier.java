package io.github.qiangjian.connect;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class CallerVerifier {
    static int verify(PackageManager pm, int uid) {
        try {
            String[] packages = pm.getPackagesForUid(uid);
            if (packages == null || packages.length != 1) return 0;
            String pkg = packages[0];
            if (!CallerPolicy.XIAOMI.equals(pkg) && !CallerPolicy.OPLUS.equals(pkg)) return 0;
            PackageInfo p = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
            if (p.applicationInfo == null || p.applicationInfo.uid != uid || p.signingInfo == null) return 0;
            Signature[] signers = p.signingInfo.getApkContentsSigners();
            if (signers.length != 1) return 0;
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray());
            StringBuilder cert = new StringBuilder();
            for (byte b : hash) cert.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return CallerPolicy.source(pkg, p.getLongVersionCode(), p.versionName, cert.toString(), packages.length);
        } catch (PackageManager.NameNotFoundException | NoSuchAlgorithmException | SecurityException e) {
            return 0;
        }
    }
}
