package tile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import entity.Player;

public class LevelManager {
  private final Map<String, String> levelResources = new HashMap<>();
  private final Map<String, TiledMap> loadedMaps = new HashMap<>();

  private String currentMapId;
  private TiledMap currentMap;
  private boolean portalLock;

  public void registerLevel(String mapId, String mapResourcePath) {
    levelResources.put(mapId, mapResourcePath);
  }

  public boolean hasLevels() {
    return !levelResources.isEmpty();
  }

  public boolean hasLevel(String mapId) {
    return levelResources.containsKey(mapId);
  }

  public boolean updatePortals(Player triggeringPlayer, List<Player> party) {
    if (currentMap == null) {
      return false;
    }

    TiledMap.Portal intersectingPortal = currentMap.findIntersectingPortal(triggeringPlayer.getHitbox());
    if (intersectingPortal == null) {
      portalLock = false;
      return false;
    }

    if (portalLock) {
      return false;
    }

    transitionPartyTo(
        intersectingPortal.getTargetMap(),
        intersectingPortal.getTargetX(),
        intersectingPortal.getTargetY(),
        party);
    portalLock = true;
    return true;
  }

  public TiledMap getCurrentMap() {
    return currentMap;
  }

  private TiledMap loadMap(String mapId) {
    TiledMap existing = loadedMaps.get(mapId);
    if (existing != null) {
      return existing;
    }

    String resourcePath = levelResources.get(mapId);
    if (resourcePath == null) {
      throw new IllegalArgumentException("Unknown map id: " + mapId);
    }
    TiledMap loaded = TiledMapLoader.loadFromResource(resourcePath);
    loadedMaps.put(mapId, loaded);
    return loaded;
  }

  private void transitionPartyTo(String mapId, double spawnX, double spawnY, List<Player> party) {
    TiledMap next = loadMap(mapId);
    currentMap = next;
    currentMapId = mapId;

    for (int i = 0; i < party.size(); i++) {
      double offsetX = (i % 2) * 24;
      double offsetY = (i / 2) * 24;
      party.get(i).setWorldPosition(spawnX + offsetX, spawnY + offsetY);
    }
  }

  public String getCurrentMapId() {
    return currentMapId;
  }

  public void setCurrentMap(String mapId) {
    currentMap = loadMap(mapId);
    currentMapId = mapId;
    portalLock = true;
  }
}
