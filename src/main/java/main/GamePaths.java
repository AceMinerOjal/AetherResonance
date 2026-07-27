package main;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class GamePaths {
  public static final String MAP_RESOURCE_DIR = "maps/";
  public static final String MAP_RESOURCE_EXT = ".json";
  public static final String[] DEFAULT_MAP_IDS = { "world", "cave", "dungeon" };

  public static final String SAVE_DIR = "saves";
  public static final String QUICKSAVE_FILE = "quicksave.properties";
  public static final Path QUICKSAVE_PATH = Paths.get(SAVE_DIR, QUICKSAVE_FILE);

  private GamePaths() {
  }

  public static String mapResource(String mapId) {
    return MAP_RESOURCE_DIR + mapId + MAP_RESOURCE_EXT;
  }
}
