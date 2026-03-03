package net;

import java.util.List;

public record NetSnapshot(
    String mapId,
    List<NetPlayerState> players) {
}
