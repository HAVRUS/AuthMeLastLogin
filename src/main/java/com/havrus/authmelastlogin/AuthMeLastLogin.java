package com.havrus.authmelastlogin;

import com.mysql.cj.jdbc.Driver;
import fr.xephi.authme.events.LoginEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.util.Tristate;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class AuthMeLastLogin extends JavaPlugin implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private String jdbcUrl;
    private String dbUser;
    private String dbPassword;
    private String table;
    private String usernameColumn;
    private String lastLoginColumn;

    private String headerFormat;
    private String lineFormat;
    private String neverFormat;
    private int limit;
    private long delayTicks;
    private DateTimeFormatter dateFormatter;
    private volatile boolean motdEnabled;
    private FileConfiguration lang;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadLanguage(getConfig().getString("language", "en"));
        loadSettings();

        // Register the (relocated/shaded) MySQL driver explicitly,
        // since we're not relying on ServiceLoader auto-discovery.
        try {
            DriverManager.registerDriver(new Driver());
        } catch (SQLException e) {
            getLogger().severe("Could not register the MySQL JDBC driver: " + e.getMessage());
        }

        Bukkit.getPluginManager().registerEvents(this, this);

        var command = getCommand("authmelastlogin");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        } else {
            getLogger().severe("Could not register /authmelastlogin - check plugin.yml.");
        }

        getLogger().info("AuthMeLastLogin enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("AuthMeLastLogin disabled.");
    }

    private void loadSettings() {
        FileConfiguration cfg = getConfig();

        String host = cfg.getString("database.host", "localhost");
        int port = cfg.getInt("database.port", 3306);
        String database = cfg.getString("database.database", "authme");
        dbUser = cfg.getString("database.username", "authme");
        dbPassword = cfg.getString("database.password", "");
        table = sanitizeIdentifier(cfg.getString("database.table", "authme"));
        usernameColumn = sanitizeIdentifier(cfg.getString("database.username-column", "username"));
        lastLoginColumn = sanitizeIdentifier(cfg.getString("database.lastlogin-column", "lastlogin"));
        String sslMode = mapSslMode(cfg.getString("database.ssl-mode", "disable"));

        // allowPublicKeyRetrieval=true avoids "Public Key Retrieval is not
        // allowed" errors with caching_sha2_password auth (MySQL 8 default)
        // when SSL is off - harmless to leave on even when SSL is enabled.
        jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?sslMode=" + sslMode
                + "&allowPublicKeyRetrieval=true";

        limit = cfg.getInt("message.limit", 0);
        int delaySeconds = cfg.getInt("message.delay-seconds", 2);
        delayTicks = delaySeconds * 20L; // Paper runs 20 ticks/second

        String pattern = cfg.getString("message.date-pattern", "yyyy-MM-dd HH:mm:ss");
        dateFormatter = DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault());

        motdEnabled = cfg.getBoolean("motd.enabled", true);

        headerFormat = msg("last-login.header", "&6=== Last Login Times ===");
        lineFormat = msg("last-login.line", "&f%time%&7: &e%player%");
        neverFormat = msg("last-login.never", "&fnever logged in&7: &e%player%");
    }

    // Extracts the bundled language files into plugins/AuthMeLastLogin/lang/
    // (without overwriting any the admin has already customized), then
    // loads whichever one "language" in config.yml selects. Falls back to
    // a bundled resource directly, then to English, if the requested
    // language isn't found anywhere.
    private void loadLanguage(String language) {
        for (String bundled : new String[]{"en", "ru"}) {
            saveResource("lang/" + bundled + ".yml", false);
        }

        File langFile = new File(getDataFolder(), "lang/" + language + ".yml");
        if (langFile.exists()) {
            lang = YamlConfiguration.loadConfiguration(langFile);
            return;
        }

        try (InputStream stream = getResource("lang/" + language + ".yml")) {
            if (stream != null) {
                lang = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
                return;
            }
        } catch (Exception ignored) {
            // Fall through to the English default below.
        }

        getLogger().warning("Language '" + language + "' not found (no lang/" + language
                + ".yml in the plugin's data folder or jar). Falling back to English.");
        try (InputStream stream = getResource("lang/en.yml")) {
            lang = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (Exception e) {
            getLogger().severe("Could not load the bundled English language file either: " + e.getMessage());
            lang = new YamlConfiguration(); // msg() will fall back to its own defaults below
        }
    }

    // Looks up a player-facing string from the active language file,
    // falling back to the given default if the key is missing (e.g. an
    // admin's custom language file predates a newer message key).
    private String msg(String key, String fallback) {
        return lang.getString(key, fallback);
    }

    // Translates the config's familiar (Postgres-style) ssl-mode values into
    // MySQL Connector/J 8.x's own sslMode parameter values.
    private String mapSslMode(String configValue) {
        return switch (configValue.toLowerCase()) {
            case "disable" -> "DISABLED";
            case "prefer" -> "PREFERRED";
            case "require" -> "REQUIRED";
            case "verify-ca" -> "VERIFY_CA";
            case "verify-full" -> "VERIFY_IDENTITY";
            default -> throw new IllegalArgumentException(
                    "Invalid database.ssl-mode in config.yml: " + configValue);
        };
    }

    // Table/column names come from our own config, not user chat input, but
    // we still keep this narrow (letters, digits, underscore only) since
    // they get concatenated into SQL rather than bound as parameters.
    private String sanitizeIdentifier(String identifier) {
        if (identifier == null || !identifier.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Invalid table/column name in config.yml: " + identifier);
        }
        return identifier;
    }

    // AuthMe fires this only after the player has successfully logged in
    // (or auto-registered), not the moment they connect.
    @EventHandler
    public void onAuthMeLogin(LoginEvent event) {
        if (!motdEnabled) {
            return;
        }
        Player player = event.getPlayer();

        // Wait the configured delay, then kick off the (async) DB query.
        // Both the delay and the DB call happen off the main thread so
        // neither one blocks the server.
        Bukkit.getScheduler().runTaskLaterAsynchronously(this,
                () -> fetchAndSend(player), delayTicks);
    }

    // /authmelastlogin (alias /amlo) <show | motd enable | motd disable | reload>
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("authmelastlogin")) {
            return false;
        }

        if (args.length == 0) {
            sendUsage(sender, label);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "show" -> handleShow(sender);
            case "motd" -> handleMotd(sender, label, args);
            case "reload" -> handleReload(sender);
            default -> sendUsage(sender, label);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("show", "motd", "reload").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("motd")) {
            return List.of("enable", "disable").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .toList();
        }
        return List.of();
    }

    private void sendUsage(CommandSender sender, String label) {
        sender.sendMessage(toComponent(msg("command.usage-show", "&cUsage: /%label% show")
                .replace("%label%", label)));
        sender.sendMessage(toComponent(msg("command.usage-motd", "&cUsage: /%label% motd <enable|disable>")
                .replace("%label%", label)));
        sender.sendMessage(toComponent(msg("command.usage-reload", "&cUsage: /%label% reload")
                .replace("%label%", label)));
    }

    private void handleShow(CommandSender sender) {
        if (!hasPermission(sender, "authmelastlogin.command.show")) {
            sender.sendMessage(toComponent(msg("command.no-permission", "&cYou don't have permission to do that.")));
            return;
        }
        // No delay for the on-demand command - only the automatic
        // post-login message waits.
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> fetchAndSend(sender));
    }

    private void handleMotd(CommandSender sender, String label, String[] args) {
        if (!hasPermission(sender, "authmelastlogin.command.motd")) {
            sender.sendMessage(toComponent(msg("command.no-permission", "&cYou don't have permission to do that.")));
            return;
        }
        if (args.length < 2 || !(args[1].equalsIgnoreCase("enable") || args[1].equalsIgnoreCase("disable"))) {
            sender.sendMessage(toComponent(msg("command.usage-motd", "&cUsage: /%label% motd <enable|disable>")
                    .replace("%label%", label)));
            return;
        }

        boolean enable = args[1].equalsIgnoreCase("enable");
        motdEnabled = enable;
        getConfig().set("motd.enabled", enable);
        saveConfig();

        sender.sendMessage(toComponent(enable
                ? msg("command.motd-enabled", "&aThe automatic login message is now &lenabled&r&a.")
                : msg("command.motd-disabled", "&cThe automatic login message is now &ldisabled&r&c.")));
    }

    private void handleReload(CommandSender sender) {
        if (!hasPermission(sender, "authmelastlogin.command.reload")) {
            sender.sendMessage(toComponent(msg("command.no-permission", "&cYou don't have permission to do that.")));
            return;
        }

        reloadConfig();
        loadLanguage(getConfig().getString("language", "en"));
        loadSettings();

        sender.sendMessage(toComponent(msg("command.reload-success", "&aConfiguration and language file reloaded.")));
    }

    // Checks the permission via LuckPerms's own API first (if LuckPerms is
    // installed and the sender is a player), falling back to the normal
    // Bukkit permission check otherwise. In practice this gives the same
    // result as sender.hasPermission(...) whenever LuckPerms is the active
    // permission provider (which it registers itself as), but this makes
    // the LuckPerms integration explicit rather than incidental, and still
    // works correctly with any other permissions plugin or vanilla OP.
    private boolean hasPermission(CommandSender sender, String node) {
        if (sender instanceof Player player) {
            var luckPermsPlugin = Bukkit.getPluginManager().getPlugin("LuckPerms");
            if (luckPermsPlugin != null && luckPermsPlugin.isEnabled()) {
                try {
                    LuckPerms api = LuckPermsProvider.get();
                    User user = api.getUserManager().getUser(player.getUniqueId());
                    if (user != null) {
                        Tristate result = user.getCachedData().getPermissionData().checkPermission(node);
                        if (result != Tristate.UNDEFINED) {
                            return result.asBoolean();
                        }
                    }
                } catch (IllegalStateException e) {
                    // LuckPerms plugin present but its API isn't ready yet -
                    // fall through to the standard Bukkit check below.
                }
            }
        }
        return sender.hasPermission(node);
    }

    private void fetchAndSend(CommandSender sender) {
        List<String> lines = new ArrayList<>();

        StringBuilder sql = new StringBuilder("SELECT ")
                .append(usernameColumn).append(", ").append(lastLoginColumn)
                .append(" FROM ").append(table)
                .append(" ORDER BY ").append(lastLoginColumn).append(" DESC");
        if (limit > 0) {
            sql.append(" LIMIT ").append(limit);
        }

        try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword);
             PreparedStatement stmt = conn.prepareStatement(sql.toString());
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                String name = rs.getString(1);
                long lastLoginMillis = rs.getLong(2);
                boolean isNull = rs.wasNull();

                if (isNull || lastLoginMillis <= 0) {
                    lines.add(neverFormat.replace("%player%", name));
                } else {
                    String time = dateFormatter.format(Instant.ofEpochMilli(lastLoginMillis));
                    lines.add(lineFormat.replace("%player%", name).replace("%time%", time));
                }
            }
        } catch (SQLException e) {
            getLogger().severe("Failed to query the AuthMe database: " + e.getMessage());
            return;
        }

        Bukkit.getScheduler().runTask(this, () -> sendToSender(sender, lines));
    }

    private void sendToSender(CommandSender sender, List<String> lines) {
        // A player may have disconnected during the delay/DB call.
        // Console/command-block senders are always "present".
        if (sender instanceof Player player && !player.isOnline()) {
            return;
        }
        sender.sendMessage(toComponent(headerFormat));
        for (String line : lines) {
            sender.sendMessage(toComponent(line));
        }
    }

    private Component toComponent(String legacyText) {
        return LEGACY.deserialize(legacyText);
    }
}
