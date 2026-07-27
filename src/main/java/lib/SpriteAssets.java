package lib;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;

public record SpriteAssets(
    BufferedImage walkSheet,
    BufferedImage actionSheet) {

  private static final SpriteAssets EMPTY = new SpriteAssets(null, null);
  private static final Map<String, SpriteAssets> PLAYER_CACHE = new ConcurrentHashMap<>();

  public static SpriteAssets loadPlayer(String appearanceId) {
    if (appearanceId == null || appearanceId.isBlank()) {
      return EMPTY;
    }
    return PLAYER_CACHE.computeIfAbsent(appearanceId, SpriteAssets::loadPlayerAssets);
  }

  public boolean isLoaded() {
    return walkSheet != null && actionSheet != null;
  }

  private static SpriteAssets loadPlayerAssets(String appearanceId) {
    String baseName = "sprites/players/" + appearanceId;
    BufferedImage walk = readImage(baseName + "_walk.png");
    BufferedImage action = readImage(baseName + "_action.png");
    if (walk == null || action == null) {
      System.err.println("Missing player sprite assets for " + appearanceId);
      return EMPTY;
    }
    return new SpriteAssets(walk, action);
  }

  private static BufferedImage readImage(String resourcePath) {
    BufferedImage classpathImage = readClasspathImage(resourcePath);
    if (classpathImage != null) {
      return classpathImage;
    }

    Path filesystemPath = Paths.get("res").resolve(resourcePath);
    if (!Files.exists(filesystemPath)) {
      return null;
    }

    try (InputStream in = Files.newInputStream(filesystemPath)) {
      return ImageIO.read(in);
    } catch (Exception ex) {
      System.err.println("Failed to read sprite image " + filesystemPath + ": " + ex.getMessage());
      return null;
    }
  }

  private static BufferedImage readClasspathImage(String resourcePath) {
    try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath)) {
      if (in == null) {
        return null;
      }
      return ImageIO.read(in);
    } catch (Exception ex) {
      System.err.println("Failed to read classpath sprite image " + resourcePath + ": " + ex.getMessage());
      return null;
    }
  }
}
