package io.github.qiangjian.connect;

import java.util.Set;

/** Business operations that can back a logical channel; control operations cannot back themselves. */
final class NativeLogicalOperations {
    static final Set<String> BUSINESS = Set.of("clipboard_write", "clipboard_rich_write", "clipboard_uri_write",
            "file_prepare", "file_offer", "file_chunk", "file_commit", "file_cancel", "app_relay", "media_control");
    private NativeLogicalOperations() {}
    static boolean valid(Set<String> operations) {
        return operations != null && !operations.isEmpty() && BUSINESS.containsAll(operations);
    }
}
