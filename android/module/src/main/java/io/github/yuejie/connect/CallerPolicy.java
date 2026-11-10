package io.github.yuejie.connect;

/** Pure, fail-closed policy, shared by hook setup and the Binder boundary. */
final class CallerPolicy {
    static final String SYSTEM_UI = "com.android.systemui";
    static final String PHONE = "com.android.phone";
    static int phonePackage(long version, String name, String cert) {
        if (version == 37 && "17".equals(name) && "c9009d01ebf9f5d0302bc71b2fe9aa9a47a432bba17308a3111b75d7b2149025".equals(cert)) return 1;
        if (version == 17007005 && "17.7.5".equals(name) && "e49802409584ce53152a9000820a51e4fa8a723b7bcc263e335240acf100bf9e".equals(cert)) return 2;
        return 0;
    }
    static final String XIAOMI = "com.milink.service";
    static final String OPLUS = "com.oplus.linker";
    static final String MIRROR = "com.xiaomi.mirror";
    static final String OPLUS_FINDER = "com.coloros.findmyphone";
    static final String OPLUS_CAST = "com.oplus.cast";
    static final String OPLUS_PAIRING = "com.heytap.accessory";
    static boolean mirrorPackage(long version,String name,String cert){
        return version==180201L&&"18.02.01-26.09.10".equals(name)
                &&"c9009d01ebf9f5d0302bc71b2fe9aa9a47a432bba17308a3111b75d7b2149025".equals(cert);
    }
    static boolean executorAllowed(String pkg,String operation){
        if("notification_action".equals(operation))return SYSTEM_UI.equals(pkg);
        if("notification_post".equals(operation)||"notification_remove".equals(operation))return XIAOMI.equals(pkg)||OPLUS.equals(pkg);
        if (PHONE.equals(pkg)) return "telephony_call_state".equals(operation) || "telephony_call_control".equals(operation);
        if("network_share".equals(operation))return XIAOMI.equals(pkg)||OPLUS.equals(pkg);
        if("nfc_tap".equals(operation))return MIRROR.equals(pkg);
        if("screen_drag".equals(operation))return MIRROR.equals(pkg)||OPLUS.equals(pkg);
        if("screen_request".equals(operation))return OPLUS.equals(pkg);
        if("screen_input".equals(operation))return MIRROR.equals(pkg)||OPLUS_CAST.equals(pkg);
        if("screen_control".equals(operation))return MIRROR.equals(pkg)||OPLUS_CAST.equals(pkg);
        if(java.util.Set.of("telephony_call_state","telephony_call_control","telephony_sms_status").contains(operation))
            return MIRROR.equals(pkg)||OPLUS.equals(pkg);
        if("device_finder".equals(operation))return XIAOMI.equals(pkg)||OPLUS_FINDER.equals(pkg);
        if("clipboard_write".equals(operation)||"clipboard_rich_write".equals(operation)||"app_relay".equals(operation)||"logical_open".equals(operation)||"logical_close".equals(operation)||"logical_state".equals(operation)
                ||java.util.Set.of("file_prepare","file_offer","file_chunk","file_commit","file_cancel","clipboard_uri_write","media_control").contains(operation))
            return MIRROR.equals(pkg)||OPLUS.equals(pkg);
        return false;
    }
    static boolean moduleExecutorAllowed(String operation){
        return "notification_post".equals(operation)||"notification_remove".equals(operation)||"notification_action".equals(operation);
    }
    static int source(String pkg, long version, String versionName, String cert, int packageCount) {
        // Shared UID is deliberately unsupported: a package name is not proof of a process identity.
        if (packageCount != 1) return 0;
        if (XIAOMI.equals(pkg) && version == 180020300L && "18.2.3.0.2610211842".equals(versionName)
                && "c9009d01ebf9f5d0302bc71b2fe9aa9a47a432bba17308a3111b75d7b2149025".equals(cert)) return 1;
        if (OPLUS.equals(pkg) && version == 170070030L && "17.7.30".equals(versionName)
                && "e49802409584ce53152a9000820a51e4fa8a723b7bcc263e335240acf100bf9e".equals(cert)) return 2;
        if (OPLUS_FINDER.equals(pkg) && version==100008L && "10.0.8".equals(versionName)
                && "159e6f37c336f2f41272542dbc1dc85721dd567ed4ac68ac13f2e43003ce0dce".equals(cert)) return 2;
        if (OPLUS_CAST.equals(pkg) && version==170017L && "17.0.017".equals(versionName)
                && "e49802409584ce53152a9000820a51e4fa8a723b7bcc263e335240acf100bf9e".equals(cert)) return 2;
        if (OPLUS_PAIRING.equals(pkg) && version==170060016L && "17.6.16".equals(versionName)
                && "e49802409584ce53152a9000820a51e4fa8a723b7bcc263e335240acf100bf9e".equals(cert)) return 2;
        return 0;
    }
}
