package ro.server.orderplugin.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

public class BukkitSchedulerAdapter implements SchedulerAdapter {

    @Override
    public void runOnEntity(Plugin plugin, Entity entity, Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }

    @Override
    public Object runOnEntityLater(Plugin plugin, Entity entity, Runnable task, long delayTicks) {
        return Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
    }

    @Override
    public Object runOnEntityTimer(Plugin plugin, Entity entity, Runnable task, long initialDelayTicks, long periodTicks) {
        return Bukkit.getScheduler().runTaskTimer(plugin, task, initialDelayTicks, periodTicks);
    }

    @Override
    public void runOnRegion(Plugin plugin, Location location, Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }

    @Override
    public void runOnRegionLater(Plugin plugin, Location location, Runnable task, long delayTicks) {
        Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
    }

    @Override
    public void runGlobal(Plugin plugin, Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }

    @Override
    public void runGlobalLater(Plugin plugin, Runnable task, long delayTicks) {
        Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
    }

    @Override
    public Object runGlobalTimer(Plugin plugin, Runnable task, long initialDelayTicks, long periodTicks) {
        return Bukkit.getScheduler().runTaskTimer(plugin, task, initialDelayTicks, periodTicks);
    }

    @Override
    public void runAsync(Plugin plugin, Runnable task) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    @Override
    public void runAsyncLater(Plugin plugin, Runnable task, long delayTicks) {
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delayTicks);
    }

    @Override
    public void cancelTask(Object taskHandle) {
        if (taskHandle instanceof BukkitTask) {
            ((BukkitTask) taskHandle).cancel();
        }
    }
}
