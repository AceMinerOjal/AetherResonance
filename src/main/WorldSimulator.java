package main;

import java.util.ArrayList;
import java.util.List;

import entity.enemy.DefaultEnemy;
import entity.enemy.Enemy;
import entity.enemy.Slime;
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

    for (Player player : players) {
      player.update(dt, map, enemies);

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

    for (int i = 0; i < enemies.size(); i++) {
      Enemy enemy = enemies.get(i);
      double oldX = enemy.getX();
      double oldY = enemy.getY();
      enemy.update(dt, map, players);
      if (map != null && map.collides(enemy.getHitbox())) {
        enemy.setWorldPosition(oldX, oldY);
      }
      if (!enemy.isAlive()) {
        enemies.remove(i);
        i--;
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

    boolean first = true;
    for (int[] spawn : map.getEnemySpawnTilesByVariant()) {
      int tileX = spawn[0];
      int tileY = spawn[1];
      int variant = spawn[2];
      if (first) {
        enemies.add(new Slime(
            tileX * map.getTileWidth(),
            tileY * map.getTileHeight(),
            tileX, tileY));
        first = false;
      } else {
        enemies.add(new DefaultEnemy(
            tileX * map.getTileWidth(),
            tileY * map.getTileHeight(),
            variant,
            tileX, tileY));
      }
    }
  }

  private void refreshFriendlyFireFlags(TiledMap map) {
    for (Player player : players) {
      boolean enabled = map != null && map.isFriendlyFireEnabled(player.getHitbox());
      player.setFriendlyFireEnabled(enabled);
    }
  }
}
