package ru.leymooo.antirelog.util;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.plugin.Plugin;
import ru.leymooo.antirelog.config.Settings;
import ru.leymooo.antirelog.manager.CooldownManager;
import ru.leymooo.antirelog.manager.CooldownManager.CooldownType;
import ru.leymooo.antirelog.manager.PvPManager;

import java.util.Arrays;
import java.util.List;

public class ProtocolLibUtils {

    private static boolean hasProtocolLib;

    static {
        hasProtocolLib = Bukkit.getPluginManager().isPluginEnabled("ProtocolLib") && VersionUtils.isVersion(9);
    }

    public static boolean isHasProtocolLib() {
        return hasProtocolLib;
    }

    public static void createListener(CooldownManager cooldownManager, PvPManager pvPManager, Plugin plugin) {
        if (!hasProtocolLib) return;

        Settings settings = cooldownManager.getSettings();
        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(plugin, ListenerPriority.LOWEST, PacketType.Play.Server.SET_COOLDOWN) {
            final List<CooldownType> types = Arrays.asList(CooldownType.CHORUS, CooldownType.ENDER_PEARL);

            @Override
            public void onPacketSending(PacketEvent event) {
                // Определяем material из пакета
                Material material = null;

                if (VersionUtils.isVersion(21, 4)) {
                    // 1.21.4+: пакет содержит строковый ключ
                    try {
                        String key = event.getPacket().getStrings().read(0);
                        material = Material.matchMaterial(key);
                    } catch (Exception e) {
                        return;
                    }
                } else {
                    // До 1.21.4: читаем через getSpecificModifier по классу Item
                    try {
                        Object itemObj = event.getPacket().getModifier().read(0);
                        if (itemObj == null) return;
                        // Конвертируем NMS Item → Bukkit Material через CraftMagicNumbers
                        Class<?> craftMagicNumbers = Class.forName(
                                Bukkit.getServer().getClass().getPackage().getName()
                                        .replace("craftbukkit", "craftbukkit") + ".util.CraftMagicNumbers"
                        );
                        material = (Material) craftMagicNumbers
                                .getMethod("getMaterial", itemObj.getClass())
                                .invoke(null, itemObj);
                    } catch (Exception e) {
                        return;
                    }
                }

                if (material == null) return;

                int duration = event.getPacket().getIntegers().read(0);
                duration = duration * 50;

                for (CooldownType cooldownType : types) {
                    if (material == cooldownType.getMaterial()) {
                        boolean hasCooldown = cooldownManager.hasCooldown(event.getPlayer(), cooldownType, cooldownType.getCooldown(settings) * 1000);
                        if (hasCooldown) {
                            long remaining = cooldownManager.getRemaining(event.getPlayer(), cooldownType, cooldownType.getCooldown(settings) * 1000);
                            if (Math.abs(remaining - duration) > 100) {
                                if (!pvPManager.isPvPModeEnabled() || pvPManager.isInPvP(event.getPlayer())) {
                                    if (duration == 0) {
                                        event.setCancelled(true);
                                        return;
                                    }
                                    event.getPacket().getIntegers().write(0, (int) Math.ceil(remaining / 50f));
                                }
                            }
                        }
                    }
                }
            }
        });
    }
}