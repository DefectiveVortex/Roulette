package com.vortex.roulette.table;

import com.vortex.roulette.RoulettePlugin;
import com.vortex.roulette.config.ConfigManager;
import com.vortex.roulette.game.Round;
import com.vortex.roulette.game.RoundListener;
import com.vortex.roulette.game.RoundPhase;
import com.vortex.roulette.game.RoundResult;
import com.vortex.roulette.game.TableRules;
import com.vortex.roulette.model.BetSpot;
import com.vortex.roulette.model.WheelType;
import com.vortex.roulette.model.layout.FeltFrame;
import com.vortex.roulette.model.layout.FeltFrame.Position;
import com.vortex.roulette.model.layout.FeltLayout;
import com.vortex.roulette.model.layout.FeltLayout.Placed;
import com.vortex.roulette.table.TableShape.Seat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;

/**
 * One table while the server runs: who sits where, its round, and what is shown on it. The blocks are placed and
 * removed by {@link TableManager}; everything else about a table lives here.
 */
public final class RouletteTable {
    /** Scoreboard tag of the invisible stands players sit on. */
    public static final String SEAT_TAG = "roulette-seat";
    /** A seated player's hips are this far above their feet; a marker stand carries its rider 0.6 below itself. */
    private static final double RIDER_HIP_HEIGHT = 0.6;
    private static final double STAND_ABOVE_RIDER_FEET = 0.6;

    public enum SitResult { OK, FULL, SEAT_TAKEN, NO_ECONOMY, WORLD_MISSING, NOT_ALLOWED }

    /** Why a player is leaving; decides how gently their seat can be taken apart. */
    public enum Leave { STOOD_UP, GONE, TABLE_CLOSED }

    private final RoulettePlugin plugin;
    private final TableManager manager;
    private TableDef def;
    private TableShape.Placement placement;
    private FeltLayout layout;
    private FeltFrame aimFrame;

    private Round round;
    private FeltView feltView;
    private final List<TableAttachment> attachments = new ArrayList<>();
    private final Map<UUID, Integer> seatOf = new LinkedHashMap<>();
    private final ArmorStand[] stands = new ArmorStand[TableShape.seats().size()];
    private BossBar bar;
    private boolean shown;

    RouletteTable(RoulettePlugin plugin, TableManager manager, TableDef def) {
        this.plugin = plugin;
        this.manager = manager;
        apply(def);
    }

