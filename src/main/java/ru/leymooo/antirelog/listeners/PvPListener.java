package ru.leymooo.antirelog.listeners;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import ru.leymooo.antirelog.config.Messages;
import ru.leymooo.antirelog.config.Settings;
import ru.leymooo.antirelog.manager.PvPManager;
import ru.leymooo.antirelog.util.Utils;
import ru.leymooo.antirelog.util.VersionUtils;

import java.util.HashMap;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class PvPListener implements Listener {

    private final static String META_KEY = "ar-f-shooter";
    private final static List<String> SERVER_SPAM_KICK_REASONS = Arrays.asList("spam");

    private final Plugin plugin;
    private final PvPManager pvpManager;
    private final Messages messages;
    private final Settings settings;
    private final Map<Player, AtomicInteger> allowedTeleports = new HashMap<>();
    private final Map<UUID, CommandSpamState> commandSpam = new HashMap<>();


    public PvPListener(Plugin plugin, PvPManager pvpManager, Settings settings) {
        this.plugin = plugin;
        this.pvpManager = pvpManager;
        this.settings = settings;
        this.messages = settings.getMessages();
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            allowedTeleports.values().forEach(ai -> ai.set(ai.get() + 1));
            allowedTeleports.values().removeIf(ai -> ai.get() >= 5);
        }, 1l, 1l);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getEntity().getType() != EntityType.PLAYER) {
            return;
        }
        Player target = (Player) event.getEntity();
        Player damager = getDamager(event.getDamager());
        pvpManager.playerDamagedByPlayer(damager, target);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractWithEntity(PlayerInteractEntityEvent event) {
        if (settings.isCancelInteractWithEntities() && pvpManager.isPvPModeEnabled() && pvpManager.isInPvP(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCombust(EntityCombustByEntityEvent event) {
        if (!(event.getEntity() instanceof Player))
            return;
        Player target = (Player) event.getEntity();
        Player damager = getDamager(event.getCombuster());
        pvpManager.playerDamagedByPlayer(damager, target);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShootBow(EntityShootBowEvent event) {
        if (VersionUtils.isVersion(14) && event.getProjectile() instanceof Firework && event.getEntity().getType() == EntityType.PLAYER) {
            event.getProjectile().setMetadata(META_KEY, new FixedMetadataValue(plugin, event.getEntity().getUniqueId()));
        }
    }


    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent e) {
        if (e.getPotion() != null && e.getPotion().getShooter() instanceof Player) {
            Player shooter = (Player) e.getPotion().getShooter();
            for (LivingEntity en : e.getAffectedEntities()) {
                if (en.getType() == EntityType.PLAYER && en != shooter) {
                    for (PotionEffect ef : e.getPotion().getEffects()) {
                        if (ef.getType().equals(PotionEffectType.POISON)) {
                            pvpManager.playerDamagedByPlayer(shooter, (Player) en);
                        }
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent ev) {

        if (settings.isDisableTeleportsInPvp() && pvpManager.isInPvP(ev.getPlayer())) {
            if (allowedTeleports.containsKey(ev.getPlayer())) { //allow all teleport in 4-5 ticks after chorus or ender pearl
                return;
            }

            if ((VersionUtils.isVersion(9) && ev.getCause() == TeleportCause.CHORUS_FRUIT) || ev.getCause() == TeleportCause.ENDER_PEARL) {
                allowedTeleports.put(ev.getPlayer(), new AtomicInteger(0));
                return;
            }
            if (ev.getFrom().getWorld() != ev.getTo().getWorld()) {
                ev.setCancelled(true);
                return;
            }
            if (ev.getFrom().distanceSquared(ev.getTo()) > 100) { //10 = 10 blocks
                ev.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (settings.isDisableCommandsInPvp() && pvpManager.isInPvP(e.getPlayer())) {
            String command = e.getMessage().split(" ")[0].replaceFirst("/", "");
            if (pvpManager.isCommandWhiteListed(command)) {
                return;
            }
            e.setCancelled(true);
            if (handleCommandSpam(e.getPlayer())) {
                return;
            }
            String message = Utils.color(messages.getCommandsDisabled());
            if (!message.isEmpty()) {
                e.getPlayer().sendMessage(Utils.replaceTime(message, pvpManager.getTimeRemainingInPvP(e.getPlayer())));
            }
        }
    }


    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onKick(PlayerKickEvent e) {
        Player player = e.getPlayer();
        commandSpam.remove(player.getUniqueId());

        if (pvpManager.isInSilentPvP(player)) {
            pvpManager.stopPvPSilent(player);
            return;
        }

        if (!pvpManager.isInPvP(player)) {
            return;
        }

        if (!shouldPunishKick(e)) {
            pvpManager.stopPvPSilent(player);
            return;
        }

        pvpManager.stopPvPSilent(player);
        kickedInPvp(player);
    }

    private boolean shouldPunishKick(PlayerKickEvent event) {
        if (settings.getKickMessages().isEmpty()) {
            return true;
        }
        if (event.getReason() == null) {
            return false;
        }
        String reason = ChatColor.stripColor(event.getReason()).toLowerCase(Locale.ROOT);
        for (String spamReason : SERVER_SPAM_KICK_REASONS) {
            if (reason.contains(spamReason)) {
                return true;
            }
        }
        for (String killReason : settings.getKickMessages()) {
            if (reason.contains(killReason.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private void kickedInPvp(Player player) {
        if (settings.isKillOnKick()) {
            player.setHealth(0);
            sendLeavedInPvpMessage(player);
        }
        if (settings.isRunCommandsOnKick()) {
            runCommands(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent e) {
        allowedTeleports.remove(e.getPlayer());
        commandSpam.remove(e.getPlayer().getUniqueId());
        if (settings.isHideLeaveMessage()) {
            e.setQuitMessage(null);
        }
        if (pvpManager.isInPvP(e.getPlayer())) {
            pvpManager.stopPvPSilent(e.getPlayer());
            if (settings.isKillOnLeave()) {
                sendLeavedInPvpMessage(e.getPlayer());
                e.getPlayer().setHealth(0);
            } else {
                pvpManager.stopPvPSilent(e.getPlayer());
            }
            runCommands(e.getPlayer());
        }
        if (pvpManager.isInSilentPvP(e.getPlayer())) {
            pvpManager.stopPvPSilent(e.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent e) {
        commandSpam.remove(e.getEntity().getUniqueId());
        if (settings.isHideDeathMessage()) {
            e.setDeathMessage(null);
        }

        if (pvpManager.isInSilentPvP(e.getEntity()) || pvpManager.isInPvP(e.getEntity())) {
            pvpManager.stopPvPSilent(e.getEntity());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        if (settings.isHideJoinMessage()) {
            e.setJoinMessage(null);
        }
    }

    private void sendLeavedInPvpMessage(Player p) {
        String message = Utils.color(messages.getPvpLeaved()).replace("%player%", p.getName());
        if (!message.isEmpty()) {
            for (Player pl : Bukkit.getOnlinePlayers()) {
                pl.sendMessage(message);
            }
        }
    }

    private void runCommands(Player leaved) {
        if (!settings.getCommandsOnLeave().isEmpty()) {
            settings.getCommandsOnLeave().forEach(command -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    Utils.color(command).replace("%player%", leaved.getName())));
        }
    }

    private Player getDamager(Entity damager) {
        if (damager instanceof Player) {
            return (Player) damager;
        } else if (damager instanceof Projectile) {
            Projectile proj = (Projectile) damager;
            if (proj.getShooter() instanceof Player) {
                return (Player) proj.getShooter();
            }
        } else if (damager instanceof TNTPrimed) {
            TNTPrimed tntPrimed = (TNTPrimed) damager;
            return getDamager(tntPrimed.getSource());
        } else if (VersionUtils.isVersion(9) && damager instanceof AreaEffectCloud) {
            AreaEffectCloud aec = (AreaEffectCloud) damager;
            if (aec.getSource() instanceof Player) {
                return (Player) aec.getSource();
            }
        } else if (VersionUtils.isVersion(14) && damager instanceof Firework) {
            if (damager.hasMetadata(META_KEY)) {
                MetadataValue metadata = null;
                for (MetadataValue metadataValue : damager.getMetadata(META_KEY)) {
                    if (metadataValue.getOwningPlugin() == plugin) {
                        metadata = metadataValue;
                        break;
                    }
                }
                if (metadata != null) {
                    damager.removeMetadata(META_KEY, plugin);
                    return Bukkit.getPlayer((UUID) metadata.value());
                }
            }
        }
        return null;
    }

    private boolean handleCommandSpam(Player player) {
        int threshold = settings.getCommandSpamPunishThreshold();
        int windowMillis = settings.getCommandSpamWindowSeconds() * 1000;
        if (threshold <= 0 || windowMillis <= 0) {
            return false;
        }

        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();
        CommandSpamState state = commandSpam.get(uuid);
        if (state == null || now - state.firstCommandMillis > windowMillis) {
            state = new CommandSpamState(now);
            commandSpam.put(uuid, state);
        }
        state.count++;

        if (state.count >= threshold) {
            commandSpam.remove(uuid);
            player.setHealth(0);
            return true;
        }
        return false;
    }

    private static class CommandSpamState {

        private final long firstCommandMillis;
        private int count;

        private CommandSpamState(long firstCommandMillis) {
            this.firstCommandMillis = firstCommandMillis;
        }
    }
}
