package net;

public record NetEnemyState(
    String appearanceId,
    double x,
    double y,
    double width,
    double height,
    String direction,
    String animation,
    int frame) {
}