    private void apply(TableDef newDef) {
        def = newDef;
        placement = newDef.placement();
        layout = FeltLayout.of(newDef.rules().wheel());
        aimFrame = placement.felt(0);
        feltView = new FeltView(plugin, this, manager.chips());
        attachments.clear();
        attachments.add(feltView);
        for (TableAttachmentFactory factory : manager.attachmentFactories()) {
            try {
                attachments.add(factory.create(this));
            } catch (RuntimeException e) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Table '" + id() + "': an attachment failed to start", e);
            }
        }
    }

    /** A factory registered after this table was built. Tables are not shown yet at that point. */
    void attach(TableAttachmentFactory factory) {
        TableAttachment attachment = factory.create(this);
        attachments.add(attachment);
        if (round != null) {
            round.addListener(attachment);
        }
        if (shown) {
            guarded(attachment::show);
        }
    }

    // ------------------------------------------------------------------ what a table is

    public String id() {
        return def.id();
    }

    public TableDef def() {
        return def;
    }

    public TableRules rules() {
        return def.rules();
    }

    public WheelType wheel() {
        return def.rules().wheel();
    }

    /** Null while the table's world is not loaded. */
    public World world() {
        return Bukkit.getWorld(def.world());
    }

    public TableShape.Placement placement() {
        return placement;
    }

    public FeltLayout layout() {
        return layout;
    }

    /** Centre of the 3x3 wheel canvas, on the top face of the table blocks. Null while the world is not loaded. */
    public Location wheelCentre() {
        World world = world();
        if (world == null) {
            return null;
        }
        Position p = placement.wheelCentre(0);
        return new Location(world, p.x(), p.y(), p.z(), displayYaw(), 0f);
    }

    /** The direction the layout runs in, from the wheel towards the column bets: texture +x of every flat model. */
    public BlockFace uFacing() {
        return def.facing();
    }

    /** Entity yaw that turns a flat model's texture +x onto {@link #uFacing()}. */
    public float displayYaw() {
        return placement.displayYaw();
    }

    /** Everyone seated here who is online. */
    public List<Player> players() {
        List<Player> players = new ArrayList<>();
        for (UUID id : seatOf.keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                players.add(player);
            }
        }
        return players;
    }

    public boolean isSeated(UUID player) {
        return seatOf.containsKey(player);
    }

    public int seatedCount() {
        return seatOf.size();
    }

    /** The round machine, made on first use. Null while no economy is installed. */
    public Round round() {
        if (round == null && plugin.bank() != null) {
            round = plugin.newRound(id(), def.rules());
            round.addListener(announcer);
            attachments.forEach(round::addListener);
        }
        return round;
    }

    Round roundIfAny() {
        return round;
    }

    public RoundPhase phase() {
        return round == null ? RoundPhase.IDLE : round.phase();
    }

    /** The bet spot this player is looking at, if they look at the felt. */
    public Optional<Placed> aimOf(Player player) {
        Location eye = player.getEyeLocation();
        if (eye.getWorld() != world()) {
            return Optional.empty();
        }
        org.bukkit.util.Vector d = eye.getDirection();
        return aimFrame.hit(eye.getX(), eye.getY(), eye.getZ(), d.getX(), d.getY(), d.getZ(), manager.maxAimDistance())
                .flatMap(layout::spotAt);
    }

    /** Moves this player's own highlight; null hides it. */
    public void showAim(Player player, Placed target) {
        feltView.aim(player, target);
    }

    // ------------------------------------------------------------------ sitting down and leaving

    public int freeSeat(int preferred) {
        if (preferred >= 0 && preferred < stands.length && !seatOf.containsValue(preferred)) {
            return preferred;
        }
        for (int seat = 0; seat < stands.length; seat++) {
            if (!seatOf.containsValue(seat)) {
                return seat;
            }
        }
        return -1;
    }

    /** The free stool closest to where the player stands, or -1. */
    public int nearestFreeSeat(Location from) {
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (Seat seat : TableShape.seats()) {
            if (seatOf.containsValue(seat.index())) {
                continue;
            }
            Position p = placement.seat(seat);
            double dx = p.x() - from.getX();
            double dz = p.z() - from.getZ();
            if (dx * dx + dz * dz < bestDistance) {
                bestDistance = dx * dx + dz * dz;
                best = seat.index();
            }
        }
        return best;
    }

    /** Seats the player, on {@code preferredSeat} if that stool is free and otherwise on the first free one. */
    public SitResult sit(Player player, int preferredSeat) {
        World world = world();
        if (world == null) {
            return SitResult.WORLD_MISSING;
        }
        if (player.getGameMode() == GameMode.SPECTATOR || player.isDead()) {
            return SitResult.NOT_ALLOWED;
        }
        if (round() == null) {
            return SitResult.NO_ECONOMY;
        }
        int seatIndex = freeSeat(preferredSeat);
        if (seatIndex < 0) {
            return SitResult.FULL;
        }
        Seat seat = TableShape.seats().get(seatIndex);
        Position sit = placement.seat(seat);
        Location at = new Location(world, sit.x(), sit.y(), sit.z(), placement.seatYaw(seat), 25f);

        player.leaveVehicle();
        player.teleport(at);
        ArmorStand stand = world.spawn(
                at.clone().add(0, STAND_ABOVE_RIDER_FEET - RIDER_HIP_HEIGHT, 0), ArmorStand.class, s -> {
                    s.setMarker(true);
                    s.setVisible(false);
                    s.setGravity(false);
                    s.setInvulnerable(true);
                    s.setSilent(true);
                    s.setBasePlate(false);
                    s.setPersistent(false);
                    s.addScoreboardTag(SEAT_TAG);
                    s.addScoreboardTag(TableAttachment.ENTITY_TAG);
                });
        if (!stand.addPassenger(player)) {
            stand.remove();
            return SitResult.NOT_ALLOWED;
        }
        stands[seatIndex] = stand;
        seatOf.put(player.getUniqueId(), seatIndex);
        manager.seated(player.getUniqueId(), this);

        show();
        bar.addPlayer(player);
        manager.chips().give(player);
        plugin.pack().offerAtTable(player);
        for (String line : plugin.config().messageList("table-sit-hint")) {
            player.sendMessage(line);
        }
        return SitResult.OK;
    }

    /** The player is no longer at the table. Chips still on the felt while betting is open are refunded. */
    public void leave(Player player, Leave why) {
        UUID id = player.getUniqueId();
        Integer seatIndex = seatOf.remove(id);
        if (seatIndex == null) {
            return;
        }
        manager.unseated(id);

        if (round != null && round.phase() == RoundPhase.BETTING) {
            long stake = round.stakeOf(id);
            round.leave(id);
            if (stake > 0) {
                send(player, "round-refunded", "amount", plugin.config().money(stake));
            }
        }

        ArmorStand stand = stands[seatIndex];
        stands[seatIndex] = null;
        if (stand != null) {
            if (why == Leave.STOOD_UP && plugin.isEnabled()) {
                // the player is being taken off the stand right now; remove it once that has finished
                Bukkit.getScheduler().runTask(plugin, stand::remove);
            } else {
                stand.eject();
                stand.remove();
            }
        }
        manager.chips().take(player);
        manager.menu().close(player);
        feltView.forget(id);
        if (bar != null) {
            bar.removePlayer(player);
        }
        if (why != Leave.TABLE_CLOSED) {
            send(player, "table-left");
            hideIfDone();
        }
    }

    private void show() {
        if (shown) {
            return;
        }
        shown = true;
        bar = Bukkit.createBossBar("", BarColor.WHITE, BarStyle.SOLID);
        attachments.forEach(a -> guarded(a::show));
        updateBar();
    }

    private void hide() {
        if (!shown) {
            return;
        }
        shown = false;
        attachments.forEach(a -> guarded(a::hide));
        if (bar != null) {
            bar.removeAll();
            bar = null;
        }
    }

    /** An empty table keeps its chips and wheel until bets that still ride on it are settled. */
    private void hideIfDone() {
        if (seatOf.isEmpty() && phase() == RoundPhase.IDLE) {
            hide();
        }
    }

    /** Sends everyone away, refunds the round and removes everything shown. For removal, a rule change and disable. */
    void close() {
        for (Player player : players()) {
            leave(player, Leave.TABLE_CLOSED);
        }
        // what is still staked now belongs to a spin in progress or to players who already left
        Map<UUID, Long> refunds = new LinkedHashMap<>();
        if (round != null && (round.phase() == RoundPhase.BETTING || round.phase() == RoundPhase.SPINNING)) {
            for (UUID bettor : round.bettors()) {
                refunds.put(bettor, round.stakeOf(bettor));
            }
        }
        seatOf.clear();
        if (round != null) {
            plugin.discardRound(round);
            round.removeListener(announcer);
            attachments.forEach(round::removeListener);
            round = null;
        }
        refunds.forEach((bettor, amount) -> {
            Player player = Bukkit.getPlayer(bettor);
            if (player != null && amount > 0) {
                send(player, "round-refunded", "amount", plugin.config().money(amount));
            }
        });
        hide();
        for (int i = 0; i < stands.length; i++) {
            if (stands[i] != null) {
                stands[i].remove();
                stands[i] = null;
            }
        }
    }

    /** New rules (or a new wheel). Only between rounds; the caller checks {@link #phase()}. */
    void reconfigure(TableDef newDef) {
        List<Player> seated = players();
        Map<UUID, Integer> seats = new LinkedHashMap<>(seatOf);
        close();
        apply(newDef);
        for (Player player : seated) {
            sit(player, seats.getOrDefault(player.getUniqueId(), -1));
        }
    }

    // ------------------------------------------------------------------ the countdown bar

    /** Called a few times a second while the table is shown. */
    void tick() {
        if (shown) {
            updateBar();
        }
    }

    private void updateBar() {
        if (bar == null) {
            return;
        }
        ConfigManager config = plugin.config();
        TableRules rules = def.rules();
        switch (phase()) {
            case BETTING -> {
                long left = round.ticksLeft();
                bar.setTitle(config.message("hud-betting", "seconds", (left + 19) / 20));
                bar.setColor(left <= 100 ? BarColor.YELLOW : BarColor.GREEN);
                bar.setProgress(fraction(left, rules.bettingTicks()));
            }
            case SPINNING -> {
                bar.setTitle(config.message("hud-spinning"));
                bar.setColor(BarColor.RED);
                bar.setProgress(fraction(round.ticksLeft(), rules.spinTicks()));
            }
            case RESULT -> {
                bar.setTitle(config.message("hud-result", "number",
                        round.result() == null ? "" : config.number(round.result())));
                bar.setColor(BarColor.PURPLE);
                bar.setProgress(1);
            }
            default -> {
                bar.setTitle(config.message("hud-idle", "min", config.money(rules.minBet()),
                        "max", config.money(rules.maxBet())));
                bar.setColor(BarColor.WHITE);
                bar.setProgress(1);
            }
        }
    }

    private static double fraction(long left, long of) {
        return of <= 0 ? 0 : Math.max(0, Math.min(1, (double) left / of));
    }

    // ------------------------------------------------------------------ what the round says

    private final RoundListener announcer = new RoundListener() {
        @Override
        public void bettingOpened(Round r, int countdownTicks) {
            tellSeated("round-betting-open", "seconds", countdownTicks / 20);
            updateBar();
        }

        @Override
        public void betPlaced(Round r, UUID player, BetSpot spot, long amount, long playerOnSpot) {
            manager.menu().refresh(RouletteTable.this, player);
        }

        @Override
        public void betRemoved(Round r, UUID player, BetSpot spot, long amount, long playerOnSpot) {
            manager.menu().refresh(RouletteTable.this, player);
        }

        @Override
        public void betsClosed(Round r) {
            tellSeated("round-bets-closed");
            updateBar();
        }

        @Override
        public void resultSettled(Round r, RoundResult result) {
            ConfigManager config = plugin.config();
            tellSeated("round-result", "number", config.number(result.pocket()));
            for (RoundResult.PlayerResult mine : result.players()) {
                Player player = Bukkit.getPlayer(mine.player());
                if (player == null) {
                    continue;
                }
                if (!seatOf.containsKey(mine.player())) {
                    send(player, "round-result", "number", config.number(result.pocket()));
                }
                if (mine.net() > 0) {
                    send(player, "round-won", "amount", config.money(mine.net()));
                } else if (mine.payout() > 0) {
                    send(player, "round-partial", "amount", config.money(mine.payout()));
                } else {
                    send(player, "round-lost");
                }
            }
            updateBar();
        }

        @Override
        public void cleared(Round r) {
            manager.menu().refreshAll(RouletteTable.this);
            updateBar();
            hideIfDone();
        }
    };

    private void tellSeated(String key, Object... args) {
        String message = plugin.config().prefixed(key, args);
        if (!message.isEmpty()) {
            players().forEach(p -> p.sendMessage(message));
        }
    }

    private void send(Player player, String key, Object... args) {
        String message = plugin.config().prefixed(key, args);
        if (!message.isEmpty()) {
            player.sendMessage(message);
        }
    }

    private void guarded(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Table '" + id() + "': a view failed", e);
        }
    }
}
