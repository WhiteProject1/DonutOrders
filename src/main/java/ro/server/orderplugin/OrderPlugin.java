package ro.server.orderplugin;

import java.util.List;
import java.util.logging.Logger;

import net.milkbowl.vault.economy.Economy;

import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import ro.server.orderplugin.command.AdminCommand;
import ro.server.orderplugin.command.LangCommand;
import ro.server.orderplugin.command.OrderCommand;
import ro.server.orderplugin.config.ConfigUpdater;
import ro.server.orderplugin.config.Settings;
import ro.server.orderplugin.economy.TaxService;
import ro.server.orderplugin.gui.AdminGuiManager;
import ro.server.orderplugin.item.CustomItemBridge;
import ro.server.orderplugin.sync.NoOpSyncService;
import ro.server.orderplugin.sync.PollingSyncService;
import ro.server.orderplugin.sync.SyncService;
import ro.server.orderplugin.gui.GuiListener;
import ro.server.orderplugin.level.LevelManager;
import ro.server.orderplugin.level.LevelStorage;
import ro.server.orderplugin.gui.GuiManager;
import ro.server.orderplugin.i18n.LangListener;
import ro.server.orderplugin.i18n.LangStorage;
import ro.server.orderplugin.i18n.LanguageManager;
import ro.server.orderplugin.i18n.NameTranslator;
import ro.server.orderplugin.menu.MenuRegistry;
import ro.server.orderplugin.model.OrderManager;
import ro.server.orderplugin.storage.MySQLStorage;
import ro.server.orderplugin.scheduler.BukkitSchedulerAdapter;
import ro.server.orderplugin.scheduler.FoliaSchedulerAdapter;
import ro.server.orderplugin.scheduler.SchedulerAdapter;
import ro.server.orderplugin.util.ChatInputManager;
import ro.server.orderplugin.util.CooldownManager;
import ro.server.orderplugin.util.FoliaUtil;
import ro.server.orderplugin.util.InputManager;
import ro.server.orderplugin.util.SoundSpec;
import ro.server.orderplugin.util.PendingMessageManager;
import ro.server.orderplugin.util.SignInputManager;
import ro.server.orderplugin.util.TextUtil;

public final class OrderPlugin extends JavaPlugin {

    private static final Logger LOGGER = Logger.getLogger("Minecraft");
    private static OrderPlugin instance;
    private static Economy economy = null;

    private SchedulerAdapter schedulerAdapter;
    private Settings settings;
    private LanguageManager language;
    private LangStorage langStorage;
    private NameTranslator names;
    private MenuRegistry menus;
    private TaxService tax;
    private CustomItemBridge customItems;
    private SyncService sync;
    private LevelManager levels;
    private LevelStorage levelStorage;
    private OrderManager orderManager;
    private GuiManager guiManager;
    private AdminGuiManager adminGuiManager;
    private PendingMessageManager pendingMessageManager;
    private SignInputManager signInputManager;
    private ChatInputManager chatInputManager;
    private InputManager inputManager;
    private CooldownManager cooldowns;
    private Object cleanupTaskHandle;

