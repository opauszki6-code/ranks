package hu.donutrank;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class DonutRankPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final String TEAM_PREFIX = "dr_";
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Map<UUID, String> ranks = new HashMap<>();
    private File dataFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        loadData();

        getServer().getPluginManager().registerEvents(this, this);

        PluginCommand cmd = getCommand("donutrank");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            applyRank(p);
        }
    }

    @Override
    public void onDisable() {
        saveData();
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Team t : new ArrayList<>(sb.getTeams())) {
            if (t.getName().startsWith(TEAM_PREFIX)) {
                t.unregister();
            }
        }
    }

    // ---------- Adatkezelés ----------

    private void loadData() {
        ranks.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection sec = yml.getConfigurationSection("players");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            try {
                ranks.put(UUID.fromString(key), sec.getString(key, "").toLowerCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void saveData() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, String> e : ranks.entrySet()) {
            yml.set("players." + e.getKey(), e.getValue());
        }
        try {
            getDataFolder().mkdirs();
            yml.save(dataFile);
        } catch (IOException ex) {
            getLogger().severe("Nem sikerült menteni a data.yml-t: " + ex.getMessage());
        }
    }

    // ---------- Rang logika ----------

    private List<String> rankNames() {
        ConfigurationSection sec = getConfig().getConfigurationSection("ranks");
        return sec == null ? new ArrayList<>() : new ArrayList<>(sec.getKeys(false));
    }

    private Component prefixOf(String rank) {
        String raw = getConfig().getString("ranks." + rank + ".prefix", "");
        return mm.deserialize(raw);
    }

    private String teamName(String rank) {
        int prio = getConfig().getInt("ranks." + rank + ".priority", 50);
        String name = TEAM_PREFIX + prio + "_" + rank;
        return name.length() > 40 ? name.substring(0, 40) : name;
    }

    private void clearRank(Player p) {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Team t : sb.getTeams()) {
            if (t.getName().startsWith(TEAM_PREFIX) && t.hasEntry(p.getName())) {
                t.removeEntry(p.getName());
            }
        }
    }

    private void applyRank(Player p) {
        clearRank(p);
        String rank = ranks.get(p.getUniqueId());
        if (rank == null || !rankNames().contains(rank)) return;

        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        String tn = teamName(rank);
        Team team = sb.getTeam(tn);
        if (team == null) team = sb.registerNewTeam(tn);
        team.prefix(prefixOf(rank));
        team.color(NamedTextColor.WHITE);
        team.addEntry(p.getName());
    }

    private void refreshAll() {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Team t : new ArrayList<>(sb.getTeams())) {
            if (t.getName().startsWith(TEAM_PREFIX)) t.unregister();
        }
        for (Player p : Bukkit.getOnlinePlayers()) applyRank(p);
    }

    // ---------- Eventek ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        applyRank(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onChat(AsyncChatEvent e) {
        if (!getConfig().getBoolean("chat-prefix", true)) return;
        String rank = ranks.get(e.getPlayer().getUniqueId());
        if (rank == null || !rankNames().contains(rank)) return;

        Component prefix = prefixOf(rank);
        e.renderer((source, displayName, message, viewer) ->
                Component.empty()
                        .append(prefix)
                        .append(displayName)
                        .append(Component.text(": "))
                        .append(message));
    }

    // ---------- Parancsok ----------

    private void msg(CommandSender s, String text) {
        s.sendMessage(mm.deserialize("<gray>[<gold>DonutRank</gold>]</gray> " + text));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("donutrank.admin")) {
            msg(sender, "<red>Nincs jogosultságod ehhez!");
            return true;
        }
        if (args.length == 0) {
            msg(sender, "<yellow>/donutrank give <játékos> <rang>");
            msg(sender, "<yellow>/donutrank remove <játékos>");
            msg(sender, "<yellow>/donutrank list");
            msg(sender, "<yellow>/donutrank reload");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> {
                if (args.length < 3) {
                    msg(sender, "<red>Használat: /donutrank give <játékos> <rang>");
                    return true;
                }
                String rank = args[2].toLowerCase(Locale.ROOT);
                if (!rankNames().contains(rank)) {
                    msg(sender, "<red>Nincs ilyen rang! Elérhető: " + String.join(", ", rankNames()));
                    return true;
                }
                OfflinePlayer target = findPlayer(args[1]);
                if (target == null) {
                    msg(sender, "<red>Nem található ilyen játékos: " + args[1]);
                    return true;
                }
                ranks.put(target.getUniqueId(), rank);
                saveData();
                if (target.isOnline() && target.getPlayer() != null) applyRank(target.getPlayer());
                msg(sender, "<green>" + args[1] + " megkapta a(z) <white>" + rank + "</white> rangot.");
            }
            case "remove" -> {
                if (args.length < 2) {
                    msg(sender, "<red>Használat: /donutrank remove <játékos>");
                    return true;
                }
                OfflinePlayer target = findPlayer(args[1]);
                if (target == null) {
                    msg(sender, "<red>Nem található ilyen játékos: " + args[1]);
                    return true;
                }
                ranks.remove(target.getUniqueId());
                saveData();
                if (target.isOnline() && target.getPlayer() != null) clearRank(target.getPlayer());
                msg(sender, "<green>" + args[1] + " rangja eltávolítva.");
            }
            case "list" -> {
                msg(sender, "<yellow>Elérhető rangok:");
                for (String r : rankNames()) {
                    sender.sendMessage(Component.text(" - " + r + " ").append(prefixOf(r)));
                }
            }
            case "reload" -> {
                reloadConfig();
                loadData();
                refreshAll();
                msg(sender, "<green>Újratöltve!");
            }
            default -> msg(sender, "<red>Ismeretlen alparancs.");
        }
        return true;
    }

    private OfflinePlayer findPlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        return Bukkit.getOfflinePlayerIfCached(name);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("donutrank.admin")) return out;

        if (args.length == 1) {
            for (String s : List.of("give", "remove", "list", "reload")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("remove"))) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(p.getName());
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            for (String r : rankNames()) {
                if (r.startsWith(args[2].toLowerCase(Locale.ROOT))) out.add(r);
            }
        }
        return out;
    }
}
