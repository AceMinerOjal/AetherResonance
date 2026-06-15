package main;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import tile.SimpleJsonParser;

public class BiomeRegistry {
  private final Map<String, Biome> biomes = new LinkedHashMap<>();
  private final Map<Integer, String> tileToBiome = new HashMap<>();
  private Biome fallback;

  public boolean load(String resourcePath) {
    try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath)) {
      if (in == null) {
        System.err.println("BiomeRegistry: resource not found: " + resourcePath);
        return false;
      }
      String text = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
          .lines().collect(Collectors.joining("\n"));
      Object parsed = SimpleJsonParser.parse(text);
      if (!(parsed instanceof Map<?, ?> root)) {
        System.err.println("BiomeRegistry: expected JSON object at root");
        return false;
      }
      @SuppressWarnings("unchecked")
      List<Object> biomeList = (List<Object>) ((Map<String, Object>) root).get("biomes");
      if (biomeList == null) {
        System.err.println("BiomeRegistry: missing 'biomes' array");
        return false;
      }
      for (Object obj : biomeList) {
        if (!(obj instanceof Map<?, ?> entry)) continue;
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) entry;
        String id = string(map, "id");
        String name = string(map, "name");
        if (id == null) continue;

        List<Integer> tiles = intList(map.get("tiles"));
        List<Biome.MusicEntry> music = parseMusic(map.get("music"));
        List<Biome.AmbientEntry> ambient = parseAmbient(map.get("ambient"));

        biomes.put(id, new Biome(id, name != null ? name : id,
            List.copyOf(tiles), List.copyOf(music), List.copyOf(ambient)));

        for (int gid : tiles) {
          tileToBiome.put(gid, id);
        }
      }
      fallback = biomes.get("plains");
      if (fallback == null && !biomes.isEmpty()) fallback = biomes.values().iterator().next();
      System.out.println("BiomeRegistry: loaded " + biomes.size() + " biomes, " + tileToBiome.size() + " tile mappings");
      return true;
    } catch (Exception e) {
      System.err.println("BiomeRegistry: error loading " + resourcePath + ": " + e.getMessage());
      return false;
    }
  }

  public Biome getBiome(String id) {
    Biome b = biomes.get(id);
    return b != null ? b : fallback;
  }

  public String getBiomeIdForTile(int tileGid) {
    if (tileGid < 0) return null;
    return tileToBiome.get(tileGid);
  }

  public boolean hasBiome(String id) {
    return biomes.containsKey(id);
  }

  public List<Biome> allBiomes() {
    return List.copyOf(biomes.values());
  }

  private static List<Biome.MusicEntry> parseMusic(Object raw) {
    List<Biome.MusicEntry> result = new ArrayList<>();
    if (!(raw instanceof List<?> list)) return result;
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> m)) continue;
      @SuppressWarnings("unchecked")
      Map<String, Object> entry = (Map<String, Object>) m;
      String path = string(entry, "path");
      if (path == null) continue;
      int weight = intOrDefault(entry.get("weight"), 1);
      result.add(new Biome.MusicEntry(path, Math.max(1, weight)));
    }
    return result;
  }

  private static List<Biome.AmbientEntry> parseAmbient(Object raw) {
    List<Biome.AmbientEntry> result = new ArrayList<>();
    if (!(raw instanceof List<?> list)) return result;
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> m)) continue;
      @SuppressWarnings("unchecked")
      Map<String, Object> entry = (Map<String, Object>) m;
      String path = string(entry, "path");
      if (path == null) continue;
      int weight = intOrDefault(entry.get("weight"), 1);
      double minInt = doubleOrDefault(entry.get("minInterval"), 3.0);
      double maxInt = doubleOrDefault(entry.get("maxInterval"), 8.0);
      if (maxInt < minInt) maxInt = minInt;
      result.add(new Biome.AmbientEntry(path, Math.max(1, weight), Math.max(0.5, minInt), maxInt));
    }
    return result;
  }

  private static List<Integer> intList(Object raw) {
    List<Integer> result = new ArrayList<>();
    if (!(raw instanceof List<?> list)) return result;
    for (Object item : list) {
      if (item instanceof Number n) result.add(n.intValue());
    }
    return result;
  }

  private static String string(Map<String, Object> map, String key) {
    Object v = map.get(key);
    return v instanceof String s ? s : null;
  }

  private static int intOrDefault(Object v, int def) {
    if (v instanceof Number n) return n.intValue();
    return def;
  }

  private static double doubleOrDefault(Object v, double def) {
    if (v instanceof Number n) return n.doubleValue();
    return def;
  }
}
