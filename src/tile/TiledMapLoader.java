package tile;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;

public final class TiledMapLoader {
  private TiledMapLoader() {
  }

  public static TiledMap loadFromResource(String mapResourcePath) {
    String json = readResourceText(mapResourcePath);
    Map<String, Object> root = asObject(SimpleJsonParser.parse(json), "root");

    String orientation = asString(root.get("orientation"), "orientation");
    if (!"orthogonal".equals(orientation)) {
      throw new IllegalArgumentException("Only orthogonal maps are supported.");
    }

    int width = asInt(root.get("width"), "width");
    int height = asInt(root.get("height"), "height");
    int tileWidth = asInt(root.get("tilewidth"), "tilewidth");
    int tileHeight = asInt(root.get("tileheight"), "tileheight");
    TiledMap map = new TiledMap(width, height, tileWidth, tileHeight);

    List<Map<String, Object>> tilesetObjects = asObjectList(root.get("tilesets"), "tilesets");
    List<TiledMap.Tileset> tilesets = new ArrayList<>();
    for (Map<String, Object> tilesetObject : tilesetObjects) {
      tilesets.add(parseTileset(tilesetObject, mapResourcePath));
    }
    tilesets.sort(Comparator.comparingInt(TiledMap.Tileset::getFirstGid));
    for (TiledMap.Tileset tileset : tilesets) {
      map.addTileset(tileset);
    }

    List<Map<String, Object>> layerObjects = asObjectList(root.get("layers"), "layers");
    for (Map<String, Object> layerObject : layerObjects) {
      String type = asString(layerObject.get("type"), "layers[].type");
      if ("tilelayer".equals(type)) {
        String name = asStringOrDefault(layerObject.get("name"), "Layer");
        boolean visible = asBooleanOrDefault(layerObject.get("visible"), true);
        boolean collidable = isLayerCollidable(name, layerObject);
        int[] data = asIntArray(layerObject.get("data"), width * height, "layers[].data");
        map.addLayer(new TiledMap.Layer(name, data, visible, collidable));
      } else if ("objectgroup".equals(type)) {
        parsePortals(layerObject, map);
      }
    }

    return map;
  }

  private static TiledMap.Tileset parseTileset(Map<String, Object> object, String mapResourcePath) {
    if (object.get("source") != null) {
      throw new IllegalArgumentException("External TSX tilesets are not supported yet.");
    }

    int firstGid = asInt(object.get("firstgid"), "tilesets[].firstgid");
    int tileWidth = asInt(object.get("tilewidth"), "tilesets[].tilewidth");
    int tileHeight = asInt(object.get("tileheight"), "tilesets[].tileheight");
    int columns = asInt(object.get("columns"), "tilesets[].columns");
    int tileCount = asInt(object.get("tilecount"), "tilesets[].tilecount");
    String imagePath = asString(object.get("image"), "tilesets[].image");
    String resolvedImagePath = resolveRelativePath(mapResourcePath, imagePath);
    BufferedImage image = readImageResource(resolvedImagePath);

    Set<Integer> solidLocalTileIds = parseSolidTileIds(object.get("tiles"));
    return new TiledMap.Tileset(firstGid, tileWidth, tileHeight, columns, tileCount, image, solidLocalTileIds);
  }

  private static Set<Integer> parseSolidTileIds(Object tilesObject) {
    Set<Integer> solidLocalTileIds = TiledMap.emptySolidSet();
    if (!(tilesObject instanceof List<?> tiles)) {
      return solidLocalTileIds;
    }

    for (Object tileEntryObj : tiles) {
      Map<String, Object> tileEntry = asObject(tileEntryObj, "tilesets[].tiles[]");
      int id = asInt(tileEntry.get("id"), "tilesets[].tiles[].id");
      Object propertiesObj = tileEntry.get("properties");
      if (!(propertiesObj instanceof List<?> properties)) {
        continue;
      }
      for (Object propertyObj : properties) {
        Map<String, Object> property = asObject(propertyObj, "tilesets[].tiles[].properties[]");
        String name = asString(property.get("name"), "tilesets[].tiles[].properties[].name");
        if (!"solid".equals(name)) {
          continue;
        }
        boolean solid = asBooleanOrDefault(property.get("value"), false);
        if (solid) {
          solidLocalTileIds.add(id);
        }
      }
    }

    return solidLocalTileIds;
  }

  private static boolean isLayerCollidable(String name, Map<String, Object> layerObject) {
    Object propertiesObject = layerObject.get("properties");
    if (propertiesObject instanceof List<?> properties) {
      for (Object propertyObj : properties) {
        Map<String, Object> property = asObject(propertyObj, "layers[].properties[]");
        String propName = asString(property.get("name"), "layers[].properties[].name");
        if ("collidable".equals(propName)) {
          return asBooleanOrDefault(property.get("value"), false);
        }
      }
    }
    return "collision".equalsIgnoreCase(name);
  }

