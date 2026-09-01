package ro.server.orderplugin.util;

import java.util.Arrays;
import java.util.stream.Collectors;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class ItemBuilder {
    private final ItemStack item;
    private final ItemMeta meta;

    public ItemBuilder(Material material) {
        this.item = new ItemStack(material);
        this.meta = this.item.getItemMeta();
    }

    public ItemBuilder(ItemStack itemStack) {
        this.item = new ItemStack(itemStack.clone());
        this.meta = itemStack.getItemMeta();
    }

    public ItemBuilder setName(String name) {
        this.meta.setDisplayName(TextUtil.colorize(name));
        return this;
    }

    public ItemBuilder setLore(String... lore) {
        this.meta.setLore(Arrays.stream(lore).map(TextUtil::colorize).collect(Collectors.toList()));
        return this;
    }

    public ItemBuilder setLore(java.util.List<String> lore) {
        this.meta.setLore(lore.stream().map(TextUtil::colorize).collect(Collectors.toList()));
        return this;
    }

    /** Kaynak paketi model numarasi; 0 ya da negatif ise dokunulmaz. */
    public ItemBuilder setCustomModelData(int data) {
        if (data > 0) this.meta.setCustomModelData(data);
        return this;
    }

    /**
     * Buyu parildamasi ekler.
     *
     * <p>Gorunmez bir buyu + HIDE_ENCHANTS ile yapilir; boylece esyanin
     * aciklamasinda "Unbreaking I" gibi bir satir belirmez.</p>
     */
    public ItemBuilder setGlowing() {
        try {
            this.meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
        } catch (Exception ignored) {
            // Eski/yeni surum enum farki parildamayi kaybettirir, menuyu bozmaz.
        }
        this.meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        return this;
    }

    public ItemBuilder setAmount(int amount) {
        this.item.setAmount(amount);
        return this;
    }

    public ItemBuilder hideFlags() {
        this.meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        this.meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        this.meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        this.meta.addItemFlags(ItemFlag.HIDE_DESTROYS);
        this.meta.addItemFlags(ItemFlag.HIDE_PLACED_ON);
        this.meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        this.meta.addItemFlags(ItemFlag.HIDE_DYE);
        return this;
    }

    public ItemStack build() {
        this.item.setItemMeta(this.meta);
        return this.item;
    }
}
