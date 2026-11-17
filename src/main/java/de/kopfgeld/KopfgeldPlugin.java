package de.kopfgeld;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Kopfgeld 1.1 - jeder bekommt ein Ziel, 15 Minuten Zeit, sonst Tod + Bann.
 * Neu: /kopfgeld Menue, Skip-Befehle, Gewinner-Show.
 */
public class KopfgeldPlugin extends JavaPlugin implements Listener {

    private enum Phase { IDLE, COUNTDOWN, WAITING, DRAW, HUNT, EVAL, ENDED }

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String PREFIX =
            "<gradient:#ff0000:#ffaa00><bold>KOPFGELD</bold></gradient> <dark_gray>» <gray>";

    private Phase phase = Phase.IDLE;
    private int gen = 0; // wird bei Reset erhoeht, alte Tasks brechen dann ab

    private final Set<UUID> alive = new LinkedHashSet<>();
    private final Map<UUID, UUID> targets = new LinkedHashMap<>(); // Jaeger -> Ziel
    private final Set<UUID> done = new HashSet<>();   // Jaeger, die "sicher" sind
    private final Set<UUID> doomed = new HashSet<>(); // werden gerade hingerichtet
    private final Set<UUID> out = new HashSet<>();    // ausgeschieden (Zuschauer)
    private final Set<UUID> escaping = new HashSet<>(); // haben ihr Ziel gekillt und muessen jetzt entkommen
    private int beatTick = 0;

    private BossBar bar;
    private BossBar showBar;
    private BossBar escapeBar;
    private BukkitTask ticker;
    private BukkitTask compassTask;
    private BukkitTask countdownTask;
    private BukkitTask introTask;
    private BukkitTask pendingDraw;
    private BukkitTask evalTask;
    private BukkitTask showTask;
    private final List<Roulette> roulettes = new ArrayList<>();
    private boolean inRoulette = false;
    private boolean paused = false;
    private int round = 0;
    private int remaining = 0;
    private int total = 1;
    private int pendingRoulettes = 0;

    private World showWorld;
    private long savedTime;
    private boolean savedStorm;
    private boolean savedThunder;

