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
      sb.append("|").append(p.slot())
          .append(",").append(p.x())
          .append(",").append(p.y())
          .append(",").append(p.width())
          .append(",").append(p.height());
    }
    return sb.toString();
  }

  public static String heartbeat() {
    return "HEARTBEAT";
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
    for (int i = 2; i < parts.length; i++) {
      String[] v = parts[i].split(",", -1);
      if (v.length != 5) {
        continue;
      }
      try {
        players.add(new NetPlayerState(
            Integer.parseInt(v[0]),
            Double.parseDouble(v[1]),
            Double.parseDouble(v[2]),
            Double.parseDouble(v[3]),
            Double.parseDouble(v[4])));
      } catch (NumberFormatException ignored) {
      }
    }
    return new NetSnapshot(mapId, players);
  }

  public static boolean isHeartbeat(String msg) {
    return "HEARTBEAT".equals(msg);
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
}
