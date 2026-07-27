package main;

import entity.enemy.DefaultEnemy;
import entity.enemy.EnemyFactory;
import entity.enemy.Slime;
import net.NetworkMode;
import net.NetworkSession;
import render.vulkan.VulkanGameRenderer;
import render.vulkan.VulkanRenderer;
import save.SaveStateManager;
import tile.LevelManager;
import tile.TiledMap;

public class GameContext implements AutoCloseable {
  public final KeyHandler keyHandler;
  public final VulkanRenderer renderer;
  public final VulkanGameRenderer gameRenderer;
  public final AudioManager audioManager;
  public final BiomeRegistry biomeRegistry;
  public final SoundtrackManager soundtrack;
  public final NetworkMode networkMode;
  public final NetworkSession networkSession;
  public final PlayerRoster playerRoster;
  public final LevelManager levelManager;
  public final WorldSimulator worldSimulator;
  public final SaveStateManager saveStateManager;
  public final int screenWidth;
  public final int screenHeight;

  public static GameContext create(LaunchConfig config, long window) {
    int screenWidth = config.screenWidth();
    int screenHeight = config.screenHeight();

    KeyHandler keyHandler = new KeyHandler();

    VulkanRenderer renderer = new VulkanRenderer(window, screenWidth, screenHeight);
    VulkanGameRenderer gameRenderer = new VulkanGameRenderer(renderer, screenWidth, screenHeight);

    AudioManager audioManager = new AudioManager();
    if (!audioManager.init()) {
      System.err.println("AudioManager init failed; continuing without audio.");
    }
    audioManager.loadSound("attack", "audio/attack.ogg");
    audioManager.loadSound("portal", "audio/portal.ogg");
    audioManager.loadSound("click", "audio/click.ogg");

    BiomeRegistry biomeRegistry = new BiomeRegistry();
    biomeRegistry.load("data/biomes.json");
    SoundtrackManager soundtrack = new SoundtrackManager(audioManager, biomeRegistry);

    NetworkMode networkMode = config.networkConfig().mode();
    NetworkSession networkSession = networkMode.isLocal() ? null : new NetworkSession(config.networkConfig());

    PlayerRoster playerRoster = new PlayerRoster(networkMode, keyHandler, audioManager);
    LevelManager levelManager = new LevelManager(audioManager);

    EnemyFactory enemyFactory = (x, y, variant, spawnTileX, spawnTileY, layerName) ->
        "slime".equalsIgnoreCase(layerName)
            ? new Slime(x, y, spawnTileX, spawnTileY)
            : new DefaultEnemy(x, y, variant, spawnTileX, spawnTileY);

    WorldSimulator worldSimulator = new WorldSimulator(levelManager, playerRoster.players(),
        screenWidth, screenHeight, enemyFactory);
    SaveStateManager saveStateManager = new SaveStateManager();

    return new GameContext(keyHandler, renderer, gameRenderer, audioManager, biomeRegistry,
        soundtrack, networkMode, networkSession, playerRoster, levelManager, worldSimulator,
        saveStateManager, screenWidth, screenHeight);
  }

  private GameContext(KeyHandler keyHandler, VulkanRenderer renderer, VulkanGameRenderer gameRenderer,
      AudioManager audioManager, BiomeRegistry biomeRegistry, SoundtrackManager soundtrack,
      NetworkMode networkMode, NetworkSession networkSession, PlayerRoster playerRoster,
      LevelManager levelManager, WorldSimulator worldSimulator, SaveStateManager saveStateManager,
      int screenWidth, int screenHeight) {
    this.keyHandler = keyHandler;
    this.renderer = renderer;
    this.gameRenderer = gameRenderer;
    this.audioManager = audioManager;
    this.biomeRegistry = biomeRegistry;
    this.soundtrack = soundtrack;
    this.networkMode = networkMode;
    this.networkSession = networkSession;
    this.playerRoster = playerRoster;
    this.levelManager = levelManager;
    this.worldSimulator = worldSimulator;
    this.saveStateManager = saveStateManager;
    this.screenWidth = screenWidth;
    this.screenHeight = screenHeight;
  }

  public void registerMaps(String[] mapIds) {
    for (String mapId : mapIds) {
      String mapPath = GamePaths.mapResource(mapId);
      if (resourceExists(mapPath)) levelManager.registerLevel(mapId, mapPath);
    }
    try {
      if (levelManager.hasLevels()) {
        for (String mapId : mapIds) {
          if (levelManager.hasLevel(mapId)) { levelManager.setCurrentMap(mapId); break; }
        }
      }
    } catch (RuntimeException ex) { System.err.println("Level init failed: " + ex.getMessage()); }
  }

  private boolean resourceExists(String path) {
    return Thread.currentThread().getContextClassLoader().getResource(path) != null;
  }

  @Override
  public void close() {
    if (networkSession != null) networkSession.close();
    audioManager.cleanup();
    renderer.close();
  }
}
