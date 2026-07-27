package main;

import static org.lwjgl.glfw.GLFW.*;

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
      new PlayerControls(GLFW_KEY_W, GLFW_KEY_S, GLFW_KEY_A, GLFW_KEY_D,
          GLFW_KEY_LEFT_SHIFT,
          new int[] { GLFW_KEY_1, GLFW_KEY_2, GLFW_KEY_3, GLFW_KEY_4 }),
      new PlayerControls(GLFW_KEY_UP, GLFW_KEY_DOWN, GLFW_KEY_LEFT, GLFW_KEY_RIGHT,
          GLFW_KEY_RIGHT_SHIFT,
          new int[] { GLFW_KEY_7, GLFW_KEY_8, GLFW_KEY_9, GLFW_KEY_0 }),
      new PlayerControls(GLFW_KEY_I, GLFW_KEY_K, GLFW_KEY_J, GLFW_KEY_L,
          GLFW_KEY_U,
          new int[] { GLFW_KEY_5, GLFW_KEY_6, GLFW_KEY_SEMICOLON, GLFW_KEY_APOSTROPHE }),
      new PlayerControls(GLFW_KEY_KP_8, GLFW_KEY_KP_5, GLFW_KEY_KP_4, GLFW_KEY_KP_6,
          GLFW_KEY_KP_0,
          new int[] { GLFW_KEY_KP_1, GLFW_KEY_KP_2, GLFW_KEY_KP_3, GLFW_KEY_KP_ADD })
  };

  private final NetworkMode networkMode;
  private final KeyHandler sharedKeyHandler;
  private final AudioBus audioBus;
  private final KeyHandler[] slotKeyHandlers = new KeyHandler[MAX_PLAYERS];
  private final boolean[] joinedSlots = new boolean[MAX_PLAYERS];
  private final List<Player> players = new ArrayList<>();

  public PlayerRoster(NetworkMode networkMode, KeyHandler sharedKeyHandler, AudioBus audioBus) {
    this.networkMode = networkMode;
    this.sharedKeyHandler = sharedKeyHandler;
    this.audioBus = audioBus;
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
    player.setSlot(slot);

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

    for (int i = 0; i < snapshots.size(); i++) {
      PlayerSaveState snapshot = snapshots.get(i);
      int slot = snapshot.slot();
      if (slot < 0 || slot >= MAX_PLAYERS) continue;

      Player player = createPlayerForSlot(slot, snapshot.x(), snapshot.y());
      if (player == null || !player.loadPlayerSaveState(snapshot)) {
        continue;
      }
      player.setSlot(slot);

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
          player.getSlot(),
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

  public void restorePlayerFromState(int slot, PlayerSaveState state, int boundWidth, int boundHeight) {
    if (slot < 0 || slot >= MAX_PLAYERS) {
      return;
    }

    removePlayerInSlot(slot);

    Player player = createPlayerForSlot(slot, state.x(), state.y());
    if (player != null && player.loadPlayerSaveState(state)) {
      player.setSlot(slot);
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

  public Player findPlayerBySlot(int slot) {
    for (Player player : players) {
      if (player.getSlot() == slot) {
        return player;
      }
    }
    return null;
  }

  public int slotForClassName(String className) {
    if (className.equals(Mage.class.getName())) return 0;
    if (className.equals(Warrior.class.getName())) return 1;
    if (className.equals(Tank.class.getName())) return 2;
    if (className.equals(Priest.class.getName())) return 3;
    return -1;
  }

  private Player createPlayerForSlot(int slot, double x, double y) {
    KeyHandler slotKeyHandler = slotKeyHandlers[slot];
    return switch (slot) {
      case 0 -> new Mage(x, y, slotKeyHandler, SLOT_CONTROLS[0], audioBus);
      case 1 -> new Warrior(x, y, slotKeyHandler, SLOT_CONTROLS[1], audioBus);
      case 2 -> new Tank(x, y, slotKeyHandler, SLOT_CONTROLS[2], audioBus);
      case 3 -> new Priest(x, y, slotKeyHandler, SLOT_CONTROLS[3], audioBus);
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
