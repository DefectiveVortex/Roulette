package com.vortex.roulette.stats;

import com.vortex.roulette.RoulettePlugin;
import com.vortex.roulette.model.Pocket;
import java.util.List;
import java.util.Locale;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/**
 * PlaceholderAPI placeholders, all read from memory. Only load this class when PlaceholderAPI is installed: use
 * {@link Placeholders#hook}.
 *
 * <pre>
 * %roulette_rounds% %roulette_wins% %roulette_win_rate%         the player's rounds, winning rounds, percent
 * %roulette_wagered% %roulette_won% %roulette_net% %roulette_biggest_win%   plain numbers
 * ..._formatted on any of the four money ones                   as money ("$1,250")
 * %roulette_top_&lt;wagered|won|net|biggest|rounds&gt;_&lt;1-10&gt;_name%   and ..._value%, ..._value_formatted%
 * %roulette_last_number% %roulette_hot_number% %roulette_spins% over every table
 * %roulette_hits_&lt;number&gt;%                                     how often 0-36 or 00 came up
 * </pre>
 */
public final class RouletteExpansion extends PlaceholderExpansion {
    private final RoulettePlugin plugin;
    private final StatsManager stats;

    RouletteExpansion(RoulettePlugin plugin, StatsManager stats) {
        this.plugin = plugin;
        this.stats = stats;
    }

    @Override
    public String getIdentifier() {
        return "roulette";
    }

    @Override
    public String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    /** Stay registered through /papi reload: the expansion lives as long as the plugin. */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String key = params.toLowerCase(Locale.ROOT);
        boolean formatted = key.endsWith("_formatted");
        if (formatted) {
            key = key.substring(0, key.length() - "_formatted".length());
        }
        switch (key) {
            case "spins":
                return Long.toString(stats.spins());
            case "last_number":
                Pocket last = stats.lastNumber();
                return last == null ? "" : last.label();
            case "hot_number":
                String hot = stats.hotNumber();
                return hot == null ? "" : hot;
            default:
                break;
        }
        if (key.startsWith("hits_")) {
            return Long.toString(stats.hits(key.substring("hits_".length())));
        }
        if (key.startsWith("top_")) {
            return top(key.substring("top_".length()), formatted);
        }
        PlayerStats own = player == null ? null : stats.stats(player.getUniqueId());
        Long value = switch (key) {
            case "rounds" -> own == null ? 0 : own.rounds();
            case "wins" -> own == null ? 0 : own.wins();
            case "win_rate" -> own == null ? 0 : own.winRatePercent();
            case "wagered" -> own == null ? 0 : own.wagered();
            case "won" -> own == null ? 0 : own.paid();
            case "net" -> own == null ? 0 : own.net();
            case "biggest_win" -> own == null ? 0 : own.biggestWin();
            default -> null;
        };
        if (value == null) {
            return null; // not ours: PlaceholderAPI leaves the text as it is
        }
        boolean money = !List.of("rounds", "wins", "win_rate").contains(key);
        return formatted && money ? money(value) : Long.toString(value);
    }

    /** {@code <ranking>_<place>_<name|value>} */
    private String top(String rest, boolean formatted) {
        String[] parts = rest.split("_");
        if (parts.length != 3) {
            return null;
        }
        StatsManager.Ranking ranking = StatsManager.Ranking.parse(parts[0]);
        int place;
        try {
            place = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (ranking == null || place < 1 || place > 10) {
            return null;
        }
        List<PlayerStats> top = stats.top(ranking, place);
        PlayerStats entry = top.size() < place ? null : top.get(place - 1);
        return switch (parts[2]) {
            case "name" -> entry == null || entry.name() == null ? "" : entry.name();
            case "value" -> entry == null ? ""
                    : formatted && ranking.isMoney() ? money(ranking.of(entry)) : Long.toString(ranking.of(entry));
            default -> null;
        };
    }

    private String money(long amount) {
        return (amount < 0 ? "-" : "") + plugin.config().money(Math.abs(amount));
    }
}
