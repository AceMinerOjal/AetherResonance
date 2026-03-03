package main;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JPanel;

import entity.Player;
import entity.Enemy;
import entity.player.classes.Mage;
import entity.player.classes.Priest;
import entity.player.classes.Tank;
import entity.player.classes.Warrior;
import lib.Hitbox;
import net.NetInput;
import net.NetPlayerState;
import net.NetSnapshot;
import net.NetworkConfig;
import net.NetworkMode;
import net.NetworkSession;
import save.PlayerSaveState;
import save.SaveState;
import save.SaveStateManager;
import tile.LevelManager;
import tile.TiledMap;

@SuppressWarnings("serial")
public class GamePanel extends JPanel implements Runnable {

  private static final int TILE_SIZE = 32;
  private static final int BASE_WIDTH = 640;
  private static final int BASE_HEIGHT = 360;
  private static final int UPS = 30;
  private static final double PLAYER_SPAWN_X = 100;
  private static final double PLAYER_SPAWN_Y = 100;
  private static final double PARTY_SPAWN_OFFSET = 24;

  private static final int MAX_PLAYERS = 4;

  private static final Color[] PLAYER_COLORS = {
      new Color(75, 200, 255),
      new Color(255, 170, 70),
      new Color(140, 255, 110),
      new Color(255, 105, 180)
  };

  private static final PlayerControls[] SLOT_CONTROLS = {
      new PlayerControls(KeyEvent.VK_W, KeyEvent.VK_S, KeyEvent.VK_A, KeyEvent.VK_D,
          KeyEvent.VK_SHIFT,
          new int[] { KeyEvent.VK_1, KeyEvent.VK_2, KeyEvent.VK_3, KeyEvent.VK_4 }),
      new PlayerControls(KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT,
          KeyEvent.VK_ENTER,
          new int[] { KeyEvent.VK_NUMPAD1, KeyEvent.VK_NUMPAD2, KeyEvent.VK_NUMPAD3, KeyEvent.VK_NUMPAD4 }),
      new PlayerControls(KeyEvent.VK_I, KeyEvent.VK_K, KeyEvent.VK_J, KeyEvent.VK_L,
          KeyEvent.VK_O,
          new int[] { KeyEvent.VK_Z, KeyEvent.VK_X, KeyEvent.VK_C, KeyEvent.VK_V }),
      new PlayerControls(KeyEvent.VK_T, KeyEvent.VK_G, KeyEvent.VK_F, KeyEvent.VK_H,
          KeyEvent.VK_Y,
          new int[] { KeyEvent.VK_R, KeyEvent.VK_U, KeyEvent.VK_B, KeyEvent.VK_N })
  };

  private static final int[] JOIN_KEYS = {
      KeyEvent.VK_F1,
      KeyEvent.VK_F2,
      KeyEvent.VK_F3,
      KeyEvent.VK_F4
  };
  private static final String[] MAP_IDS = { "world", "cave", "dungeon" };
  private static final NetInput EMPTY_INPUT = new NetInput(
      false, false, false, false, false, false, false, false, false);

  final int actualTileSize;
  final int screenWidth;
  final int screenHeight;

  private final NetworkMode networkMode;
  private final NetworkSession networkSession;

  private Thread gameThread;
  private final KeyHandler kh = new KeyHandler();
  private final List<Player> players = new ArrayList<>();
  private final List<Enemy> enemies = new ArrayList<>();
  private final boolean[] joinedSlots = new boolean[MAX_PLAYERS];
  private String enemyMapId;

  private final LevelManager levelManager = new LevelManager();
  private final SaveStateManager saveStateManager = new SaveStateManager();
  private NetSnapshot clientSnapshot = new NetSnapshot("", List.of());

  public GamePanel() {
    this(NetworkConfig.local());
  }

