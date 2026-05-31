package entity.enemy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import entity.Health;
import entity.Dialectics;
import entity.player.Player;
import entity.statusEffects.EffectTarget;
import entity.statusEffects.StatusEffect;
import lib.Entity;
import tile.TiledMap;

public abstract class Enemy extends Entity implements EffectTarget {
  private static final int SENSE_RANGE_TILES = 10;
  private static final double SPEED_PX_PER_SEC = 52.0;
  private static final double REPATH_INTERVAL_SEC = 0.2;
  private static final int PATROL_RADIUS_TILES = 10;

  protected Health hp;
  protected Dialectics ap;
  protected Dialectics defence;
  protected boolean alive = true;
  protected final List<StatusEffect> activeStatusEffects = new ArrayList<>();

  private final List<int[]> path = new ArrayList<>();
  private final int movementVariant;
  private final int spawnTileX;
  private final int spawnTileY;
  protected int level = 1;
  private double repathCooldown;

  public Enemy(double x, double y, int movementVariant, int spawnTileX, int spawnTileY) {
    setPosition(x, y);
    setHitbox(20, 24, 6, 8);
    this.movementVariant = movementVariant;
    this.spawnTileX = spawnTileX;
    this.spawnTileY = spawnTileY;

    // Default base stats
    this.hp = new Health(50, 50, 0);
    this.ap = new Dialectics(10);
    this.defence = new Dialectics(5);
  }

  @Override
  public int getLevel() {
    return level;
  }

  @Override
  public double getAttackPower() {
    return ap.get();
  }

  @Override
  public double getDefence() {
    return defence.get();
  }

  @Override
  public void applyDamage(double amount) {
    hp.damage(amount);
    if (hp.get() <= 0) {
      alive = false;
    }
  }

  public int getMovementVariant() {
    return movementVariant;
  }

  public void setWorldPosition(double x, double y) {
    setPosition(x, y);
  }

  public boolean isAlive() {
    return alive;
  }

  @Override
  public void addStatusEffect(StatusEffect effect) {
    for (StatusEffect active : activeStatusEffects) {
      if (active.getName().equals(effect.getName())) {
        active.start();
        return;
      }
    }
    effect.apply(this);
    activeStatusEffects.add(effect);
  }

  @Override
  public void removeStatusEffect(StatusEffect effect) {
    activeStatusEffects.remove(effect);
  }

  public void update(double dt, TiledMap map, List<Player> players) {
    if (!alive) return;
    hp.update(dt);
    ap.update(dt);
    defence.update(dt);

    for (int i = activeStatusEffects.size() - 1; i >= 0; i--) {
      activeStatusEffects.get(i).update(dt);
      if (!activeStatusEffects.get(i).isActive()) {
        activeStatusEffects.remove(i);
      }
    }

    updateAnimation((float) dt);
    if (map == null || players == null || players.isEmpty()) {
      path.clear();
      setAnimation(AnimationState.IDLE);
      return;
    }

    Player target = nearestSensedPlayer(map, players);
    if (target == null) {
      path.clear();
      setAnimation(AnimationState.IDLE);
      return;
    }

    repathCooldown -= dt;
    if (repathCooldown <= 0.0 || path.isEmpty()) {
      rebuildPathTo(map, target);
      repathCooldown = REPATH_INTERVAL_SEC;
    }

    followPath(dt, map);
    constrainToPatrolRadius(map);
  }

