package main;

import java.util.List;

public record Biome(
    String id,
    String name,
    List<Integer> tiles,
    List<MusicEntry> music,
    List<AmbientEntry> ambient) {

  public record MusicEntry(String path, int weight) {}

  public record AmbientEntry(String path, int weight, double minInterval, double maxInterval) {}
}
