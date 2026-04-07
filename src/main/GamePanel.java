package main;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JPanel;

import entity.player.Player;
import net.NetInput;
import net.NetPlayerState;
import net.NetSnapshot;
import net.NetworkConfig;
import net.NetworkMode;
import net.NetworkSession;
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

  static final double PLAYER_SPAWN_X = 100;
  static final double PLAYER_SPAWN_Y = 100;
  static final double PARTY_SPAWN_OFFSET = 24;

  private static final int[] JOIN_KEYS = {
      KeyEvent.VK_F1,
      KeyEvent.VK_F2,
      KeyEvent.VK_F3,
      KeyEvent.VK_F4
  };
  private static final String[] MAP_IDS = GamePaths.DEFAULT_MAP_IDS;

  final int actualTileSize;
  final int screenWidth;
  final int screenHeight;

  private final NetworkMode networkMode;
  private final NetworkSession networkSession;
  private final KeyHandler kh = new KeyHandler();
  private final PlayerRoster playerRoster;
  private final LevelManager levelManager = new LevelManager();
  private final SaveStateManager saveStateManager = new SaveStateManager();
  private final GameRenderer gameRenderer = new GameRenderer();
  private final WorldSimulator worldSimulator;

  private Thread gameThread;
  private NetSnapshot clientSnapshot = new NetSnapshot("", java.util.List.of(), java.util.List.of());
  private boolean peerDisconnected = false;
  private long lastReconnectAttempt = 0;
  private static final long RECONNECT_INTERVAL_MS = 2000;

  public GamePanel() {
    this(NetworkConfig.local());
  }

  public GamePanel(NetworkConfig networkConfig) {
    this.networkMode = networkConfig.mode();
    this.networkSession = networkMode.isLocal() ? null : new NetworkSession(networkConfig);
    this.playerRoster = new PlayerRoster(networkMode, kh);

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

    registerMaps();
    worldSimulator = new WorldSimulator(levelManager, playerRoster.players(), screenWidth, screenHeight);

    if (!networkMode.isPeer()) {
      joinSlot(0);
    }

    // Set up restore listener for host mode
    if (networkSession != null && networkMode.isHost()) {
      networkSession.setRestoreListener(this::handlePeerRestore);
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
    if (networkMode.isPeer()) {
      updatePeer();
      return;
    }

    if (networkMode.isLocal()) {
      handleJoinHotkeys();
    }
    handleSaveHotkeys();

    if (networkMode.isHost()) {
      ensureConnectedSlotsJoined();
      playerRoster.syncNetworkPlayers(networkSession);
    }

    worldSimulator.simulate(dt);

    if (networkMode.isP2P()) {
      networkSession.publishSnapshot(buildSnapshot());
    }
  }

  private void updatePeer() {
    refreshPeerConnection();
    boolean wasDisconnected = peerDisconnected;
    peerDisconnected = networkSession != null && !networkSession.isConnected();
    handlePeerConnectionTransition(wasDisconnected);

    if (peerDisconnected) {
      runDisconnectedPeerSimulation();
    } else {
      syncConnectedPeerSnapshot();
    }
  }

  private void refreshPeerConnection() {
    if (networkSession == null) {
      return;
    }
    networkSession.updateConnectionState();
    if (networkSession.isConnected()) {
      return;
    }

    long now = System.currentTimeMillis();
    if (now - lastReconnectAttempt >= RECONNECT_INTERVAL_MS) {
      networkSession.attemptReconnect();
      lastReconnectAttempt = now;
    }
  }

  private void handlePeerConnectionTransition(boolean wasDisconnected) {
    if (networkSession == null) {
      return;
    }
    if (peerDisconnected && !wasDisconnected) {
      System.out.println("[P2P] Transitioning to local simulation mode.");
      List<Player> localPlayers = playerRoster.players();
      if (!localPlayers.isEmpty()) {
        networkSession.setRestoreState(localPlayers.get(0).createPlayerSaveState());
      }
      return;
    }
    if (!peerDisconnected && wasDisconnected) {
      System.out.println("[P2P] Reconnected to host, sending restore state.");
      networkSession.sendRestoreStateIfNeeded();
    }
  }

  private void runDisconnectedPeerSimulation() {
    handleJoinHotkeys();
    handleSaveHotkeys();
    worldSimulator.simulate(1.0 / UPS);
    if (!playerRoster.isJoined(0)) {
      joinSlot(0);
    }
    clientSnapshot = buildSnapshot();
  }

  private void syncConnectedPeerSnapshot() {
    NetInput input = NetInput.fromClientKeys(kh);
    networkSession.sendInput(input);
    networkSession.sendRestoreStateIfNeeded();

    NetSnapshot incoming = networkSession.latestSnapshot();
    if (incoming == null) {
      return;
    }

    clientSnapshot = incoming;
    syncRemoteMap(incoming);
  }

  private void syncRemoteMap(NetSnapshot incoming) {
    if (incoming.mapId().isBlank() || !levelManager.hasLevel(incoming.mapId())) {
      return;
    }
    String currentMapId = levelManager.getCurrentMapId();
    if (currentMapId == null || !incoming.mapId().equals(currentMapId)) {
      levelManager.setCurrentMap(incoming.mapId());
    }
  }

  private void handleJoinHotkeys() {
    for (int slot = 0; slot < JOIN_KEYS.length; slot++) {
      if (kh.isTriggered(JOIN_KEYS[slot]) && joinSlot(slot) != null) {
        System.out.println("Player joined: slot " + (slot + 1));
      }
    }
  }

  private void handleSaveHotkeys() {
    if (kh.isTriggered(KeyEvent.VK_F5)) {
      SaveState save = new SaveState(levelManager.getCurrentMapId() == null ? "" : levelManager.getCurrentMapId(),
          playerRoster.createSaveStates());
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
        applySaveState(saveStateManager.loadQuick());
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

    TiledMap current = levelManager.getCurrentMap();
    int boundW = current != null ? current.getPixelWidth() : screenWidth;
    int boundH = current != null ? current.getPixelHeight() : screenHeight;
    playerRoster.restorePlayers(save.players(), boundW, boundH, PLAYER_SPAWN_X, PLAYER_SPAWN_Y);
  }

  private NetSnapshot buildSnapshot() {
    return new NetSnapshot(levelManager.getCurrentMapId() == null ? "" : levelManager.getCurrentMapId(),
        playerRoster.buildNetStates(), worldSimulator.buildNetStates());
  }

  /**
   * Called when a peer reconnects and sends their restore state.
   * Restores the peer's local player with their accumulated stats/position.
   */
  private void handlePeerRestore(int slot, save.PlayerSaveState state) {
    TiledMap current = levelManager.getCurrentMap();
    int boundW = current != null ? current.getPixelWidth() : screenWidth;
    int boundH = current != null ? current.getPixelHeight() : screenHeight;

    playerRoster.restorePlayerFromState(slot, state, boundW, boundH);
    System.out.println("[P2P] Restored peer in slot " + slot + " at (" + state.x() + ", " + state.y() + ")");
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);

    Graphics2D g2 = (Graphics2D) g;
    TiledMap map = levelManager.getCurrentMap();
    if (networkMode.isPeer()) {
      gameRenderer.renderRemote(g2, map, clientSnapshot);
      if (peerDisconnected) {
        g2.setColor(new Color(1, 0, 0, 0.7f));
        g2.fillRect(0, 0, screenWidth, 20);
        g2.setColor(java.awt.Color.WHITE);
        g2.drawString("DISCONNECTED - Running in local mode", 10, 14);
      }
    } else {
      gameRenderer.renderLocal(g2, map, playerRoster.players(), worldSimulator.enemies());
    }
    g2.dispose();
  }

  private void ensureConnectedSlotsJoined() {
    for (int slot : networkSession.connectedSlots()) {
      if (!playerRoster.isJoined(slot)) {
        joinSlot(slot);
      }
    }
  }

  private Player joinSlot(int slot) {
    double spawnX = PLAYER_SPAWN_X + (slot % 2) * PARTY_SPAWN_OFFSET;
    double spawnY = PLAYER_SPAWN_Y + (slot / 2) * PARTY_SPAWN_OFFSET;
    return playerRoster.joinSlot(slot, spawnX, spawnY);
  }

  private void registerMaps() {
    boolean[] mapExists = new boolean[MAP_IDS.length];
    for (int i = 0; i < MAP_IDS.length; i++) {
      String mapPath = GamePaths.mapResource(MAP_IDS[i]);
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
  }

  private boolean resourceExists(String path) {
    return Thread.currentThread().getContextClassLoader().getResource(path) != null;
  }

  public void shutdown() {
    gameThread = null;
    if (networkSession != null) {
      networkSession.close();
    }
  }
}