  public GamePanel(NetworkConfig networkConfig) {
    this.networkMode = networkConfig.mode();
    this.networkSession = networkMode.isLocal() ? null : new NetworkSession(networkConfig);

    Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();

    int scale = Math.max(1, Math.min(screenSize.width / BASE_WIDTH, screenSize.height / BASE_HEIGHT));

    actualTileSize = TILE_SIZE * scale;
    screenWidth = BASE_WIDTH * scale;
    screenHeight = BASE_HEIGHT * scale;

    setPreferredSize(new Dimension(screenWidth, screenHeight));
    setBackground(Color.BLACK);
    setDoubleBuffered(true);
    addKeyListener(kh);
    setFocusable(true);

    boolean[] mapExists = new boolean[MAP_IDS.length];
    for (int i = 0; i < MAP_IDS.length; i++) {
      String mapPath = "maps/" + MAP_IDS[i] + ".json";
      mapExists[i] = resourceExists(mapPath);
      if (mapExists[i]) {
        levelManager.registerLevel(MAP_IDS[i], mapPath);
      }
    }

    try {
      if (levelManager.hasLevels()) {
        String startingMap = mapExists[0] ? MAP_IDS[0] : (mapExists[1] ? MAP_IDS[1] : MAP_IDS[2]);
        levelManager.setCurrentMap(startingMap);
      }
    } catch (RuntimeException ex) {
      System.err.println("Level init failed: " + ex.getMessage());
    }

    if (!networkMode.isClient()) {
      joinSlot(0);
    }
  }

  public void startGameThread() {
    gameThread = new Thread(this, "GameThread");
    gameThread.start();
  }

