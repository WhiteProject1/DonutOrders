package ro.server.orderplugin.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;

public class FoliaSchedulerAdapter implements SchedulerAdapter {

    @Override
    public void runOnEntity(Plugin plugin, Entity entity, Runnable task) {
        entity.getScheduler().execute(plugin, task, null, 0);
    }

    @Override
    public Object runOnEntityLater(Plugin plugin, Entity entity, Runnable task, long delayTicks) {
        return entity.getScheduler().runDelayed(plugin, scheduledTask -> task.run(), null, Math.max(1, delayTicks));
    }

    @Override
    public Object runOnEntityTimer(Plugin plugin, Entity entity, Runnable task, long initialDelayTicks, long periodTicks) {
        return entity.getScheduler().runAtFixedRate(plugin, scheduledTask -> task.run(), null, Math.max(1, initialDelayTicks), periodTicks);
    }

    @Override
    public void runOnRegion(Plugin plugin, Location location, Runnable task) {
        Bukkit.getServer().getRegionScheduler().execute(plugin, location, task);
    }

    @Override
    public void runOnRegionLater(Plugin plugin, Location location, Runnable task, long delayTicks) {
        Bukkit.getServer().getRegionScheduler().runDelayed(plugin, location, scheduledTask -> task.run(), delayTicks);
    }

    @Override
    public void runGlobal(Plugin plugin, Runnable task) {
        Bukkit.getServer().getGlobalRegionScheduler().execute(plugin, task);
    }

    @Override
    public void runGlobalLater(Plugin plugin, Runnable task, long delayTicks) {
        Bukkit.getServer().getGlobalRegionScheduler().runDelayed(plugin, scheduledTask -> task.run(), delayTicks);
    }

    @Override
    public Object runGlobalTimer(Plugin plugin, Runnable task, long initialDelayTicks, long periodTicks) {
        return Bukkit.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, scheduledTask -> task.run(), Math.max(1, initialDelayTicks), periodTicks);
    }

    @Override
    public void runAsync(Plugin plugin, Runnable task) {
        Bukkit.getServer().getAsyncScheduler().runNow(plugin, scheduledTask -> task.run());
    }

    @Override
    public void runAsyncLater(Plugin plugin, Runnable task, long delayTicks) {
        Bukkit.getServer().getAsyncScheduler().runDelayed(plugin, scheduledTask -> task.run(), delayTicks * 50, TimeUnit.MILLISECONDS);
    }

    @Override
    public void cancelTask(Object taskHandle) {
        if (taskHandle instanceof ScheduledTask) {
            ((ScheduledTask) taskHandle).cancel();
        }
    }
}
