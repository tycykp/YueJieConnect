package io.github.yuejie.connect;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Reuses the OEM audio volume widget in PhoneCard; the stock phone layout has no volume row. */
final class XiaomiPhoneVolumeCard implements AutoCloseable {
    private final BridgeClient client;
    private final BooleanSupplier allowed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<Entry> entries = new ArrayList<>();
    private final ArrayList<java.lang.ref.WeakReference<View>> cards = new ArrayList<>();
    private final HookHandle hook;
    private final BridgeClient.Subscription subscription;
    private final Class<?> volumeType;
    private final Method info, cardHeight, setCardHeight;
    private final int containerId;
    private final int itemMarginId, itemSpacingId;
    private boolean closed;

    XiaomiPhoneVolumeCard(XposedModule module, BridgeClient client, ClassLoader loader, BooleanSupplier allowed)
            throws ReflectiveOperationException {
        this.client = client; this.allowed = allowed;
        Class<?> card = Class.forName("com.miui.circulate.world.sticker.blecard.PhoneCard", false, loader);
        volumeType = Class.forName("com.miui.circulate.world.view.BaseAudioVolumeBarView", false, loader);
        info = card.getMethod("getMInfo");
        containerId = Class.forName("com.miui.circulate.world.R$id", false, loader).getField("container").getInt(null);
        Class<?> dimensions = Class.forName("com.miui.circulate.world.R$dimen", false, loader);
        itemMarginId = dimensions.getField("circulate_device_item_margin").getInt(null);
        itemSpacingId = dimensions.getField("circulate_device_item_margin_top").getInt(null);
        cardHeight = card.getMethod("getCardH"); setCardHeight = card.getMethod("setCardH", int.class);
        hook = module.hook(card.getMethod("J2")).setId("yj.phone.card.volume").intercept(chain -> {
            Object result = chain.proceed();
            View view = (View) chain.getThisObject();
            cards.removeIf(reference -> reference.get() == null || reference.get() == view);
            if (cards.size() < 16) cards.add(new java.lang.ref.WeakReference<>(view));
            install(view);
            return result;
        });
        subscription = client.addProjectionListener(p -> main.post(this::refresh));
    }
    private boolean current(String id, String session) {
        JSONObject p = client.projection();
        if (closed || !allowed.getAsBoolean() || p == null || !id.equals(p.optString("id"))
                || !session.equals(p.optString("session_id"))) return false;
        var ops = p.optJSONArray("operations");
        if (ops != null) for (int i = 0; i < ops.length(); i++)
            if (MediaControlTasks.OPERATION.equals(ops.optString(i))) return true;
        return false;
    }
    private void install(View card) {
        try {
            JSONObject p = client.projection();
            Object device = info.invoke(card);
            String id = (String) device.getClass().getField("id").get(device);
            if (p == null || !current(id, p.optString("session_id"))) return;
            for (Entry entry : entries) if (entry.card == card) return;
            if (entries.size() >= 16) return;
            Entry entry = new Entry(card, id, p.getString("session_id"));
            entries.add(entry);
            entry.poll();
        } catch (Exception error) {
            android.util.Log.w("YueJieMedia", "phone_volume_card_unavailable", error);
        }
    }
    private void refresh() {
        for (Entry entry : new ArrayList<>(entries)) if (!entry.valid()) entry.close();
        cards.removeIf(reference -> reference.get() == null);
        for (var reference : cards) { View card = reference.get(); if (card != null && card.isAttachedToWindow()) install(card); }
    }
    private final class Entry implements View.OnAttachStateChangeListener {
        final View card, row;
        final LinearLayout parent;
        final SeekBar slider;
        final TextView number;
        final String id, session;
        final MediaControlNativeRoute route;
        final Runnable poll = this::poll;
        boolean touching, busy, disposed;
        int height;
        Entry(View card, String id, String session) throws Exception {
            this.card = card; this.id = id; this.session = session;
            parent = (LinearLayout) card.findViewById(containerId);
            row = (View) volumeType.getConstructor(Context.class).newInstance(card.getContext());
            slider = (SeekBar) volumeType.getMethod("getVolumeRowSlider").invoke(row);
            number = (TextView) volumeType.getMethod("getVolumeNum").invoke(row);
            LinearLayout title = (LinearLayout) volumeType.getMethod("getVolumeBarTitleContainer").invoke(row);
            if (title.getChildAt(0) instanceof TextView label) label.setText("手机音量");
            slider.setContentDescription("手机音量"); slider.setEnabled(false); number.setText("—");
            route = new MediaControlNativeRoute(new MediaControlBridgeTransport(client), SystemClock::elapsedRealtime);
            route.bind(new MediaControlNativeRoute.Scope(id, session, id, 0, 0), this, "", this::valid);
            // SynergyView owns its horizontal inset internally. This widget does not:
            // its OEM speaker layout supplies the inset on the outer layout params.
            // Use PhoneCard's resource values so both rows share the same edges.
            int margin = card.getResources().getDimensionPixelSize(itemMarginId);
            var params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMarginStart(margin);
            params.setMarginEnd(margin);
            params.topMargin = card.getResources().getDimensionPixelSize(itemSpacingId);
            View volumeContainer = (View) volumeType.getMethod("getVolumeBarContainer").invoke(row);
            // The OEM widget has a resource-defined height. J2 can run before the
            // parent is measured, so measuring against parent.getWidth() is unsafe.
            height = volumeContainer.getLayoutParams().height + params.topMargin;
            if (height <= params.topMargin) throw new IllegalStateException("native_volume_height_unavailable");
            parent.addView(row, Math.max(0, parent.getChildCount() - 1), params);
            // PhoneCard uses explicit height animations in portrait. Include the native row once.
            setCardHeight.invoke(card, (int) cardHeight.invoke(card) + height);
            card.addOnAttachStateChangeListener(this);
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar bar, int value, boolean user) {
                    if (user) number.setText(Integer.toString(value));
                }
                public void onStartTrackingTouch(SeekBar bar) { touching = true; route.cancelQuery(); }
                public void onStopTrackingTouch(SeekBar bar) {
                    touching = false;
                    if (!valid()) { close(); return; }
                    busy = true; slider.setEnabled(false);
                    route.volumeAbsolute(Entry.this, bar.getProgress()).thenAccept(result -> main.post(() -> {
                        busy = false; apply(result);
                    }));
                }
            });
        }
        boolean valid() { return !disposed && current(id, session); }
        void poll() {
            main.removeCallbacks(poll);
            if (!valid()) { close(); return; }
            if (card.isAttachedToWindow() && !touching && !busy) {
                busy = true;
                route.volumeQuery(this).thenAccept(result -> main.post(() -> { busy = false; apply(result); }));
            }
            main.postDelayed(poll, 2000);
        }
        void apply(MediaControlSession.Result result) {
            if (!valid()) { close(); return; }
            if (touching) return;
            if (!result.confirmed() || result.state() == null) {
                slider.setEnabled(false); number.setText("暂不可用"); return;
            }
            var state = result.state();
            slider.setMin(state.minVolume()); slider.setMax(state.maxVolume()); slider.setProgress(state.volume());
            number.setText(Integer.toString(state.volume())); slider.setEnabled(state.maxVolume() > state.minVolume());
        }
        public void onViewAttachedToWindow(View view) { poll(); }
        public void onViewDetachedFromWindow(View view) { close(); }
        void close() {
            if (disposed) return;
            disposed = true; route.close(); main.removeCallbacks(poll);
            card.removeOnAttachStateChangeListener(this); parent.removeView(row); entries.remove(this);
            try { setCardHeight.invoke(card, Math.max(0, (int) cardHeight.invoke(card) - height)); }
            catch (ReflectiveOperationException ignored) { }
        }
    }
    public void close() {
        closed = true; hook.unhook(); subscription.close(); cards.clear();
        main.post(() -> { for (Entry entry : new ArrayList<>(entries)) entry.close(); });
    }
}