  @Override
  public void run() {
    final double drawInterval = 1_000_000_000.0 / UPS;

    long lastTickNs = System.nanoTime();
    double pendingFrames = 0.0;
    double dtPerFrame = 1.0 / UPS;

    while (gameThread != null) {
      long currentNs = System.nanoTime();
      long elapsedNs = currentNs - lastTickNs;
      lastTickNs = currentNs;

      pendingFrames += elapsedNs / drawInterval;

      while (pendingFrames >= 1) {
        update(dtPerFrame);
        pendingFrames--;
      }

      repaint();

      long sleepMs = (long) ((drawInterval - elapsedNs) / 1_000_000);
      if (sleepMs > 0) {
        try {
          Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
  }

  public void update(double dt) {
    if (networkMode.isClient()) {
      updateClient();
      return;
    }

    if (networkMode.isLocal()) {
      handleJoinHotkeys();
    } else {
      applyHostRemoteInputs();
    }
    handleSaveHotkeys();

    simulateWorld(dt);

    if (networkMode.isHost()) {
      networkSession.publishSnapshot(buildSnapshot());
    }
  }

  private void updateClient() {
    NetInput input = NetInput.fromClientKeys(kh);
    networkSession.sendInput(input);

    NetSnapshot incoming = networkSession.latestSnapshot();
    if (incoming != null) {
      clientSnapshot = incoming;
      if (!incoming.mapId().isBlank() && levelManager.hasLevel(incoming.mapId())
          && (levelManager.getCurrentMapId() == null || !incoming.mapId().equals(levelManager.getCurrentMapId()))) {
        levelManager.setCurrentMap(incoming.mapId());
      }
    }
  }

  private void simulateWorld(double dt) {
    TiledMap map = levelManager.getCurrentMap();
    ensureEnemiesForCurrentMap(map);
    refreshFriendlyFireFlags(map);

    for (Player player : players) {
      double oldX = player.getX();
      double oldY = player.getY();

      player.update(dt);

      if (map != null && map.collides(player.getHitbox())) {
        player.setWorldPosition(oldX, oldY);
      }

      if (map != null) {
        player.clampToBounds(map.getPixelWidth(), map.getPixelHeight());
      } else {
        player.clampToBounds(screenWidth, screenHeight);
      }
    }

    for (Player player : players) {
      if (levelManager.updatePortals(player, players)) {
        map = levelManager.getCurrentMap();
        ensureEnemiesForCurrentMap(map);
        refreshFriendlyFireFlags(map);
        break;
      }
    }

    for (Enemy enemy : enemies) {
      double oldX = enemy.getX();
      double oldY = enemy.getY();
      enemy.update(dt, map, players);
      if (map != null && map.collides(enemy.getHitbox())) {
        enemy.setWorldPosition(oldX, oldY);
      }
    }

    refreshFriendlyFireFlags(map);
  }

  private void ensureEnemiesForCurrentMap(TiledMap map) {
    String currentMapId = levelManager.getCurrentMapId();
    if (map == null || currentMapId == null || currentMapId.isBlank()) {
      enemies.clear();
      enemyMapId = null;
      return;
    }
    if (currentMapId.equals(enemyMapId) && !enemies.isEmpty()) {
      return;
    }

    enemies.clear();
    enemyMapId = currentMapId;

    List<int[]> spawnTiles = map.getEnemySpawnTilesByVariant();
    for (int[] spawn : spawnTiles) {
      int tileX = spawn[0];
      int tileY = spawn[1];
      int variant = spawn[2];
      double spawnX = tileX * map.getTileWidth() + (map.getTileWidth() * 0.5);
      double spawnY = tileY * map.getTileHeight() + (map.getTileHeight() * 0.5);
      enemies.add(new Enemy(spawnX, spawnY, variant));
    }
  }

  private void refreshFriendlyFireFlags(TiledMap map) {
    for (Player player : players) {
      boolean enabled = map != null && map.isFriendlyFireEnabled(player.getHitbox());
      player.setFriendlyFireEnabled(enabled);
    }
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);

    Graphics2D g2 = (Graphics2D) g;
    TiledMap map = levelManager.getCurrentMap();
    if (map != null) {
      map.draw(g2);
    }

    if (networkMode.isClient()) {
      for (NetPlayerState p : clientSnapshot.players()) {
        g2.setColor(PLAYER_COLORS[Math.max(0, Math.min(PLAYER_COLORS.length - 1, p.slot()))]);
        g2.fillRect((int) p.x(), (int) p.y(), (int) p.width(), (int) p.height());
      }
    } else {
      for (Player player : players) {
        int slot = slotForClassName(player.getClass().getName());
        g2.setColor(PLAYER_COLORS[Math.max(0, Math.min(PLAYER_COLORS.length - 1, slot))]);
        Hitbox hb = player.getHitbox();
        g2.fillRect((int) hb.getLeft(), (int) hb.getTop(), (int) hb.getWidth(), (int) hb.getHeight());
      }
      g2.setColor(new Color(210, 60, 60));
      for (Enemy enemy : enemies) {
        Hitbox hb = enemy.getHitbox();
        g2.fillRect((int) hb.getLeft(), (int) hb.getTop(), (int) hb.getWidth(), (int) hb.getHeight());
      }
    }

    g2.dispose();
  }

  private boolean resourceExists(String path) {
    return Thread.currentThread().getContextClassLoader().getResource(path) != null;
  }

  private void handleJoinHotkeys() {
    for (int slot = 0; slot < MAX_PLAYERS; slot++) {
      if (kh.isTriggered(JOIN_KEYS[slot])) {
        if (joinSlot(slot) != null) {
          System.out.println("Player joined: slot " + (slot + 1));
        }
      }
    }
  }

  private Player joinSlot(int slot) {
    if (slot < 0 || slot >= MAX_PLAYERS || joinedSlots[slot]) {
      return null;
    }

    double spawnX = PLAYER_SPAWN_X + (slot % 2) * PARTY_SPAWN_OFFSET;
    double spawnY = PLAYER_SPAWN_Y + (slot / 2) * PARTY_SPAWN_OFFSET;

    Player player = createPlayerForSlot(slot, spawnX, spawnY);
    if (player == null) {
      return null;
    }

    players.add(player);
    joinedSlots[slot] = true;
    syncPartyRefs();
    return player;
  }

  private Player createPlayerForSlot(int slot, double x, double y) {
    return switch (slot) {
      case 0 -> new Mage(x, y, kh, SLOT_CONTROLS[0]);
      case 1 -> new Warrior(x, y, kh, SLOT_CONTROLS[1]);
      case 2 -> new Tank(x, y, kh, SLOT_CONTROLS[2]);
      case 3 -> new Priest(x, y, kh, SLOT_CONTROLS[3]);
      default -> null;
    };
  }

  private int slotForClassName(String className) {
    return switch (className) {
      case "entity.player.classes.Mage" -> 0;
      case "entity.player.classes.Warrior" -> 1;
      case "entity.player.classes.Tank" -> 2;
      case "entity.player.classes.Priest" -> 3;
      default -> -1;
    };
  }

  private void handleSaveHotkeys() {
    if (kh.isTriggered(KeyEvent.VK_F5)) {
      List<PlayerSaveState> snapshots = new ArrayList<>(players.size());
      for (Player player : players) {
        snapshots.add(player.createPlayerSaveState());
      }

      SaveState save = new SaveState(levelManager.getCurrentMapId() == null ? "" : levelManager.getCurrentMapId(), snapshots);
      try {
        saveStateManager.saveQuick(save);
        System.out.println("Saved quicksave.");
      } catch (RuntimeException ex) {
        System.err.println("Save failed: " + ex.getMessage());
      }
    }

    if (kh.isTriggered(KeyEvent.VK_F9)) {
      if (!saveStateManager.hasQuickSave()) {
        System.out.println("No quicksave found.");
        return;
      }

      try {
        SaveState save = saveStateManager.loadQuick();
        applySaveState(save);

        System.out.println("Loaded quicksave.");
      } catch (RuntimeException ex) {
        System.err.println("Load failed: " + ex.getMessage());
      }
    }
  }

  private void applySaveState(SaveState save) {
    if (!save.mapId().isBlank() && levelManager.hasLevel(save.mapId())) {
      levelManager.setCurrentMap(save.mapId());
    }

    players.clear();
    clearJoinedSlots();

    for (PlayerSaveState snapshot : save.players()) {
      int slot = slotForClassName(snapshot.playerClassName());
      if (slot < 0 || joinedSlots[slot]) {
        continue;
      }

      Player player = createPlayerForSlot(slot, snapshot.x(), snapshot.y());
      if (player == null) {
        continue;
      }

      if (!player.loadPlayerSaveState(snapshot)) {
        continue;
      }
      players.add(player);
      joinedSlots[slot] = true;
    }

    if (players.isEmpty()) {
      joinSlot(0);
    }

    TiledMap current = levelManager.getCurrentMap();
    int boundW = current != null ? current.getPixelWidth() : screenWidth;
    int boundH = current != null ? current.getPixelHeight() : screenHeight;
    for (Player player : players) {
      player.clampToBounds(boundW, boundH);
    }
    syncPartyRefs();
  }

  private void applyHostRemoteInputs() {
    for (int slot = 1; slot < MAX_PLAYERS; slot++) {
      NetInput input = networkSession.hostInputs().get(slot);
      if (input != null && !joinedSlots[slot]) {
        joinSlot(slot);
      }
      applyInputToSlot(slot, input == null ? EMPTY_INPUT : input);
    }
  }

  private void applyInputToSlot(int slot, NetInput input) {
    PlayerControls controls = SLOT_CONTROLS[slot];
    int[] skills = controls.skillKeys();
    kh.setVirtualDown(controls.upKey(), input.up());
    kh.setVirtualDown(controls.downKey(), input.down());
    kh.setVirtualDown(controls.leftKey(), input.left());
    kh.setVirtualDown(controls.rightKey(), input.right());
    kh.setVirtualDown(controls.itemModifierKey(), input.item());
    kh.setVirtualDown(skills[0], input.skill1());
    kh.setVirtualDown(skills[1], input.skill2());
    kh.setVirtualDown(skills[2], input.skill3());
    kh.setVirtualDown(skills[3], input.skill4());
  }

  private NetSnapshot buildSnapshot() {
    List<NetPlayerState> states = new ArrayList<>(players.size());
    for (Player player : players) {
      int slot = slotForClassName(player.getClass().getName());
      Hitbox hb = player.getHitbox();
      states.add(new NetPlayerState(slot, hb.getLeft(), hb.getTop(), hb.getWidth(), hb.getHeight()));
    }
    return new NetSnapshot(levelManager.getCurrentMapId() == null ? "" : levelManager.getCurrentMapId(), states);
  }

  private void clearJoinedSlots() {
    Arrays.fill(joinedSlots, false);
  }

  private void syncPartyRefs() {
    for (Player player : players) {
      player.setParty(players);
    }
  }

  public void shutdown() {
    gameThread = null;
    if (networkSession != null) {
      networkSession.close();
    }
  }
}
