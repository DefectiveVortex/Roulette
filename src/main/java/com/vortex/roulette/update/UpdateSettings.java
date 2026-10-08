package com.vortex.roulette.update;

import org.bukkit.configuration.ConfigurationSection;

/** The {@code updates:} section of config.yml. */
public record UpdateSettings(boolean check, boolean autoDownload, Channel channel, int intervalHours, String apiUrl) {

    public static final String DEFAULT_API = "https://api.modrinth.com/v2";

    public static final UpdateSettings DEFAULT = new UpdateSettings(true, true, Channel.RELEASE, 12, DEFAULT_API);

    /** Missing or bad values fall back to the defaults. {@code api-url} is for testing against a mock. */
    public static UpdateSettings from(ConfigurationSection s) {
        if (s == null) return DEFAULT;
        Channel channel = Channel.parse(s.getString("channel"));
        String api = s.getString("api-url", "");
        return new UpdateSettings(
            s.getBoolean("check", DEFAULT.check),
            s.getBoolean("auto-download", DEFAULT.autoDownload),
            channel == null ? DEFAULT.channel : channel,
            Math.max(1, s.getInt("interval-hours", DEFAULT.intervalHours)),
            api == null || api.isBlank() ? DEFAULT_API : stripSlash(api.trim()));
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
