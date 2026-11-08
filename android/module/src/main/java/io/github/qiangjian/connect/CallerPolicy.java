package io.github.qiangjian.connect;

/** Pure, fail-closed policy, shared by hook setup and the Binder boundary. */
final class CallerPolicy {
    static final String XIAOMI = "com.milink.service";
    static final String OPLUS = "com.oplus.linker";
    static int source(String pkg, long version, String versionName, String cert, int packageCount) {
        // Shared UID is deliberately unsupported: a package name is not proof of a process identity.
        if (packageCount != 1) return 0;
        if (XIAOMI.equals(pkg) && version == 180020300L && "18.2.3.0.2610211842".equals(versionName)
                && "c9009d01ebf9f5d0302bc71b2fe9aa9a47a432bba17308a3111b75d7b2149025".equals(cert)) return 1;
        if (OPLUS.equals(pkg) && version == 170070030L && "17.7.30".equals(versionName)
                && "e49802409584ce53152a9000820a51e4fa8a723b7bcc263e335240acf100bf9e".equals(cert)) return 2;
        return 0;
    }
}
