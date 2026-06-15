package render.vulkan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import entity.enemy.Enemy;
import entity.player.Player;
import lib.Entity;
import lib.SpriteAssets;
import net.NetEnemyState;
import net.NetPlayerState;
import net.NetSnapshot;
import tile.TiledMap;
import tile.TiledMap.Layer;
import tile.TiledMap.Tileset;

public final class VulkanGameRenderer {
  private static final float WHITE = 1.0f;
  private static final float FALLBACK_R = 0.3f;
  private static final float FALLBACK_G = 0.6f;
  private static final float FALLBACK_B = 1.0f;
  private static final int FRAME_SIZE = 32;

  private static final SpriteSheetLayout WALK_LAYOUT =
      new SpriteSheetLayout(4, 8, FRAME_SIZE, FRAME_SIZE);
  private static final SpriteSheetLayout ACTION_LAYOUT =
      new SpriteSheetLayout(4, 4, FRAME_SIZE, FRAME_SIZE);

  private final VulkanRenderer renderer;
  private final int screenWidth;
  private final int screenHeight;
  private final Map<Tileset, Integer> tilesetTex = new IdentityHashMap<>();
  private final Map<String, Integer> walkTex = new HashMap<>();
  private final Map<String, Integer> actionTex = new HashMap<>();
  private final Map<String, Integer> enemyTex = new HashMap<>();

  public VulkanGameRenderer(VulkanRenderer renderer, int screenWidth, int screenHeight) {
    this.renderer = renderer;
    this.screenWidth = screenWidth;
    this.screenHeight = screenHeight;
  }

  public List<VulkanRenderer.RenderCommand> buildLocalCommands(
      TiledMap map, List<Player> players, List<Enemy> enemies, float camX, float camY) {
    ensureTilesetTexturesLoaded(map);
    ensureLivePlayerTexturesLoaded(players);

    List<VulkanRenderer.RenderCommand> commands = new ArrayList<>();
    buildTileCommands(commands, map, camX, camY);
    for (Player player : players) {
      appendPlayerCommand(commands, PlayerVisual.fromPlayer(player));
    }
    for (Enemy enemy : enemies) {
      if (!enemy.isAlive()) continue;
      appendEnemyCommand(commands, EnemyVisual.fromEnemy(enemy));
    }
    return commands;
  }

  public List<VulkanRenderer.RenderCommand> buildRemoteCommands(TiledMap map, NetSnapshot snapshot, float camX, float camY) {
    ensureTilesetTexturesLoaded(map);
    ensureSnapshotPlayerTexturesLoaded(snapshot.players());

    List<VulkanRenderer.RenderCommand> commands = new ArrayList<>();
    buildTileCommands(commands, map, camX, camY);
    for (NetPlayerState state : snapshot.players()) {
      appendPlayerCommand(commands, PlayerVisual.fromSnapshot(state));
    }
    for (NetEnemyState state : snapshot.enemies()) {
      appendEnemyCommand(commands, EnemyVisual.fromSnapshot(state));
    }
    return commands;
  }

  private void ensureTilesetTexturesLoaded(TiledMap map) {
    if (map == null) return;
    for (Tileset tileset : map.getTilesets()) {
      if (tilesetTex.containsKey(tileset) || tileset.getImage() == null) continue;
      int slot = renderer.loadTexture(tileset.getImage());
      if (slot < 0) {
        System.err.println("[VulkanGameRenderer] Failed to load tileset texture, using fallback.");
        tilesetTex.put(tileset, 0);
      } else {
        tilesetTex.put(tileset, slot);
      }
    }
  }

