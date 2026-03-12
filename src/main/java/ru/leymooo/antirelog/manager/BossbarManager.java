package ru.leymooo.antirelog.manager;

import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import ru.leymooo.antirelog.config.Settings;
import ru.leymooo.antirelog.util.Utils;

import java.util.HashMap;
import java.util.Map;

public class BossbarManager {

    private final Map<Player, BossBar> playerBossBars = new HashMap<>(); // персональные бары
    private final Map<Player, String> lastOpponent = new HashMap<>();
    private final Settings settings;

    public BossbarManager(Settings settings) {
        this.settings = settings;
    }

    public void createBossBars() {
        // Предварительное создание больше не нужно — бары создаём динамически.
        clearBossbars();
    }

    public void setBossBar(Player player, int time, String opponentName) {
        String titleTemplate = settings.getMessages().getInPvpBossbar();
        if (titleTemplate.isEmpty()) {
            return;
        }

        if (opponentName != null) {
            lastOpponent.put(player, opponentName);
        }

        String opponent = lastOpponent.getOrDefault(player, "");
        String title = Utils.color(
                Utils.replaceTime(titleTemplate, time)
                        .replace("%opponent%", opponent)
        );

        double progress = Math.min((double) time / settings.getPvpTime(), 1.0);

        BossBar bar = playerBossBars.get(player);
        if (bar == null) {
            bar = Bukkit.createBossBar(title, BarColor.RED, BarStyle.SOLID);
            bar.addPlayer(player);
            playerBossBars.put(player, bar);
        } else {
            bar.setTitle(title);
        }
        bar.setProgress(progress);
    }

    public void clearBossbar(Player player) {
        BossBar bar = playerBossBars.remove(player);
        if (bar != null) {
            bar.removeAll();
        }
        lastOpponent.remove(player);
    }

    public void clearBossbars() {
        playerBossBars.forEach((p, bar) -> bar.removeAll());
        playerBossBars.clear();
        lastOpponent.clear();
    }
}
