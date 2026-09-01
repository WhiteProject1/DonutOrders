package ro.server.orderplugin.scheduler;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

public interface SchedulerAdapter {
    void runOnEntity(Plugin plugin, Entity entity, Runnable task);
    /** @return a task handle that can be canceled with {@link #cancelTask} */
    Object runOnEntityLater(Plugin plugin, Entity entity, Runnable task, long delayTicks);
    Object runOnEntityTimer(Plugin plugin, Entity entity, Runnable task, long initialDelayTicks, long periodTicks);
    void runOnRegion(Plugin plugin, Location location, Runnable task);
    void runOnRegionLater(Plugin plugin, Location location, Runnable task, long delayTicks);
    void runGlobal(Plugin plugin, Runnable task);
    void runGlobalLater(Plugin plugin, Runnable task, long delayTicks);
    Object runGlobalTimer(Plugin plugin, Runnable task, long initialDelayTicks, long periodTicks);
    void runAsync(Plugin plugin, Runnable task);
    void runAsyncLater(Plugin plugin, Runnable task, long delayTicks);
    void cancelTask(Object taskHandle);
}
