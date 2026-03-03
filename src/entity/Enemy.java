package entity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import lib.Entity;
import tile.TiledMap;

public class Enemy extends Entity {
  private static final int SENSE_RANGE_TILES = 10;
  private static final double SPEED_PX_PER_SEC = 52.0;
  private static final double REPATH_INTERVAL_SEC = 0.2;

  private final List<int[]> path = new ArrayList<>();
  private final int movementVariant;
  private double repathCooldown;

  public Enemy(double x, double y, int movementVariant) {
    setPosition(x, y);
    setHitbox(20, 24, 6, 8);
    this.movementVariant = movementVariant;
  }

  public void setWorldPosition(double x, double y) {
    setPosition(x, y);
  }

  public void update(double dt, TiledMap map, List<Player> players) {
    if (map == null || players == null || players.isEmpty()) {
      path.clear();
      return;
    }

    Player target = nearestSensedPlayer(map, players);
    if (target == null) {
      path.clear();
      return;
    }

    repathCooldown -= dt;
    if (repathCooldown <= 0.0 || path.isEmpty()) {
      rebuildPathTo(map, target);
      repathCooldown = REPATH_INTERVAL_SEC;
    }

    followPath(dt, map);
  }

  private Player nearestSensedPlayer(TiledMap map, List<Player> players) {
    int[] enemyTile = toTile(map, x, y);
    Player best = null;
    double bestDistSq = Double.MAX_VALUE;

    for (Player player : players) {
      int[] playerTile = toTile(map, player.getX(), player.getY());
      int dxTiles = playerTile[0] - enemyTile[0];
      int dyTiles = playerTile[1] - enemyTile[1];
      double tileDistSq = (dxTiles * dxTiles) + (dyTiles * dyTiles);
      if (tileDistSq > SENSE_RANGE_TILES * SENSE_RANGE_TILES) {
        continue;
      }
      if (map.getVariantAtTile(playerTile[0], playerTile[1]) != movementVariant) {
        continue;
      }
      if (tileDistSq < bestDistSq) {
        best = player;
        bestDistSq = tileDistSq;
      }
    }
    return best;
  }

  private void rebuildPathTo(TiledMap map, Player target) {
    int[] start = toTile(map, x, y);
    int[] goal = toTile(map, target.getX(), target.getY());
    List<int[]> nextPath = bfsPath(map, start[0], start[1], goal[0], goal[1]);
    path.clear();
    path.addAll(nextPath);
    if (!path.isEmpty() && path.get(0)[0] == start[0] && path.get(0)[1] == start[1]) {
      path.remove(0);
    }
  }

  private void followPath(double dt, TiledMap map) {
    if (path.isEmpty()) {
      return;
    }

    int[] nextTile = path.get(0);
    double targetX = nextTile[0] * map.getTileWidth() + (map.getTileWidth() * 0.5);
    double targetY = nextTile[1] * map.getTileHeight() + (map.getTileHeight() * 0.5);

    double dx = targetX - x;
    double dy = targetY - y;
    double distance = Math.hypot(dx, dy);

    if (distance < 1.5) {
      path.remove(0);
      return;
    }

    double maxStep = SPEED_PX_PER_SEC * dt;
    double step = Math.min(maxStep, distance);
    double nx = x + (dx / distance) * step;
    double ny = y + (dy / distance) * step;
    setPosition(nx, ny);
  }

  private List<int[]> bfsPath(TiledMap map, int sx, int sy, int gx, int gy) {
    if (!isWalkable(map, sx, sy) || !isWalkable(map, gx, gy)) {
      return Collections.emptyList();
    }
    if (sx == gx && sy == gy) {
      return List.of(new int[] { sx, sy });
    }

    int w = map.getWidthTiles();
    int h = map.getHeightTiles();
    boolean[][] visited = new boolean[h][w];
    int[][] prevX = new int[h][w];
    int[][] prevY = new int[h][w];

    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        prevX[y][x] = -1;
        prevY[y][x] = -1;
      }
    }

    ArrayDeque<int[]> queue = new ArrayDeque<>();
    queue.add(new int[] { sx, sy });
    visited[sy][sx] = true;

    int[] dirs = { 1, 0, -1, 0, 0, 1, 0, -1 };
    while (!queue.isEmpty()) {
      int[] node = queue.removeFirst();
      int x = node[0];
      int y = node[1];
      if (x == gx && y == gy) {
        break;
      }
      for (int i = 0; i < dirs.length; i += 2) {
        int nx = x + dirs[i];
        int ny = y + dirs[i + 1];
        if (nx < 0 || ny < 0 || nx >= w || ny >= h) {
          continue;
        }
        if (visited[ny][nx] || !isWalkable(map, nx, ny)) {
          continue;
        }
        visited[ny][nx] = true;
        prevX[ny][nx] = x;
        prevY[ny][nx] = y;
        queue.addLast(new int[] { nx, ny });
      }
    }

    if (!visited[gy][gx]) {
      return Collections.emptyList();
    }

    ArrayList<int[]> reversed = new ArrayList<>();
    int cx = gx;
    int cy = gy;
    while (cx >= 0 && cy >= 0) {
      reversed.add(new int[] { cx, cy });
      if (cx == sx && cy == sy) {
        break;
      }
      int px = prevX[cy][cx];
      int py = prevY[cy][cx];
      cx = px;
      cy = py;
    }

    Collections.reverse(reversed);
    return reversed;
  }

  private boolean isWalkable(TiledMap map, int tileX, int tileY) {
    return !map.isTileBlocked(tileX, tileY) && map.getVariantAtTile(tileX, tileY) == movementVariant;
  }

  private int[] toTile(TiledMap map, double px, double py) {
    int tileX = clamp((int) (px / map.getTileWidth()), 0, map.getWidthTiles() - 1);
    int tileY = clamp((int) (py / map.getTileHeight()), 0, map.getHeightTiles() - 1);
    return new int[] { tileX, tileY };
  }

  private int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }
}
