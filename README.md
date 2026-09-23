# AuthMeLastLogin

A Paper plugin that reads directly from AuthMe's MySQL database (no
AuthMe API calls for the data itself — just a JDBC read) and privately
sends a player a list of every registered player's last-login time —
either automatically, 2 seconds after they log in via AuthMe, or on
demand via `/authmelastonline show` (alias `/amlo show`). Admins can turn
the automatic message on or off server-wide with
`/authmelastonline motd <enable|disable>`. All in-game text is
translatable — see [Translations](#translations) below.

## How it works

1. A player successfully logs in via AuthMe (`fr.xephi.authme.events.LoginEvent`
   — fires after authentication, not on raw connect).
2. The plugin waits `message.delay-seconds` (default 2s), off the main
   thread, then opens a JDBC connection to your MySQL database and runs:
   ```sql
   SELECT username, lastlogin FROM authme ORDER BY lastlogin DESC
   ```
   (table/column names are configurable — see below).
3. Back on the main thread, it sends a header line plus one line per
   player — **only to the player who just logged in**, not to global
   chat — e.g.:
   ```
   === Last Login Times ===
   2026-09-20 14:03:11: Steve
   2026-09-18 09:47:02: RockInRoll
   never logged in: Alex
   ```
   If the player disconnects during the delay/query, the message is
   silently dropped instead of erroring.

`/authmelastonline show` runs the same query and sends the same style of
message immediately (no `delay-seconds` wait) to whoever ran the command.

## Commands & permissions

| Command | Permission node | Default | What it does |
|---|---|---|---|
| `/authmelastonline show` (or `/amlo show`) | `authmelastlogin.command.show` | everyone | Sends the last-login list to whoever ran it, immediately (no delay) |
| `/authmelastonline motd enable` \| `disable` (or `/amlo motd ...`) | `authmelastlogin.command.motd` | OP only | Turns the automatic post-login message on/off server-wide, persisted to `config.yml`'s `motd.enabled` |
| `/authmelastonline reload` (or `/amlo reload`) | `authmelastlogin.command.reload` | OP only | Reloads `config.yml` and the active language file without restarting the server |

**LuckPerms integration:** permission checks go through LuckPerms's own
API when LuckPerms is installed (checking the player's cached permission
data directly via `net.luckperms.api`), falling back to the standard
Bukkit `hasPermission` check otherwise — so it also works correctly with
vanilla OP or any other permissions plugin. Either way, you manage access
the normal LuckPerms way, e.g.:
```
/lp group default permission set authmelastlogin.command.show true
/lp group admin permission set authmelastlogin.command.motd true
```
Console can run both commands too (permission checks always pass for
non-player senders, matching normal Bukkit behavior for console).

## Before building

1. **Match your server version.** Same note as before: edit
   `<paper.api.version>` in `pom.xml` and `api-version` in `plugin.yml` to
   your actual Paper/Minecraft version — "26.1.2-74" isn't a recognized
   Paper version string.
2. **Verify AuthMe's schema.** This assumes AuthMe 6.0.0's default table
   `authme` with a `username` column and a `lastlogin` column stored as
   epoch-milliseconds (AuthMe's default). If you customized AuthMe's
   `DataSource` settings (different table/column names), check
   AuthMe's `config.yml` under the `DataSource` section and update
   `plugins/AuthMeLastLogin/config.yml` to match after first run (see below).

## Build

Requires Java 21 and Maven.

```bash
mvn clean package
```

Output: `target/authme-lastlogin-1.0.0.jar`. This jar has the MySQL
JDBC driver bundled (shaded and relocated internally), so you don't need to
install a separate driver on the server.

## Install & configure

1. Make sure `AuthMe-*.jar` (the Paper build) is already in `plugins/`.
2. Copy `authme-lastlogin-1.0.0.jar` into `plugins/` too.
3. Start the server once so it generates `plugins/AuthMeLastLogin/config.yml`,
   then stop the server (or just `/reload`, though a restart is safer).
4. Edit `plugins/AuthMeLastLogin/config.yml`:
   ```yaml
   database:
     host: localhost
     port: 3306
     database: authme
     username: authme
     password: "your-real-password"
     table: authme
     username-column: username
     lastlogin-column: lastlogin
   ```
   Use the same credentials AuthMe itself uses to reach MySQL (check
   AuthMe's own `config.yml`).
5. Restart the server.

## Config options

| Key | Meaning |
|---|---|
| `database.*` | Connection info and table/column names |
| `language` | Which file under `lang/` to use for in-game text — `en` or `ru` bundled, or your own |
| `message.date-pattern` | `DateTimeFormatter` pattern for `%time%` |
| `message.limit` | `0` = show everyone; set a positive number to only show the N most recently active players |
| `message.delay-seconds` | Seconds to wait after login before sending the automatic message |
| `motd.enabled` | Whether the automatic post-login message is sent; toggled at runtime by `/authmelastonline motd <enable|disable>` and saved back here |

## Translations

Every piece of text a **player** sees (the header, per-player lines,
command usage, permission-denied, and MOTD on/off confirmations) lives in
a language file, not `config.yml` — because that text needs full sentences
rewritten per language, not just values swapped. Console log lines
(warnings, startup messages, SQL errors) are **not** translated — those
stay in English for whoever's reading the server log.

On first run, the plugin extracts `lang/en.yml` and `lang/ru.yml` into
`plugins/AuthMeLastLogin/lang/`. Set `language: en` or `language: ru` in
`config.yml` to pick one.

To add another language:
1. Copy `plugins/AuthMeLastLogin/lang/en.yml` to e.g. `lang/de.yml`.
2. Translate the values (keep the `%player%`, `%time%`, `%label%`
   placeholders somewhere in each string — you can reorder them, but
   don't delete them).
3. Set `language: de` in `config.yml` and run `/authmelastonline reload`
   (or restart the server) to pick it up.

If a language file is missing a key (e.g. you're using an older custom
file after an update added a new message), that one string falls back to
its English default automatically rather than showing blank or erroring.

## Notes / things to double check

- **Private, not broadcast:** the list goes only to whoever triggered it
  (the logging-in player, or whoever ran `/authmelastonline show`) —
  nobody else sees it.
- **Read-only intent:** this plugin only runs `SELECT` queries — it never
  writes to AuthMe's tables.
- **Credentials:** the DB password sits in plain text in `config.yml`, same
  as it does in AuthMe's own config — restrict file permissions accordingly.
- **`depend: [AuthMe]`** in `plugin.yml` is a hard dependency — the plugin
  needs AuthMe's `LoginEvent` class to even enable, so Paper won't start it
  without AuthMe present.
- **`softdepend: [LuckPerms]`** just means LuckPerms, if present, loads
  before this plugin — it's optional; permission checks fall back to
  standard Bukkit behavior (OP-only, or whatever your permissions plugin
  decides) if LuckPerms isn't installed.
