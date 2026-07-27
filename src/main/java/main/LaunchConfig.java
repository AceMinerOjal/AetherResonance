package main;

import net.NetworkConfig;
import net.NetworkMode;

public record LaunchConfig(NetworkConfig networkConfig, int screenWidth, int screenHeight) {

  public static LaunchConfig fromArgs(String[] args, int baseWidth, int baseHeight) {
    NetworkMode mode = NetworkMode.LOCAL;
    String host = "127.0.0.1";
    int port = 7777;
    java.util.List<String> peerAddresses = new java.util.ArrayList<>();

    for (String arg : args) {
      if (arg.startsWith("--mode=")) {
        String value = arg.substring("--mode=".length()).trim().toLowerCase();
        mode = switch (value) {
          case "local" -> NetworkMode.LOCAL;
          case "p2p-host" -> NetworkMode.P2P_HOST;
          case "p2p-peer" -> NetworkMode.P2P_PEER;
          default -> mode;
        };
      } else if (arg.startsWith("--host=")) {
        host = arg.substring("--host=".length()).trim();
      } else if (arg.startsWith("--port=")) {
        try { port = Integer.parseInt(arg.substring("--port=".length()).trim()); }
        catch (NumberFormatException ignored) {}
      } else if (arg.startsWith("--peer=")) {
        peerAddresses.add(arg.substring("--peer=".length()).trim());
      }
    }
    return new LaunchConfig(new NetworkConfig(mode, host, port, peerAddresses), baseWidth, baseHeight);
  }
}
