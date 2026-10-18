package me.everyone.oculusai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import me.everyone.oculusai.manager.AnalysisManager;
import me.everyone.oculusai.manager.ApiManager;
import me.everyone.oculusai.manager.CommandManager;
import me.everyone.oculusai.manager.ConfigManager;
import me.everyone.oculusai.manager.DataManager;
import me.everyone.oculusai.manager.DatasetManager;
import me.everyone.oculusai.manager.DisplayManager;
import me.everyone.oculusai.manager.HistoryManager;
import me.everyone.oculusai.manager.JournalManager;
import me.everyone.oculusai.manager.LangManager;
import me.everyone.oculusai.manager.Manager;
import me.everyone.oculusai.manager.MenuManager;
import me.everyone.oculusai.manager.MonitorManager;
import me.everyone.oculusai.manager.NpcManager;
import me.everyone.oculusai.manager.PacketManager;
import me.everyone.oculusai.manager.ProbConfigManager;
import me.everyone.oculusai.manager.PunishmentManager;
import me.everyone.oculusai.manager.SetupManager;
import me.everyone.oculusai.manager.TestServerManager;
import me.everyone.oculusai.manager.ThemeManager;
import me.everyone.oculusai.manager.TrackerManager;
import me.everyone.oculusai.manager.UploadQueue;
import me.everyone.oculusai.manager.UpdateManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class OculusAI extends JavaPlugin {

    private final Map<Class<? extends Manager>, Manager> managers = new LinkedHashMap<>();

    private ConfigManager configManager;
    private ApiManager apiManager;
    private DataManager dataManager;
    private TrackerManager trackerManager;
    private DatasetManager datasetManager;
    private LangManager langManager;
    private ThemeManager themeManager;
    private PunishmentManager punishmentManager;
    private AnalysisManager analysisManager;
    private DisplayManager displayManager;
    private MonitorManager monitorManager;
    private ProbConfigManager probConfigManager;
    private HistoryManager historyManager;
    private JournalManager journalManager;
    private PacketManager packetManager;
    private MenuManager menuManager;
    private CommandManager commandManager;
    private NpcManager npcManager;
    private SetupManager setupManager;
    private UploadQueue uploadQueue;
    private TestServerManager testServerManager;
    private UpdateManager updateManager;
    private String buildVersion = "";
    private String buildFlavour = "";

    @Override
    public void onEnable() {
        saveDefaultConfig();

        configManager = register(new ConfigManager(this));
        themeManager = register(new ThemeManager(this));
        langManager = register(new LangManager(this));
        apiManager = register(new ApiManager(this));
        dataManager = register(new DataManager(this));
        trackerManager = register(new TrackerManager(this));
        uploadQueue = register(new UploadQueue(this));
        datasetManager = register(new DatasetManager(this));
        punishmentManager = register(new PunishmentManager(this));
        analysisManager = register(new AnalysisManager(this));
        probConfigManager = register(new ProbConfigManager(this));
        displayManager = register(new DisplayManager(this));
        monitorManager = register(new MonitorManager(this));
        historyManager = register(new HistoryManager(this));
        journalManager = register(new JournalManager(this));
        packetManager = register(new PacketManager(this));
        menuManager = register(new MenuManager(this));
        commandManager = register(new CommandManager(this));
        npcManager = register(new NpcManager(this));
        setupManager = register(new SetupManager(this));
        testServerManager = register(new TestServerManager(this));
        readBuild();
        updateManager = register(new UpdateManager(this));

        for (Manager manager : managers.values()) {
            try {
                manager.enable();
            } catch (Throwable throwable) {
                getLogger().log(Level.SEVERE, "Failed to enable " + manager.name(), throwable);
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
        }

        getLogger().info("OculusAI enabled, service at " + configManager.apiUrl());
    }

    @Override
    public void onDisable() {
        List<Manager> reversed = new ArrayList<>(managers.values());
        Collections.reverse(reversed);
        for (Manager manager : reversed) {
            try {
                manager.disable();
            } catch (Throwable throwable) {
                getLogger().log(java.util.logging.Level.SEVERE, "Failed to disable " + manager.name(), throwable);
            }
        }
        managers.clear();
    }

    public void reloadEverything() {
        reloadConfig();
        for (Manager manager : managers.values()) {
            manager.reload();
        }
    }

    private <T extends Manager> T register(T manager) {
        managers.put(manager.getClass(), manager);
        return manager;
    }

    public ConfigManager config() {
        return configManager;
    }

    public UpdateManager updates() {
        return updateManager;
    }

    /** This jar's version, as Gradle stamped it at build time. */
    public String buildVersion() {
        return buildVersion;
    }

    /** Which of the two jars this is - "1.16.5" or "1.21.11". The update check
     *  asks for its own flavour by name; handing a 1.21 build to a 1.16 server
     *  would be worse than not updating at all. */
    public String buildFlavour() {
        return buildFlavour;
    }

    /** The installed jar, or null when it cannot be located. A staged update
     *  has to carry the same file name for the server to apply it. */
    public java.nio.file.Path pluginJar() {
        java.io.File file = getFile();
        return file == null ? null : file.toPath();
    }

    private void readBuild() {
        java.util.Properties properties = new java.util.Properties();
        try (java.io.InputStream in = getResource("build.properties")) {
            if (in != null) {
                properties.load(in);
            }
        } catch (java.io.IOException unreadable) {
            getLogger().warning("Could not read build.properties: " + unreadable.getMessage());
        }
        buildVersion = properties.getProperty("version", getDescription().getVersion()).trim();
        buildFlavour = properties.getProperty("flavour", "").trim();
    }

    public ApiManager api() {
        return apiManager;
    }

    public DataManager data() {
        return dataManager;
    }

    public TrackerManager tracker() {
        return trackerManager;
    }

    /** Captures the service has not accepted yet, retried on a timer. */
    public UploadQueue uploads() {
        return uploadQueue;
    }

    public DatasetManager datasets() {
        return datasetManager;
    }

    public LangManager lang() {
        return langManager;
    }

    public ThemeManager theme() {
        return themeManager;
    }

    public PunishmentManager punishments() {
        return punishmentManager;
    }

    public AnalysisManager analysis() {
        return analysisManager;
    }

    public DisplayManager displays() {
        return displayManager;
    }

    public MonitorManager monitors() {
        return monitorManager;
    }

    public ProbConfigManager probConfig() {
        return probConfigManager;
    }

    public HistoryManager history() {
        return historyManager;
    }

    public JournalManager journal() {
        return journalManager;
    }

    public PacketManager packets() {
        return packetManager;
    }

    public MenuManager menus() {
        return menuManager;
    }

    public CommandManager commands() {
        return commandManager;
    }

    public NpcManager npcs() {
        return npcManager;
    }

    public SetupManager setup() {
        return setupManager;
    }

    public TestServerManager testServer() {
        return testServerManager;
    }
}