    @Override
    public void onEnable() {
        instance = this;

        this.saveDefaultConfig();
        ConfigUpdater.update(this);
        this.reloadConfig();

        this.settings = new Settings(this);
        this.language = new LanguageManager(this);
        this.language.load();
        this.settings.load();
        this.langStorage = new LangStorage(this);
        this.names = new NameTranslator(this.language);
        this.menus = new MenuRegistry(this);
        this.menus.load();

        if (!this.setupVault()) {
            LOGGER.severe(String.format("[%s] - Vault/Ekonomi eklentisi bulunamadi! Devre disi birakiliyor.",
                    this.getDescription().getName()));
            this.getServer().getPluginManager().disablePlugin((Plugin) this);
            return;
        }

        this.schedulerAdapter = FoliaUtil.isFolia() ? new FoliaSchedulerAdapter() : new BukkitSchedulerAdapter();
        LOGGER.info(String.format("[%s] Scheduler: %s", this.getDescription().getName(),
                FoliaUtil.isFolia() ? "Folia" : "Bukkit"));

        this.tax = new TaxService(this);
        this.customItems = new CustomItemBridge(this);
        this.customItems.load();
        this.levelStorage = new LevelStorage(this);
        this.levels = new LevelManager(this);
        this.levels.load();

        this.orderManager = new OrderManager(this);
        this.guiManager = new GuiManager(this);
        this.adminGuiManager = new AdminGuiManager(this, this.guiManager);
        this.pendingMessageManager = new PendingMessageManager(this);
        this.signInputManager = new SignInputManager(this);
        this.chatInputManager = new ChatInputManager(this);
        this.inputManager = new InputManager(this);
        this.cooldowns = new CooldownManager(this);
        this.orderManager.loadOrders();
        this.sync = createSyncService();
        this.sync.start();

        // MySQL depolama acikken network.enabled: false ise, bu veritabanini
        // paylasan baska bir sunucu varsa iki sunucu ayni teslimati odeyebilir
        // (tryFillAtomic dogrulamasi devre disi kalir). Sessizce yanlis
        // calismaktansa sahibi burada acikca uyarilir.
        if (this.orderManager.mysqlStorage() != null && !this.sync.enabled()) {
            LOGGER.warning(String.format(
                    "[%s] storage.type MYSQL/MARIADB olarak ayarli ama network.enabled: false! "
                            + "Bu veritabanini birden fazla sunucu paylasiyorsa iki sunucu ayni teslimati "
                            + "odeyebilir. Boyle bir kurulumda config.yml -> network.enabled: true yapin.",
                    this.getDescription().getName()));
        }

        this.getCommand("order").setExecutor((CommandExecutor) new OrderCommand(this));
        AdminCommand adminCommand = new AdminCommand(this);
        this.getCommand("donutordersadmin").setExecutor((CommandExecutor) adminCommand);
        this.getCommand("donutordersadmin").setTabCompleter((TabCompleter) adminCommand);
        LangCommand langCommand = new LangCommand(this);
        this.getCommand("orderlang").setExecutor((CommandExecutor) langCommand);
        this.getCommand("orderlang").setTabCompleter((TabCompleter) langCommand);

        this.getServer().getPluginManager().registerEvents((Listener) new GuiListener(this), (Plugin) this);
        this.getServer().getPluginManager().registerEvents((Listener) this.pendingMessageManager, (Plugin) this);
        this.getServer().getPluginManager().registerEvents((Listener) this.signInputManager, (Plugin) this);
        this.getServer().getPluginManager().registerEvents((Listener) this.chatInputManager, (Plugin) this);
        this.getServer().getPluginManager().registerEvents((Listener) new LangListener(this), (Plugin) this);

        long interval = this.settings.cleanupIntervalTicks();
        this.cleanupTaskHandle = this.schedulerAdapter.runGlobalTimer(this, () -> {
            this.orderManager.cleanupExpiredOrders();
            // Seviye kayitlari her xp kazaniminda degil, burada toplu yazilir;
            // teslim eden bir oyuncu saniyede birkac kez xp kazanabilir.
            this.levelStorage.flush();
        }, interval, interval);

        LOGGER.info(String.format("[%s] Aktif - Surum %s", this.getDescription().getName(),
                this.getDescription().getVersion()));
    }

    @Override
    public void onDisable() {
        if (this.cleanupTaskHandle != null && this.schedulerAdapter != null) {
            this.schedulerAdapter.cancelTask(this.cleanupTaskHandle);
        }
        if (this.sync != null) this.sync.stop();
        if (this.levels != null) this.levels.flush();
        // Kapanista scheduler durdugu icin arka plana atmadan, burada yazilir.
        if (this.levelStorage != null) this.levelStorage.flushNow();
        if (this.orderManager != null) {
            this.orderManager.saveOrders();
            this.orderManager.shutdown();
        }
        LOGGER.info(String.format("[%s] Devre disi.", this.getDescription().getName()));
    }


    /**
     * Ag servisini kurar.
     *
     * <p>Cross-server ortak bir veritabani gerektirir. MEMORY/SQLITE secilmisken
     * acilirsa <b>sessizce yanlis calismaktansa</b> net bir hata basilip
     * senkronizasyon kapatilir: her sunucu kendi dosyasini yazardi ve siparisler
     * asla eslesmezdi.</p>
     */
    private SyncService createSyncService() {
        String id = getConfig().getString("network.server-id", "");
        if (id == null || id.isBlank()) {
            id = getServer().getName() + "-" + getServer().getPort();
        }

        if (!getConfig().getBoolean("network.enabled", false)) {
            return new NoOpSyncService(id);
        }

        MySQLStorage storage = this.orderManager.mysqlStorage();
        if (storage == null) {
            LOGGER.severe("network.enabled: true ama storage.type MYSQL/MARIADB degil! "
                    + "Cross-server ortak bir veritabani gerektirir; senkronizasyon KAPATILDI.");
            return new NoOpSyncService(id);
        }

        long interval = getConfig().getLong("network.poll.interval-ticks", 40L);
        int batch = getConfig().getInt("network.poll.batch-size", 200);
        long timeout = getConfig().getLong("network.heartbeat-timeout", 30L) * 1000L;
        return new PollingSyncService(this, storage, id, interval, batch, timeout);
    }

    private boolean setupVault() {
        if (this.getServer().getPluginManager().getPlugin("Vault") == null) {
            LOGGER.severe("Vault bulunamadi!");
            return false;
        }
        return this.setupEconomy();
    }

