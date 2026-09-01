package ro.server.orderplugin.util;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

public class PriceUtil {

    public static double getItemSellPrice(Player player, ItemStack itemStack) {
        if (itemStack == null) {
            return -1.0;
        }

        // Try CMI
        try {
            if (Bukkit.getPluginManager().getPlugin("CMI") != null) {
                Object cmiInstance = Class.forName("com.Zrips.CMI.CMI").getMethod("getInstance").invoke(null);
                Object worthManager = cmiInstance.getClass().getMethod("getWorthManager").invoke(cmiInstance);
                Object result = worthManager.getClass().getMethod("getWorth", ItemStack.class).invoke(worthManager, itemStack);
                if (result instanceof Double) {
                    double price = (Double) result;
                    if (price > 0.0) return price;
                }
            }
        } catch (Exception ignored) {}

        // Try ShopGUIPlus
        try {
            if (Bukkit.getPluginManager().getPlugin("ShopGUIPlus") != null) {
                Class<?> shopApi = Class.forName("net.brcdev.shopgui.ShopGuiPlusApi");
                Method method = shopApi.getMethod("getItemStackPriceSell", ItemStack.class);
                Object result = method.invoke(null, itemStack);
                if (result instanceof Double) {
                    double price = (Double) result;
                    if (price > 0.0) return price;
                }
            }
        } catch (Exception ignored) {}

        // Try EconomyShopGUI
        try {
            if (Bukkit.getPluginManager().getPlugin("EconomyShopGUI") != null) {
                Class<?> hookClass = Class.forName("me.gyropo.economyshopgui.api.EconomyShopGUIHook");
                Object instance = hookClass.getMethod("instance").invoke(null);
                Object result = hookClass.getMethod("getItemSellPrice", ItemStack.class).invoke(instance, itemStack);
                if (result instanceof Double) {
                    double price = (Double) result;
                    if (price > 0.0) return price;
                }
            }
        } catch (Exception ignored) {}

        // Try Essentials
        try {
            if (Bukkit.getPluginManager().getPlugin("Essentials") != null) {
                try {
                    Class<?> economyClass = Class.forName("com.earth2me.essentials.api.Economy");
                    Method getWorth = economyClass.getMethod("getWorth", ItemStack.class);
                    Object result = getWorth.invoke(null, itemStack);
                    if (result instanceof BigDecimal) {
                        return ((BigDecimal) result).doubleValue();
                    }
                } catch (Exception ignored2) {}

                Plugin essentials = Bukkit.getPluginManager().getPlugin("Essentials");
                Object worthObj = essentials.getClass().getMethod("getWorth").invoke(essentials);
                Method getPriceMethod = worthObj.getClass().getMethod("getPrice", ItemStack.class);
                Object priceResult = getPriceMethod.invoke(worthObj, itemStack);
                if (priceResult instanceof BigDecimal) {
                    return ((BigDecimal) priceResult).doubleValue();
                }
            }
        } catch (Exception ignored) {}

        return -1.0;
    }
}
