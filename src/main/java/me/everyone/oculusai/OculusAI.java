package me.everyone.oculusai;

import java.util.Properties;
import me.everyone.oculusai.check.Alerts;
import me.everyone.oculusai.check.Checks;
import me.everyone.oculusai.command.Commands;
import me.everyone.oculusai.config.Config;
import me.everyone.oculusai.config.Configuration;
import me.everyone.oculusai.config.HudConfig;
import me.everyone.oculusai.config.Language;
import me.everyone.oculusai.config.Themes;
import me.everyone.oculusai.core.AsyncWorker;
import me.everyone.oculusai.core.MainThread;
import me.everyone.oculusai.core.Platform;
import me.everyone.oculusai.core.Service;
import me.everyone.oculusai.core.ServiceHost;
import me.everyone.oculusai.core.TickLoop;
import me.everyone.oculusai.detection.Detection;
import me.everyone.oculusai.diagnostics.TestServer;
import me.everyone.oculusai.network.ApiClient;
import me.everyone.oculusai.network.Network;
import me.everyone.oculusai.network.UploadQueue;
import me.everyone.oculusai.npc.Npcs;
import me.everyone.oculusai.packet.PacketBridge;
import me.everyone.oculusai.player.Bypass;
import me.everyone.oculusai.player.EntityTracker;
import me.everyone.oculusai.player.PlayerRegistry;
import me.everyone.oculusai.player.Players;
import me.everyone.oculusai.player.SetupNotifier;
import me.everyone.oculusai.punishment.BanCounter;
import me.everyone.oculusai.punishment.Punishments;
import me.everyone.oculusai.storage.DatasetStore;
import me.everyone.oculusai.storage.HistoryStore;
import me.everyone.oculusai.storage.JournalStore;
import me.everyone.oculusai.storage.Storage;
import me.everyone.oculusai.ui.Hud;
import me.everyone.oculusai.ui.Menus;
import me.everyone.oculusai.ui.Monitor;
import me.everyone.oculusai.ui.Ui;
import me.everyone.oculusai.update.Updater;
import org.bukkit.plugin.java.JavaPlugin;

public final class OculusAI extends JavaPlugin {

    private ServiceHost host;
    private AsyncWorker worker;
    private MainThread mainThread;
    private Platform platform;
    private TickLoop ticks;

    private Configuration configuration;
    private Network network;
    private Players players;
    private Bypass bypass;
    private Storage storage;
    private Ui ui;
    private Alerts alerts;
    private Punishments punishments;
    private BanCounter banCounter;
    private Checks checks;
    private Detection detection;
    private PacketBridge packets;
    private Commands commands;
    private Npcs npcs;
    private TestServer testServer;
    private Updater updater;

    private String buildVersion = "";
    private String buildFlavour = "";
    private boolean running;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        host = new ServiceHost(getLogger());
        worker = new AsyncWorker(getLogger());
        mainThread = new MainThread(this);
        platform = new Platform(this);
        if (platform.folia()) {
            getLogger().info("Folia detected: analysis, alerts and punishments run per region."
                    + " Holograms, the sparring manikin and the test arena are off - each of"
                    + " them reads entities across region boundaries, which Folia does not"
                    + " allow from one thread.");
        }

        ticks = register(new TickLoop(this));
        configuration = register(new Configuration(this));
        network = register(new Network(this));
        players = register(new Players(this));
        bypass = register(new Bypass(this));
        storage = register(new Storage(this));
        alerts = register(new Alerts(this));
        banCounter = register(new BanCounter(this));
        punishments = register(new Punishments(this));
        checks = register(new Checks(this));
        detection = register(new Detection(this));
        ui = register(new Ui(this));
        packets = register(new PacketBridge(this));
        commands = register(new Commands(this));
        npcs = register(new Npcs(this));
        testServer = register(new TestServer(this));
        readBuild();
        updater = register(new Updater(this));

        try {
            host.enableAll();
            running = true;
        } catch (Throwable failure) {
            host.clear();
            worker.shutdown();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getLogger().info("OculusAI enabled, service at " + config().apiUrl());
    }

    @Override
    public void onDisable() {
        if (running && host != null) {
            host.disableAll();
        }
        running = false;
        if (host != null) {
            host.clear();
        }
        if (worker != null) {
            worker.shutdown();
        }
    }

    public void reloadEverything() {
        reloadConfig();
        host.reloadAll();
    }

    private <T extends Service> T register(T service) {
        return host.add(service);
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
        Properties properties = new Properties();
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

    public TickLoop ticks() {
        return ticks;
    }

    public AsyncWorker worker() {
        return worker;
    }

    public MainThread mainThread() {
        return mainThread;
    }

    /** Which server fork this is, and how to get onto the thread that owns a
     *  given entity. */
    public Platform platform() {
        return platform;
    }

    public Configuration configuration() {
        return configuration;
    }

    public Config config() {
        return configuration.settings();
    }

    public Language lang() {
        return configuration.language();
    }

    public Themes theme() {
        return configuration.theme();
    }

    public HudConfig hudConfig() {
        return configuration.hud();
    }

    public Network network() {
        return network;
    }

    public ApiClient api() {
        return network.api();
    }

    /** Captures the service has not accepted yet, retried on a timer. */
    public UploadQueue uploads() {
        return network.uploads();
    }

    public Players players() {
        return players;
    }

    public PlayerRegistry data() {
        return players.registry();
    }

    public Bypass bypass() {
        return bypass;
    }

    public EntityTracker tracker() {
        return players.tracker();
    }

    public SetupNotifier setup() {
        return players.setup();
    }

    public Storage storage() {
        return storage;
    }

    public DatasetStore datasets() {
        return storage.datasets();
    }

    public HistoryStore history() {
        return storage.history();
    }

    public JournalStore journal() {
        return storage.journal();
    }

    public Ui ui() {
        return ui;
    }

    public Hud displays() {
        return ui.hud();
    }

    public Monitor monitors() {
        return ui.monitor();
    }

    public Menus menus() {
        return ui.menus();
    }

    public Punishments punishments() {
        return punishments;
    }

    /** Punishments collected towards a ban, per player. */
    public BanCounter banCounter() {
        return banCounter;
    }

    public Alerts alerts() {
        return alerts;
    }

    public Checks checks() {
        return checks;
    }

    public Detection detection() {
        return detection;
    }

    public Detection analysis() {
        return detection;
    }

    public PacketBridge packets() {
        return packets;
    }

    public Commands commands() {
        return commands;
    }

    public Npcs npcs() {
        return npcs;
    }

    public TestServer testServer() {
        return testServer;
    }

    public Updater updates() {
        return updater;
    }
}