    private boolean setupEconomy() {
        RegisteredServiceProvider<Economy> rsp = this.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            LOGGER.severe("Ekonomi eklentisi bulunamadi (ornegin Essentials, CMI)!");
            return false;
        }
        economy = rsp.getProvider();
        return economy != null;
    }

    // ------------------------------------------------------------------ metin

    /** Alicinin dilinde, renkleri uygulanmis metin. */
    public String msg(CommandSender target, String key, String... replacements) {
        return this.language.msg(target, key, replacements);
    }

    /** Renk kodlari uygulanmamis ham metin (karsilastirma yapmak icin). */
    public String rawMsg(CommandSender target, String key) {
        return this.language.raw(this.language.resolve(target), key);
    }

    /** Liste degerli metin (lore, tabela satirlari, animasyon kareleri). */
    public List<String> msgList(CommandSender target, String key, String... replacements) {
        return this.language.msgList(target, key, replacements);
    }

    /** Oyuncuya ses calar; {@code sounds.enabled} kapaliysa ya da anahtar yoksa sessizdir. */
    public void playSound(Player player, String soundKey, float volume, float pitch) {
        if (soundKey == null || !this.settings.soundsEnabled()) return;
        try {
            player.playSound(player.getLocation(), soundKey, volume, pitch);
        } catch (Exception e) {
            // Gecersiz ses anahtari menuyu bozmamali.
            getLogger().warning("Ses calinamadi: " + soundKey + " (" + e.getMessage() + ")");
        }
    }

    /** Yapilandirilmis bir ses tanimini calar. */
    public void playSound(Player player, SoundSpec sound) {
        if (sound == null || sound.silent()) return;
        playSound(player, sound.key(), sound.volume(), sound.pitch());
    }

    /**
     * {@code sounds.events.<id>} altinda tanimli olay sesini calar.
     *
     * <p>Bilinmeyen kimlik sessizce yok sayilir; yeni bir olay eklerken sesi
     * config'e yazmayi unutmak eklentiyi bozmaz.</p>
     */
    public void playEventSound(Player player, String id) {
        playSound(player, this.settings.eventSound(id));
    }

    public void playError(Player player) {
        playEventSound(player, "error");
    }

    public void playSuccess(Player player) {
        playEventSound(player, "success");
    }

    // ------------------------------------------------------------------ yeniden yukleme

    public void reloadAllConfigs() {
        LOGGER.info("Tum yapilandirmalar yeniden yukleniyor...");
        this.reloadConfig();
        ConfigUpdater.update(this);
        this.reloadConfig();
        this.language.load();
        this.settings.load();
        this.names.invalidate();
        this.langStorage.reload();
        this.menus.load();
        this.customItems.load();
        if (this.levels != null) {
            // Once bekleyen xp diske yazilir: yeniden yukleme veri kaybettirmemeli.
            // Arka plana atilamaz — reload() dosyayi hemen ardindan okuyor.
            this.levels.flush();
            this.levelStorage.flushNow();
            this.levelStorage.reload();
            this.levels.load();
        }
        if (this.guiManager != null) {
            this.guiManager.invalidateAllOrderCaches();
            this.guiManager.rebuildItemList();
        }
        // Sayaclar sifirlanir: sureleri kisaltan bir sunucu sahibi eski, uzun
        // beklemenin bitmesini beklemek zorunda kalmasin.
        if (this.cooldowns != null) this.cooldowns.clear();
        LOGGER.info("Yapilandirmalar yeniden yuklendi.");
    }

    // ------------------------------------------------------------------ erisim

    public static OrderPlugin getInstance() { return instance; }
    public static Economy getEconomy() { return economy; }

    public SchedulerAdapter getSchedulerAdapter() { return this.schedulerAdapter; }
    public Settings settings() { return this.settings; }
    public LanguageManager getLanguage() { return this.language; }
    public LangStorage getLangStorage() { return this.langStorage; }
    public NameTranslator names() { return this.names; }
    public MenuRegistry menus() { return this.menus; }
    public TaxService tax() { return this.tax; }
    public CustomItemBridge customItems() { return this.customItems; }
    public SyncService sync() { return this.sync; }
    public LevelManager levels() { return this.levels; }
    public LevelStorage getLevelStorage() { return this.levelStorage; }
    public OrderManager getOrderManager() { return this.orderManager; }
    public GuiManager getGuiManager() { return this.guiManager; }
    public AdminGuiManager adminGui() { return this.adminGuiManager; }
    public PendingMessageManager getPendingMessageManager() { return this.pendingMessageManager; }
    public SignInputManager getSignInputManager() { return this.signInputManager; }
    public ChatInputManager getChatInputManager() { return this.chatInputManager; }
    public InputManager input() { return this.inputManager; }
    public CooldownManager cooldowns() { return this.cooldowns; }

    /** config.yml -> prefix, renkleri uygulanmis. */
    public String prefix() {
        return TextUtil.colorize(getConfig().getString("prefix", ""));
    }
}
