package io.github.yuejie.connect;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.util.Log;
import io.github.libxposed.api.XposedModule;
import java.util.HexFormat;
import java.util.List;

/** Lets the native dispatcher open the matching activity directly on ColorOS. */
final class OplusNfcTap {
    static final String PACKAGE = "com.android.nfc";
    private static final String HANDLER_PACKAGE = "io.github.yuejie.connect";
    private static final byte[] SIGNER = HexFormat.of().parseHex(
            "e49802409584ce53152a9000820a51e4fa8a723b7bcc263e335240acf100bf9e");

    static void install(XposedModule module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> manager = Class.forName("com.oplus.nfc.dispatch.OplusNotificationManager", false, loader);
        var method = manager.getDeclaredMethod("showNotificationIfNeeded", List.class, String.class, Uri.class, Intent.class);
        module.hook(Application.class.getDeclaredMethod("attach", Context.class)).setId("yj.nfc.context").intercept(chain -> {
            Object result = chain.proceed();
            Context context = (Context) chain.getArg(0);
            if (PACKAGE.equals(context.getPackageName()) && verified(context.getPackageManager())) {
                module.hook(method).setId("yj.nfc.dispatch").intercept(dispatch -> {
                    if (!matches((List<?>) dispatch.getArg(0), (Intent) dispatch.getArg(3))) return dispatch.proceed();
                    Log.i(NfcTapActivity.LOG, "coloros_direct_dispatch");
                    return false;
                });
                Log.i(NfcTapActivity.LOG, "coloros_dispatch_ready");
            }
            return result;
        });
    }

    private static boolean verified(PackageManager manager) {
        try {
            return manager.getPackageInfo(PACKAGE, 0).getLongVersionCode() == 170000014L
                    && manager.hasSigningCertificate(PACKAGE, SIGNER, PackageManager.CERT_INPUT_SHA256);
        } catch (PackageManager.NameNotFoundException | SecurityException unavailable) { return false; }
    }

    private static boolean matches(List<?> handlers, Intent intent) {
        if (handlers == null || handlers.size() != 1 || !(handlers.get(0) instanceof ResolveInfo handler)
                || handler.activityInfo == null || !HANDLER_PACKAGE.equals(handler.activityInfo.packageName)
                || !NfcTapActivity.class.getName().equals(handler.activityInfo.name)
                || intent == null || !NfcAdapter.ACTION_NDEF_DISCOVERED.equals(intent.getAction())) return false;
        try { return XiaomiPadNfcTag.target(intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag.class)) != null; }
        catch (RuntimeException invalid) { return false; }
    }
}
