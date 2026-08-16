package ru.leymooo.antirelog.manager;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.codemc.worldguardwrapper.WorldGuardWrapper;
import org.codemc.worldguardwrapper.region.IWrappedRegion;
import ru.leymooo.antirelog.config.Settings;
import ru.leymooo.antirelog.event.PvpPreStartEvent.PvPStatus;
import ru.leymooo.antirelog.event.PvpStartedEvent;
import ru.leymooo.antirelog.event.PvpStoppedEvent;
import ru.leymooo.antirelog.util.VersionUtils;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Показывает визуальный барьер по границе конкретного региона WorldGuard (pvp-border-regions)
// при активном режиме боя и блокирует выход за пределы региона через PlayerMoveEvent.
// Регионы задаются в формате "мир:регион" (нижний регистр).
// На 1.18+ отображается WorldBorder только для конкретного игрока.
// Для получения границ использует getHandle() из внутренней реализации worldguardwrapper
// (работает как с WG6, так и с WG7).
public class PvpBorderManager implements Listener {

    // {minX, maxX, minZ, maxZ} в мировых координатах
    private final Map<UUID, double[]> activeBorders = new HashMap<>();
    private final Settings settings;
    private final PvPManager pvpManager;

    public PvpBorderManager(Settings settings, PvPManager pvpManager) {
        this.settings = settings;
        this.pvpManager = pvpManager;
    }

    @EventHandler
    public void onPvpStart(PvpStartedEvent event) {
        if (settings.getPvpBorderRegions().isEmpty()) return;

        PvPStatus status = event.getPvpStatus();
        if (status == PvPStatus.ALL_NOT_IN_PVP) {
            showBorderIfInRegion(event.getAttacker());
            showBorderIfInRegion(event.getDefender());
        } else if (status == PvPStatus.ATTACKER_IN_PVP) {
            showBorderIfInRegion(event.getDefender());
        } else if (status == PvPStatus.DEFENDER_IN_PVP) {
            showBorderIfInRegion(event.getAttacker());
        }
    }

    @EventHandler
    public void onPvpStop(PvpStoppedEvent event) {
        removeBorder(event.getPlayer());
    }

    // Блокирует движение за пределы региона во время активного PvP.
    // При попытке выйти за границу — позиция корректируется к ближайшему краю.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        double[] bounds = activeBorders.get(player.getUniqueId());
        if (bounds == null) return;

        Location to = event.getTo();
        if (to == null) return;

        // Проверяем только при смене блока — оптимизация
        Location from = event.getFrom();
        if (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ()) return;

        double x = to.getX();
        double z = to.getZ();
        double minX = bounds[0], maxX = bounds[1], minZ = bounds[2], maxZ = bounds[3];

        boolean outsideX = x < minX || x > maxX;
        boolean outsideZ = z < minZ || z > maxZ;

        if (outsideX || outsideZ) {
            double safeX = outsideX ? (x < minX ? minX + 0.5 : maxX - 0.5) : x;
            double safeZ = outsideZ ? (z < minZ ? minZ + 0.5 : maxZ - 0.5) : z;
            event.setTo(new Location(to.getWorld(), safeX, to.getY(), safeZ, to.getYaw(), to.getPitch()));
        }
    }

    // Ищет регион из настройки pvp-border-regions в позиции игрока (мир:регион),
    // сохраняет границы и показывает визуальный WorldBorder.
    private void showBorderIfInRegion(Player player) {
        if (player == null) return;
        if (pvpManager.isBypassed(player)) return;

        Set<String> borderRegions = settings.getPvpBorderRegions();
        if (borderRegions.isEmpty()) return;

        String worldName = player.getWorld().getName().toLowerCase();

        Set<IWrappedRegion> regions = WorldGuardWrapper.getInstance().getRegions(player.getLocation());
        if (regions.isEmpty()) return;

        IWrappedRegion targetRegion = null;
        double[] targetBounds = null;
        double smallestArea = Double.MAX_VALUE;

        for (IWrappedRegion region : regions) {
            if (region.getId().equals("__global__")) continue;

            String key = worldName + ":" + region.getId().toLowerCase();
            if (!borderRegions.contains(key)) continue;

            double[] bounds = getRegionBounds(region);
            if (bounds == null) continue;

            double area = (bounds[1] - bounds[0]) * (bounds[3] - bounds[2]);
            if (area < smallestArea) {
                smallestArea = area;
                targetRegion = region;
                targetBounds = bounds;
            }
        }

        if (targetRegion == null || targetBounds == null) return;

        activeBorders.put(player.getUniqueId(), targetBounds);

        if (!VersionUtils.isVersion(18)) return;

        double minX = targetBounds[0], maxX = targetBounds[1];
        double minZ = targetBounds[2], maxZ = targetBounds[3];
        double centerX = (minX + maxX) / 2.0;
        double centerZ = (minZ + maxZ) / 2.0;
        // WorldBorder квадратный — берём большую сторону, чтобы барьер охватывал весь регион
        double diameter = Math.max(maxX - minX, maxZ - minZ);

        WorldBorder border = Bukkit.createWorldBorder();
        border.setCenter(centerX, centerZ);
        border.setSize(diameter);
        border.setWarningDistance(settings.getPvpBorderWarningDistance());
        border.setWarningTime(0);
        border.setDamageAmount(0);
        border.setDamageBuffer(0);
        player.setWorldBorder(border);
    }

    // Получает границы региона через getHandle() из реализации worldguardwrapper.
    // Работает для WG6 (BlockVector.getX() -> double) и WG7 (BlockVector3.getX() -> int).
    // Возвращает {minX, maxX, minZ, maxZ} или null при ошибке.
    private static double[] getRegionBounds(IWrappedRegion wrapped) {
        try {
            Method getHandle = wrapped.getClass().getMethod("getHandle");
            Object pr = getHandle.invoke(wrapped);

            Object min = pr.getClass().getMethod("getMinimumPoint").invoke(pr);
            Object max = pr.getClass().getMethod("getMaximumPoint").invoke(pr);

            double minX = ((Number) min.getClass().getMethod("getX").invoke(min)).doubleValue();
            double minZ = ((Number) min.getClass().getMethod("getZ").invoke(min)).doubleValue();
            double maxX = ((Number) max.getClass().getMethod("getX").invoke(max)).doubleValue();
            double maxZ = ((Number) max.getClass().getMethod("getZ").invoke(max)).doubleValue();

            // +1 к верхней грани — игрок может стоять на верхней поверхности крайних блоков
            return new double[]{minX, maxX + 1.0, minZ, maxZ + 1.0};
        } catch (Exception e) {
            return null;
        }
    }

    public void removeBorder(Player player) {
        if (player == null) return;
        if (activeBorders.remove(player.getUniqueId()) != null && VersionUtils.isVersion(18)) {
            player.setWorldBorder(null);
        }
    }

    public void clearAll() {
        if (VersionUtils.isVersion(18)) {
            for (UUID uuid : activeBorders.keySet()) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    player.setWorldBorder(null);
                }
            }
        }
        activeBorders.clear();
    }
}
