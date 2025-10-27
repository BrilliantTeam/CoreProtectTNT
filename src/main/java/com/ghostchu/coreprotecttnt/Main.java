package com.ghostchu.coreprotecttnt;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.RespawnAnchor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.entity.minecart.ExplosiveMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class Main extends JavaPlugin implements Listener {
    private final Cache<Object, String> probablyCache = CacheBuilder
            .newBuilder()
            .expireAfterAccess(1, TimeUnit.HOURS)
            .concurrencyLevel(4)
            .maximumSize(50000)
            .recordStats()
            .build();
    private CoreProtectAPI api;

    private static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        saveDefaultConfig();
        Plugin depend = Bukkit.getPluginManager().getPlugin("CoreProtect");
        if (depend == null) {
            getPluginLoader().disablePlugin(this);
            return;
        }
        api = ((CoreProtect) depend).getAPI();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlayerInteractBedOrRespawnAnchorExplosion(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block clickedBlock = e.getClickedBlock();
        Location locationHead = clickedBlock.getLocation();
        if (clickedBlock.getBlockData() instanceof Bed bed) {
            Location locationFoot = locationHead.clone().subtract(bed.getFacing().getDirection());
            if (bed.getPart() == Bed.Part.FOOT) {
                locationHead.add(bed.getFacing().getDirection());
            }
            String reason = "#bed-" + e.getPlayer().getName();
            probablyCache.put(locationHead, reason);
            probablyCache.put(locationFoot, reason);
        }
        if (clickedBlock.getBlockData() instanceof RespawnAnchor) {
            probablyCache.put(clickedBlock.getLocation(), "#respawnanchor-" + e.getPlayer().getName());
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlayerInteractCreeper(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof Creeper)) {
            return;
        }
        probablyCache.put(e.getRightClicked(), "#ignite-creeper-" + e.getPlayer().getName());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockExplode(BlockExplodeEvent e) {
        ConfigurationSection section = Util.bakeConfigSection(getConfig(), "block-explosion");
        if (!section.getBoolean("enable", true)) {
            return;
        }
        Location location = e.getBlock().getLocation();
        String probablyCauses = probablyCache.getIfPresent(e.getBlock());
        if (probablyCauses == null) {
            probablyCauses = probablyCache.getIfPresent(location);
        }
        if (probablyCauses == null) {
            if (section.getBoolean("disable-unknown", true)) {
                e.blockList().clear();
                Util.broadcastNearPlayers(location, section.getString("alert"));
            }
        }
        for (Block block : e.blockList()) {
            api.logRemoval(probablyCauses, block.getLocation(), block.getType(), block.getBlockData());
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockPlaceOnHanging(BlockPlaceEvent event) {
        probablyCache.put(event.getBlock().getLocation(), event.getPlayer().getName());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockBreak(BlockPlaceEvent event) {
        probablyCache.put(event.getBlock().getLocation(), event.getPlayer().getName());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onClickItemFrame(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof ItemFrame itemFrame)) {
            return;
        }
        ConfigurationSection section = Util.bakeConfigSection(getConfig(), "itemframe");
        if (!section.getBoolean("enable", true)) {
            return;
        }
        api.logInteraction(e.getPlayer().getName(), e.getRightClicked().getLocation());
        if (itemFrame.getItem().getType().isAir()) {
            ItemStack mainItem = e.getPlayer().getInventory().getItemInMainHand();
            ItemStack offItem = e.getPlayer().getInventory().getItemInOffHand();
            ItemStack putIn = mainItem.getType().isAir() ? offItem : mainItem;
            if (!putIn.getType().isAir()) {
                api.logPlacement("#additem-" + e.getPlayer().getName(), e.getRightClicked().getLocation(), putIn.getType(), null);
                return;
            }
        }
        api.logRemoval("#rotate-" + e.getPlayer().getName(), e.getRightClicked().getLocation(), itemFrame.getItem().getType(), null);
        api.logPlacement("#rotate-" + e.getPlayer().getName(), e.getRightClicked().getLocation(), itemFrame.getItem().getType(), null);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onProjectileLaunch(ProjectileLaunchEvent e) {
        if (e.getEntity().getShooter() == null) {
            return;
        }
        Projectile projectile = e.getEntity();
        if (isFolia()) {
            EntityScheduler scheduler = projectile.getScheduler();
            scheduler.run(this, (task) -> {
                try {
                    processProjectileLaunch(e, projectile);
                } catch (Exception ex) {
                    getLogger().severe("Error processing ProjectileLaunchEvent in Folia scheduler: " + ex.getMessage());
                    ex.printStackTrace();
                }
            }, null);
        } else {
            processProjectileLaunch(e, projectile);
        }
    }

    private void processProjectileLaunch(ProjectileLaunchEvent e, Projectile projectile) {
        ProjectileSource projectileSource = projectile.getShooter();
        String source = "";
        if (!(projectileSource instanceof Player)) {
            source += "#";
        }
        source += e.getEntity().getName() + "-";
        if (projectileSource instanceof Entity entity) {
            if (projectileSource instanceof Mob mob && mob.getTarget() != null) {
                source += mob.getTarget().getName();
            } else {
                source += entity.getName();
            }
        } else if (projectileSource instanceof Block block) {
            source += block.getType().name();
        } else {
            source += projectileSource.getClass().getName();
        }
        probablyCache.put(e.getEntity(), source);
        probablyCache.put(projectileSource, source);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onIgniteTNT(EntitySpawnEvent e) {
        if (!(e.getEntity() instanceof TNTPrimed)) {
            return;
        }
        Entity tnt = e.getEntity();
        if (isFolia()) {
            EntityScheduler scheduler = tnt.getScheduler();
            scheduler.run(this, (task) -> {
                try {
                    processIgniteTNT(tnt, (TNTPrimed) tnt);
                } catch (Exception ex) {
                    getLogger().severe("Error processing EntitySpawnEvent in Folia scheduler: " + ex.getMessage());
                    ex.printStackTrace();
                }
            }, null);
        } else {
            processIgniteTNT(tnt, (TNTPrimed) tnt);
        }
    }

    private void processIgniteTNT(Entity tnt, TNTPrimed tntPrimed) {
        Entity source = tntPrimed.getSource();
        if (source != null) {
            String sourceFromCache = probablyCache.getIfPresent(source);
            if (sourceFromCache != null) {
                probablyCache.put(tnt, sourceFromCache);
            }
            if (source.getType() == EntityType.PLAYER) {
                probablyCache.put(tntPrimed, source.getName());
                return;
            }
        }
        Location blockCorner = tnt.getLocation().clone().subtract(0.5, 0, 0.5);
        for (Map.Entry<Object, String> entry : probablyCache.asMap().entrySet()) {
            if (entry.getKey() instanceof Location loc) {
                if (loc.getWorld().equals(blockCorner.getWorld()) && loc.distance(blockCorner) < 0.5) {
                    probablyCache.put(tnt, entry.getValue());
                    break;
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onEndCrystalHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof EnderCrystal)) {
            return;
        }
        Entity crystal = e.getEntity();
        if (isFolia()) {
            EntityScheduler scheduler = crystal.getScheduler();
            scheduler.run(this, (task) -> {
                try {
                    processEndCrystalHit(e, crystal);
                } catch (Exception ex) {
                    getLogger().severe("Error processing EntityDamageByEntityEvent in Folia scheduler: " + ex.getMessage());
                    ex.printStackTrace();
                }
            }, null);
        } else {
            processEndCrystalHit(e, crystal);
        }
    }

    private void processEndCrystalHit(EntityDamageByEntityEvent e, Entity crystal) {
        if (e.getDamager() instanceof Player) {
            probablyCache.put(crystal, e.getDamager().getName());
        } else {
            String sourceFromCache = probablyCache.getIfPresent(e.getDamager());
            if (sourceFromCache != null) {
                probablyCache.put(crystal, sourceFromCache);
            } else if (e.getDamager() instanceof Projectile projectile) {
                if (projectile.getShooter() != null && projectile.getShooter() instanceof Player player) {
                    probablyCache.put(crystal, player.getName());
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onHangingBreak(HangingBreakEvent e) {
        ConfigurationSection section = Util.bakeConfigSection(getConfig(), "hanging");
        if (!section.getBoolean("enable", true)) {
            return;
        }
        if (e.getCause() == HangingBreakEvent.RemoveCause.PHYSICS || e.getCause() == HangingBreakEvent.RemoveCause.DEFAULT) {
            return;
        }
        Block hangingPosBlock = e.getEntity().getLocation().getBlock();
        String reason = probablyCache.getIfPresent(hangingPosBlock.getLocation());
        if (reason != null) {
            Material mat = Material.matchMaterial(e.getEntity().getType().name());
            if (mat != null) {
                api.logRemoval("#" + e.getCause().name() + "-" + reason, hangingPosBlock.getLocation(), Material.matchMaterial(e.getEntity().getType().name()), null);
            } else {
                api.logInteraction("#" + e.getCause().name() + "-" + reason, hangingPosBlock.getLocation());
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onHangingHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Hanging)) {
            return;
        }
        ConfigurationSection section = Util.bakeConfigSection(getConfig(), "itemframe");
        if (!section.getBoolean("enable", true)) {
            return;
        }
        ItemFrame itemFrame = (ItemFrame) e.getEntity();
        if (itemFrame.getItem().getType().isAir() || itemFrame.isInvulnerable()) {
            return;
        }
        if (e.getDamager() instanceof Player) {
            probablyCache.put(e.getEntity(), e.getDamager().getName());
            api.logInteraction(e.getDamager().getName(), itemFrame.getLocation());
            api.logRemoval(e.getDamager().getName(), itemFrame.getLocation(), itemFrame.getItem().getType(), null);
        } else {
            String cause = probablyCache.getIfPresent(e.getDamager());
            if (cause != null) {
                String reason = "#" + e.getDamager().getName() + "-" + cause;
                probablyCache.put(e.getEntity(), reason);
                api.logRemoval(reason, itemFrame.getLocation(), itemFrame.getItem().getType(), null);
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPaintingHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Painting)) {
            return;
        }
        ConfigurationSection section = Util.bakeConfigSection(getConfig(), "painting");
        if (!section.getBoolean("enable", true)) {
            return;
        }
        ItemFrame itemFrame = (ItemFrame) e.getEntity();
        if (itemFrame.getItem().getType().isAir() || itemFrame.isInvulnerable()) {
            return;
        }
        if (e.getDamager() instanceof Player) {
            api.logInteraction(e.getDamager().getName(), itemFrame.getLocation());
            api.logRemoval(e.getDamager().getName(), itemFrame.getLocation(), itemFrame.getItem().getType(), null);
        } else {
            String reason = probablyCache.getIfPresent(e.getDamager());
            if (reason != null) {
                api.logInteraction("#" + e.getDamager().getName() + "-" + reason, itemFrame.getLocation());
                api.logRemoval("#" + e.getDamager().getName() + "-" + reason, itemFrame.getLocation(), itemFrame.getItem().getType(), null);
            } else {
                if (section.getBoolean("disable-unknown")) {
                    e.setCancelled(true);
                    e.setDamage(0.0d);
                    Util.broadcastNearPlayers(e.getEntity().getLocation(), section.getString("alert"));
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onEntityHitByProjectile(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Projectile projectile) {
            if (isFolia()) {
                EntityScheduler scheduler = e.getEntity().getScheduler();
                scheduler.run(this, (task) -> {
                    try {
                        processEntityHitByProjectile(e, projectile);
                    } catch (Exception ex) {
                        getLogger().severe("Error processing EntityDamageByEntityEvent in Folia scheduler: " + ex.getMessage());
                        ex.printStackTrace();
                    }
                }, null);
            } else {
                processEntityHitByProjectile(e, projectile);
            }
        }
    }

    private void processEntityHitByProjectile(EntityDamageByEntityEvent e, Projectile projectile) {
        if (projectile.getShooter() instanceof Player player) {
            probablyCache.put(e.getEntity(), player.getName());
            return;
        }
        String reason = probablyCache.getIfPresent(e.getDamager());
        if (reason != null) {
            probablyCache.put(e.getEntity(), reason);
            return;
        }
        probablyCache.put(e.getEntity(), e.getDamager().getName());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockIgnite(BlockIgniteEvent e) {
        if (e.getIgnitingEntity() != null) {
            if (e.getIgnitingEntity().getType() == EntityType.PLAYER) {
                probablyCache.put(e.getBlock().getLocation(), e.getPlayer().getName());
                return;
            }
            if (isFolia()) {
                EntityScheduler scheduler = e.getIgnitingEntity().getScheduler();
                scheduler.run(this, (task) -> {
                    try {
                        processBlockIgnite(e);
                    } catch (Exception ex) {
                        getLogger().severe("Error processing BlockIgniteEvent in Folia scheduler: " + ex.getMessage());
                        ex.printStackTrace();
                    }
                }, null);
            } else {
                processBlockIgnite(e);
            }
        } else if (e.getIgnitingBlock() != null) {
            String sourceFromCache = probablyCache.getIfPresent(e.getIgnitingBlock().getLocation());
            if (sourceFromCache != null) {
                probablyCache.put(e.getBlock().getLocation(), sourceFromCache);
            }
        } else {
            ConfigurationSection section = Util.bakeConfigSection(getConfig(), "fire");
            if (!section.getBoolean("enable", true)) {
                return;
            }
            if (section.getBoolean("disable-unknown", true)) {
                e.setCancelled(true);
            }
        }
    }

    private void processBlockIgnite(BlockIgniteEvent e) {
        String sourceFromCache = probablyCache.getIfPresent(e.getIgnitingEntity());
        if (sourceFromCache != null) {
            probablyCache.put(e.getBlock().getLocation(), sourceFromCache);
            return;
        } else if (e.getIgnitingEntity() instanceof Projectile projectile) {
            if (projectile.getShooter() != null && projectile.getShooter() instanceof Player player) {
                probablyCache.put(e.getBlock().getLocation(), player.getName());
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockBurn(BlockBurnEvent e) {
        ConfigurationSection section = Util.bakeConfigSection(getConfig(), "fire");
        if (!section.getBoolean("enable", true)) {
            return;
        }
        if (e.getIgnitingBlock() != null) {
            String sourceFromCache = probablyCache.getIfPresent(e.getIgnitingBlock().getLocation());
            if (sourceFromCache != null) {
                probablyCache.put(e.getBlock().getLocation(), sourceFromCache);
                api.logRemoval("#fire-" + sourceFromCache, e.getBlock().getLocation(), e.getBlock().getType(), e.getBlock().getBlockData());
            } else if (section.getBoolean("disable-unknown", true)) {
                e.setCancelled(true);
                Util.broadcastNearPlayers(e.getIgnitingBlock().getLocation(), section.getString("alert"));
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onBombHit(ProjectileHitEvent e) {
        if (e.getHitEntity() instanceof ExplosiveMinecart || e.getEntityType() == EntityType.END_CRYSTAL) {
            if (e.getEntity().getShooter() != null && e.getEntity().getShooter() instanceof Player) {
                if (e.getHitEntity() != null) {
                    if (isFolia()) {
                        EntityScheduler scheduler = e.getHitEntity().getScheduler();
                        scheduler.run(this, (task) -> {
                            try {
                                processBombHit(e);
                            } catch (Exception ex) {
                                getLogger().severe("Error processing ProjectileHitEvent in Folia scheduler: " + ex.getMessage());
                                ex.printStackTrace();
                            }
                        }, null);
                    } else {
                        processBombHit(e);
                    }
                }
            }
        }
    }

    private void processBombHit(ProjectileHitEvent e) {
        String sourceFromCache = probablyCache.getIfPresent(e.getEntity());
        if (sourceFromCache != null) {
            probablyCache.put(e.getHitEntity(), sourceFromCache);
        } else {
            if (e.getEntity().getShooter() != null && e.getEntity().getShooter() instanceof Player shooter) {
                probablyCache.put(e.getHitEntity(), shooter.getName());
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onExplode(EntityExplodeEvent e) {
        Entity entity = e.getEntity();
        List<Block> blockList = e.blockList();
        if (blockList.isEmpty()) {
            return;
        }
        List<Entity> pendingRemoval = new ArrayList<>();
        String entityName = e.getEntityType().name().toLowerCase(Locale.ROOT);
        ConfigurationSection section = Util.bakeConfigSection(getConfig(), "entity-explosion");
        if (!section.getBoolean("enable", true)) {
            return;
        }
        String track = probablyCache.getIfPresent(entity);
        if (entity instanceof TNTPrimed || entity instanceof EnderCrystal) {
            if (track != null) {
                String reason = "#" + entityName + "-" + track;
                for (Block block : blockList) {
                    api.logRemoval(reason, block.getLocation(), block.getType(), block.getBlockData());
                    probablyCache.put(block.getLocation(), reason);
                }
                pendingRemoval.add(entity);
            } else {
                if (!section.getBoolean("disable-unknown", true)) {
                    return;
                }
                e.blockList().clear();
                e.getEntity().remove();
                Util.broadcastNearPlayers(entity.getLocation(), section.getString("alert"));
            }
            pendingRemoval.forEach(probablyCache::invalidate);
            return;
        }
        if (entity instanceof Creeper creeper) {
            if (track != null) {
                for (Block block : blockList) {
                    api.logRemoval(track, block.getLocation(), block.getType(), block.getBlockData());
                }
            } else {
                LivingEntity creeperTarget = null;
                if (isFolia()) {
                    EntityScheduler scheduler = creeper.getScheduler();
                    scheduler.run(this, (task) -> {
                        try {
                            LivingEntity target = creeper.getTarget();
                            if (target != null) {
                                for (Block block : blockList) {
                                    api.logRemoval("#creeper-" + target.getName(), block.getLocation(), block.getType(), block.getBlockData());
                                    probablyCache.put(block.getLocation(), "#creeper-" + target.getName());
                                }
                            } else {
                                if (!section.getBoolean("disable-unknown")) {
                                    return;
                                }
                                e.blockList().clear();
                                e.getEntity().remove();
                                Util.broadcastNearPlayers(e.getLocation(), section.getString("alert"));
                            }
                        } catch (Exception ex) {
                            getLogger().severe("Error processing EntityExplodeEvent in Folia scheduler: " + ex.getMessage());
                            ex.printStackTrace();
                        }
                    }, null);
                } else {
                    creeperTarget = creeper.getTarget();
                    if (creeperTarget != null) {
                        for (Block block : blockList) {
                            api.logRemoval("#creeper-" + creeperTarget.getName(), block.getLocation(), block.getType(), block.getBlockData());
                            probablyCache.put(block.getLocation(), "#creeper-" + creeperTarget.getName());
                        }
                    } else {
                        if (!section.getBoolean("disable-unknown")) {
                            return;
                        }
                        e.blockList().clear();
                        e.getEntity().remove();
                        Util.broadcastNearPlayers(e.getLocation(), section.getString("alert"));
                    }
                }
            }
            return;
        }
        if (entity instanceof Fireball) {
            if (track != null) {
                String reason = "#fireball-" + track;
                for (Block block : blockList) {
                    api.logRemoval(reason, block.getLocation(), block.getType(), block.getBlockData());
                    probablyCache.put(block.getLocation(), reason);
                }
                pendingRemoval.add(entity);
            } else {
                if (section.getBoolean("disable-unknown")) {
                    e.blockList().clear();
                    e.getEntity().remove();
                    Util.broadcastNearPlayers(entity.getLocation(), section.getString("alert"));
                }
            }
            pendingRemoval.forEach(probablyCache::invalidate);
            return;
        }
        if (entity instanceof ExplosiveMinecart) {
            boolean isLogged = false;
            Location blockCorner = entity.getLocation().clone().subtract(0.5, 0, 0.5);
            for (Map.Entry<Object, String> entry : probablyCache.asMap().entrySet()) {
                if (entry.getKey() instanceof Location loc) {
                    if (loc.getWorld().equals(blockCorner.getWorld()) && loc.distance(blockCorner) < 1) {
                        for (Block block : blockList) {
                            api.logRemoval("#tnt-minecart-" + entry.getValue(), block.getLocation(), block.getType(), block.getBlockData());
                            probablyCache.put(block.getLocation(), "#tnt-minecart-" + entry.getValue());
                        }
                        isLogged = true;
                        break;
                    }
                }
            }
            if (!isLogged) {
                if (probablyCache.getIfPresent(entity) != null) {
                    String reason = "#tnt-minecart-" + probablyCache.getIfPresent(entity);
                    for (Block block : blockList) {
                        api.logRemoval(reason, block.getLocation(), block.getType(), block.getBlockData());
                        probablyCache.put(block.getLocation(), reason);
                    }
                    pendingRemoval.add(entity);
                } else if (section.getBoolean("disable-unknown")) {
                    e.blockList().clear();
                    Util.broadcastNearPlayers(entity.getLocation(), section.getString("alert"));
                }
            }
            pendingRemoval.forEach(probablyCache::invalidate);
            return;
        }
        if (track == null || track.isEmpty()) {
            if (e.getEntity() instanceof Mob mob) {
                if (isFolia()) {
                    EntityScheduler scheduler = mob.getScheduler();
                    scheduler.run(this, (task) -> {
                        try {
                            Entity target = mob.getTarget();
                            if (target != null) {
                                String mobTrack = target.getName();
                                for (Block block : e.blockList()) {
                                    api.logRemoval(mobTrack, block.getLocation(), block.getType(), block.getBlockData());
                                }
                            } else if (section.getBoolean("disable-unknown")) {
                                e.blockList().clear();
                                e.getEntity().remove();
                                Util.broadcastNearPlayers(entity.getLocation(), section.getString("alert"));
                            }
                        } catch (Exception ex) {
                            getLogger().severe("Error processing EntityExplodeEvent in Folia scheduler: " + ex.getMessage());
                            ex.printStackTrace();
                        }
                    }, null);
                } else {
                    if (mob.getTarget() != null) {
                        track = mob.getTarget().getName();
                    }
                }
            }
        }
        if (track != null && !track.isEmpty()) {
            for (Block block : e.blockList()) {
                api.logRemoval(track, block.getLocation(), block.getType(), block.getBlockData());
            }
        } else if (section.getBoolean("disable-unknown")) {
            e.blockList().clear();
            e.getEntity().remove();
            Util.broadcastNearPlayers(entity.getLocation(), section.getString("alert"));
        }
    }
}