  private void buildTileCommands(List<VulkanRenderer.RenderCommand> commands, TiledMap map, float camX, float camY) {
    if (map == null) return;

    int tileWidth = map.getTileWidth();
    int tileHeight = map.getTileHeight();
    int mapWidth = map.getWidthTiles();
    int mapHeight = map.getHeightTiles();

    // Viewport culling: only tiles within the visible rectangle
    int minTX = Math.max(0, (int) Math.floor(camX / tileWidth));
    int minTY = Math.max(0, (int) Math.floor(camY / tileHeight));
    int maxTX = Math.min(mapWidth - 1, (int) Math.ceil((camX + screenWidth) / tileWidth));
    int maxTY = Math.min(mapHeight - 1, (int) Math.ceil((camY + screenHeight) / tileHeight));

    for (int li = 0; li < map.getLayerCount(); li++) {
      Layer layer = map.getLayer(li);
      if (!layer.isVisible()) continue;
      int z = li;

      int[] data = layer.getData();
      // Batch tiles by texture slot: accumulate vertices per texture, flush per layer
      Map<Integer, List<float[]>> batch = new HashMap<>();
      for (int tileY = minTY; tileY <= maxTY; tileY++) {
        for (int tileX = minTX; tileX <= maxTX; tileX++) {
          int gid = data[tileY * mapWidth + tileX];
          if (gid == 0) continue;

          Tileset tileset = resolveTileset(map, gid);
          if (tileset == null) continue;

          Integer textureIndex = tilesetTex.get(tileset);
          if (textureIndex == null) continue;

          TileUv tileUv = resolveTileUv(tileset, gid);
          float px = tileX * tileWidth;
          float py = tileY * tileHeight;

          batch.computeIfAbsent(textureIndex, k -> new ArrayList<>())
              .add(new float[]{px, py, tileUv.u0(), tileUv.v0(), tileUv.u1(), tileUv.v1()});
        }
      }

      // Flush batch as merged commands (one per texture per layer)
      for (Map.Entry<Integer, List<float[]>> entry : batch.entrySet()) {
        List<float[]> quads = entry.getValue();
        int ti = entry.getKey();
        float[] verts = new float[quads.size() * 4 * 8];
        int vi = 0;
        for (float[] q : quads) {
          float px = q[0], py = q[1], u0 = q[2], v0 = q[3], u1 = q[4], v1 = q[5];
          verts[vi++] = px;       verts[vi++] = py + tileHeight; verts[vi++] = u0;  verts[vi++] = v1;
          verts[vi++] = WHITE;    verts[vi++] = WHITE;           verts[vi++] = WHITE; verts[vi++] = WHITE;
          verts[vi++] = px + tileWidth; verts[vi++] = py + tileHeight; verts[vi++] = u1;  verts[vi++] = v1;
          verts[vi++] = WHITE;    verts[vi++] = WHITE;           verts[vi++] = WHITE; verts[vi++] = WHITE;
          verts[vi++] = px + tileWidth; verts[vi++] = py;        verts[vi++] = u1;    verts[vi++] = v0;
          verts[vi++] = WHITE;    verts[vi++] = WHITE;           verts[vi++] = WHITE; verts[vi++] = WHITE;
          verts[vi++] = px;       verts[vi++] = py;              verts[vi++] = u0;    verts[vi++] = v0;
          verts[vi++] = WHITE;    verts[vi++] = WHITE;           verts[vi++] = WHITE; verts[vi++] = WHITE;
        }
        commands.add(new VulkanRenderer.RenderCommand(verts, ti, z));
      }
    }
  }

  private Tileset resolveTileset(TiledMap map, int gid) {
    Tileset result = null;
    for (Tileset tileset : map.getTilesets()) {
      if (tileset.getFirstGid() <= gid) {
        result = tileset;
      } else {
        break;
      }
    }
    return result != null && result.contains(gid) ? result : null;
  }

  private TileUv resolveTileUv(Tileset tileset, int gid) {
    int localId = gid - tileset.getFirstGid();
    int columns = tileset.getColumns();
    int margin = tileset.getMargin();
    int spacing = tileset.getSpacing();
    int column = localId % columns;
    int row = localId / columns;

    int imgW = tileset.getImage().getWidth();
    int imgH = tileset.getImage().getHeight();
    int effectiveTileW = tileset.getTileWidth();
    int effectiveTileH = tileset.getTileHeight();

    float u0 = (float) (margin + column * (effectiveTileW + spacing)) / imgW;
    float v0 = (float) (margin + row * (effectiveTileH + spacing)) / imgH;
    float u1 = (float) (margin + column * (effectiveTileW + spacing) + effectiveTileW) / imgW;
    float v1 = (float) (margin + row * (effectiveTileH + spacing) + effectiveTileH) / imgH;
    return new TileUv(u0, v0, u1, v1);
  }

  // -- Player textures --

  private void ensureLivePlayerTexturesLoaded(List<Player> players) {
    for (Player player : players) {
      ensurePlayerTexturesLoaded(player.getAppearanceId());
    }
  }

  private void ensureSnapshotPlayerTexturesLoaded(List<NetPlayerState> players) {
    for (NetPlayerState player : players) {
      ensurePlayerTexturesLoaded(player.appearanceId());
    }
  }

  private void ensurePlayerTexturesLoaded(String appearanceId) {
    if (appearanceId == null || appearanceId.isBlank() || walkTex.containsKey(appearanceId)) return;

    SpriteAssets assets = SpriteAssets.loadPlayer(appearanceId);
    if (!assets.isLoaded()) return;

    int walkSlot = renderer.loadTexture(assets.walkSheet());
    int actionSlot = renderer.loadTexture(assets.actionSheet());
    if (walkSlot < 0 || actionSlot < 0) {
      System.err.println("[VulkanGameRenderer] Failed to load player textures for: " + appearanceId);
      return;
    }
    walkTex.put(appearanceId, walkSlot);
    actionTex.put(appearanceId, actionSlot);
  }

  // -- Enemy textures --

