
package ru.praydein.atwdonat;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class AtwDonatTp extends JavaPlugin implements Listener {
    private final Map<UUID, Set<UUID>> blocked = new HashMap<>();
    private final Map<UUID, Set<UUID>> exceptions = new HashMap<>();
    private final Set<UUID> blockAll = new HashSet<>();
    private final Map<UUID, Long> lastTeleport = new HashMap<>();
    // Кэш имён: ник в нижнем регистре -> UUID. Заполняется из базы
    // при запуске и при каждом заходе игрока. Поиск — мгновенный,
    // без переборов и обращений к диску.
    private final Map<String, UUID> nameToUuid = new HashMap<>();
    private Connection database;
    private boolean teleportDisabled;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            Class.forName("org.sqlite.JDBC");
            database = DriverManager.getConnection("jdbc:sqlite:" + new File(getDataFolder(), "data.db").getAbsolutePath());
            try (Statement statement = database.createStatement()) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value INTEGER NOT NULL)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS block_all (owner TEXT PRIMARY KEY)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS player_rules (owner TEXT NOT NULL, visitor TEXT NOT NULL, allowed INTEGER NOT NULL, PRIMARY KEY (owner, visitor))");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS player_names (uuid TEXT PRIMARY KEY, name TEXT NOT NULL)");
            }
            migrateLegacyData();
            loadState();
        } catch (Exception ex) {
            getLogger().severe("Не удалось открыть SQLite: " + ex.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        if (database != null) {
            try {
                database.close();
            } catch (SQLException ex) {
                getLogger().severe("Не удалось закрыть SQLite: " + ex.getMessage());
            }
        }
    }

    private void execute(String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = database.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
            statement.executeUpdate();
        }
    }

    private void migrateLegacyData() throws SQLException {
        try (Statement statement = database.createStatement();
             ResultSet result = statement.executeQuery("SELECT value FROM settings WHERE key = 'migrated'")) {
            if (result.next()) return;
        }
        File file = new File(getDataFolder(), "data.yml");
        YamlConfiguration legacy = YamlConfiguration.loadConfiguration(file);
        database.setAutoCommit(false);
        try {
            execute("INSERT OR REPLACE INTO settings VALUES ('teleport-disabled', ?)", legacy.getBoolean("teleport-disabled", false) ? 1 : 0);
            ConfigurationSection players = legacy.getConfigurationSection("players");
            if (players != null) {
                for (String key : players.getKeys(false)) {
                    UUID owner;
                    try {
                        owner = UUID.fromString(key);
                    } catch (IllegalArgumentException ex) {
                        getLogger().warning("Некорректный UUID в data.yml: " + key);
                        continue;
                    }
                    if (players.getBoolean(key + ".block-all")) execute("INSERT OR IGNORE INTO block_all VALUES (?)", owner.toString());
                    for (String list : new String[] {"blocked", "exceptions"}) {
                        for (String value : players.getStringList(key + "." + list)) {
                            try {
                                String visitor = UUID.fromString(value).toString();
                                // Запрет имеет приоритет, если в legacy-данных есть оба правила.
                                execute("INSERT OR IGNORE INTO player_rules VALUES (?, ?, ?)", owner.toString(), visitor, list.equals("exceptions") ? 1 : 0);
                            } catch (IllegalArgumentException ex) {
                                getLogger().warning("Некорректный UUID в data.yml: " + value);
                            }
                        }
                    }
                }
            }
            execute("INSERT INTO settings VALUES ('migrated', 1)");
            database.commit();
        } catch (SQLException ex) {
            database.rollback();
            throw ex;
        } finally {
            database.setAutoCommit(true);
        }
    }

    private void loadState() throws SQLException {
        try (Statement statement = database.createStatement(); ResultSet result = statement.executeQuery("SELECT value FROM settings WHERE key = 'teleport-disabled'")) {
            teleportDisabled = result.next() && result.getInt(1) != 0;
        }
        try (Statement statement = database.createStatement(); ResultSet result = statement.executeQuery("SELECT owner FROM block_all")) {
            while (result.next()) blockAll.add(UUID.fromString(result.getString(1)));
        }
        try (Statement statement = database.createStatement(); ResultSet result = statement.executeQuery("SELECT owner, visitor, allowed FROM player_rules")) {
            while (result.next()) {
                UUID owner = UUID.fromString(result.getString(1));
                UUID visitor = UUID.fromString(result.getString(2));
                Map<UUID, Set<UUID>> rules = result.getInt(3) == 0 ? blocked : exceptions;
                rules.computeIfAbsent(owner, id -> new HashSet<UUID>()).add(visitor);
            }
        }
        try (Statement statement = database.createStatement(); ResultSet result = statement.executeQuery("SELECT uuid, name FROM player_names")) {
            while (result.next()) {
                try {
                    nameToUuid.put(result.getString(2).toLowerCase(Locale.ROOT), UUID.fromString(result.getString(1)));
                } catch (IllegalArgumentException ex) {
                    getLogger().warning("Некорректный UUID в базе имён: " + result.getString(1));
                }
            }
        }
    }

    private boolean updateRules(CommandSender sender, UUID owner, UUID visitor, boolean deny) {
        try {
            database.setAutoCommit(false);
            if (visitor == null) {
                if (deny) {
                    execute("INSERT OR IGNORE INTO block_all VALUES (?)", owner.toString());
                    execute("DELETE FROM player_rules WHERE owner = ? AND allowed = 1", owner.toString());
                } else {
                    execute("DELETE FROM block_all WHERE owner = ?", owner.toString());
                    execute("DELETE FROM player_rules WHERE owner = ?", owner.toString());
                }
            } else if (deny || blockAll.contains(owner)) {
                execute("INSERT OR REPLACE INTO player_rules VALUES (?, ?, ?)", owner.toString(), visitor.toString(), deny ? 0 : 1);
            } else {
                execute("DELETE FROM player_rules WHERE owner = ? AND visitor = ?", owner.toString(), visitor.toString());
            }
            database.commit();
            return true;
        } catch (SQLException ex) {
            try { database.rollback(); } catch (SQLException rollback) { getLogger().severe("Ошибка отката SQLite: " + rollback.getMessage()); }
            getLogger().severe("Не удалось сохранить правила SQLite: " + ex.getMessage());
            message(sender, "storage-error");
            return false;
        } finally {
            try { database.setAutoCommit(true); } catch (SQLException ex) { getLogger().severe("Ошибка SQLite: " + ex.getMessage()); }
        }
    }

    private void message(CommandSender sender, String key, String... replacements) {
        String text = getConfig().getString("messages.prefix", "") + getConfig().getString("messages." + key, "");
        for (int i = 0; i + 1 < replacements.length; i += 2) text = text.replace(replacements[i], replacements[i + 1]);
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', text));
    }

    /**
     * Перехватываем /tp на LOWEST: наш обработчик срабатывает ПЕРЕД
     * остальными плагинами и отменяет команду, так что чужие обработчики
     * (warmup-телепорты и пр.) с отменённым событием уже не выполняются —
     * именно они давали секундную задержку на несуществующих никах.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String input = event.getMessage().substring(1).trim();
        if (input.isEmpty()) return;
        String[] parts = input.split("\\s+");
        if (!parts[0].equalsIgnoreCase("tp")) return;
        event.setCancelled(true);
        String[] args = new String[parts.length - 1];
        System.arraycopy(parts, 1, args, 0, args.length);
        teleport(event.getPlayer(), args, false);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        String name = event.getPlayer().getName();
        nameToUuid.put(name.toLowerCase(Locale.ROOT), id);
        try {
            execute("INSERT OR REPLACE INTO player_names VALUES (?, ?)", id.toString(), name);
        } catch (SQLException ex) {
            getLogger().warning("Не удалось сохранить имя игрока: " + ex.getMessage());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("tpdonblock")) {
            if (!sender.hasPermission("atwdon.admin.tpdonblock")) message(sender, "no-permission");
            else if (args.length != 0) message(sender, "admin-usage");
            else {
                try {
                    execute("INSERT OR REPLACE INTO settings VALUES ('teleport-disabled', ?)", teleportDisabled ? 0 : 1);
                } catch (SQLException ex) {
                    getLogger().severe("Не удалось сохранить состояние SQLite: " + ex.getMessage());
                    message(sender, "storage-error");
                    return true;
                }
                teleportDisabled = !teleportDisabled;
                message(sender, teleportDisabled ? "admin-disabled" : "admin-enabled");
            }
            return true;
        }
        if (!(sender instanceof Player)) {
            message(sender, "players-only");
            return true;
        }
        Player player = (Player) sender;
        if (name.equals("tp") || name.equals("htp")) {
            teleport(player, args, name.equals("htp"));
        } else if (name.equals("tpblock") || name.equals("tptrue")) {
            changeBlock(player, args, name.equals("tpblock"));
        }
        return true;
    }

    /**
     * Телепорт только к игрокам в сети: getPlayerExact — это поиск
     * по внутренней карте Bukkit, выполняется мгновенно.
     * Оффлайн или несуществующий ник — сразу «player-not-found».
     */
    private void teleport(Player player, String[] args, boolean admin) {
        if (!player.hasPermission(admin ? "atwdon.admin.htp" : "atwdon.tp")) {
            message(player, "no-permission");
            return;
        }
        if (args.length != 1) {
            message(player, admin ? "htp-usage" : "tp-usage");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            message(player, "player-not-found");
            return;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            message(player, "self-target");
            return;
        }
        if (!admin) {
            if (teleportDisabled) {
                message(player, "teleport-disabled");
                return;
            }
            UUID owner = target.getUniqueId();
            UUID visitor = player.getUniqueId();
            if (blocked.getOrDefault(owner, new HashSet<UUID>()).contains(visitor)
                    || (blockAll.contains(owner) && !exceptions.getOrDefault(owner, new HashSet<UUID>()).contains(visitor))) {
                message(player, "target-blocked");
                return;
            }
            if (!player.hasPermission("atwdon.admin.blockworld")) {
                if (containsWorld(getConfig().getStringList("blocked-source-worlds"), player.getWorld().getName())) {
                    message(player, "source-world-blocked");
                    return;
                }
                if (containsWorld(getConfig().getStringList("blocked-destination-worlds"), target.getWorld().getName())) {
                    message(player, "destination-world-blocked");
                    return;
                }
            }
            long duration = Math.max(0L, getConfig().getLong("cooldown-seconds", 30L)) * 1000L;
            long remaining = duration - (System.currentTimeMillis() - lastTeleport.getOrDefault(visitor, 0L));
            if (remaining > 0) {
                message(player, "cooldown", "{seconds}", Long.toString((remaining + 999) / 1000));
                return;
            }
        }
        if (player.teleport(target.getLocation())) {
            if (!admin) lastTeleport.put(player.getUniqueId(), System.currentTimeMillis());
            message(player, "teleport-success", "{player}", target.getName());
        } else message(player, "teleport-failed");
    }

    private boolean containsWorld(List<String> worlds, String name) {
        for (String world : worlds) if (world.equalsIgnoreCase(name)) return true;
        return false;
    }

    private void changeBlock(Player player, String[] args, boolean deny) {
        if (!player.hasPermission(deny ? "atwdon.tpblock" : "atwdon.tpallow")) {
            message(player, "no-permission");
            return;
        }
        UUID owner = player.getUniqueId();

        // Специальный аргумент "-all" — применить ко всем.
        if (args.length == 1 && args[0].equalsIgnoreCase("-all")) {
            if (!updateRules(player, owner, null, deny)) return;
            if (deny) {
                blockAll.add(owner);
                exceptions.remove(owner);
                message(player, "blocked-all");
            } else {
                blockAll.remove(owner);
                blocked.remove(owner);
                exceptions.remove(owner);
                message(player, "allowed-all");
            }
            return;
        }

        // Команда без аргументов — только синтаксис.
        if (args.length == 0) {
            message(player, deny ? "block-usage" : "allow-usage");
            return;
        }

        if (args.length > 1) {
            message(player, "block-usage");
            return;
        }

        // Имя разрешаем по кэшу в памяти — без перебора getOfflinePlayers.
        UUID cached = nameToUuid.get(args[0].toLowerCase(Locale.ROOT));
        if (cached == null) {
            message(player, "player-not-found");
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(cached);
        UUID visitor = target.getUniqueId();
        if (owner.equals(visitor)) {
            message(player, "self-target");
            return;
        }
        if (!updateRules(player, owner, visitor, deny)) return;
        Set<UUID> denied = blocked.computeIfAbsent(owner, id -> new HashSet<UUID>());
        Set<UUID> allowed = exceptions.computeIfAbsent(owner, id -> new HashSet<UUID>());
        if (deny) {
            denied.add(visitor);
            allowed.remove(visitor);
        } else {
            denied.remove(visitor);
            if (blockAll.contains(owner)) allowed.add(visitor);
        }
        message(player, deny ? "blocked-player" : "allowed-player", "{player}", target.getName());
    }
}
