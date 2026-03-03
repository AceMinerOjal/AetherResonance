package net;

public enum NetworkMode {
  LOCAL,
  LAN_HOST,
  LAN_CLIENT,
  TCP_HOST,
  TCP_CLIENT;

  public boolean isLocal() {
    return this == LOCAL;
  }

  public boolean isHost() {
    return this == LAN_HOST || this == TCP_HOST;
  }

  public boolean isClient() {
    return this == LAN_CLIENT || this == TCP_CLIENT;
  }

  public boolean isLan() {
    return this == LAN_HOST || this == LAN_CLIENT;
  }
}
