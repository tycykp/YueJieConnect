package io.github.yuejie.connect;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Real platform SMS backend. No default-SIM fallback, sent broadcasts alone never mean delivered. */
final class TelephonySyncSmsBackend implements TelephonySyncSms.Backend {
    private final Context context;
    TelephonySyncSmsBackend(Context context) { this.context = context; }
    @Override public TelephonySyncSms.Prepared prepare(TelephonySyncSms.Command command) {
        enforce(command.subscription());
        SmsManager manager = context.getSystemService(SmsManager.class).createForSubscriptionId(command.subscription());
        ArrayList<String> parts = manager.divideMessage(command.body());
        return new Prepared(command, manager, parts);
    }
    private void enforce(int subscription) {
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("sms_permission_missing");
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("sms_permission_missing");
        SubscriptionManager subscriptions = context.getSystemService(SubscriptionManager.class);
        List<SubscriptionInfo> active = activeSubscriptions(subscriptions);
        if (active == null) throw new SecurityException("sms_subscription_access_unavailable");
        if (active.stream().noneMatch(s -> s.getSubscriptionId() == subscription))
            throw new IllegalArgumentException("subscription_not_active");
    }
    // This code executes in the injected OEM process, whose grants are unrelated to our APK manifest.
    // Keep the real host check and revocation catch here; the module itself never requests this grant.
    @SuppressLint("MissingPermission")
    private List<SubscriptionInfo> activeSubscriptions(SubscriptionManager subscriptions) {
        if (subscriptions == null || context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) return null;
        try { return subscriptions.getActiveSubscriptionInfoList(); }
        catch (SecurityException revoked) { return null; }
    }
    private final class Prepared implements TelephonySyncSms.Prepared {
        private final TelephonySyncSms.Command command;
        private final SmsManager manager;
        private final ArrayList<String> parts;
        private final ArrayList<PendingIntent> intents = new ArrayList<>();
        private BroadcastReceiver receiver;
        private boolean used;
        Prepared(TelephonySyncSms.Command c, SmsManager manager, ArrayList<String> parts) {
            this.command = c; this.manager = manager; this.parts = parts;
        }
        @Override public int parts() { return parts.size(); }
        @Override public void send(TelephonySyncSms.Completion callback) {
            if (used) throw new IllegalStateException("already_submitted");
            used = true; enforce(command.subscription());
            String token = UUID.randomUUID().toString();
            String action = context.getPackageName() + ".YJ_SMS_SENT." + token;
            receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent intent) {
                    if (!action.equals(intent.getAction())) return;
                    Uri data = intent.getData();
                    if (data == null || !token.equals(data.getHost())) return;
                    try { callback.sent(Integer.parseInt(data.getLastPathSegment()), getResultCode()); }
                    catch (NumberFormatException ignored) { }
                }
            };
            IntentFilter filter = new IntentFilter(action); filter.addDataScheme("yjsms");
            context.registerReceiver(receiver, filter, null, new Handler(Looper.getMainLooper()), Context.RECEIVER_NOT_EXPORTED);
            for (int i = 0; i < parts.size(); i++) {
                Intent intent = new Intent(action).setPackage(context.getPackageName()).setData(Uri.parse("yjsms://" + token + "/" + i));
                intents.add(PendingIntent.getBroadcast(context, i, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT));
            }
            manager.sendMultipartTextMessage(command.destination(), null, parts, intents, null);
        }
        @Override public void dispose() {
            if (receiver != null) {
                try { context.unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) { }
                receiver = null;
            }
            for (PendingIntent intent : intents) intent.cancel();
            intents.clear();
        }
    }
}