  private Player nearestSensedPlayer(TiledMap map, List<Player> players) {
    int[] enemyTile = toTile(map, x, y);
    Player best = null;
    double bestDistSq = Double.MAX_VALUE;

    for (Player player : players) {
      int[] playerTile = toTile(map, player.getX(), player.getY());

      // Player must be within patrol radius from spawn
      int pxFromSpawn = playerTile[0] - spawnTileX;
      int pyFromSpawn = playerTile[1] - spawnTileY;
      int playerDistSqFromSpawn = pxFromSpawn * pxFromSpawn + pyFromSpawn * pyFromSpawn;
      if (playerDistSqFromSpawn > PATROL_RADIUS_TILES * PATROL_RADIUS_TILES) {
        continue;
      }

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

  /**
   * If the enemy has wandered beyond PATROL_RADIUS_TILES from its spawn tile,
   * redirect it back toward the spawn point.
   */
  private void constrainToPatrolRadius(TiledMap map) {
    int[] enemyTile = toTile(map, x, y);
    int dx = enemyTile[0] - spawnTileX;
    int dy = enemyTile[1] - spawnTileY;
    int distSq = dx * dx + dy * dy;

    if (distSq <= PATROL_RADIUS_TILES * PATROL_RADIUS_TILES) {
      return; // within patrol radius
    }

    // Outside radius: set path back toward spawn tile
    path.clear();
    List<int[]> returnPath = bfsPath(map, enemyTile[0], enemyTile[1], spawnTileX, spawnTileY);
    path.addAll(returnPath);

    // If BFS failed, move directly toward spawn as a fallback
    if (path.isEmpty()) {
      double targetX = spawnTileX * map.getTileWidth();
      double targetY = spawnTileY * map.getTileHeight();
      double ddx = targetX - x;
      double ddy = targetY - y;
      double dist = Math.hypot(ddx, ddy);
      if (dist > 0.5) {
        double step = Math.min(SPEED_PX_PER_SEC * 0.016, dist); // ~1 frame at 60fps
        double nx = x + (ddx / dist) * step;
        double ny = y + (ddy / dist) * step;
        setPosition(nx, ny);
        direction = Math.abs(ddx) > Math.abs(ddy)
            ? (ddx >= 0 ? Direction.RIGHT : Direction.LEFT)
            : (ddy >= 0 ? Direction.DOWN : Direction.UP);
        setAnimation(AnimationState.WALK);
      }
    }
  }

  private void rebuildPathTo(TiledMap map, Player target) {
    int[] start = toTile(map, x, y);
    int[] goal = toTile(map, target.getX(), target.getY());

    // Don't chase beyond patrol radius from spawn
    int gxFromSpawn = goal[0] - spawnTileX;
    int gyFromSpawn = goal[1] - spawnTileY;
    int goalDistSq = gxFromSpawn * gxFromSpawn + gyFromSpawn * gyFromSpawn;
    if (goalDistSq > PATROL_RADIUS_TILES * PATROL_RADIUS_TILES) {
      // Clamp goal toward spawn tile to the edge of the patrol radius
      int[] clamped = clampToward(spawnTileX, spawnTileY, goal[0], goal[1], PATROL_RADIUS_TILES);
      goal = clamped;
    }

    List<int[]> nextPath = bfsPath(map, start[0], start[1], goal[0], goal[1]);
    path.clear();
    path.addAll(nextPath);
    if (!path.isEmpty() && path.get(0)[0] == start[0] && path.get(0)[1] == start[1]) {
      path.remove(0);
    }
  }

  /**
   * Clamps (tx,ty) toward (ox,oy) so the result is at most maxDist tiles away
   * from (ox,oy).
   */
  private static int[] clampToward(int ox, int oy, int tx, int ty, int maxDist) {
    int dx = tx - ox;
    int dy = ty - oy;
    int distSq = dx * dx + dy * dy;
    if (distSq <= maxDist * maxDist) {
      return new int[] { tx, ty };
    }
    double dist = Math.sqrt(distSq);
    double scale = (double) maxDist / dist;
    return new int[] { ox + (int) Math.round(dx * scale), oy + (int) Math.round(dy * scale) };
  }

  private void followPath(double dt, TiledMap map) {
    if (path.isEmpty()) {
      setAnimation(AnimationState.IDLE);
      return;
    }

    int[] nextTile = path.get(0);
    double targetX = nextTile[0] * map.getTileWidth();
    double targetY = nextTile[1] * map.getTileHeight();

    double dx = targetX - x;
    double dy = targetY - y;
    double distance = Math.hypot(dx, dy);

    if (distance < 1.5) {
      path.remove(0);
      setAnimation(path.isEmpty() ? AnimationState.IDLE : AnimationState.WALK);
      return;
    }

    direction = Math.abs(dx) > Math.abs(dy)
        ? (dx >= 0 ? Direction.RIGHT : Direction.LEFT)
        : (dy >= 0 ? Direction.DOWN : Direction.UP);
    setAnimation(AnimationState.WALK);

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

    // Use a flat array and primitive storage to reduce allocations
    int[] dist = new int[w * h];
    int[] prev = new int[w * h];
    java.util.Arrays.fill(dist, Integer.MAX_VALUE);
    java.util.Arrays.fill(prev, -1);

    java.util.PriorityQueue<Node> pq = new java.util.PriorityQueue<>();
    dist[sy * w + sx] = 0;
    pq.add(new Node(sx, sy, 0, Math.abs(gx - sx) + Math.abs(gy - sy)));

    int[] dirs = { 1, 0, -1, 0, 0, 1, 0, -1 };
    while (!pq.isEmpty()) {
      Node current = pq.poll();
      if (current.x == gx && current.y == gy) break;
      if (current.g > dist[current.y * w + current.x]) continue;

      for (int i = 0; i < dirs.length; i += 2) {
        int nx = current.x + dirs[i];
        int ny = current.y + dirs[i + 1];

        if (nx >= 0 && ny >= 0 && nx < w && ny < h && isWalkable(map, nx, ny)) {
          int newG = current.g + 1;
          if (newG < dist[ny * w + nx]) {
            dist[ny * w + nx] = newG;
            prev[ny * w + nx] = current.y * w + current.x;
            pq.add(new Node(nx, ny, newG, Math.abs(gx - nx) + Math.abs(gy - ny)));
          }
        }
      }
    }

    if (prev[gy * w + gx] == -1) return Collections.emptyList();

    ArrayList<int[]> reversed = new ArrayList<>();
    int curr = gy * w + gx;
    while (curr != -1) {
      reversed.add(new int[] { curr % w, curr / w });
      if (curr == sy * w + sx) break;
      curr = prev[curr];
    }
    Collections.reverse(reversed);
    return reversed;
  }

  private static class Node implements Comparable<Node> {
    int x, y, g, h;
    Node(int x, int y, int g, int h) { this.x = x; this.y = y; this.g = g; this.h = h; }
    @Override public int compareTo(Node o) { return Integer.compare(this.g + this.h, o.g + o.h); }
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
