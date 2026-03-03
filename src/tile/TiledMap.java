package tile;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import lib.Hitbox;

public class TiledMap {
  public static final class Portal {
    private final double x;
    private final double y;
    private final double width;
    private final double height;
    private final String targetMap;
    private final double targetX;
    private final double targetY;

    public Portal(double x, double y, double width, double height,
        String targetMap, double targetX, double targetY) {
      this.x = x;
      this.y = y;
      this.width = width;
      this.height = height;
      this.targetMap = targetMap;
      this.targetX = targetX;
      this.targetY = targetY;
    }

    public String getTargetMap() {
      return targetMap;
    }

    public double getTargetX() {
      return targetX;
    }

    public double getTargetY() {
      return targetY;
    }

    public boolean intersects(Hitbox hitbox) {
      return hitbox.getLeft() < x + width
          && hitbox.getRight() > x
          && hitbox.getTop() < y + height
          && hitbox.getBottom() > y;
    }
  }

  public static final class FriendlyFireZone {
    private final double x;
    private final double y;
    private final double width;
    private final double height;

    public FriendlyFireZone(double x, double y, double width, double height) {
      this.x = x;
      this.y = y;
      this.width = width;
      this.height = height;
    }

    public boolean intersects(Hitbox hitbox) {
      return hitbox.getLeft() < x + width
          && hitbox.getRight() > x
          && hitbox.getTop() < y + height
          && hitbox.getBottom() > y;
    }
  }

  public static final class Layer {
    private final String name;
    private final int[] data;
    private final boolean visible;
    private final boolean collidable;

    public Layer(String name, int[] data, boolean visible, boolean collidable) {
      this.name = name;
      this.data = data;
      this.visible = visible;
      this.collidable = collidable;
    }
  }

  public static final class Tileset {
    private final int firstGid;
    private final int tileWidth;
    private final int tileHeight;
    private final int columns;
    private final int tileCount;
    private final BufferedImage image;
    private final Set<Integer> solidLocalTileIds;

    public Tileset(int firstGid, int tileWidth, int tileHeight, int columns,
        int tileCount, BufferedImage image, Set<Integer> solidLocalTileIds) {
      this.firstGid = firstGid;
      this.tileWidth = tileWidth;
      this.tileHeight = tileHeight;
      this.columns = columns;
      this.tileCount = tileCount;
      this.image = image;
      this.solidLocalTileIds = solidLocalTileIds;
    }

    public boolean contains(int gid) {
      int localId = gid - firstGid;
      return localId >= 0 && localId < tileCount;
    }

    public int getFirstGid() {
      return firstGid;
    }

    public boolean isSolid(int gid) {
      return solidLocalTileIds.contains(gid - firstGid);
    }
  }

  private final int width;
  private final int height;
  private final int tileWidth;
  private final int tileHeight;
  private final List<Layer> layers = new ArrayList<>();
  private final List<Tileset> tilesets = new ArrayList<>();
  private final List<Portal> portals = new ArrayList<>();
  private final List<FriendlyFireZone> friendlyFireZones = new ArrayList<>();

  public TiledMap(int width, int height, int tileWidth, int tileHeight) {
    this.width = width;
    this.height = height;
    this.tileWidth = tileWidth;
    this.tileHeight = tileHeight;
  }

  public void addLayer(Layer layer) {
    layers.add(layer);
  }

  public void addTileset(Tileset tileset) {
    tilesets.add(tileset);
  }

  public void addPortal(Portal portal) {
    portals.add(portal);
  }

  public void addFriendlyFireZone(FriendlyFireZone zone) {
    friendlyFireZones.add(zone);
  }

  public int getPixelWidth() {
    return width * tileWidth;
  }

  public int getPixelHeight() {
    return height * tileHeight;
  }

  public void draw(Graphics2D g2) {
    for (Layer layer : layers) {
      if (!layer.visible) {
        continue;
      }
      drawLayer(g2, layer);
    }
  }

  public Portal findIntersectingPortal(Hitbox hitbox) {
    for (Portal portal : portals) {
      if (portal.intersects(hitbox)) {
        return portal;
      }
    }
    return null;
  }

  public boolean isFriendlyFireEnabled(Hitbox hitbox) {
    for (FriendlyFireZone zone : friendlyFireZones) {
      if (zone.intersects(hitbox)) {
        return true;
      }
    }
    return false;
  }

  public boolean collides(Hitbox hitbox) {
    int minTileX = clamp((int) Math.floor(hitbox.getLeft() / tileWidth), 0, width - 1);
    int minTileY = clamp((int) Math.floor(hitbox.getTop() / tileHeight), 0, height - 1);
    int maxTileX = clamp((int) Math.floor((hitbox.getRight() - 0.0001) / tileWidth), 0, width - 1);
    int maxTileY = clamp((int) Math.floor((hitbox.getBottom() - 0.0001) / tileHeight), 0, height - 1);

    for (Layer layer : layers) {
      if (!layer.collidable) {
        continue;
      }
      for (int tileY = minTileY; tileY <= maxTileY; tileY++) {
        for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
          int gid = layer.data[tileY * width + tileX];
          if (gid == 0) {
            continue;
          }
          if (isSolidGid(gid)) {
            return true;
          }
        }
      }
    }

    return false;
  }

  private boolean isSolidGid(int gid) {
    for (Tileset tileset : tilesets) {
      if (tileset.contains(gid)) {
        return tileset.isSolid(gid);
      }
    }
    return false;
  }

  private void drawLayer(Graphics2D g2, Layer layer) {
    for (int tileY = 0; tileY < height; tileY++) {
      for (int tileX = 0; tileX < width; tileX++) {
        int gid = layer.data[tileY * width + tileX];
        if (gid == 0) {
          continue;
        }

        Tileset tileset = resolveTileset(gid);
        if (tileset == null) {
          continue;
        }

        int localId = gid - tileset.firstGid;
        int sourceX = (localId % tileset.columns) * tileset.tileWidth;
        int sourceY = (localId / tileset.columns) * tileset.tileHeight;

        int destX = tileX * tileWidth;
        int destY = tileY * tileHeight;
        g2.drawImage(
            tileset.image,
            destX, destY, destX + tileWidth, destY + tileHeight,
            sourceX, sourceY, sourceX + tileset.tileWidth, sourceY + tileset.tileHeight,
            null);
      }
    }
  }

  private Tileset resolveTileset(int gid) {
    Tileset resolved = null;
    for (Tileset tileset : tilesets) {
      if (tileset.firstGid <= gid) {
        resolved = tileset;
      } else {
        break;
      }
    }
    return (resolved != null && resolved.contains(gid)) ? resolved : null;
  }

  private int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }

  public static Set<Integer> emptySolidSet() {
    return new HashSet<>();
  }
}
