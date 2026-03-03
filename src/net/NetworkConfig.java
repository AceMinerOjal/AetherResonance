package net;

public record NetworkConfig(
    NetworkMode mode,
    String host,
    int port) {

  public static NetworkConfig local() {
    return new NetworkConfig(NetworkMode.LOCAL, "127.0.0.1", 7777);
  }
}
