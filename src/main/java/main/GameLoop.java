package main;

import static org.lwjgl.glfw.GLFW.*;

import java.util.List;

import entity.player.Player;
import net.NetInput;
import net.NetSnapshot;
import render.vulkan.RenderCommand;
import save.SaveState;
import save.SaveStateManager;
import tile.TiledMap;

public class GameLoop {
  private static final int UPS = 30;
  private static final double PLAYER_SPAWN_X = 100;
  private static final double PLAYER_SPAWN_Y = 100;
  private static final double PARTY_SPAWN_OFFSET = 24;
  private static final long RECONNECT_INTERVAL_MS = 2000;

  private final GameContext ctx;

  public GameLoop(GameContext ctx) {
    this.ctx = ctx;
  }

  public void run(long window) {
    if (!ctx.networkMode.isPeer()) {
      joinSlot(0);
    }

    if (ctx.networkSession != null && ctx.networkMode.isHost()) {
      ctx.networkSession.setRestoreListener((slot, state) -> {
        TiledMap current = ctx.levelManager.getCurrentMap();
        int boundW = current != null ? current.getPixelWidth() : ctx.screenWidth;
        int boundH = current != null ? current.getPixelHeight() : ctx.screenHeight;
        ctx.playerRoster.restorePlayerFromState(slot, state, boundW, boundH);
        System.out.println("[P2P] Restored peer in slot " + slot + " at (" + state.x() + ", " + state.y() + ")");
      });
    }

    NetSnapshot clientSnapshot = new NetSnapshot("", List.of(), List.of());
    boolean peerDisconnected = false;
    long[] lastReconnectAttempt = { 0 };

    final double drawInterval = 1_000_000_000.0 / UPS;
    long lastTickNs = System.nanoTime();
    double pendingFrames = 0.0;

    while (!glfwWindowShouldClose(window)) {
      glfwPollEvents();

      long currentNs = System.nanoTime();
      long elapsedNs = currentNs - lastTickNs;
      lastTickNs = currentNs;
      pendingFrames += elapsedNs / drawInterval;
      if (pendingFrames > 5.0) pendingFrames = 5.0;

      while (pendingFrames >= 1) {
        if (ctx.networkMode.isPeer()) {
          PeerUpdateResult peerUpdate = updatePeer(clientSnapshot, lastReconnectAttempt);
          peerDisconnected = peerUpdate.disconnected();
          clientSnapshot = peerUpdate.snapshot();
        } else {
          updateHostOrLocal();
        }
        pendingFrames--;
      }

      Player primary = ctx.playerRoster.findPlayerBySlot(0);
      float camX = primary != null ? (float) primary.getX() : 0;
      float camY = primary != null ? (float) primary.getY() : 0;

      renderFrame(clientSnapshot, peerDisconnected, camX, camY);

      Player p = ctx.playerRoster.findPlayerBySlot(0);
      if (p != null) {
        ctx.audioManager.setListenerData((float) p.getX(), (float) p.getY(), 0.0f);
      }
      ctx.soundtrack.update(camX, camY, ctx.levelManager.getCurrentMap(), 1.0 / UPS);
      ctx.audioManager.updateMusicFade();

      long sleepMs = (long) ((drawInterval - elapsedNs) / 1_000_000);
      if (sleepMs > 0) {
        try { Thread.sleep(sleepMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Update logic
  // ---------------------------------------------------------------------------

  private PeerUpdateResult updatePeer(NetSnapshot clientSnapshot, long[] lastReconnectAttemptRef) {
    if (ctx.networkSession == null) return new PeerUpdateResult(false, new NetSnapshot("", List.of(), List.of()));

    ctx.networkSession.updateConnectionState();
    boolean disconnected = !ctx.networkSession.isConnected();

    if (disconnected) {
      long now = System.currentTimeMillis();
      if (now - lastReconnectAttemptRef[0] >= RECONNECT_INTERVAL_MS) {
        ctx.networkSession.attemptReconnect();
        lastReconnectAttemptRef[0] = now;
      }
    }

    if (disconnected) {
      handleJoinHotkeys();
      handleSaveHotkeys();
      ctx.worldSimulator.simulate(1.0 / UPS);
      if (!ctx.playerRoster.isJoined(0)) joinSlot(0);
      return new PeerUpdateResult(true, buildSnapshot());
    } else {
      NetInput input = NetInput.fromClientKeys(ctx.keyHandler);
      ctx.networkSession.sendInput(input);
      ctx.networkSession.sendRestoreStateIfNeeded();

      NetSnapshot incoming = ctx.networkSession.latestSnapshot();
      if (incoming != null) {
        syncRemoteMap(incoming);
        return new PeerUpdateResult(false, incoming);
      }
      return new PeerUpdateResult(false, new NetSnapshot("", List.of(), List.of()));
    }
  }

  private void updateHostOrLocal() {
    if (ctx.networkMode.isLocal()) handleJoinHotkeys();
    handleSaveHotkeys();

    if (ctx.networkMode.isHost()) {
      ensureConnectedSlotsJoined();
      ctx.playerRoster.syncNetworkPlayers(ctx.networkSession);
    }

    ctx.worldSimulator.simulate(1.0 / UPS);

    if (ctx.networkMode.isP2P()) {
      ctx.networkSession.publishSnapshot(buildSnapshot());
    }
  }

  private void handleJoinHotkeys() {
    int[] joinKeys = { GLFW_KEY_F1, GLFW_KEY_F2, GLFW_KEY_F3, GLFW_KEY_F4 };
    for (int i = 0; i < joinKeys.length; i++) {
      if (ctx.keyHandler.isTriggered(joinKeys[i]) && joinSlot(i) != null) {
        System.out.println("Player joined: slot " + (i + 1));
      }
    }
  }

  private void handleSaveHotkeys() {
    SaveStateManager sm = ctx.saveStateManager;

    if (ctx.keyHandler.isTriggered(GLFW_KEY_F5)) {
      SaveState save = new SaveState(
          ctx.levelManager.getCurrentMapId() == null ? "" : ctx.levelManager.getCurrentMapId(),
          ctx.playerRoster.createSaveStates());
      try { sm.saveQuick(save); System.out.println("Saved quicksave."); }
      catch (RuntimeException ex) { System.err.println("Save failed: " + ex.getMessage()); }
    }

    if (ctx.keyHandler.isTriggered(GLFW_KEY_F9)) {
      if (!sm.hasQuickSave()) { System.out.println("No quicksave found."); return; }
      try {
        SaveState save = sm.loadQuick();
        TiledMap current = ctx.levelManager.getCurrentMap();
        int boundW = current != null ? current.getPixelWidth() : 640;
        int boundH = current != null ? current.getPixelHeight() : 360;
        ctx.playerRoster.restorePlayers(save.players(), boundW, boundH, PLAYER_SPAWN_X, PLAYER_SPAWN_Y);
        System.out.println("Loaded quicksave.");
      } catch (RuntimeException ex) { System.err.println("Load failed: " + ex.getMessage()); }
    }
  }

  private void syncRemoteMap(NetSnapshot incoming) {
    if (incoming.mapId().isBlank() || !ctx.levelManager.hasLevel(incoming.mapId())) return;
    String currentMapId = ctx.levelManager.getCurrentMapId();
    if (currentMapId == null || !incoming.mapId().equals(currentMapId)) ctx.levelManager.setCurrentMap(incoming.mapId());
  }

  private Player joinSlot(int slot) {
    double spawnX = PLAYER_SPAWN_X + (slot % 2) * PARTY_SPAWN_OFFSET;
    double spawnY = PLAYER_SPAWN_Y + (slot / 2) * PARTY_SPAWN_OFFSET;
    return ctx.playerRoster.joinSlot(slot, spawnX, spawnY);
  }

  private void ensureConnectedSlotsJoined() {
    for (int slot : ctx.networkSession.connectedSlots()) {
      if (!ctx.playerRoster.isJoined(slot)) joinSlot(slot);
    }
  }

  private NetSnapshot buildSnapshot() {
    return new NetSnapshot(
        ctx.levelManager.getCurrentMapId() == null ? "" : ctx.levelManager.getCurrentMapId(),
        ctx.playerRoster.buildNetStates(), ctx.worldSimulator.buildNetStates());
  }

  // ---------------------------------------------------------------------------
  // Rendering
  // ---------------------------------------------------------------------------

  private void renderFrame(NetSnapshot clientSnapshot, boolean peerDisconnected, float camX, float camY) {
    try {
      ctx.renderer.beginFrame();
      int imageIndex = ctx.renderer.acquireNextImage();
      if (imageIndex < 0) return;

      List<RenderCommand> commands = ctx.networkMode.isPeer()
          ? ctx.gameRenderer.buildRemoteCommands(ctx.levelManager.getCurrentMap(), clientSnapshot, camX, camY)
          : ctx.gameRenderer.buildLocalCommands(ctx.levelManager.getCurrentMap(),
              ctx.playerRoster.players(), ctx.worldSimulator.enemies(), camX, camY);

      if (ctx.networkMode.isPeer() && peerDisconnected)
        commands.add(RenderCommand.rect(0, 0, ctx.screenWidth, 20, 1.0f, 0.0f, 0.0f, 0.7f, 9999));

      ctx.renderer.recordCommandBuffer(imageIndex,
          buildOrthographicProjection(ctx.screenWidth, ctx.screenHeight, camX, camY), commands);
      ctx.renderer.submitCommandBuffer(imageIndex);
    } catch (Exception ex) { System.err.println("Render error: " + ex.getMessage()); ex.printStackTrace(); }
  }

  private static float[] buildOrthographicProjection(int width, int height, float camX, float camY) {
    float[] proj = new float[16];
    proj[0] = 2.0f / width;
    proj[5] = 2.0f / height;
    proj[12] = -1.0f - 2.0f * camX / width;
    proj[13] = -1.0f - 2.0f * camY / height;
    proj[10] = 1.0f; proj[15] = 1.0f;
    return proj;
  }

  private record PeerUpdateResult(boolean disconnected, NetSnapshot snapshot) {}
}
