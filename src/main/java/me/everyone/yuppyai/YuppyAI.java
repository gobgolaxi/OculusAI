package me.everyone.yuppyai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import me.everyone.yuppyai.manager.AnalysisManager;
import me.everyone.yuppyai.manager.ApiManager;
import me.everyone.yuppyai.manager.CommandManager;
import me.everyone.yuppyai.manager.ConfigManager;
import me.everyone.yuppyai.manager.DataManager;
import me.everyone.yuppyai.manager.DatasetManager;
import me.everyone.yuppyai.manager.DisplayManager;
import me.everyone.yuppyai.manager.HistoryManager;
import me.everyone.yuppyai.manager.JournalManager;
import me.everyone.yuppyai.manager.LangManager;
import me.everyone.yuppyai.manager.Manager;
import me.everyone.yuppyai.manager.MenuManager;
import me.everyone.yuppyai.manager.MonitorManager;
import me.everyone.yuppyai.manager.NpcManager;
import me.everyone.yuppyai.manager.PacketManager;
import me.everyone.yuppyai.manager.ProbConfigManager;
import me.everyone.yuppyai.manager.PunishmentManager;
import me.everyone.yuppyai.manager.SetupManager;
import me.everyone.yuppyai.manager.TestServerManager;
import me.everyone.yuppyai.manager.ThemeManager;
import me.everyone.yuppyai.manager.TrackerManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class YuppyAI extends JavaPlugin {

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
    private TestServerManager testServerManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        configManager = register(new ConfigManager(this));
        themeManager = register(new ThemeManager(this));
        langManager = register(new LangManager(this));
        apiManager = register(new ApiManager(this));
        dataManager = register(new DataManager(this));
        trackerManager = register(new TrackerManager(this));
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

        for (Manager manager : managers.values()) {
            try {
                manager.enable();
            } catch (Throwable throwable) {
                getLogger().log(Level.SEVERE, "Failed to enable " + manager.name(), throwable);
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
        }

        getLogger().info("YuppyAI enabled, service at " + configManager.apiUrl());
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

    public ApiManager api() {
        return apiManager;
    }

    public DataManager data() {
        return dataManager;
    }

    public TrackerManager tracker() {
        return trackerManager;
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
