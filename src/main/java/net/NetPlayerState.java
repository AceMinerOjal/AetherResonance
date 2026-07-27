package net;

public record NetPlayerState(
    int slot,
    String appearanceId,
    double x,
    double y,
    double width,
    double height,
    String direction,
    String animation,
    int frame) {
}
