package main;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.glfw.*;
import org.lwjgl.system.MemoryUtil;

import entity.player.Player;
import net.NetInput;
import net.NetSnapshot;
import net.NetworkConfig;
import net.NetworkMode;
import net.NetworkSession;
import render.vulkan.VulkanRenderer;
import render.vulkan.VulkanRenderer.RenderCommand;
import render.vulkan.VulkanGameRenderer;
import save.SaveState;
import save.SaveStateManager;
import tile.LevelManager;
import tile.TiledMap;

public class Main {

  private static final int BASE_WIDTH = 640;
  private static final int BASE_HEIGHT = 360;
  private static final int UPS = 30;

  private static final int[] JOIN_KEYS_AWT = {
      java.awt.event.KeyEvent.VK_F1,
      java.awt.event.KeyEvent.VK_F2,
      java.awt.event.KeyEvent.VK_F3,
      java.awt.event.KeyEvent.VK_F4
  };

  private static final String[] MAP_IDS = GamePaths.DEFAULT_MAP_IDS;

  private static final double PLAYER_SPAWN_X = 100;
  private static final double PLAYER_SPAWN_Y = 100;
  private static final double PARTY_SPAWN_OFFSET = 24;

  public static void main(String[] args) {
    LaunchOptions launchOptions = parseArgs(args);

    if (!glfwInit()) {
      throw new IllegalStateException("Unable to initialize GLFW");
    }

    GLFWVidMode vidMode = glfwGetVideoMode(glfwGetPrimaryMonitor());
    int scale = Math.max(1, Math.min(vidMode.width() / BASE_WIDTH, vidMode.height() / BASE_HEIGHT));
    int screenWidth = BASE_WIDTH * scale;
    int screenHeight = BASE_HEIGHT * scale;

    glfwDefaultWindowHints();
    glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
    glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);
    glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);

    long window = glfwCreateWindow(BASE_WIDTH, BASE_HEIGHT, "AetherResonance", MemoryUtil.NULL, MemoryUtil.NULL);
    if (window == MemoryUtil.NULL) {
      glfwTerminate();
      throw new RuntimeException("Failed to create GLFW window");
    }

    glfwSetWindowSize(window, screenWidth, screenHeight);
    GLFWVidMode mode = glfwGetVideoMode(glfwGetPrimaryMonitor());
    glfwSetWindowPos(window, (mode.width() - screenWidth) / 2, (mode.height() - screenHeight) / 2);

    KeyHandler keyHandler = new KeyHandler();
    glfwSetKeyCallback(window, createGlfwKeyCallback(keyHandler));
    glfwShowWindow(window);

    VulkanRenderer renderer = new VulkanRenderer(window, screenWidth, screenHeight);
    VulkanGameRenderer gameRenderer = new VulkanGameRenderer(renderer, screenWidth, screenHeight);

    AudioManager.init();
    // Load sounds here (placeholders)
    AudioManager.loadSound("attack", "audio/attack.ogg");
    AudioManager.loadSound("portal", "audio/portal.ogg");
    AudioManager.loadSound("click", "audio/click.ogg");

    NetworkMode networkMode = launchOptions.networkConfig().mode();
    NetworkSession networkSession = networkMode.isLocal() ? null : new NetworkSession(launchOptions.networkConfig());

    PlayerRoster playerRoster = new PlayerRoster(networkMode, keyHandler);
    LevelManager levelManager = new LevelManager();
    WorldSimulator worldSimulator = new WorldSimulator(levelManager, playerRoster.players(), screenWidth, screenHeight);

    registerMaps(levelManager);

    if (!networkMode.isPeer()) {
      joinSlot(0, playerRoster);
    }

    if (networkSession != null && networkMode.isHost()) {
      networkSession.setRestoreListener((slot, state) -> {
        TiledMap current = levelManager.getCurrentMap();
        int boundW = current != null ? current.getPixelWidth() : screenWidth;
        int boundH = current != null ? current.getPixelHeight() : screenHeight;
        playerRoster.restorePlayerFromState(slot, state, boundW, boundH);
        System.out.println("[P2P] Restored peer in slot " + slot + " at (" + state.x() + ", " + state.y() + ")");
      });
    }

    NetSnapshot clientSnapshot = new NetSnapshot("", List.of(), List.of());
    boolean peerDisconnected = false;
    long[] lastReconnectAttempt = { 0 };
    final long RECONNECT_INTERVAL_MS = 2000;

    final double drawInterval = 1_000_000_000.0 / UPS;
    long lastTickNs = System.nanoTime();
    double pendingFrames = 0.0;

    while (!glfwWindowShouldClose(window)) {
      glfwPollEvents();

      long currentNs = System.nanoTime();
      long elapsedNs = currentNs - lastTickNs;
      lastTickNs = currentNs;
      pendingFrames += elapsedNs / drawInterval;

      while (pendingFrames >= 1) {
        if (networkMode.isPeer()) {
          PeerUpdateResult peerUpdate = updatePeer(networkSession, keyHandler, playerRoster, worldSimulator,
              levelManager, lastReconnectAttempt, RECONNECT_INTERVAL_MS);
          peerDisconnected = peerUpdate.disconnected();
          clientSnapshot = peerUpdate.snapshot();
        } else {
          updateHostOrLocal(networkSession, networkMode, keyHandler, playerRoster, worldSimulator, levelManager);
        }
        pendingFrames--;
      }

      renderFrame(renderer, gameRenderer, levelManager, playerRoster, worldSimulator, clientSnapshot,
          networkMode, peerDisconnected, screenWidth, screenHeight);

      updateAudioManager(playerRoster);

      long sleepMs = (long) ((drawInterval - elapsedNs) / 1_000_000);
      if (sleepMs > 0) {
        try { Thread.sleep(sleepMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
      }
    }

    if (networkSession != null) networkSession.close();
    AudioManager.cleanup();
    renderer.close();
    glfwDestroyWindow(window);
    glfwTerminate();
  }

  private static void updateAudioManager(PlayerRoster playerRoster) {
    Player p = playerRoster.findPlayerBySlot(0);
    if (p != null) {
      AudioManager.setListenerData((float) p.getX(), (float) p.getY(), 0.0f);
    }
  }

  // ---------------------------------------------------------------------------
  // Update logic
  // ---------------------------------------------------------------------------

  private static PeerUpdateResult updatePeer(NetworkSession networkSession, KeyHandler keyHandler,
      PlayerRoster playerRoster, WorldSimulator worldSimulator, LevelManager levelManager,
      long[] lastReconnectAttemptRef, long RECONNECT_INTERVAL_MS) {

    if (networkSession == null) return new PeerUpdateResult(false, new NetSnapshot("", List.of(), List.of()));

    networkSession.updateConnectionState();
    boolean disconnected = !networkSession.isConnected();

    if (disconnected) {
      long now = System.currentTimeMillis();
      if (now - lastReconnectAttemptRef[0] >= RECONNECT_INTERVAL_MS) {
        networkSession.attemptReconnect();
        lastReconnectAttemptRef[0] = now;
      }
    }

    if (disconnected) {
      handleJoinHotkeys(keyHandler, playerRoster);
      handleSaveHotkeys(keyHandler, levelManager, playerRoster);
      worldSimulator.simulate(1.0 / UPS);
      if (!playerRoster.isJoined(0)) joinSlot(0, playerRoster);
      return new PeerUpdateResult(true, buildSnapshot(levelManager, playerRoster, worldSimulator));
    } else {
      NetInput input = NetInput.fromClientKeys(keyHandler);
      networkSession.sendInput(input);
      networkSession.sendRestoreStateIfNeeded();

      NetSnapshot incoming = networkSession.latestSnapshot();
      if (incoming != null) {
        syncRemoteMap(incoming, levelManager);
        return new PeerUpdateResult(false, incoming);
      }
    }
    return new PeerUpdateResult(false, networkSession.latestSnapshot());
  }

  private static void updateHostOrLocal(NetworkSession networkSession, NetworkMode networkMode,
      KeyHandler keyHandler, PlayerRoster playerRoster, WorldSimulator worldSimulator, LevelManager levelManager) {

    if (networkMode.isLocal()) handleJoinHotkeys(keyHandler, playerRoster);
    handleSaveHotkeys(keyHandler, levelManager, playerRoster);

    if (networkMode.isHost()) {
      ensureConnectedSlotsJoined(networkSession, playerRoster);
      playerRoster.syncNetworkPlayers(networkSession);
    }

    worldSimulator.simulate(1.0 / UPS);

    if (networkMode.isP2P()) {
      networkSession.publishSnapshot(buildSnapshot(levelManager, playerRoster, worldSimulator));
    }
  }

  private static void handleJoinHotkeys(KeyHandler kh, PlayerRoster roster) {
    for (int i = 0; i < JOIN_KEYS_AWT.length; i++) {
      if (kh.isTriggered(JOIN_KEYS_AWT[i]) && joinSlot(i, roster) != null) {
        System.out.println("Player joined: slot " + (i + 1));
      }
    }
  }

  private static void handleSaveHotkeys(KeyHandler kh, LevelManager levelManager, PlayerRoster playerRoster) {
    SaveStateManager saveStateManager = new SaveStateManager();

    if (kh.isTriggered(java.awt.event.KeyEvent.VK_F5)) {
      SaveState save = new SaveState(
          levelManager.getCurrentMapId() == null ? "" : levelManager.getCurrentMapId(),
          playerRoster.createSaveStates());
      try { saveStateManager.saveQuick(save); System.out.println("Saved quicksave."); }
      catch (RuntimeException ex) { System.err.println("Save failed: " + ex.getMessage()); }
    }

    if (kh.isTriggered(java.awt.event.KeyEvent.VK_F9)) {
      if (!saveStateManager.hasQuickSave()) { System.out.println("No quicksave found."); return; }
      try {
        SaveState save = saveStateManager.loadQuick();
        TiledMap current = levelManager.getCurrentMap();
        int boundW = current != null ? current.getPixelWidth() : 640;
        int boundH = current != null ? current.getPixelHeight() : 360;
        playerRoster.restorePlayers(save.players(), boundW, boundH, PLAYER_SPAWN_X, PLAYER_SPAWN_Y);
        System.out.println("Loaded quicksave.");
      } catch (RuntimeException ex) { System.err.println("Load failed: " + ex.getMessage()); }
    }
  }

  private static void syncRemoteMap(NetSnapshot incoming, LevelManager levelManager) {
    if (incoming.mapId().isBlank() || !levelManager.hasLevel(incoming.mapId())) return;
    String currentMapId = levelManager.getCurrentMapId();
    if (currentMapId == null || !incoming.mapId().equals(currentMapId)) levelManager.setCurrentMap(incoming.mapId());
  }

  private static Player joinSlot(int slot, PlayerRoster roster) {
    double spawnX = PLAYER_SPAWN_X + (slot % 2) * PARTY_SPAWN_OFFSET;
    double spawnY = PLAYER_SPAWN_Y + (slot / 2) * PARTY_SPAWN_OFFSET;
    return roster.joinSlot(slot, spawnX, spawnY);
  }

  private static void ensureConnectedSlotsJoined(NetworkSession networkSession, PlayerRoster roster) {
    for (int slot : networkSession.connectedSlots()) {
      if (!roster.isJoined(slot)) joinSlot(slot, roster);
    }
  }

  private static NetSnapshot buildSnapshot(LevelManager levelManager, PlayerRoster roster, WorldSimulator ws) {
    return new NetSnapshot(
        levelManager.getCurrentMapId() == null ? "" : levelManager.getCurrentMapId(),
        roster.buildNetStates(), ws.buildNetStates());
  }

  private static void registerMaps(LevelManager levelManager) {
    for (String mapId : MAP_IDS) {
      String mapPath = GamePaths.mapResource(mapId);
      if (resourceExists(mapPath)) levelManager.registerLevel(mapId, mapPath);
    }
    try {
      if (levelManager.hasLevels()) {
        for (String mapId : MAP_IDS) {
          if (levelManager.hasLevel(mapId)) { levelManager.setCurrentMap(mapId); break; }
        }
      }
    } catch (RuntimeException ex) { System.err.println("Level init failed: " + ex.getMessage()); }
  }

  private static boolean resourceExists(String path) {
    return Thread.currentThread().getContextClassLoader().getResource(path) != null;
  }

  // ---------------------------------------------------------------------------
  // Rendering
  // ---------------------------------------------------------------------------

  private static void renderFrame(VulkanRenderer renderer, VulkanGameRenderer gameRenderer,
      LevelManager levelManager, PlayerRoster playerRoster, WorldSimulator worldSimulator,
      NetSnapshot clientSnapshot, NetworkMode networkMode,
      boolean peerDisconnected, int screenWidth, int screenHeight) {
    try {
      renderer.beginFrame();
      int imageIndex = renderer.acquireNextImage();
      if (imageIndex < 0) return;

      List<RenderCommand> commands = networkMode.isPeer()
          ? gameRenderer.buildRemoteCommands(levelManager.getCurrentMap(), clientSnapshot)
          : gameRenderer.buildLocalCommands(levelManager.getCurrentMap(),
              playerRoster.players(), worldSimulator.enemies());

      if (networkMode.isPeer() && peerDisconnected)
        commands.add(RenderCommand.rect(0, 0, screenWidth, 20, 1.0f, 0.0f, 0.0f, 0.7f));

      renderer.recordCommandBuffer(imageIndex, buildOrthographicProjection(screenWidth, screenHeight), commands);
      renderer.submitCommandBuffer(imageIndex);
    } catch (Exception ex) { System.err.println("Render error: " + ex.getMessage()); ex.printStackTrace(); }
  }

  private static float[] buildOrthographicProjection(int width, int height) {
    float[] proj = new float[16];
    proj[0] = 2.0f / width;
    proj[5] = 2.0f / height;
    proj[10] = 1.0f; proj[12] = -1.0f; proj[13] = -1.0f; proj[15] = 1.0f;
    return proj;
  }

  // ---------------------------------------------------------------------------
  // GLFW key callback
  // ---------------------------------------------------------------------------

  private static GLFWKeyCallback createGlfwKeyCallback(KeyHandler keyHandler) {
    return GLFWKeyCallback.create((window, key, scancode, action, mods) -> {
      int awtKeyCode = glfwToAwtKeyCode(key);
      if (awtKeyCode < 0) return;
      if (action == GLFW_PRESS) keyHandler.setKeyDown(awtKeyCode, true);
      else if (action == GLFW_RELEASE) keyHandler.setKeyDown(awtKeyCode, false);
    });
  }

  private static int glfwToAwtKeyCode(int glfwKey) {
    if (glfwKey >= GLFW_KEY_A && glfwKey <= GLFW_KEY_Z) return glfwKey + (java.awt.event.KeyEvent.VK_A - GLFW_KEY_A);
    if (glfwKey >= GLFW_KEY_0 && glfwKey <= GLFW_KEY_9) return glfwKey + (java.awt.event.KeyEvent.VK_0 - GLFW_KEY_0);
    if (glfwKey >= GLFW_KEY_F1 && glfwKey <= GLFW_KEY_F12) return java.awt.event.KeyEvent.VK_F1 + (glfwKey - GLFW_KEY_F1);
    return switch (glfwKey) {
      case GLFW_KEY_LEFT_SHIFT, GLFW_KEY_RIGHT_SHIFT -> java.awt.event.KeyEvent.VK_SHIFT;
      case GLFW_KEY_LEFT_CONTROL, GLFW_KEY_RIGHT_CONTROL -> java.awt.event.KeyEvent.VK_CONTROL;
      case GLFW_KEY_LEFT_ALT, GLFW_KEY_RIGHT_ALT -> java.awt.event.KeyEvent.VK_ALT;
      case GLFW_KEY_SPACE -> java.awt.event.KeyEvent.VK_SPACE;
      case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> java.awt.event.KeyEvent.VK_ENTER;
      case GLFW_KEY_ESCAPE -> java.awt.event.KeyEvent.VK_ESCAPE;
      case GLFW_KEY_UP -> java.awt.event.KeyEvent.VK_UP;
      case GLFW_KEY_DOWN -> java.awt.event.KeyEvent.VK_DOWN;
      case GLFW_KEY_LEFT -> java.awt.event.KeyEvent.VK_LEFT;
      case GLFW_KEY_RIGHT -> java.awt.event.KeyEvent.VK_RIGHT;
      case GLFW_KEY_TAB -> java.awt.event.KeyEvent.VK_TAB;
      case GLFW_KEY_BACKSPACE -> java.awt.event.KeyEvent.VK_BACK_SPACE;
      case GLFW_KEY_INSERT -> java.awt.event.KeyEvent.VK_INSERT;
      case GLFW_KEY_DELETE -> java.awt.event.KeyEvent.VK_DELETE;
      case GLFW_KEY_HOME -> java.awt.event.KeyEvent.VK_HOME;
      case GLFW_KEY_END -> java.awt.event.KeyEvent.VK_END;
      case GLFW_KEY_PAGE_UP -> java.awt.event.KeyEvent.VK_PAGE_UP;
      case GLFW_KEY_PAGE_DOWN -> java.awt.event.KeyEvent.VK_PAGE_DOWN;
      case GLFW_KEY_CAPS_LOCK -> java.awt.event.KeyEvent.VK_CAPS_LOCK;
      case 320 -> java.awt.event.KeyEvent.VK_NUMPAD0;
      case 321 -> java.awt.event.KeyEvent.VK_NUMPAD1;
      case 322 -> java.awt.event.KeyEvent.VK_NUMPAD2;
      case 323 -> java.awt.event.KeyEvent.VK_NUMPAD3;
      case 324 -> java.awt.event.KeyEvent.VK_NUMPAD4;
      case 325 -> java.awt.event.KeyEvent.VK_NUMPAD5;
      case 326 -> java.awt.event.KeyEvent.VK_NUMPAD6;
      case 327 -> java.awt.event.KeyEvent.VK_NUMPAD7;
      case 328 -> java.awt.event.KeyEvent.VK_NUMPAD8;
      case 329 -> java.awt.event.KeyEvent.VK_NUMPAD9;
      case 96 -> java.awt.event.KeyEvent.VK_BACK_QUOTE;
      case 45 -> java.awt.event.KeyEvent.VK_MINUS;
      case 61 -> java.awt.event.KeyEvent.VK_EQUALS;
      case 91 -> java.awt.event.KeyEvent.VK_OPEN_BRACKET;
      case 93 -> java.awt.event.KeyEvent.VK_CLOSE_BRACKET;
      case 92 -> java.awt.event.KeyEvent.VK_BACK_SLASH;
      case 59 -> java.awt.event.KeyEvent.VK_SEMICOLON;
      case 39 -> java.awt.event.KeyEvent.VK_QUOTE;
      case 44 -> java.awt.event.KeyEvent.VK_COMMA;
      case 46 -> java.awt.event.KeyEvent.VK_PERIOD;
      case 47 -> java.awt.event.KeyEvent.VK_SLASH;
      default -> -1;
    };
  }

  // ---------------------------------------------------------------------------
  // Argument parsing
  // ---------------------------------------------------------------------------

  private record LaunchOptions(NetworkConfig networkConfig) {}
  private record PeerUpdateResult(boolean disconnected, NetSnapshot snapshot) {}

  private static LaunchOptions parseArgs(String[] args) {
    NetworkMode mode = NetworkMode.LOCAL;
    String host = "127.0.0.1";
    int port = 7777;
    List<String> peerAddresses = new ArrayList<>();

    for (String arg : args) {
      if (arg.startsWith("--mode=")) {
        String value = arg.substring("--mode=".length()).trim().toLowerCase();
        mode = switch (value) { case "local" -> NetworkMode.LOCAL; case "p2p-host" -> NetworkMode.P2P_HOST;
          case "p2p-peer" -> NetworkMode.P2P_PEER; default -> mode; };
      } else if (arg.startsWith("--host=")) host = arg.substring("--host=".length()).trim();
      else if (arg.startsWith("--port=")) { try { port = Integer.parseInt(arg.substring("--port=".length()).trim()); } catch (NumberFormatException ignored) {} }
      else if (arg.startsWith("--peer=")) peerAddresses.add(arg.substring("--peer=".length()).trim());
    }
    return new LaunchOptions(new NetworkConfig(mode, host, port, peerAddresses));
  }
}
