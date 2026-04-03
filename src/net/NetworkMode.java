package net;

public enum NetworkMode {
  LOCAL,
  P2P_HOST,
  P2P_PEER;

  public boolean isLocal() {
    return this == LOCAL;
  }

  public boolean isP2P() {
    return this == P2P_HOST || this == P2P_PEER;
  }

  public boolean isHost() {
    return this == P2P_HOST;
  }

  public boolean isPeer() {
    return this == P2P_PEER;
  }
}
