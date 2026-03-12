package ru.leymooo.antirelog.manager;

import com.Zrips.CMI.CMI;
import com.Zrips.CMI.Containers.CMIUser;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import ru.leymooo.antirelog.config.Settings;
import ru.leymooo.antirelog.util.Utils;

public class PowerUpsManager {

    private final Settings settings;

    private boolean cmi;

    public PowerUpsManager(Settings settings) {
        this.settings = settings;
        detectPlugins();
    }


    public boolean disablePowerUps(Player player) {
        if (player.hasPermission("antirelog.bypass.checks")) {
            return false;
        }

        boolean disabled = false;
        if (player.getGameMode() == GameMode.CREATIVE) {
            if (Bukkit.getDefaultGameMode() == GameMode.ADVENTURE) {
                player.setGameMode(GameMode.ADVENTURE);
            } else {
                player.setGameMode(GameMode.SURVIVAL);
            }
            disabled = true;
        }

        if (player.isFlying() || player.getAllowFlight()) {
            player.setFlying(false);
            player.setAllowFlight(false);
            disabled = true;
        }

        if (checkCMI(player)) {
            disabled = true;
        }

        return disabled;
    }


    public void disablePowerUpsWithRunCommands(Player player) {
        if (disablePowerUps(player) && !settings.getCommandsOnPowerupsDisable().isEmpty()) {
            settings.getCommandsOnPowerupsDisable().forEach(command -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    Utils.color(command.replace("%player%", player.getName()))));
            String message = settings.getMessages().getPvpStartedWithPowerups();
            if (!message.isEmpty()) {
                player.sendMessage(Utils.color(message));
            }
        }
    }

    public void detectPlugins() {
        PluginManager pluginManager = Bukkit.getPluginManager();
        this.cmi = pluginManager.isPluginEnabled("CMI");
    }

    private boolean checkCMI(Player player) {
        boolean disabled = false;
        if (cmi) {
            CMIUser user = CMI.getInstance().getPlayerManager().getUser(player);
            if (user != null) {
                if (user.isGod()) {
                    CMI.getInstance().getNMS().changeGodMode(player, false);
                    user.setTgod(0);
                    disabled = true;
                }
                if (user.isVanished()) {
                    user.setVanished(false);
                    disabled = true;
                }
            }
        }
        return disabled;
    }
}
