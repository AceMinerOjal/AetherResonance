package main;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import entity.player.Player;
import entity.player.classes.Mage;
import entity.player.classes.Priest;
import entity.player.classes.Tank;
import entity.player.classes.Warrior;
import net.NetInput;
import net.NetPlayerState;
import net.NetworkMode;
import net.NetworkSession;
import save.PlayerSaveState;

public class PlayerRoster {
  private static final int MAX_PLAYERS = 4;

  private static final PlayerControls[] SLOT_CONTROLS = {
      new PlayerControls(KeyEvent.VK_W, KeyEvent.VK_S, KeyEvent.VK_A, KeyEvent.VK_D,
          KeyEvent.VK_SHIFT,
          new int[] { KeyEvent.VK_1, KeyEvent.VK_2, KeyEvent.VK_3, KeyEvent.VK_4 }),
      new PlayerControls(KeyEvent.VK_W, KeyEvent.VK_S, KeyEvent.VK_A, KeyEvent.VK_D,
          KeyEvent.VK_SHIFT,
          new int[] { KeyEvent.VK_1, KeyEvent.VK_2, KeyEvent.VK_3, KeyEvent.VK_4 }),
      new PlayerControls(KeyEvent.VK_W, KeyEvent.VK_S, KeyEvent.VK_A, KeyEvent.VK_D,
          KeyEvent.VK_SHIFT,
          new int[] { KeyEvent.VK_1, KeyEvent.VK_2, KeyEvent.VK_3, KeyEvent.VK_4 }),
      new PlayerControls(KeyEvent.VK_W, KeyEvent.VK_S, KeyEvent.VK_A, KeyEvent.VK_D,
          KeyEvent.VK_SHIFT,
          new int[] { KeyEvent.VK_1, KeyEvent.VK_2, KeyEvent.VK_3, KeyEvent.VK_4 })
  };

  private final NetworkMode networkMode;
  private final KeyHandler sharedKeyHandler;
  private final KeyHandler[] slotKeyHandlers = new KeyHandler[MAX_PLAYERS];
  private final boolean[] joinedSlots = new boolean[MAX_PLAYERS];
  private final List<Player> players = new ArrayList<>();

  public PlayerRoster(NetworkMode networkMode, KeyHandler sharedKeyHandler) {
    this.networkMode = networkMode;
    this.sharedKeyHandler = sharedKeyHandler;
    initializeSlotKeyHandlers();
  }

  public List<Player> players() {
    return players;
  }

  public Player joinSlot(int slot, double spawnX, double spawnY) {
    if (slot < 0 || slot >= MAX_PLAYERS || joinedSlots[slot]) {
      return null;
    }

    Player player = createPlayerForSlot(slot, spawnX, spawnY);
    if (player == null) {
      return null;
    }

    players.add(player);
    joinedSlots[slot] = true;
    syncPartyRefs();
    return player;
  }

  public List<PlayerSaveState> createSaveStates() {
    List<PlayerSaveState> states = new ArrayList<>(players.size());
    for (Player player : players) {
      states.add(player.createPlayerSaveState());
    }
    return states;
  }

  public void restorePlayers(List<PlayerSaveState> snapshots, int boundWidth, int boundHeight,
      double fallbackSpawnX, double fallbackSpawnY) {
    players.clear();
    Arrays.fill(joinedSlots, false);

    for (PlayerSaveState snapshot : snapshots) {
      int slot = slotForClassName(snapshot.playerClassName());
      if (slot < 0 || joinedSlots[slot]) {
        continue;
      }

      Player player = createPlayerForSlot(slot, snapshot.x(), snapshot.y());
      if (player == null || !player.loadPlayerSaveState(snapshot)) {
        continue;
      }

      players.add(player);
      joinedSlots[slot] = true;
    }

    if (players.isEmpty()) {
      joinSlot(0, fallbackSpawnX, fallbackSpawnY);
    }

    for (Player player : players) {
      player.clampToBounds(boundWidth, boundHeight);
    }
    syncPartyRefs();
  }

  public List<NetPlayerState> buildNetStates() {
    List<NetPlayerState> states = new ArrayList<>(players.size());
    for (Player player : players) {
      states.add(new NetPlayerState(
          slotForClassName(player.getClass().getName()),
          player.getAppearanceId(),
          player.getX(),
          player.getY(),
          player.getSpriteWidth(),
          player.getSpriteHeight(),
          player.getDirection().name(),
          player.getCurrentAnimation().name(),
          player.getCurrentFrame()));
    }
    return states;
  }

