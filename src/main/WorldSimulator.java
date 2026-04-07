package main;

import java.util.ArrayList;
import java.util.List;

import entity.enemy.Enemy;
import entity.player.Player;
import net.NetEnemyState;
import tile.LevelManager;
import tile.TiledMap;

public class WorldSimulator {
  private final LevelManager levelManager;
  private final List<Player> players;
  private final List<Enemy> enemies = new ArrayList<>();
  private final int screenWidth;
  private final int screenHeight;

  private String enemyMapId;

  public WorldSimulator(LevelManager levelManager, List<Player> players, int screenWidth, int screenHeight) {
    this.levelManager = levelManager;
    this.players = players;
    this.screenWidth = screenWidth;
    this.screenHeight = screenHeight;
  }

  public List<Enemy> enemies() {
    return enemies;
  }

  public void simulate(double dt) {
    TiledMap map = levelManager.getCurrentMap();
    ensureEnemiesForCurrentMap(map);
    refreshFriendlyFireFlags(map);

    for (Player player : players) {
      double oldX = player.getX();
      double oldY = player.getY();
      player.update(dt);

      if (map != null && map.collides(player.getHitbox())) {
        player.setWorldPosition(oldX, oldY);
      }

      if (map != null) {
        player.clampToBounds(map.getPixelWidth(), map.getPixelHeight());
      } else {
        player.clampToBounds(screenWidth, screenHeight);
      }
    }

    for (Player player : players) {
      if (levelManager.updatePortals(player, players)) {
        map = levelManager.getCurrentMap();
        ensureEnemiesForCurrentMap(map);
        refreshFriendlyFireFlags(map);
        break;
      }
    }

    for (Enemy enemy : enemies) {
      double oldX = enemy.getX();
      double oldY = enemy.getY();
      enemy.update(dt, map, players);
      if (map != null && map.collides(enemy.getHitbox())) {
        enemy.setWorldPosition(oldX, oldY);
      }
    }

    refreshFriendlyFireFlags(map);
  }

  public List<NetEnemyState> buildNetStates() {
    List<NetEnemyState> states = new ArrayList<>(enemies.size());
    for (Enemy enemy : enemies) {
      states.add(new NetEnemyState(
          "enemy-" + enemy.getMovementVariant(),
          enemy.getX(),
          enemy.getY(),
          enemy.getSpriteWidth(),
          enemy.getSpriteHeight(),
          enemy.getDirection().name(),
          enemy.getCurrentAnimation().name(),
          enemy.getCurrentFrame()));
    }
    return states;
  }

  private void ensureEnemiesForCurrentMap(TiledMap map) {
    String currentMapId = levelManager.getCurrentMapId();
    if (map == null || currentMapId == null || currentMapId.isBlank()) {
      enemies.clear();
      enemyMapId = null;
      return;
    }
    if (currentMapId.equals(enemyMapId) && !enemies.isEmpty()) {
      return;
    }

    enemies.clear();
    enemyMapId = currentMapId;

    for (int[] spawn : map.getEnemySpawnTilesByVariant()) {
      int tileX = spawn[0];
      int tileY = spawn[1];
      int variant = spawn[2];
      enemies.add(new Enemy(tileX * map.getTileWidth(), tileY * map.getTileHeight(), variant));
    }
  }

  private void refreshFriendlyFireFlags(TiledMap map) {
    for (Player player : players) {
      boolean enabled = map != null && map.isFriendlyFireEnabled(player.getHitbox());
      player.setFriendlyFireEnabled(enabled);
    }
  }
}