  private void ensureEnemyTextureLoaded(int variant) {
    String key = "enemy-" + variant;
    if (enemyTex.containsKey(key)) return;

    SpriteAssets assets = SpriteAssets.loadPlayer("enemies/" + key);
    if (!assets.isLoaded()) return;

    int walkSlot = renderer.loadTexture(assets.walkSheet());
    int actionSlot = renderer.loadTexture(assets.actionSheet());
    if (walkSlot < 0 || actionSlot < 0) return;
    enemyTex.put(key, walkSlot);
  }

  // -- Player command --

  private void appendPlayerCommand(List<VulkanRenderer.RenderCommand> commands, PlayerVisual player) {
    int z = 1000;

    if (player.appearanceId().isBlank()) {
      commands.add(VulkanRenderer.RenderCommand.rect(
          player.x(), player.y(), player.width(), player.height(),
          FALLBACK_R, FALLBACK_G, FALLBACK_B, WHITE, z));
      return;
    }

    TextureSelection texture = resolvePlayerTexture(player.appearanceId(), player.animation());
    if (texture.textureIndex() == null) {
      commands.add(VulkanRenderer.RenderCommand.rect(
          player.x(), player.y(), player.width(), player.height(),
          FALLBACK_R, FALLBACK_G, FALLBACK_B, WHITE, z));
      return;
    }

    SpriteUv spriteUv = resolveSpriteUv(texture.layout(), player.animation(), player.direction(), player.frame());
    if (player.direction() == Entity.Direction.LEFT) {
      commands.add(VulkanRenderer.RenderCommand.texQuad(
          player.x(), player.y(), player.width(), player.height(),
          spriteUv.u1(), spriteUv.v0(), spriteUv.u0(), spriteUv.v1(),
          texture.textureIndex(), WHITE, WHITE, WHITE, WHITE, z));
      return;
    }

    commands.add(VulkanRenderer.RenderCommand.texQuad(
        player.x(), player.y(), player.width(), player.height(),
        spriteUv.u0(), spriteUv.v0(), spriteUv.u1(), spriteUv.v1(),
        texture.textureIndex(), WHITE, WHITE, WHITE, WHITE, z));
  }

  // -- Enemy command --

  private void appendEnemyCommand(List<VulkanRenderer.RenderCommand> commands, EnemyVisual enemy) {
    int z = 1001;

    String key = "enemy-" + enemy.variant;
    Integer texSlot = enemyTex.get(key);
    if (texSlot != null) {
      // Use loaded enemy sprite sheet (same walk layout as players)
      SpriteUv spriteUv = resolveSpriteUv(WALK_LAYOUT, enemy.animation(), enemy.direction(), enemy.frame());
      if (enemy.direction() == Entity.Direction.LEFT) {
        commands.add(VulkanRenderer.RenderCommand.texQuad(
            enemy.x(), enemy.y(), enemy.width(), enemy.height(),
            spriteUv.u1(), spriteUv.v0(), spriteUv.u0(), spriteUv.v1(),
            texSlot, WHITE, WHITE, WHITE, WHITE, z));
      } else {
        commands.add(VulkanRenderer.RenderCommand.texQuad(
            enemy.x(), enemy.y(), enemy.width(), enemy.height(),
            spriteUv.u0(), spriteUv.v0(), spriteUv.u1(), spriteUv.v1(),
            texSlot, WHITE, WHITE, WHITE, WHITE, z));
      }
      return;
    }

    // Try loading the enemy texture on demand
    ensureEnemyTextureLoaded(enemy.variant);
    texSlot = enemyTex.get(key);
    if (texSlot != null) {
      SpriteUv spriteUv = resolveSpriteUv(WALK_LAYOUT, enemy.animation(), enemy.direction(), enemy.frame());
      if (enemy.direction() == Entity.Direction.LEFT) {
        commands.add(VulkanRenderer.RenderCommand.texQuad(
            enemy.x(), enemy.y(), enemy.width(), enemy.height(),
            spriteUv.u1(), spriteUv.v0(), spriteUv.u0(), spriteUv.v1(),
            texSlot, WHITE, WHITE, WHITE, WHITE, z));
      } else {
        commands.add(VulkanRenderer.RenderCommand.texQuad(
            enemy.x(), enemy.y(), enemy.width(), enemy.height(),
            spriteUv.u0(), spriteUv.v0(), spriteUv.u1(), spriteUv.v1(),
            texSlot, WHITE, WHITE, WHITE, WHITE, z));
      }
      return;
    }

    // Fallback: colored rect
    float r, g, b;
    if (enemy.variant == 0) {
      r = 0.2f; g = 0.8f; b = 0.2f;
    } else {
      r = 0.4f + 0.1f * (enemy.variant % 5);
      g = 0.3f + 0.1f * ((enemy.variant * 3) % 5);
      b = 0.5f + 0.1f * ((enemy.variant * 7) % 5);
    }
    commands.add(VulkanRenderer.RenderCommand.rect(
        enemy.x(), enemy.y(), enemy.width(), enemy.height(), r, g, b, WHITE, z));
  }