    // ------------------------------------------------------------------ Lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        bar = BossBar.bossBar(mm("<gold>Kopfgeld"), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        escapeBar = BossBar.bossBar(mm("<green>Entkomme"), 1f, BossBar.Color.GREEN, BossBar.Overlay.NOTCHED_20);
        resetBorder();
        Bukkit.getScheduler().runTaskTimer(this, this::refreshMenus, 10L, 10L);
        getLogger().info("Kopfgeld geladen - /kopfgeld oeffnet das Menue.");
    }

    @Override
    public void onDisable() {
        hardReset(false);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        switch (cmd.getName().toLowerCase()) {
            case "start" -> beginCountdown(sender);
            case "kopfgeldstop" -> {
                hardReset(true);
                sender.sendMessage(mm(PREFIX + "Spiel abgebrochen und zurückgesetzt."));
            }
            case "kopfgeld" -> handleKopfgeld(sender, args);
            default -> { return false; }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (cmd.getName().equalsIgnoreCase("kopfgeld")) {
            if (args.length == 1) return filter(List.of("menu", "start", "stop", "skip", "pause", "status"), args[0]);
            if (args.length == 2 && args[0].equalsIgnoreCase("skip")) return filter(List.of("strafe"), args[1]);
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> opts, String start) {
        List<String> r = new ArrayList<>();
        for (String o : opts) if (o.startsWith(start.toLowerCase())) r.add(o);
        return r;
    }

    private void handleKopfgeld(CommandSender s, String[] args) {
        String sub = args.length == 0 ? "menu" : args[0].toLowerCase();
        switch (sub) {
            case "menu" -> {
                if (s instanceof Player p) openMenu(p);
                else s.sendMessage(mm(PREFIX + "<red>Das Menü gibt es nur im Spiel."));
            }
            case "start" -> beginCountdown(s);
            case "stop" -> {
                hardReset(true);
                s.sendMessage(mm(PREFIX + "Spiel abgebrochen und zurückgesetzt."));
            }
            case "skip" -> {
                boolean penalty = args.length > 1 && args[1].equalsIgnoreCase("strafe");
                s.sendMessage(mm(PREFIX + skip(penalty)));
            }
            case "pause" -> s.sendMessage(mm(PREFIX + togglePause()));
            case "status" -> sendStatus(s);
            default -> s.sendMessage(mm(PREFIX + "<gray>Befehle: <white>/kopfgeld <gray>[menu | start | stop | skip [strafe] | pause | status]"));
        }
    }

    private void sendStatus(CommandSender s) {
        s.sendMessage(mm(PREFIX + "<gray>Phase: <white>" + phaseName()));
        s.sendMessage(mm("<gray>Runde: <white>" + round + " <dark_gray>| <gray>Lebende Spieler: <green>" + alive.size()));
        if (ticker != null) s.sendMessage(mm("<gray>Timer: <yellow>" + fmt(remaining) + (paused ? " <red>(pausiert)" : "")));
    }

    // ------------------------------------------------------------------ Helpers

    private Component mm(String s) { return MM.deserialize(s); }

    private void all(Consumer<Player> c) {
        for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) c.accept(p);
    }

    private void title(Player p, String t, String s, int in, int stay, int out) {
        p.showTitle(Title.title(mm(t), mm(s), Title.Times.times(
                Duration.ofMillis(in * 50L), Duration.ofMillis(stay * 50L), Duration.ofMillis(out * 50L))));
    }

    private void snd(Player p, Sound s, float vol, float pitch) {
        p.playSound(p.getLocation(), s, vol, pitch);
    }

    private void announce(String msg) { Bukkit.broadcast(mm(PREFIX + msg)); }

    private String fmt(int sec) { return String.format("%02d:%02d", sec / 60, sec % 60); }

    private String nameOf(UUID id) {
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? "???" : n;
    }

    private World world() { return Bukkit.getWorlds().get(0); }

    private void resetBorder() {
        WorldBorder b = world().getWorldBorder();
        b.setCenter(world().getSpawnLocation());
        b.setSize(getConfig().getDouble("border-start-size", 50));
    }

    private ItemStack named(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(mm(name).decoration(TextDecoration.ITALIC, false));
        if (lore.length > 0) {
            List<Component> l = new ArrayList<>();
            for (String line : lore) l.add(mm(line).decoration(TextDecoration.ITALIC, false));
            meta.lore(l);
        }
        it.setItemMeta(meta);
        return it;
    }

    private void firework(Location loc) {
        Firework fw = loc.getWorld().spawn(loc, Firework.class);
        FireworkMeta m = fw.getFireworkMeta();
        m.addEffect(FireworkEffect.builder()
                .with(FireworkEffect.Type.BALL_LARGE)
                .withColor(org.bukkit.Color.RED, org.bukkit.Color.ORANGE, org.bukkit.Color.YELLOW)
                .withFade(org.bukkit.Color.WHITE)
                .trail(true).flicker(true).build());
        fw.setFireworkMeta(m);
        fw.detonate();
    }

    private int nextWait() {
        return getConfig().getInt("initial-wait-seconds", 300)
                + round * getConfig().getInt("wait-increase-seconds", 300);
    }

    private List<Player> alivePlayersOnline() {
        List<Player> l = new ArrayList<>();
        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) l.add(p);
        }
        return l;
    }

    private String phaseName() {
        return switch (phase) {
            case IDLE -> "Wartet auf Start";
            case COUNTDOWN -> "Start-Countdown";
            case WAITING -> "Wartezeit bis zum Kopfgeld";
            case DRAW -> "Auslosung";
            case HUNT -> "Jagd läuft";
            case EVAL -> "Auswertung";
            case ENDED -> "Siegerehrung";
        };
    }

    private void cancelTasks() {
        if (ticker != null) { ticker.cancel(); ticker = null; }
        if (compassTask != null) { compassTask.cancel(); compassTask = null; }
        if (countdownTask != null) { countdownTask.cancel(); countdownTask = null; }
        if (introTask != null) { introTask.cancel(); introTask = null; }
        if (pendingDraw != null) { pendingDraw.cancel(); pendingDraw = null; }
        if (evalTask != null) { evalTask.cancel(); evalTask = null; }
        if (showTask != null) { showTask.cancel(); showTask = null; }
    }

    private void closeRoulettes() {
        for (Roulette r : roulettes) r.revealed = true; // stoppt die Animation
        all(Player::clearTitle);
    }

    private void cleanupShow() {
        if (showBar != null) {
            final BossBar sb = showBar;
            all(p -> p.hideBossBar(sb));
            showBar = null;
        }
        if (showWorld != null) {
            showWorld.setTime(savedTime);
            showWorld.setStorm(savedStorm);
            showWorld.setThundering(savedThunder);
            showWorld = null;
        }
    }

    private void hardReset(boolean resetBorderToo) {
        gen++;
        cancelTasks();
        closeRoulettes();
        cleanupShow();
        phase = Phase.IDLE;
        alive.clear(); targets.clear(); done.clear(); doomed.clear(); out.clear(); escaping.clear();
        roulettes.clear();
        round = 0; pendingRoulettes = 0; inRoulette = false; paused = false;
        if (bar != null) all(p -> { p.hideBossBar(bar); p.hideBossBar(escapeBar); p.removePotionEffect(PotionEffectType.GLOWING); });
        all(p -> p.getInventory().remove(Material.COMPASS));
        if (resetBorderToo) resetBorder();
    }

    // ------------------------------------------------------------------ Start-Countdown

    private void beginCountdown(CommandSender sender) {
        if (phase != Phase.IDLE) {
            sender.sendMessage(mm(PREFIX + "<red>Das Spiel läuft bereits!"));
            return;
        }
        List<Player> ps = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (ps.size() < 2) {
            sender.sendMessage(mm(PREFIX + "<red>Es werden mindestens 2 Spieler benötigt."));
            return;
        }
        alive.clear(); targets.clear(); done.clear(); doomed.clear(); out.clear(); escaping.clear();
        round = 0;
        for (Player p : ps) alive.add(p.getUniqueId());
        phase = Phase.COUNTDOWN;
        final int myGen = gen;
        final String[] colors = {"#ff5555", "#ffaa00", "#ffff55", "#aaff55", "#55ff55"}; // 1..5

        countdownTask = new BukkitRunnable() {
            int n = 5;

            @Override
            public void run() {
                if (myGen != gen) { cancel(); return; }
                if (n > 0) {
                    final int num = n;
                    all(p -> {
                        title(p, "<" + colors[num - 1] + "><bold>" + num, "<gray>Das Spiel beginnt...", 0, 22, 0);
                        snd(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.6f + (5 - num) * 0.2f);
                    });
                    n--;
                } else {
                    countdownTask = null;
                    cancel();
                    go();
                }
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private void go() {
        phase = Phase.WAITING;
        double size = getConfig().getDouble("border-end-size", 3000);
        long secs = getConfig().getLong("border-expand-seconds", 15);
        world().getWorldBorder().setSize(size, secs);

        all(p -> {
            title(p, "<gradient:#ff0000:#ffaa00><bold>LOS GEHT'S!", "<gray>Die Border öffnet sich...", 0, 50, 15);
            snd(p, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.8f, 1f);
            snd(p, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        });

        final int myGen = gen;
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (myGen != gen || t > 20) { cancel(); return; }
                final double r = 1 + t * 0.35;
                final int tt = t;
                all(p -> {
                    Location c = p.getLocation().add(0, 0.2, 0);
                    for (int i = 0; i < 36; i++) {
                        double a = Math.toRadians(i * 10);
                        p.getWorld().spawnParticle(Particle.END_ROD,
                                c.clone().add(Math.cos(a) * r, tt * 0.05, Math.sin(a) * r), 0, 0, 0.02, 0, 1);
                    }
                });
                t++;
            }
        }.runTaskTimer(this, 0L, 2L);

        int wait = getConfig().getInt("initial-wait-seconds", 300);
        announce("<gold>Die Border wächst! <gray>In <yellow>" + fmt(wait) + " <gray>wird das erste Kopfgeld vergeben.");
        startTimer(Phase.WAITING, wait);
    }

    // ------------------------------------------------------------------ Timer / Bossbar

    private void startTimer(Phase p, int seconds) {
        phase = p;
        paused = false;
        total = Math.max(1, seconds);
        remaining = seconds;
        if (ticker != null) ticker.cancel();
        all(pl -> pl.showBossBar(bar));
        updateBar();
        final int myGen = gen;
        ticker = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (myGen != gen || paused) return;
            tick();
        }, 20L, 20L);
    }

    private void tick() {
        remaining--;
        updateBar();

        if (phase == Phase.HUNT && remaining == 60 && total > 90
                && getConfig().getBoolean("last-minute-glow", true)) {
            lastMinute();
        }

        if (remaining > 0 && remaining <= 10) {
            final int r = remaining;
            all(p -> {
                snd(p, Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1.2f);
                if (r <= 5) {
                    String c = r <= 2 ? "#ff0000" : (r <= 4 ? "#ff8800" : "#ffdd00");
                    title(p, "<" + c + "><bold>" + r, phase == Phase.HUNT ? "<gray>Zeit läuft ab..." : "<gray>Gleich geht's los...", 0, 15, 0);
                }
            });
        }

        if (remaining <= 0) {
            if (ticker != null) { ticker.cancel(); ticker = null; }
            if (phase == Phase.WAITING) startDraw();
            else if (phase == Phase.HUNT) evaluateHunt();
        }
    }

    private void updateBar() {
        float prog = Math.max(0f, Math.min(1f, remaining / (float) total));
        bar.progress(prog);
        if (phase == Phase.HUNT) {
            escapeBar.progress(prog);
            escapeBar.name(mm("<gradient:#55ff55:#ffaa00><bold>⚡ ENTKOMME! ⚡</bold></gradient> <white>noch " + fmt(remaining)));
        }
        boolean urgent = remaining <= 10;
        String pre = paused ? "<red>⏸ PAUSIERT <dark_gray>| " : "";
        if (phase == Phase.WAITING) {
            bar.color(urgent && remaining % 2 == 0 ? BossBar.Color.RED : BossBar.Color.YELLOW);
            bar.name(mm(pre + "<gradient:#ffd000:#ff6a00><bold>» NÄCHSTES KOPFGELD IN «</bold></gradient> <white>" + fmt(remaining)));
        } else if (phase == Phase.HUNT) {
            bar.color(urgent && remaining % 2 == 0 ? BossBar.Color.WHITE : BossBar.Color.RED);
            bar.name(mm(pre + "<gradient:#ff0000:#ff9900><bold>☠ DIE JAGD LÄUFT ☠</bold></gradient> <white>" + fmt(remaining)));
        }
    }

    private String togglePause() {
        if (ticker == null) return "<red>Gerade läuft kein Timer.";
        paused = !paused;
        updateBar();
        return paused ? "<yellow>Timer pausiert." : "<green>Timer läuft weiter.";
    }

    private boolean adjustTime(int deltaSec) {
        if (ticker == null) return false;
        remaining = Math.max(1, remaining + deltaSec);
        total = Math.max(total, remaining);
        updateBar();
        return true;
    }

    // ------------------------------------------------------------------ Skip

    private String skip(boolean penalty) {
        return switch (phase) {
            case IDLE -> "<red>Es läuft gerade kein Spiel.";
            case COUNTDOWN -> {
                if (countdownTask != null) { countdownTask.cancel(); countdownTask = null; }
                go();
                yield "<green>Countdown übersprungen.";
            }
            case WAITING -> {
                if (ticker == null) yield "<red>Kein Timer aktiv.";
                remaining = 1;
                tick();
                yield "<green>Wartezeit übersprungen - die Auslosung startet.";
            }
            case HUNT -> {
                if (ticker == null) yield "<red>Kein Timer aktiv.";
                if (!penalty) done.addAll(targets.keySet());
                remaining = 1;
                tick();
                yield penalty ? "<red>Jagd beendet - wer sein Ziel nicht hat, stirbt!" : "<green>Jagd ohne Strafe beendet.";
            }
            case DRAW -> {
                skipDraw();
                yield "<green>Auslosung übersprungen.";
            }
            case EVAL -> {
                if (evalTask != null) { evalTask.cancel(); evalTask = null; }
                afterEval();
                yield "<green>Auswertung übersprungen.";
            }
            case ENDED -> {
                hardReset(true);
                yield "<green>Siegerehrung beendet.";
            }
        };
    }

    private void skipDraw() {
        if (introTask != null) { introTask.cancel(); introTask = null; }
        if (pendingDraw != null) { pendingDraw.cancel(); pendingDraw = null; }
        if (!inRoulette) { startRoulettes(); return; }
        for (Roulette r : new ArrayList<>(roulettes)) r.skip();
    }

    // ------------------------------------------------------------------ Auslosung

    private void startDraw() {
        phase = Phase.DRAW;
        round++;
        inRoulette = false;
        roulettes.clear();
        List<Player> pool = alivePlayersOnline();
        if (pool.size() < 2) {
            if (!checkWinner()) {
                announce("<red>Zu wenige Spieler online - die Auslosung wird verschoben.");
                startTimer(Phase.WAITING, nextWait());
            }
            return;
        }
        bar.color(BossBar.Color.PURPLE);
        bar.progress(1f);
        bar.name(mm("<gradient:#aa00ff:#ff55ff><bold>» AUSLOSUNG «"));

        StringBuilder cand = new StringBuilder("<white>");
        for (Player cp : pool) cand.append("<head:").append(cp.getName()).append("> ");
        final String candidates = cand.toString();
        all(p -> p.sendActionBar(mm(candidates)));

        final int myGen = gen;
        final String word = "KOPFGELD";
        introTask = new BukkitRunnable() {
            int f = 0;

            @Override
            public void run() {
                if (myGen != gen) { cancel(); return; }
                int reveal = Math.min(word.length(), f / 2);
                String shown = word.substring(0, reveal);
                String hidden = word.substring(reveal);
                String color = f % 2 == 0 ? "#ff0000" : "#ffaa00";
                String t = "<" + color + "><bold>" + shown
                        + (hidden.isEmpty() ? "" : "<obfuscated>" + hidden + "</obfuscated>");
                final int frame = f;
                all(p -> {
                    title(p, t, "<gray>Die Jagd wird ausgelost...", 0, 8, 0);
                    snd(p, Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.5f + frame * 0.05f);
                    if (frame == 0) snd(p, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1f, 0.8f);
                });
                f++;
                if (f > word.length() * 2 + 2) {
                    introTask = null;
                    cancel();
                    all(p -> {
                        p.getWorld().strikeLightningEffect(p.getLocation());
                        title(p, "<gradient:#ff0000:#ffaa00><bold>KOPFGELD", "<white>Wer ist dein Ziel?", 0, 40, 10);
                        snd(p, Sound.ENTITY_WITHER_SPAWN, 0.6f, 1.2f);
                    });
                    pendingDraw = Bukkit.getScheduler().runTaskLater(KopfgeldPlugin.this, () -> {
                        pendingDraw = null;
                        if (myGen == gen) startRoulettes();
                    }, 50L);
                }
            }
        }.runTaskTimer(this, 0L, 3L);
    }

    private void startRoulettes() {
        List<Player> online = alivePlayersOnline();
        if (online.size() < 2) {
            if (!checkWinner()) startTimer(Phase.WAITING, nextWait());
            return;
        }
        Collections.shuffle(online);
        targets.clear();
        done.clear();
        escaping.clear();
        int n = online.size();
        // zufaelliger Ring: jeder jagt genau einen anderen, jeder wird von genau einem gejagt
        for (int i = 0; i < n; i++) {
            targets.put(online.get(i).getUniqueId(), online.get((i + 1) % n).getUniqueId());
        }
        inRoulette = true;
        roulettes.clear();
        pendingRoulettes = n;
        for (Player hunter : online) {
            Player target = Bukkit.getPlayer(targets.get(hunter.getUniqueId()));
            List<Player> pool = new ArrayList<>(online);
            pool.remove(hunter);
            Roulette r = new Roulette(hunter, target, pool);
            roulettes.add(r);
            r.begin();
        }
    }

    /**
     * Gluecksspiel-Animation direkt auf dem Bildschirm (kein Inventar):
     * 5 Spielerkoepfe rollen als grosse Schrift in der Mitte durch, der Pfeil markiert den Kopf in der
     * Mitte, der Name steht darunter. Die Koepfe werden per MiniMessage-Tag als Bild gerendert,
     * dafuer braucht man kein Resource-Pack (Paper 1.21.9+).
     */
    private final class Roulette {
        private final Player hunter;
        private final Player target;
        private final List<Player> seq = new ArrayList<>();
        private final int steps = 32;
        private final int myGen = gen;
        private int s = 0;
        private boolean finished = false;
        private boolean revealed = false;

        Roulette(Player hunter, Player target, List<Player> pool) {
            this.hunter = hunter;
            this.target = target;
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            Player prev = null;
            for (int k = 0; k < steps + 9; k++) {
                Player c;
                do { c = pool.get(rnd.nextInt(pool.size())); } while (pool.size() > 1 && c == prev);
                seq.add(c);
                prev = c;
            }
            seq.set(steps + 4, target); // hier landet die Animation
        }

        void begin() {
            next();
        }

        void skip() {
            if (finished || revealed) return;
            s = steps;
            render();
            reveal(15L);
        }

        private void next() {
            if (myGen != gen || revealed) return;
            if (!hunter.isOnline()) { finish(); return; }
            render();
            if (s >= steps) { reveal(50L); return; }
            long delay = 2 + Math.round(11.0 * Math.pow(s / (double) steps, 3));
            s++;
            Bukkit.getScheduler().runTaskLater(KopfgeldPlugin.this, this::next, delay);
        }

        private String head(Player p) { return "<head:" + p.getName() + ">"; }

        private void render() {
            Player center = seq.get(s + 4);
            String row = "<white>" + head(seq.get(s + 2)) + " " + head(seq.get(s + 3))
                    + " <gold><bold>▸ <white>" + head(center) + " <gold><bold>◂ <white>"
                    + head(seq.get(s + 5)) + " " + head(seq.get(s + 6));
            String sub = "<gold>▲ <yellow><bold>" + center.getName() + " <gold>▲";
            title(hunter, row, sub, 0, 25, 0);
            snd(hunter, Sound.UI_BUTTON_CLICK, 0.7f, 0.8f + 1.0f * s / steps);
        }

        private void reveal(long hold) {
            revealed = true;
            title(hunter, "<white>" + head(target),
                    "<green><bold>✔ <white><bold>" + target.getName(), 0, (int) hold + 10, 10);
            snd(hunter, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            Location l = hunter.getLocation().add(0, 1, 0);
            hunter.getWorld().spawnParticle(Particle.END_ROD, l, 40, 0.6, 0.8, 0.6, 0.05);
            Bukkit.getScheduler().runTaskLater(KopfgeldPlugin.this, this::finish, hold);
        }

        private void finish() {
            if (finished) return;
            finished = true;
            if (myGen != gen) return;
            pendingRoulettes--;
            if (pendingRoulettes <= 0) startHunt();
        }
    }

    // ------------------------------------------------------------------ Jagd

    private void giveCompass(Player h) {
        if (h.getInventory().contains(Material.COMPASS)) return;
        ItemStack compass = named(Material.COMPASS, "<gradient:#ff0000:#ff9900><bold>Kopfgeld-Kompass");
        Map<Integer, ItemStack> left = h.getInventory().addItem(compass);
        left.values().forEach(it -> h.getWorld().dropItem(h.getLocation(), it));
    }

    private void giveCompasses() {
        for (Map.Entry<UUID, UUID> e : targets.entrySet()) {
            if (done.contains(e.getKey()) || !alive.contains(e.getKey())) continue;
            Player h = Bukkit.getPlayer(e.getKey());
            if (h != null) giveCompass(h);
        }
    }

    private void startHunt() {
        if (phase != Phase.DRAW) return;
        inRoulette = false;
        roulettes.clear();
        int huntSec = getConfig().getInt("hunt-seconds", 900);
        for (Map.Entry<UUID, UUID> e : targets.entrySet()) {
            Player h = Bukkit.getPlayer(e.getKey());
            if (h == null || !alive.contains(e.getKey())) continue;
            String tn = nameOf(e.getValue());
            giveCompass(h);
            title(h, "<gradient:#ff0000:#ff9900><bold>☠ DIE JAGD BEGINNT", "<white>Töte <red><bold>" + tn
                    + "<reset> <white>in <yellow>" + fmt(huntSec), 5, 70, 15);
            h.sendMessage(mm(PREFIX + "Dein Ziel: <red><bold>" + tn + "<reset><gray>. Du hast <yellow>" + fmt(huntSec)
                    + "<gray> Zeit - sonst stirbst du!"));
            snd(h, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.7f, 1f);
        }
        startTimer(Phase.HUNT, huntSec);

        if (compassTask != null) compassTask.cancel();
        final int myGen = gen;
        compassTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (myGen != gen) return;
            updateCompasses();
        }, 0L, 10L);
    }

    private void updateCompasses() {
        beatTick++;
        boolean warn = getConfig().getBoolean("proximity-warning", true);
        Map<UUID, List<String>> bars = new LinkedHashMap<>();

        for (Map.Entry<UUID, UUID> e : targets.entrySet()) {
            UUID hid = e.getKey();
            if (done.contains(hid) || !alive.contains(hid)) continue;
            Player h = Bukkit.getPlayer(hid);
            if (h == null) continue;
            Player t = Bukkit.getPlayer(e.getValue());
            List<String> hb = bars.computeIfAbsent(hid, k -> new ArrayList<>());
            if (t == null) {
                hb.add("<red>☠ Dein Ziel ist gerade offline");
                continue;
            }
            if (t.getWorld().equals(h.getWorld())) {
                h.setCompassTarget(t.getLocation());
                double dist = h.getLocation().distance(t.getLocation());
                hb.add("<gradient:#ff0000:#ff9900><bold>☠ " + t.getName()
                        + "</bold></gradient> <dark_gray>» <white>" + (int) dist + " Blöcke");
                // Herzschlag-Warnung fuer den Gejagten (er weiss nicht, WER ihn jagt)
                if (warn && alive.contains(t.getUniqueId())) {
                    List<String> tb = bars.computeIfAbsent(t.getUniqueId(), k -> new ArrayList<>());
                    if (dist <= 10) {
                        tb.add("<red><bold>RENN! Jemand ist direkt hinter dir!");
                        snd(t, Sound.ENTITY_WARDEN_HEARTBEAT, 1f, 1.5f);
                    } else if (dist <= 20) {
                        tb.add("<gold>Jemand ist ganz nah...");
                        if (beatTick % 2 == 0) snd(t, Sound.ENTITY_WARDEN_HEARTBEAT, 0.8f, 1.2f);
                    } else if (dist <= 40) {
                        tb.add("<yellow>Du fühlst dich beobachtet...");
                        if (beatTick % 4 == 0) snd(t, Sound.ENTITY_WARDEN_HEARTBEAT, 0.6f, 1f);
                    }
                }
            } else {
                hb.add("<gradient:#ff0000:#ff9900><bold>☠ " + t.getName()
                        + "</bold></gradient> <dark_gray>» <gray>in einer anderen Dimension");
            }
        }

        for (UUID id : escaping) {
            if (!alive.contains(id)) continue;
            bars.computeIfAbsent(id, k -> new ArrayList<>())
                    .add(0, "<gradient:#ff5555:#ffaa00><bold>⚡ ENTKOMME! ⚡</bold></gradient> <dark_gray>» <white>" + fmt(remaining));
        }

        for (Map.Entry<UUID, List<String>> e : bars.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null && !e.getValue().isEmpty()) {
                p.sendActionBar(mm(String.join(" <dark_gray>┃ ", e.getValue())));
            }
        }
    }

    private boolean isHunted(UUID id) {
        for (Map.Entry<UUID, UUID> e : targets.entrySet()) {
            if (e.getValue().equals(id) && alive.contains(e.getKey()) && !done.contains(e.getKey())) return true;
        }
        return false;
    }

    private void giveItem(Player p, ItemStack it) {
        Map<Integer, ItemStack> left = p.getInventory().addItem(it);
        left.values().forEach(x -> p.getWorld().dropItem(p.getLocation(), x));
    }

    /** Letzte Minute: wer noch gejagt wird, leuchtet - jeder kann ihn sehen. */
    private void lastMinute() {
        all(p -> {
            title(p, "<gradient:#ff0000:#ff8800><bold>LETZTE MINUTE", "<gray>Wer noch gejagt wird, leuchtet!", 0, 50, 10);
            snd(p, Sound.ENTITY_WITHER_SPAWN, 0.5f, 1.3f);
        });
        announce("<red><bold>LETZTE MINUTE! <gray>Alle, die noch gejagt werden, <white>leuchten<gray> jetzt!");
        for (Map.Entry<UUID, UUID> e : targets.entrySet()) {
            if (done.contains(e.getKey()) || !alive.contains(e.getKey())) continue;
            Player t = Bukkit.getPlayer(e.getValue());
            if (t != null && alive.contains(t.getUniqueId())) {
                t.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 20 * 65, 0, false, false));
            }
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        if (phase == Phase.IDLE || phase == Phase.ENDED || phase == Phase.COUNTDOWN) return;
        Player victim = e.getEntity();
        UUID vid = victim.getUniqueId();
        if (!alive.contains(vid) || doomed.contains(vid)) return;
        Player killer = victim.getKiller();

        if (phase == Phase.HUNT) {
            for (Map.Entry<UUID, UUID> en : targets.entrySet()) {
                UUID hid = en.getKey();
                if (!en.getValue().equals(vid) || done.contains(hid) || !alive.contains(hid)) continue;
                done.add(hid);
                Player h = Bukkit.getPlayer(hid);
                if (h == null) continue;
                h.getInventory().remove(Material.COMPASS);
                if (killer != null && killer.getUniqueId().equals(hid)) {
                    success(h, victim);
                } else {
                    h.sendMessage(mm(PREFIX + "<yellow>Dein Ziel ist anders gestorben - du bist sicher."));
                }
            }
        }

        if (getConfig().getBoolean("eliminate-on-death", true)) {
            eliminate(vid, "Kopfgeld: gestorben", getConfig().getBoolean("ban-on-any-death", false));
            announce("<red>" + victim.getName() + " <gray>ist ausgeschieden! Noch <white>" + alive.size() + "<gray> übrig.");
            Bukkit.getScheduler().runTask(this, this::checkWinner);
        }
    }

    private void success(Player hunter, Player victim) {
        final String hn = hunter.getName();
        final String vn = victim.getName();
        final UUID hid = hunter.getUniqueId();
        final boolean hunted = isHunted(hid);
        if (hunted) escaping.add(hid);

        title(hunter, "<gradient:#55ff55:#00aa00><bold>✔ KOPFGELD KASSIERT", "<white>" + vn + " ist erledigt", 5, 50, 15);
        snd(hunter, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        firework(hunter.getLocation());

        // Grosses Chat-Banner mit Spielerkoepfen
        Bukkit.broadcast(mm(" "));
        Bukkit.broadcast(mm("<dark_red><bold>━━━━━━━━ ☠ KOPFGELD KASSIERT ☠ ━━━━━━━━"));
        Bukkit.broadcast(mm(" "));
        Bukkit.broadcast(mm("   <white><head:" + hn + "> <gold><bold>" + hn + " <dark_gray>⚔ <white><head:" + vn + "> <red><bold>" + vn));
        Bukkit.broadcast(mm("   <yellow>" + hn + " <gray>hat das Kopfgeld auf <red>" + vn + " <gray>eingelöst!"));
        Bukkit.broadcast(mm(" "));
        Bukkit.broadcast(mm("<dark_red><bold>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));

        // Belohnung: Trophaee + Buffs
        if (getConfig().getBoolean("kill-reward", true)) {
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta sm = (SkullMeta) head.getItemMeta();
            sm.setOwningPlayer(victim);
            sm.displayName(mm("<gradient:#ff0000:#ffaa00><bold>Kopf von " + vn).decoration(TextDecoration.ITALIC, false));
            sm.lore(List.of(mm("<gray>Trophäe: Kopfgeld kassiert!").decoration(TextDecoration.ITALIC, false)));
            head.setItemMeta(sm);
            giveItem(hunter, head);
            giveItem(hunter, new ItemStack(Material.GOLDEN_APPLE, 2));
            hunter.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 20, 1, false, true));
            hunter.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 20 * 10, 1, false, true));
            hunter.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 20 * 30, 1, false, true));
            hunter.sendMessage(mm(PREFIX + "<gold>Belohnung: <white>Kopf von " + vn
                    + ", 2 goldene Äpfel, Speed, Regeneration & Absorption!"));
        }

        // Neues Ziel: Entkommen
        final int myGen = gen;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (myGen != gen || phase != Phase.HUNT || !hunter.isOnline()) return;
            if (hunted && escaping.contains(hid)) {
                title(hunter, "<gradient:#ff5555:#ffaa00><bold>NEUES ZIEL: ENTKOMME!",
                        "<gray>Jemand ist noch hinter dir her - überlebe bis die Jagd endet", 5, 80, 15);
                snd(hunter, Sound.ENTITY_WARDEN_HEARTBEAT, 1f, 1f);
                snd(hunter, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5f, 1.4f);
                hunter.showBossBar(escapeBar);
                hunter.sendMessage(mm(PREFIX + "<gold>Neues Ziel: <red><bold>ENTKOMME! <gray>Überlebe, bis die Jagd endet."));
            } else {
                title(hunter, "<gradient:#55ff55:#aaff55><bold>DU BIST SICHER", "<gray>Niemand jagt dich mehr", 5, 60, 15);
            }
        }, 70L);
    }

    // ------------------------------------------------------------------ Auswertung

    private void evaluateHunt() {
        phase = Phase.EVAL;
        if (compassTask != null) { compassTask.cancel(); compassTask = null; }
        all(p -> {
            p.getInventory().remove(Material.COMPASS);
            p.hideBossBar(escapeBar);
            p.removePotionEffect(PotionEffectType.GLOWING);
        });
        escaping.clear();

        List<UUID> fails = new ArrayList<>();
        for (UUID h : targets.keySet()) {
            if (alive.contains(h) && !done.contains(h)) fails.add(h);
        }

        all(p -> {
            title(p, "<gradient:#ff0000:#880000><bold>ZEIT ABGELAUFEN", "<gray>Die Jagd ist vorbei...", 5, 50, 15);
            snd(p, Sound.ENTITY_WITHER_SPAWN, 0.5f, 0.7f);
        });

        if (fails.isEmpty()) {
            announce("<green>Alle haben ihr Kopfgeld erfüllt - niemand stirbt!");
        } else {
            for (UUID id : fails) doom(id);
        }

        final int myGen = gen;
        evalTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (myGen != gen) return;
            afterEval();
        }, 120L);
    }

    private void afterEval() {
        evalTask = null;
        if (phase != Phase.EVAL) return;
        if (checkWinner()) return;
        int wait = nextWait();
        announce("<gray>Nächstes Kopfgeld in <yellow>" + fmt(wait) + "<gray>.");
        startTimer(Phase.WAITING, wait);
    }

    private void doom(UUID id) {
        doomed.add(id);
        alive.remove(id);
        out.add(id);
        String name = nameOf(id);
        announce("<red><bold>" + name + "<reset> <gray>hat sein Kopfgeld verfehlt und scheidet aus!");

        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            title(p, "<dark_red><bold>☠ DU HAST VERSAGT", "<gray>Dein Ziel lebt noch...", 5, 40, 10);
            p.getWorld().strikeLightningEffect(p.getLocation());
            snd(p, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 1f, 0.6f);
            Bukkit.getScheduler().runTaskLater(this, () -> {
                Player pp = Bukkit.getPlayer(id);
                if (pp != null && pp.getGameMode() != GameMode.SPECTATOR) pp.setHealth(0.0);
            }, 40L);
        }
        if (getConfig().getBoolean("ban-on-fail", true)) {
            Bukkit.getScheduler().runTaskLater(this, () -> banByName(name, "Kopfgeld: Ziel nicht erledigt"), 80L);
        }
    }

    private void eliminate(UUID id, String reason, boolean ban) {
        alive.remove(id);
        out.add(id);
        if (ban) {
            String name = nameOf(id);
            Bukkit.getScheduler().runTaskLater(this, () -> banByName(name, reason), 40L);
        }
    }

    private void banByName(String name, String reason) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ban " + name + " " + reason);
    }

    // ------------------------------------------------------------------ Ende / Gewinner-Show

    private boolean checkWinner() {
        if (phase == Phase.IDLE || phase == Phase.ENDED || phase == Phase.COUNTDOWN) return false;
        if (alive.size() > 1) return false;
        endGame(alive.isEmpty() ? null : alive.iterator().next());
        return true;
    }

    private void endGame(UUID winner) {
        phase = Phase.ENDED;
        cancelTasks();
        closeRoulettes();
        roulettes.clear();
        inRoulette = false;
        paused = false;
        all(p -> { p.hideBossBar(bar); p.hideBossBar(escapeBar); p.getInventory().remove(Material.COMPASS); });

        final int myGen = gen;
        Player wp = winner == null ? null : Bukkit.getPlayer(winner);
        if (wp == null) {
            final String wn = winner == null ? null : nameOf(winner);
            all(p -> {
                if (wn != null) title(p, "<gradient:#ffd000:#ffffff><bold>♛ GEWINNER ♛", "<white>" + wn, 5, 100, 20);
                else title(p, "<gradient:#ff0000:#880000><bold>NIEMAND ÜBERLEBT", "<gray>Das Kopfgeld hat alle geholt", 5, 100, 20);
                snd(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            });
            announce(wn != null ? "<gold><bold>" + wn + "<reset> <gray>gewinnt das Kopfgeld!" : "<red>Niemand hat überlebt.");
            showTask = Bukkit.getScheduler().runTaskLater(this, () -> {
                showTask = null;
                if (myGen == gen) finishGame();
            }, 200L);
            return;
        }
        winnerShow(wp);
    }

    private void finishGame() {
        cleanupShow();
        for (UUID id : out) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.getGameMode() == GameMode.SPECTATOR) p.setGameMode(GameMode.SURVIVAL);
        }
        phase = Phase.IDLE;
        alive.clear(); targets.clear(); done.clear(); doomed.clear(); out.clear(); escaping.clear();
        round = 0;
        all(p -> p.getInventory().remove(Material.COMPASS));
        resetBorder();
    }

    /**
     * Gewinner-Show (ca. 22 Sekunden):
     * Nacht + Gewitter, alle werden im Kreis um den Gewinner versammelt, Herzschlag,
     * Name baut sich Buchstabe fuer Buchstabe mit Blitzen auf, grosses Finale mit Lichtsaeule,
     * Krone aus Feuer, Regenbogen-Bossbar, Feuerwerk und der Gewinner schwebt in den Himmel.
     */
    private void winnerShow(Player winner) {
        final int myGen = gen;
        final UUID winnerId = winner.getUniqueId();
        final String name = winner.getName();
        final World w = winner.getWorld();
        final Location center = winner.getLocation().clone();

        showWorld = w;
        savedTime = w.getTime();
        savedStorm = w.hasStorm();
        savedThunder = w.isThundering();
        w.setStorm(true);
        w.setThundering(true);
        w.setTime(18000);

        winner.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 20 * 30, 0, false, false));

        // Alle anderen im Kreis um den Gewinner versammeln und zu ihm schauen
        List<Player> audience = new ArrayList<>(Bukkit.getOnlinePlayers());
        audience.remove(winner);
        int n = audience.size();
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / Math.max(1, n);
            Location l = center.clone().add(Math.cos(a) * 7, 0, Math.sin(a) * 7);
            int gy = w.getHighestBlockYAt(l) + 1;
            if (Math.abs(gy - center.getY()) <= 10) l.setY(gy);
            l.setDirection(center.toVector().subtract(l.toVector()));
            audience.get(i).teleport(l);
        }

        showBar = BossBar.bossBar(mm("<gold><bold>♛ " + name + " ♛"), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        all(p -> p.showBossBar(showBar));

        announce("<gold>Das Kopfgeld ist entschieden...");

        final int totalTicks = 440;
        final BossBar.Color[] cols = BossBar.Color.values();
        final boolean openSky = w.getHighestBlockYAt(center) <= center.getBlockY() + 1;

        showTask = new BukkitRunnable() {
            int t = 0;
            int lastRevealed = -1;
            boolean finale = false;
            Location last = center;

            @Override
            public void run() {
                if (myGen != gen) { cancel(); return; }
                Player wp = Bukkit.getPlayer(winnerId);
                if (wp != null && wp.isOnline()) last = wp.getLocation();
                final Location loc = last;
                final World ww = loc.getWorld();

                // Regenbogen-Bossbar
                showBar.progress(Math.max(0f, 1f - t / (float) totalTicks));
                showBar.color(cols[(t / 6) % cols.length]);
                showBar.name(mm("<rainbow:" + (t / 2) + "><bold>♛ " + name + " ♛"));

                if (t < 60) {
                    // Akt 1: Herzschlag
                    if (t == 0) {
                        all(p -> snd(p, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1f, 0.7f));
                        ww.strikeLightningEffect(loc);
                    }
                    if (t % 20 == 0) {
                        final int tt = t;
                        all(p -> {
                            title(p, "<dark_red><bold>NUR EINER BLEIBT", "<gray>Die Jagd ist vorbei...", 0, 30, 5);
                            snd(p, Sound.ENTITY_WARDEN_HEARTBEAT, 1f, 0.8f + tt / 200f);
                        });
                    }
                } else if (t < 170) {
                    // Akt 2: Name baut sich auf, jeder Buchstabe mit Blitz
                    int interval = Math.max(6, 100 / Math.max(1, name.length()));
                    int revealed = Math.min(name.length(), (t - 60) / interval);
                    if (revealed != lastRevealed) {
                        lastRevealed = revealed;
                        String shown = name.substring(0, revealed);
                        String hidden = name.substring(revealed);
                        String txt = "<gold><bold>♛ " + shown + (hidden.isEmpty() ? "" : "<obfuscated>" + hidden + "</obfuscated>");
                        final int rv = revealed;
                        all(p -> {
                            title(p, txt, "<gray>Der Gewinner ist...", 0, 40, 0);
                            snd(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.6f + rv * 0.08f);
                        });
                        ww.strikeLightningEffect(loc);
                    }
                    // wachsender Partikelring
                    double r = 1 + ((t - 60) % 40) * 0.12;
                    for (int i = 0; i < 24; i++) {
                        double a = Math.PI * 2 * i / 24 + t * 0.1;
                        ww.spawnParticle(Particle.SOUL_FIRE_FLAME, loc.clone().add(Math.cos(a) * r, 0.2, Math.sin(a) * r), 1, 0, 0, 0, 0);
                    }
                } else {
                    // Akt 3: Finale
                    if (!finale) {
                        finale = true;
                        all(p -> {
                            snd(p, Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 0.9f);
                            snd(p, Sound.BLOCK_BEACON_ACTIVATE, 1f, 1f);
                            snd(p, Sound.ITEM_TOTEM_USE, 1f, 1f);
                        });
                        for (int i = 0; i < 3; i++) ww.strikeLightningEffect(loc);
                        for (int i = 0; i < 8; i++) {
                            double a = Math.PI * 2 * i / 8;
                            firework(loc.clone().add(Math.cos(a) * 14, 4, Math.sin(a) * 14));
                        }
                        Bukkit.broadcast(mm("<gold><bold>━━━━━━━━━━━━━━━━━━━━━━━━━━"));
                        Bukkit.broadcast(mm("<rainbow><bold>          ♛ " + name + " ♛"));
                        Bukkit.broadcast(mm("<gray>          hat das Kopfgeld überlebt!"));
                        Bukkit.broadcast(mm("<gold><bold>━━━━━━━━━━━━━━━━━━━━━━━━━━"));
                        if (wp != null && openSky) {
                            wp.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 140, 2, false, false));
                            wp.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 20 * 40, 0, false, false));
                        }
                    }

                    if (t % 8 == 0) {
                        final int tt = t;
                        all(p -> {
                            title(p, "<rainbow:" + (tt / 2) + "><bold>♛ " + name + " ♛",
                                    "<gradient:#ffd000:#ffffff><bold>GEWINNER DES KOPFGELDS", 0, 12, 0);
                            p.sendActionBar(mm("<gradient:#ffd000:#ff5500><bold>★ ★ ★  " + name + "  ★ ★ ★"));
                        });
                    }

                    // Lichtsaeule als doppelte Spirale
                    for (int s = 0; s < 2; s++) {
                        for (int h = 0; h < 24; h++) {
                            double a = t * 0.15 + h * 0.45 + s * Math.PI;
                            double rr = 1.3 + 0.4 * Math.sin(h * 0.3 + t * 0.1);
                            ww.spawnParticle(Particle.END_ROD,
                                    loc.clone().add(Math.cos(a) * rr, h * 0.6 - 1, Math.sin(a) * rr), 1, 0, 0, 0, 0);
                        }
                    }
                    // Krone aus Feuer
                    for (int i = 0; i < 12; i++) {
                        double a = Math.PI * 2 * i / 12 + t * 0.2;
                        double crownY = 2.3 + (i % 2 == 0 ? 0.35 : 0);
                        ww.spawnParticle(Particle.FLAME, loc.clone().add(Math.cos(a) * 0.6, crownY, Math.sin(a) * 0.6), 1, 0, 0, 0, 0);
                    }
                    // Pulsierender Ring am Boden
                    double pr = 1 + (t % 40) * 0.15;
                    for (int i = 0; i < 24; i++) {
                        double a = Math.PI * 2 * i / 24;
                        ww.spawnParticle(Particle.SOUL_FIRE_FLAME, center.clone().add(Math.cos(a) * pr, 0.2, Math.sin(a) * pr), 1, 0, 0, 0, 0);
                    }
                    // Feuerwerk ausserhalb des Kreises
                    if (t % 16 == 0) {
                        ThreadLocalRandom rnd = ThreadLocalRandom.current();
                        double a = rnd.nextDouble() * Math.PI * 2;
                        firework(center.clone().add(Math.cos(a) * 14, 3 + rnd.nextInt(8), Math.sin(a) * 14));
                    }
                }

                t += 2;
                if (t >= totalTicks) {
                    cancel();
                    showTask = null;
                    Player fw = Bukkit.getPlayer(winnerId);
                    if (fw != null) fw.removePotionEffect(PotionEffectType.GLOWING);
                    all(p -> title(p, "<gradient:#ffd000:#ffffff><bold>GG!", "<gray>Danke fürs Spielen", 0, 40, 20));
                    finishGame();
                }
            }
        }.runTaskTimer(this, 0L, 2L);
    }

    // ------------------------------------------------------------------ Menue

    private static final class MenuHolder implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() { return inv; }
    }

    private void openMenu(Player p) {
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, mm("<gradient:#ff5555:#ffaa00><bold>☠ KOPFGELD ZENTRALE ☠"));
        holder.inv = inv;
        renderMenu(inv);
        p.openInventory(inv);
        snd(p, Sound.BLOCK_BEACON_ACTIVATE, 0.5f, 1.5f);
    }

    private void refreshMenus() {
        for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            Inventory top = p.getOpenInventory().getTopInventory();
            if (top.getHolder() instanceof MenuHolder) renderMenu(top);
        }
    }

    private ItemStack numItem(Material m, String title, String value, String... extra) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Aktuell: <yellow><bold>" + value);
        lore.add("");
        lore.add("<gray>Linksklick: <green>erhöhen");
        lore.add("<gray>Rechtsklick: <red>verringern");
        lore.add("<gray>Shift: <white>5x so große Schritte");
        for (String x : extra) lore.add(x);
        return named(m, title, lore.toArray(new String[0]));
    }

    private ItemStack toggleItem(String title, boolean on, String... desc) {
        List<String> lore = new ArrayList<>();
        lore.add(on ? "<gray>Status: <green><bold>AN" : "<gray>Status: <red><bold>AUS");
        lore.add("");
        for (String d : desc) lore.add(d);
        lore.add("");
        lore.add("<yellow>Klick zum Umschalten");
        return named(on ? Material.LIME_DYE : Material.RED_DYE, title, lore.toArray(new String[0]));
    }

    private void renderMenu(Inventory inv) {
        ItemStack dark = named(Material.BLACK_STAINED_GLASS_PANE, "<dark_gray> ");
        ItemStack purple = named(Material.PURPLE_STAINED_GLASS_PANE, "<dark_purple> ");
        for (int i = 0; i < 54; i++) inv.setItem(i, (i < 9 || i >= 45) ? purple : dark);

        // Status
        List<String> st = new ArrayList<>();
        st.add("<gray>Phase: <white>" + phaseName());
        st.add("<gray>Runde: <white>" + round);
        st.add("<gray>Lebende Spieler: <green>" + alive.size());
        if (ticker != null) st.add("<gray>Timer: <yellow>" + fmt(remaining) + (paused ? " <red>(pausiert)" : ""));
        st.add("<gray>Border: <white>" + (int) world().getWorldBorder().getSize() + " Blöcke");
        inv.setItem(4, named(Material.CLOCK, "<gradient:#ffd000:#ff6a00><bold>Status", st.toArray(new String[0])));

        // Aktionen
        boolean idle = phase == Phase.IDLE;
        inv.setItem(10, named(Material.EMERALD, "<green><bold>Spiel starten",
                "<gray>Startet den 5-Sekunden-Countdown.", "<gray>Mindestens 2 Spieler nötig.", "",
                idle ? "<yellow>Klick zum Starten" : "<red>Das Spiel läuft bereits"));

        if (phase == Phase.HUNT) {
            inv.setItem(11, named(Material.ARROW, "<gold><bold>Timer überspringen",
                    "<gray>Aktuell: <white>" + phaseName(), "",
                    "<gray>Klick: <green>Jagd ohne Strafe beenden",
                    "<gray>Shift+Klick: <red>Jagd MIT Auswertung", "<dark_red>(wer sein Ziel nicht hat, stirbt!)"));
        } else {
            inv.setItem(11, named(Material.ARROW, "<gold><bold>Timer überspringen",
                    "<gray>Aktuell: <white>" + phaseName(), "",
                    "<gray>Springt zur nächsten Phase.", "", "<yellow>Klick zum Überspringen"));
        }

        inv.setItem(12, named(Material.REPEATER, "<gold><bold>Zeit anpassen",
                "<gray>Linksklick: <green>+1 Minute", "<gray>Rechtsklick: <red>-1 Minute", "<gray>Shift: <white>5 Minuten", "",
                ticker != null ? "<gray>Aktuell: <yellow>" + fmt(remaining) : "<dark_gray>Kein Timer aktiv"));

        inv.setItem(13, named(Material.LEVER, paused ? "<green><bold>Timer fortsetzen" : "<yellow><bold>Timer pausieren",
                "<gray>Hält den aktuellen Timer an.", "", ticker != null ? "<yellow>Klick zum Umschalten" : "<dark_gray>Kein Timer aktiv"));

        inv.setItem(14, named(Material.BARRIER, "<red><bold>Spiel stoppen",
                "<gray>Bricht alles ab, setzt die Border", "<gray>zurück und nimmt alle Kompasse."));

        inv.setItem(15, named(Material.COMPASS, "<aqua><bold>Kompasse neu geben",
                "<gray>Gibt allen aktiven Jägern", "<gray>ihren Kompass zurück."));

        inv.setItem(16, named(Material.SPYGLASS, "<light_purple><bold>Ziele anzeigen",
                "<gray>Zeigt dir im Chat, wer wen jagt.", "<dark_gray>(nur für dich sichtbar)"));

        // Einstellungen
        FileConfigurationShim c = new FileConfigurationShim(getConfig());
        inv.setItem(19, numItem(Material.CLOCK, "<gold><bold>Wartezeit bis 1. Kopfgeld", fmt(c.i("initial-wait-seconds", 300)),
                "", "<dark_gray>Schritt: 1 Minute"));
        inv.setItem(20, numItem(Material.DIAMOND_SWORD, "<red><bold>Jagdzeit", fmt(c.i("hunt-seconds", 900)),
                "", "<dark_gray>Zeit zum Killen des Ziels", "<dark_gray>Schritt: 1 Minute"));
        inv.setItem(21, numItem(Material.ANVIL, "<yellow><bold>Pausen-Zuwachs", "+" + fmt(c.i("wait-increase-seconds", 300)),
                "", "<dark_gray>So viel länger wird die Pause", "<dark_gray>nach jedem Kopfgeld", "<dark_gray>Schritt: 1 Minute"));
        inv.setItem(22, numItem(Material.MAP, "<aqua><bold>Border-Endgröße", c.i("border-end-size", 3000) + " Blöcke",
                "", "<dark_gray>Gilt ab dem nächsten Start", "<dark_gray>Schritt: 500 Blöcke"));
        inv.setItem(23, numItem(Material.FEATHER, "<green><bold>Border-Öffnungszeit", c.i("border-expand-seconds", 15) + " Sek.",
                "", "<dark_gray>So lange wächst die Border", "<dark_gray>Schritt: 5 Sekunden"));
        inv.setItem(24, toggleItem("<red><bold>Bann bei verfehltem Ziel", c.b("ban-on-fail", true),
                "<gray>Wer sein Ziel nicht killt, stirbt", "<gray>und wird gebannt."));
        inv.setItem(25, toggleItem("<dark_red><bold>Bann bei jedem Tod", c.b("ban-on-any-death", false),
                "<gray>Wer stirbt, wird auch gebannt", "<gray>(sonst nur Zuschauer)."));
        inv.setItem(26, toggleItem("<gold><bold>Tod = ausgeschieden", c.b("eliminate-on-death", true),
                "<gray>Wer stirbt, ist raus und", "<gray>schaut nur noch zu."));

        // Extras
        inv.setItem(28, named(Material.ENDER_EYE, "<light_purple><bold>Border jetzt öffnen",
                "<gray>Setzt die Border auf die Endgröße.", "", "<yellow>Klick zum Öffnen"));
        inv.setItem(29, named(Material.IRON_BARS, "<gray><bold>Border zurücksetzen",
                "<gray>Setzt die Border sofort auf", "<gray>" + c.i("border-start-size", 50) + " Blöcke zurück.", "", "<yellow>Klick zum Zurücksetzen"));
        inv.setItem(30, named(Material.GOLDEN_APPLE, "<gold><bold>Alle heilen",
                "<gray>Heilt und sättigt alle lebenden", "<gray>Spieler voll."));

        inv.setItem(31, toggleItem("<red><bold>Herzschlag-Warnung", c.b("proximity-warning", true),
                "<gray>Gejagte hören einen Herzschlag, wenn", "<gray>ihr Jäger in der Nähe ist."));
        inv.setItem(32, toggleItem("<gold><bold>Trophäe & Belohnung", c.b("kill-reward", true),
                "<gray>Wer sein Ziel killt, bekommt dessen", "<gray>Kopf, goldene Äpfel und Buffs."));
        inv.setItem(34, toggleItem("<yellow><bold>Letzte-Minute-Leuchten", c.b("last-minute-glow", true),
                "<gray>In der letzten Minute der Jagd", "<gray>leuchten alle, die noch gejagt werden."));

        List<String> names = new ArrayList<>();
        for (UUID id : alive) names.add("<gray>• <white>" + nameOf(id));
        if (names.isEmpty()) names.add("<dark_gray>Niemand");
        inv.setItem(33, named(Material.PLAYER_HEAD, "<green><bold>Lebende Spieler (" + alive.size() + ")", names.toArray(new String[0])));

        inv.setItem(49, named(Material.BARRIER, "<red><bold>Schließen"));
    }

    /** Kleiner Helfer, damit das Menue kurz bleibt. */
    private static final class FileConfigurationShim {
        private final org.bukkit.configuration.file.FileConfiguration cfg;

        FileConfigurationShim(org.bukkit.configuration.file.FileConfiguration cfg) { this.cfg = cfg; }

        int i(String key, int def) { return cfg.getInt(key, def); }

        boolean b(String key, boolean def) { return cfg.getBoolean(key, def); }
    }

    private void adjust(String key, int def, int step, int min, int max, ClickType c) {
        int mult = c.isShiftClick() ? 5 : 1;
        int v = getConfig().getInt(key, def);
        if (c.isLeftClick()) v += step * mult;
        else if (c.isRightClick()) v -= step * mult;
        else return;
        v = Math.max(min, Math.min(max, v));
        getConfig().set(key, v);
        saveConfig();
    }

    private void toggle(String key, boolean def) {
        getConfig().set(key, !getConfig().getBoolean(key, def));
        saveConfig();
    }

    private void handleMenuClick(Player p, int slot, ClickType click) {
        if (!click.isLeftClick() && !click.isRightClick()) return;
        switch (slot) {
            case 10 -> {
                if (phase == Phase.IDLE) beginCountdown(p);
                else p.sendMessage(mm(PREFIX + "<red>Das Spiel läuft bereits!"));
            }
            case 11 -> p.sendMessage(mm(PREFIX + skip(click.isShiftClick())));
            case 12 -> {
                int delta = click.isShiftClick() ? 300 : 60;
                if (!adjustTime(click.isLeftClick() ? delta : -delta)) p.sendMessage(mm(PREFIX + "<red>Gerade läuft kein Timer."));
            }
            case 13 -> p.sendMessage(mm(PREFIX + togglePause()));
            case 14 -> {
                hardReset(true);
                p.sendMessage(mm(PREFIX + "Spiel abgebrochen und zurückgesetzt."));
            }
            case 15 -> {
                if (phase == Phase.HUNT) {
                    giveCompasses();
                    p.sendMessage(mm(PREFIX + "<green>Kompasse verteilt."));
                } else {
                    p.sendMessage(mm(PREFIX + "<red>Kompasse gibt es nur während der Jagd."));
                }
            }
            case 16 -> sendTargets(p);
            case 19 -> adjust("initial-wait-seconds", 300, 60, 10, 7200, click);
            case 20 -> adjust("hunt-seconds", 900, 60, 30, 7200, click);
            case 21 -> adjust("wait-increase-seconds", 300, 60, 0, 3600, click);
            case 22 -> adjust("border-end-size", 3000, 500, 100, 59999968, click);
            case 23 -> adjust("border-expand-seconds", 15, 5, 1, 600, click);
            case 24 -> toggle("ban-on-fail", true);
            case 25 -> toggle("ban-on-any-death", false);
            case 26 -> toggle("eliminate-on-death", true);
            case 28 -> {
                world().getWorldBorder().setSize(getConfig().getDouble("border-end-size", 3000),
                        getConfig().getLong("border-expand-seconds", 15));
                p.sendMessage(mm(PREFIX + "<green>Die Border öffnet sich."));
            }
            case 29 -> {
                resetBorder();
                p.sendMessage(mm(PREFIX + "<green>Border zurückgesetzt."));
            }
            case 30 -> {
                for (Player a : alivePlayersOnline()) {
                    a.setHealth(20.0);
                    a.setFoodLevel(20);
                    a.setSaturation(20f);
                }
                p.sendMessage(mm(PREFIX + "<green>Alle lebenden Spieler wurden geheilt."));
            }
            case 31 -> toggle("proximity-warning", true);
            case 32 -> toggle("kill-reward", true);
            case 34 -> toggle("last-minute-glow", true);
            case 49 -> {
                p.closeInventory();
                return;
            }
            default -> { return; }
        }
        snd(p, Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
        Inventory top = p.getOpenInventory().getTopInventory();
        if (top.getHolder() instanceof MenuHolder) renderMenu(top);
    }

    private void sendTargets(Player p) {
        if (targets.isEmpty()) {
            p.sendMessage(mm(PREFIX + "<gray>Es gibt noch keine Ziele."));
            return;
        }
        p.sendMessage(mm(PREFIX + "<gold>Wer jagt wen:"));
        for (Map.Entry<UUID, UUID> e : targets.entrySet()) {
            String mark = done.contains(e.getKey()) ? " <green>✔" : "";
            String dead = alive.contains(e.getKey()) ? "" : " <dark_red>(raus)";
            p.sendMessage(mm("<gray>• <white>" + nameOf(e.getKey()) + " <dark_gray>» <red>" + nameOf(e.getValue()) + mark + dead));
        }
    }

    // ------------------------------------------------------------------ Events

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (phase == Phase.IDLE) return;
        if (ticker != null) p.showBossBar(bar);
        if (showBar != null) p.showBossBar(showBar);
        if (!alive.contains(p.getUniqueId()) || out.contains(p.getUniqueId())) {
            if (phase != Phase.ENDED) {
                p.setGameMode(GameMode.SPECTATOR);
                p.sendMessage(mm(PREFIX + "<gray>Das Spiel läuft schon - du schaust zu."));
            }
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (phase == Phase.IDLE) return;
        if (out.contains(p.getUniqueId())) {
            Bukkit.getScheduler().runTask(this, () -> {
                if (p.isOnline() && phase != Phase.IDLE) p.setGameMode(GameMode.SPECTATOR);
            });
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof MenuHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!p.hasPermission("kopfgeld.admin")) return;
        if (e.getClickedInventory() != e.getInventory()) return;
        handleMenuClick(p, e.getRawSlot(), e.getClick());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof MenuHolder) e.setCancelled(true);
    }
}