  public void syncNetworkPlayers(NetworkSession networkSession) {
    boolean[] connected = new boolean[MAX_PLAYERS];
    for (int slot : networkSession.connectedSlots()) {
      connected[slot] = true;
      applyRemoteInput(slot, networkSession.remoteInputs().get(slot));
    }

    // Remove players that are no longer connected (they disappeared from existence)
    for (int slot = 1; slot < MAX_PLAYERS; slot++) {
      if (!connected[slot] && joinedSlots[slot]) {
        if (removePlayerInSlot(slot)) {
          System.out.println("[P2P] Peer in slot " + slot + " disconnected, removed from host.");
        }
      }
    }
  }

  public boolean isJoined(int slot) {
    return slot >= 0 && slot < MAX_PLAYERS && joinedSlots[slot];
  }

  /**
   * Restore or create a player in the given slot with the provided save state.
   * Used when a peer reconnects after being disconnected.
   */
  public void restorePlayerFromState(int slot, PlayerSaveState state, int boundWidth, int boundHeight) {
    if (slot < 0 || slot >= MAX_PLAYERS) {
      return;
    }

    String className = state.playerClassName();
    int existingSlot = slotForClassName(className);
    if (existingSlot >= 0) {
      removePlayerInSlot(existingSlot);
    }

    Player player = createPlayerForSlot(slot, state.x(), state.y());
    if (player != null && player.loadPlayerSaveState(state)) {
      players.add(player);
      joinedSlots[slot] = true;
      player.clampToBounds(boundWidth, boundHeight);
      syncPartyRefs();
    }
  }

  private boolean removePlayerInSlot(int slot) {
    Player player = findPlayerBySlot(slot);
    if (player == null) {
      return false;
    }
    players.remove(player);
    joinedSlots[slot] = false;
    syncPartyRefs();
    return true;
  }

  private Player findPlayerBySlot(int slot) {
    for (Player player : players) {
      if (slotForClassName(player.getClass().getName()) == slot) {
        return player;
      }
    }
    return null;
  }

  public int slotForClassName(String className) {
    return switch (className) {
      case "entity.player.classes.Mage" -> 0;
      case "entity.player.classes.Warrior" -> 1;
      case "entity.player.classes.Tank" -> 2;
      case "entity.player.classes.Priest" -> 3;
      default -> -1;
    };
  }

  private Player createPlayerForSlot(int slot, double x, double y) {
    KeyHandler slotKeyHandler = slotKeyHandlers[slot];
    return switch (slot) {
      case 0 -> new Mage(x, y, slotKeyHandler, SLOT_CONTROLS[0]);
      case 1 -> new Warrior(x, y, slotKeyHandler, SLOT_CONTROLS[1]);
      case 2 -> new Tank(x, y, slotKeyHandler, SLOT_CONTROLS[2]);
      case 3 -> new Priest(x, y, slotKeyHandler, SLOT_CONTROLS[3]);
      default -> null;
    };
  }

  private void initializeSlotKeyHandlers() {
    for (int slot = 0; slot < MAX_PLAYERS; slot++) {
      slotKeyHandlers[slot] = (slot == 0 || networkMode.isLocal()) ? sharedKeyHandler : new KeyHandler();
    }
  }

  private void applyRemoteInput(int slot, NetInput input) {
    if (slot <= 0 || slot >= MAX_PLAYERS) {
      return;
    }

    KeyHandler slotKeyHandler = slotKeyHandlers[slot];
    PlayerControls controls = SLOT_CONTROLS[slot];
    int[] skillKeys = controls.skillKeys();

    slotKeyHandler.setVirtualDown(controls.upKey(), input != null && input.up());
    slotKeyHandler.setVirtualDown(controls.downKey(), input != null && input.down());
    slotKeyHandler.setVirtualDown(controls.leftKey(), input != null && input.left());
    slotKeyHandler.setVirtualDown(controls.rightKey(), input != null && input.right());
    slotKeyHandler.setVirtualDown(controls.itemModifierKey(), input != null && input.item());
    slotKeyHandler.setVirtualDown(skillKeys[0], input != null && input.skill1());
    slotKeyHandler.setVirtualDown(skillKeys[1], input != null && input.skill2());
    slotKeyHandler.setVirtualDown(skillKeys[2], input != null && input.skill3());
    slotKeyHandler.setVirtualDown(skillKeys[3], input != null && input.skill4());
  }

  private void syncPartyRefs() {
    for (Player player : players) {
      player.setParty(players);
    }
  }
}