  private static void parsePortals(Map<String, Object> layerObject, TiledMap map) {
    String name = asStringOrDefault(layerObject.get("name"), "");
    if (!"portals".equalsIgnoreCase(name)) {
      return;
    }
    Object objectsObj = layerObject.get("objects");
    if (!(objectsObj instanceof List<?> objects)) {
      return;
    }

    for (Object objectObj : objects) {
      Map<String, Object> object = asObject(objectObj, "layers[].objects[]");
      double x = asDouble(object.get("x"), "layers[].objects[].x");
      double y = asDouble(object.get("y"), "layers[].objects[].y");
      double width = asDoubleOrDefault(object.get("width"), 0);
      double height = asDoubleOrDefault(object.get("height"), 0);
      Map<String, Object> properties = propertiesAsMap(object.get("properties"));

      String targetMap = asOptionalString(properties.get("targetMap"));
      if (targetMap == null || targetMap.isBlank()) {
        continue;
      }
      double targetX = asDoubleOrDefault(properties.get("targetX"), x);
      double targetY = asDoubleOrDefault(properties.get("targetY"), y);

      if (width <= 0 || height <= 0) {
        continue;
      }
      map.addPortal(new TiledMap.Portal(x, y, width, height, targetMap, targetX, targetY));
    }
  }

  private static Map<String, Object> propertiesAsMap(Object propertiesObj) {
    java.util.LinkedHashMap<String, Object> map = new java.util.LinkedHashMap<>();
    if (!(propertiesObj instanceof List<?> properties)) {
      return map;
    }
    for (Object propertyObj : properties) {
      Map<String, Object> property = asObject(propertyObj, "properties[]");
      String name = asString(property.get("name"), "properties[].name");
      map.put(name, property.get("value"));
    }
    return map;
  }

  private static String resolveRelativePath(String basePath, String relativePath) {
    Path base = Paths.get(basePath).getParent();
    Path resolved = (base == null ? Paths.get(relativePath) : base.resolve(relativePath)).normalize();
    return resolved.toString().replace('\\', '/');
  }

  private static String readResourceText(String path) {
    try (InputStream in = resourceStream(path)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("Failed reading resource: " + path, e);
    }
  }

  private static BufferedImage readImageResource(String path) {
    try (InputStream in = resourceStream(path)) {
      BufferedImage image = ImageIO.read(in);
      if (image == null) {
        throw new IllegalStateException("Invalid image resource: " + path);
      }
      return image;
    } catch (IOException e) {
      throw new IllegalStateException("Failed reading tileset image: " + path, e);
    }
  }

  private static InputStream resourceStream(String path) {
    InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
    if (in == null) {
      throw new IllegalArgumentException("Resource not found: " + path);
    }
    return in;
  }

  private static Map<String, Object> asObject(Object value, String field) {
    if (value instanceof Map<?, ?> raw) {
      @SuppressWarnings("unchecked")
      Map<String, Object> casted = (Map<String, Object>) raw;
      return casted;
    }
    throw new IllegalArgumentException("Expected object for " + field);
  }

  private static List<Map<String, Object>> asObjectList(Object value, String field) {
    if (!(value instanceof List<?> list)) {
      throw new IllegalArgumentException("Expected array for " + field);
    }
    List<Map<String, Object>> result = new ArrayList<>(list.size());
    for (Object item : list) {
      result.add(asObject(item, field));
    }
    return result;
  }

  private static int[] asIntArray(Object value, int expectedLength, String field) {
    if (!(value instanceof List<?> list)) {
      throw new IllegalArgumentException("Expected number array for " + field);
    }
    if (list.size() != expectedLength) {
      throw new IllegalArgumentException("Invalid array size for " + field + ". Expected " + expectedLength);
    }
    int[] result = new int[list.size()];
    for (int i = 0; i < list.size(); i++) {
      result[i] = asInt(list.get(i), field + "[" + i + "]");
    }
    return result;
  }

  private static String asString(Object value, String field) {
    if (value instanceof String str) {
      return str;
    }
    throw new IllegalArgumentException("Expected string for " + field);
  }

  private static String asStringOrDefault(Object value, String defaultValue) {
    return (value instanceof String str) ? str : defaultValue;
  }

  private static int asInt(Object value, String field) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    throw new IllegalArgumentException("Expected number for " + field);
  }

  private static double asDouble(Object value, String field) {
    if (value instanceof Number number) {
      return number.doubleValue();
    }
    throw new IllegalArgumentException("Expected number for " + field);
  }

  private static double asDoubleOrDefault(Object value, double defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    if (value instanceof Number number) {
      return number.doubleValue();
    }
    throw new IllegalArgumentException("Expected number.");
  }

  private static String asOptionalString(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof String str) {
      return str;
    }
    throw new IllegalArgumentException("Expected string.");
  }

  private static boolean asBooleanOrDefault(Object value, boolean defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    if (value instanceof Boolean bool) {
      return bool;
    }
    throw new IllegalArgumentException("Expected boolean.");
  }
}
