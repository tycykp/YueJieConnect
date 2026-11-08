package io.github.qiangjian.connect;

import android.content.Context;
import android.os.Looper;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Debug-only, caller-controlled OEM execution probe. No exported receiver or automatic launch. */
final class AppRelayNativeProbe {
    // Run only in the real OEM process on a worker, inside the coordinated device test window.
    // This does NOT prove native source selection, transport or native source completion.
    static JSONObject runSyntheticBrowser(Context context, ClassLoader loader, int brand,
                                           BooleanSupplier authenticatedAndAuthorized) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("relay_worker_required");
        if (!authenticatedAndAuthorized.getAsBoolean()) throw new SecurityException("permission_denied");
        if (brand != 1 && brand != 2) throw new IllegalArgumentException("invalid_brand");
        AppRelayEndpoint endpoint = brand == 1 ? new XiaomiRelayEndpoint(context, loader, null) : new OplusRelayEndpoint(context, loader, null);
        endpoint.dispatchAllowed = authenticatedAndAuthorized;
        AppRelayTasks tasks = new AppRelayTasks(endpoint, authenticatedAndAuthorized);
        String id = "probe-" + UUID.randomUUID();
        AppRelayRequest request = new AppRelayRequest(id, context.getPackageName(), AppRelayRequest.Kind.BROWSER,
                List.of(), "https://example.invalid/qj-app-relay-synthetic", "", "", "", 0, 0, "");
        try {
            AppRelayTasks.Result result = tasks.prepare(request);
            if (result.state() == AppRelayTasks.State.PREPARED) result = tasks.launch(id);
            while (result.state() == AppRelayTasks.State.LAUNCHING) {
                Thread.sleep(250); result = tasks.status(id);
            }
            return AppRelayCodec.encode(result).put("probe_scope", "oem_executor_only")
                    .put("native_end_to_end", false).put("synthetic", true);
        } finally { tasks.close(); }
    }
    private AppRelayNativeProbe() {}
}
