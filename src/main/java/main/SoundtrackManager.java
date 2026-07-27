package main;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import tile.TiledMap;

public class SoundtrackManager {
  private final AudioManager audio;
  private final BiomeRegistry biomes;

  private String currentBiomeId;
  private String lastTrackPath;
  private boolean enabled = true;

  private final List<AmbientTimer> ambientTimers = new ArrayList<>();
  private static final double AMBIENT_CHECK_INTERVAL = 2.0;
  private double ambientCheckTimer;

  public SoundtrackManager(AudioManager audio, BiomeRegistry biomes) {
    this.audio = audio;
    this.biomes = biomes;
  }

  public void update(double playerX, double playerY, TiledMap map, double deltaSec) {
    if (!enabled || map == null) return;

    String tileBiomeId = resolveTileBiome(playerX, playerY, map);

    if (tileBiomeId == null) {
      if (currentBiomeId != null) {
        currentBiomeId = null;
        audio.stopMusic(true);
        clearAmbientTimers();
      }
      return;
    }

    if (!tileBiomeId.equals(currentBiomeId)) {
      currentBiomeId = tileBiomeId;
      playRandomForBiome(currentBiomeId);
      rebuildAmbientTimers(currentBiomeId);
      return;
    }

    updateAmbient(deltaSec);
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
    if (!enabled) {
      audio.stopMusic(false);
      clearAmbientTimers();
    }
  }

  public boolean isEnabled() {
    return enabled;
  }

  private String resolveTileBiome(double px, double py, TiledMap map) {
    int tw = map.getTileWidth();
    int th = map.getTileHeight();
    int tx = (int) Math.floor(px / tw);
    int ty = (int) Math.floor(py / th);

    if (tx >= 0 && ty >= 0 && tx < map.getWidthTiles() && ty < map.getHeightTiles()) {
      int variant = map.getVariantAtTile(tx, ty);
      if (variant > 0) {
        TiledMap.Tileset ts = map.resolveTileset(variant);
        if (ts != null) {
          String tileBiome = biomes.getBiomeIdForTileset(ts.getImagePath());
          if (tileBiome != null) return tileBiome;
        }
      }
    }
    String mapBiome = map.getBiomeId();
    return biomes.hasBiome(mapBiome) ? mapBiome : null;
  }

  private void playRandomForBiome(String biomeId) {
    Biome biome = biomes.getBiome(biomeId);
    if (biome == null || biome.music().isEmpty()) {
      audio.stopMusic(true);
      return;
    }

    Biome.MusicEntry selected = weightedPick(biome.music());
    if (selected == null) return;

    if (selected.path().equals(lastTrackPath) && biome.music().size() > 1) {
      selected = weightedPick(biome.music(), selected.path());
      if (selected == null) return;
    }

    lastTrackPath = selected.path();
    audio.playMusic(selected.path(), false, true);

    Runnable onFinish = () -> {
      if (enabled && biomeId.equals(currentBiomeId)) {
        playRandomForBiome(biomeId);
      }
    };
    if (audio.musicStream != null) {
      audio.musicStream.onFinish = onFinish;
    }
  }

  private void updateAmbient(double deltaSec) {
    ambientCheckTimer -= deltaSec;
    if (ambientCheckTimer > 0) return;
    ambientCheckTimer = AMBIENT_CHECK_INTERVAL;

    if (currentBiomeId == null) return;
    Biome biome = biomes.getBiome(currentBiomeId);
    if (biome == null || biome.ambient().isEmpty()) return;

    long now = System.nanoTime();
    for (AmbientTimer at : ambientTimers) {
      if (now >= at.nextPlayNs) {
        audio.playSound(at.entry.path(), SoundCategory.AMBIENT);
        double interval = at.entry.minInterval()
            + ThreadLocalRandom.current().nextDouble(at.entry.maxInterval() - at.entry.minInterval());
        at.nextPlayNs = now + (long) (interval * 1_000_000_000L);
      }
    }
  }

  private void rebuildAmbientTimers(String biomeId) {
    ambientTimers.clear();
    Biome biome = biomes.getBiome(biomeId);
    if (biome == null) return;
    long now = System.nanoTime();
    for (Biome.AmbientEntry entry : biome.ambient()) {
      double firstDelay = entry.minInterval()
          + ThreadLocalRandom.current().nextDouble(entry.maxInterval() - entry.minInterval());
      ambientTimers.add(new AmbientTimer(entry, now + (long) (firstDelay * 1_000_000_000L)));
    }
  }

  private void clearAmbientTimers() {
    ambientTimers.clear();
  }

  private Biome.MusicEntry weightedPick(List<Biome.MusicEntry> entries) {
    return weightedPick(entries, null);
  }

  private Biome.MusicEntry weightedPick(List<Biome.MusicEntry> entries, String excludePath) {
    int totalWeight = 0;
    for (Biome.MusicEntry e : entries) {
      if (!e.path().equals(excludePath)) totalWeight += e.weight();
    }
    if (totalWeight <= 0) return null;

    int roll = ThreadLocalRandom.current().nextInt(totalWeight);
    int cumulative = 0;
    for (Biome.MusicEntry e : entries) {
      if (e.path().equals(excludePath)) continue;
      cumulative += e.weight();
      if (roll < cumulative) return e;
    }
    return null;
  }

  private static class AmbientTimer {
    final Biome.AmbientEntry entry;
    long nextPlayNs;

    AmbientTimer(Biome.AmbientEntry entry, long nextPlayNs) {
      this.entry = entry;
      this.nextPlayNs = nextPlayNs;
    }
  }
}