  // -- Texture resolution helpers --

  private TextureSelection resolvePlayerTexture(String appearanceId, Entity.AnimationState animation) {
    boolean isAction = animation == Entity.AnimationState.ATTACK || animation == Entity.AnimationState.DIE;
    Integer textureIndex = isAction ? actionTex.get(appearanceId) : walkTex.get(appearanceId);
    if (textureIndex == null && isAction) {
      textureIndex = walkTex.get(appearanceId);
    }
    SpriteSheetLayout layout = isAction ? ACTION_LAYOUT : WALK_LAYOUT;
    return new TextureSelection(textureIndex, layout);
  }

  private SpriteUv resolveSpriteUv(
      SpriteSheetLayout layout, Entity.AnimationState animation, Entity.Direction direction, int frame) {
    int row = layout.resolveRow(animation, direction);
    float u0 = (float) (frame * layout.frameWidth()) / layout.sheetWidth();
    float v0 = (float) (row * layout.frameHeight()) / layout.sheetHeight();
    return new SpriteUv(
        u0, v0,
        u0 + (float) layout.frameWidth() / layout.sheetWidth(),
        v0 + (float) layout.frameHeight() / layout.sheetHeight());
  }

  // -- Parsing helpers --

  private static Entity.Direction parseDirection(String raw) {
    try { return Entity.Direction.valueOf(raw); } catch (Exception ignored) { return Entity.Direction.DOWN; }
  }

  private static Entity.AnimationState parseAnimation(String raw) {
    try { return Entity.AnimationState.valueOf(raw); } catch (Exception ignored) { return Entity.AnimationState.IDLE; }
  }

  private static int parseEnemyVariant(String appearanceId) {
    if (appearanceId == null || !appearanceId.startsWith("enemy-")) return 0;
    try { return Integer.parseInt(appearanceId.substring("enemy-".length())); } catch (NumberFormatException ignored) { return 0; }
  }

  // -- Records --

  private record TileUv(float u0, float v0, float u1, float v1) {}
  private record SpriteUv(float u0, float v0, float u1, float v1) {}
  private record TextureSelection(Integer textureIndex, SpriteSheetLayout layout) {}

  private record PlayerVisual(
      String appearanceId, float x, float y, float width, float height,
      Entity.Direction direction, Entity.AnimationState animation, int frame) {
    static PlayerVisual fromPlayer(Player player) {
      return new PlayerVisual(blankSafe(player.getAppearanceId()),
          (float) player.getX(), (float) player.getY(),
          player.getSpriteWidth(), player.getSpriteHeight(),
          player.getDirection(), player.getCurrentAnimation(), player.getCurrentFrame());
    }
    static PlayerVisual fromSnapshot(NetPlayerState state) {
      return new PlayerVisual(blankSafe(state.appearanceId()),
          (float) state.x(), (float) state.y(), (float) state.width(), (float) state.height(),
          parseDirection(state.direction()), parseAnimation(state.animation()), state.frame());
    }
  }

  private record EnemyVisual(
      int variant, float x, float y, float width, float height,
      Entity.Direction direction, Entity.AnimationState animation, int frame) {
    static EnemyVisual fromEnemy(Enemy enemy) {
      return new EnemyVisual(enemy.getMovementVariant(),
          (float) enemy.getX(), (float) enemy.getY(),
          enemy.getSpriteWidth(), enemy.getSpriteHeight(),
          enemy.getDirection(), enemy.getCurrentAnimation(), enemy.getCurrentFrame());
    }
    static EnemyVisual fromSnapshot(NetEnemyState state) {
      return new EnemyVisual(parseEnemyVariant(state.appearanceId()),
          (float) state.x(), (float) state.y(), (float) state.width(), (float) state.height(),
          parseDirection(state.direction()), parseAnimation(state.animation()), state.frame());
    }
  }

  private record SpriteSheetLayout(int cols, int rows, int frameWidth, int frameHeight) {
    int sheetWidth() { return cols * frameWidth; }
    int sheetHeight() { return rows * frameHeight; }
    int resolveRow(Entity.AnimationState animation, Entity.Direction direction) {
      int baseRow = switch (animation) {
        case IDLE -> 0;
        case WALK -> 3;
        case ATTACK -> 0;
        case DIE -> 3;
      };
      int directionOffset = switch (direction) {
        case DOWN -> 0;
        case UP -> 1;
        case LEFT, RIGHT -> 2;
      };
      return Math.min(baseRow + directionOffset, rows - 1);
    }
  }

  private static String blankSafe(String value) { return value == null ? "" : value; }
}
