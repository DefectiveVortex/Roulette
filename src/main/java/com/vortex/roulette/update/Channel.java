package com.vortex.roulette.update;

import java.util.Locale;

/** Which Modrinth version types an update may come from. Each channel also takes the steadier ones. */
public enum Channel {
    RELEASE, BETA, ALPHA;

    /** Whether a Modrinth {@code version_type} ("release", "beta", "alpha") is on this channel. */
    public boolean accepts(String versionType) {
        Channel type = parse(versionType);
        return type != null && type.ordinal() <= ordinal();
    }

    /** The channel named in config, or null if the name isn't one. */
    public static Channel parse(String name) {
        if (name == null) return null;
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
