package net;

import java.util.List;

public record NetworkConfig(
    NetworkMode mode,
    String host,
    int port,
    List<String> peerAddresses) {

  public NetworkConfig(NetworkMode mode, String host, int port) {
    this(mode, host, port, List.of());
  }

  public static NetworkConfig local() {
    return new NetworkConfig(NetworkMode.LOCAL, "127.0.0.1", 7777);
  }
}
