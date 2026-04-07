package net;

import java.util.ArrayList;
import java.util.List;

final class NetProtocol {
  private NetProtocol() {
  }

  // --- Message builders ---

  public static String hello() {
    return "HELLO";
  }

  public static String assign(int slot) {
    return "ASSIGN|" + slot;
  }

  public static String input(int slot, NetInput input) {
    return "INPUT|" + slot + "|"
        + bool(input.up()) + "|"
        + bool(input.down()) + "|"
        + bool(input.left()) + "|"
        + bool(input.right()) + "|"
        + bool(input.item()) + "|"
        + bool(input.skill1()) + "|"
        + bool(input.skill2()) + "|"
        + bool(input.skill3()) + "|"
        + bool(input.skill4());
  }

  public static String snapshot(NetSnapshot snapshot) {
    StringBuilder sb = new StringBuilder();
    sb.append("SNAP|").append(snapshot.mapId() == null ? "" : snapshot.mapId());
    for (NetPlayerState p : snapshot.players()) {
      sb.append("|P,").append(p.slot())
          .append(",").append(p.appearanceId())
          .append(",").append(p.x())
          .append(",").append(p.y())
          .append(",").append(p.width())
          .append(",").append(p.height())
          .append(",").append(p.direction())
          .append(",").append(p.animation())
          .append(",").append(p.frame());
    }
    for (NetEnemyState e : snapshot.enemies()) {
      sb.append("|E,").append(e.appearanceId())
          .append(",").append(e.x())
          .append(",").append(e.y())
          .append(",").append(e.width())
          .append(",").append(e.height())
          .append(",").append(e.direction())
          .append(",").append(e.animation())
          .append(",").append(e.frame());
    }
    return sb.toString();
  }

  public static String heartbeat() {
    return "HEARTBEAT";
  }

  public static String restore(int slot, save.PlayerSaveState state) {
    return "RESTORE|" + slot + "|"
        + state.playerClassName() + "|"
        + state.signatureElement() + "|"
        + state.statusEffectType() + "|"
        + state.x() + "|"
        + state.y() + "|"
        + state.hp() + "|"
        + state.mana() + "|"
        + state.ap() + "|"
        + state.defence() + "|"
        + state.level() + "|"
        + state.exp();
  }

  // --- Parsers ---

  public static int parseAssignedSlot(String msg) {
    String[] parts = msg.split("\\|", -1);
    if (parts.length == 2 && "ASSIGN".equals(parts[0])) {
      try {
        return Integer.parseInt(parts[1]);
      } catch (NumberFormatException ignored) {
      }
    }
    return -1;
  }

  public static ParsedInputMessage parseInputMessage(String msg) {
    String[] p = msg.split("\\|", -1);
    if (p.length != 11 || !"INPUT".equals(p[0])) {
      return null;
    }
    int slot;
    try {
      slot = Integer.parseInt(p[1]);
    } catch (NumberFormatException e) {
      return null;
    }
    NetInput input = new NetInput(
        parseBool(p[2]),
        parseBool(p[3]),
        parseBool(p[4]),
        parseBool(p[5]),
        parseBool(p[6]),
        parseBool(p[7]),
        parseBool(p[8]),
        parseBool(p[9]),
        parseBool(p[10]));
    return new ParsedInputMessage(slot, input);
  }

  public static NetSnapshot parseSnapshot(String msg) {
    String[] parts = msg.split("\\|", -1);
    if (parts.length < 2 || !"SNAP".equals(parts[0])) {
      return null;
    }
    String mapId = parts[1];
    List<NetPlayerState> players = new ArrayList<>();
    List<NetEnemyState> enemies = new ArrayList<>();
    for (int i = 2; i < parts.length; i++) {
      String[] v = parts[i].split(",", -1);
      if (v.length == 10 && "P".equals(v[0])) {
        try {
          players.add(new NetPlayerState(
              Integer.parseInt(v[1]),
              v[2],
              Double.parseDouble(v[3]),
              Double.parseDouble(v[4]),
              Double.parseDouble(v[5]),
              Double.parseDouble(v[6]),
              v[7],
              v[8],
              Integer.parseInt(v[9])));
        } catch (NumberFormatException ignored) {
        }
        continue;
      }
      if (v.length != 9 || !"E".equals(v[0])) {
        continue;
      }
      try {
        enemies.add(new NetEnemyState(
            v[1],
            Double.parseDouble(v[2]),
            Double.parseDouble(v[3]),
            Double.parseDouble(v[4]),
            Double.parseDouble(v[5]),
            v[6],
            v[7],
            Integer.parseInt(v[8])));
      } catch (NumberFormatException ignored) {
      }
    }
    return new NetSnapshot(mapId, players, enemies);
  }

  public static boolean isHeartbeat(String msg) {
    return "HEARTBEAT".equals(msg);
  }

  public static ParsedRestoreMessage parseRestoreMessage(String msg) {
    String[] parts = msg.split("\\|", -1);
    if (parts.length != 13 || !"RESTORE".equals(parts[0])) {
      return null;
    }
    int slot;
    int level, exp;
    double x, y, hp, mana, ap, defence;
    try {
      slot = Integer.parseInt(parts[1]);
      x = Double.parseDouble(parts[4]);
      y = Double.parseDouble(parts[5]);
      hp = Double.parseDouble(parts[6]);
      mana = Double.parseDouble(parts[7]);
      ap = Double.parseDouble(parts[8]);
      defence = Double.parseDouble(parts[9]);
      level = Integer.parseInt(parts[10]);
      exp = Integer.parseInt(parts[11]);
    } catch (NumberFormatException e) {
      return null;
    }
    save.PlayerSaveState state = new save.PlayerSaveState(
        parts[2], parts[3], parts[4], // playerClassName, signatureElement, statusEffectType
        x, y, hp, mana, ap, defence, level, exp);
    return new ParsedRestoreMessage(slot, state);
  }

  private static String bool(boolean value) {
    return value ? "1" : "0";
  }

  private static boolean parseBool(String raw) {
    return "1".equals(raw) || "true".equalsIgnoreCase(raw);
  }

  // --- Parsed message carriers ---

  static final class ParsedInputMessage {
    private final int slot;
    private final NetInput input;

    ParsedInputMessage(int slot, NetInput input) {
      this.slot = slot;
      this.input = input;
    }

    int slot() {
      return slot;
    }

    NetInput input() {
      return input;
    }
  }

  static final class ParsedRestoreMessage {
    private final int slot;
    private final save.PlayerSaveState state;

    ParsedRestoreMessage(int slot, save.PlayerSaveState state) {
      this.slot = slot;
      this.state = state;
    }

    int slot() {
      return slot;
    }

    save.PlayerSaveState state() {
      return state;
    }
  }
}
