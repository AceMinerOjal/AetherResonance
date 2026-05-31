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

/**
 * Bridges game state to Vulkan render commands.
 */
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
  private final Map<Tileset, Integer> tilesetTex = new IdentityHashMap<>();
  private final Map<String, Integer> walkTex = new HashMap<>();
  private final Map<String, Integer> actionTex = new HashMap<>();

  public VulkanGameRenderer(VulkanRenderer renderer, int screenWidth, int screenHeight) {
    this.renderer = renderer;
  }

  public List<VulkanRenderer.RenderCommand> buildLocalCommands(
      TiledMap map, List<Player> players, List<Enemy> enemies) {
    ensureTilesetTexturesLoaded(map);
    ensureLivePlayerTexturesLoaded(players);

    List<VulkanRenderer.RenderCommand> commands = new ArrayList<>();
    buildTileCommands(commands, map);
    for (Player player : players) {
      appendPlayerCommand(commands, PlayerVisual.fromPlayer(player));
    }
    for (Enemy enemy : enemies) {
      if (!enemy.isAlive()) continue;
      appendEnemyCommand(commands, EnemyVisual.fromEnemy(enemy));
    }
    return commands;
  }

  public List<VulkanRenderer.RenderCommand> buildRemoteCommands(TiledMap map, NetSnapshot snapshot) {
    ensureTilesetTexturesLoaded(map);
    ensureSnapshotPlayerTexturesLoaded(snapshot.players());

    List<VulkanRenderer.RenderCommand> commands = new ArrayList<>();
    buildTileCommands(commands, map);
    for (NetPlayerState state : snapshot.players()) {
      appendPlayerCommand(commands, PlayerVisual.fromSnapshot(state));
    }
    for (NetEnemyState state : snapshot.enemies()) {
      appendEnemyCommand(commands, EnemyVisual.fromSnapshot(state));
    }
    return commands;
  }

  private void ensureTilesetTexturesLoaded(TiledMap map) {
    if (map == null) {
      return;
    }
    for (Tileset tileset : map.getTilesets()) {
      if (tilesetTex.containsKey(tileset) || tileset.getImage() == null) {
        continue;
      }
      int slot = renderer.loadTexture(tileset.getImage());
      if (slot < 0) {
        System.err.println("[VulkanGameRenderer] Failed to load tileset texture, using fallback.");
        tilesetTex.put(tileset, 0); // fallback to default white texture
      } else {
        tilesetTex.put(tileset, slot);
      }
    }
  }

  private void buildTileCommands(List<VulkanRenderer.RenderCommand> commands, TiledMap map) {
    if (map == null) {
      return;
    }

    int tileWidth = map.getTileWidth();
    int tileHeight = map.getTileHeight();
    int mapWidth = map.getWidthTiles();
    int mapHeight = map.getHeightTiles();

    for (Layer layer : map.getLayers()) {
      if (!layer.isVisible()) {
        continue;
      }

      int[] data = layer.getData();
      for (int tileY = 0; tileY < mapHeight; tileY++) {
        for (int tileX = 0; tileX < mapWidth; tileX++) {
          int gid = data[tileY * mapWidth + tileX];
          if (gid == 0) {
            continue;
          }

          Tileset tileset = resolveTileset(map, gid);
          if (tileset == null) {
            continue;
          }

          Integer textureIndex = tilesetTex.get(tileset);
          if (textureIndex == null) {
            continue;
          }

          TileUv tileUv = resolveTileUv(tileset, gid);
          commands.add(VulkanRenderer.RenderCommand.texQuad(
              tileX * tileWidth,
              tileY * tileHeight,
              tileWidth,
              tileHeight,
              tileUv.u0(),
              tileUv.v0(),
              tileUv.u1(),
              tileUv.v1(),
              textureIndex,
              WHITE,
              WHITE,
              WHITE,
              WHITE));
        }
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
    int rows = Math.max(1, (tileset.getTileCount() + columns - 1) / columns); // ceiling division
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
    if (appearanceId == null || appearanceId.isBlank() || walkTex.containsKey(appearanceId)) {
      return;
    }

    SpriteAssets assets = SpriteAssets.loadPlayer(appearanceId);
    if (!assets.isLoaded()) {
      return;
    }

    int walkSlot = renderer.loadTexture(assets.walkSheet());
    int actionSlot = renderer.loadTexture(assets.actionSheet());
    if (walkSlot < 0 || actionSlot < 0) {
      System.err.println("[VulkanGameRenderer] Failed to load player textures for: " + appearanceId);
      return; // don't cache — retry on next call
    }
    walkTex.put(appearanceId, walkSlot);
    actionTex.put(appearanceId, actionSlot);
  }

  private void appendPlayerCommand(List<VulkanRenderer.RenderCommand> commands, PlayerVisual player) {
    if (player.appearanceId().isBlank()) {
      commands.add(VulkanRenderer.RenderCommand.rect(
          player.x(),
          player.y(),
          player.width(),
          player.height(),
          FALLBACK_R,
          FALLBACK_G,
          FALLBACK_B,
          WHITE));
      return;
    }

    TextureSelection texture = resolvePlayerTexture(player.appearanceId(), player.animation());
    if (texture.textureIndex() == null) {
      // Fallback: render as solid-color rect when texture not yet available
      commands.add(VulkanRenderer.RenderCommand.rect(
          player.x(),
          player.y(),
          player.width(),
          player.height(),
          FALLBACK_R,
          FALLBACK_G,
          FALLBACK_B,
          WHITE));
      return;
    }

    SpriteUv spriteUv = resolveSpriteUv(texture.layout(), player.animation(), player.direction(), player.frame());
    if (player.direction() == Entity.Direction.LEFT) {
      commands.add(VulkanRenderer.RenderCommand.texQuad(
          player.x(),
          player.y(),
          player.width(),
          player.height(),
          spriteUv.u1(),
          spriteUv.v0(),
          spriteUv.u0(),
          spriteUv.v1(),
          texture.textureIndex(),
          WHITE,
          WHITE,
          WHITE,
          WHITE));
      return;
    }

    commands.add(VulkanRenderer.RenderCommand.texQuad(
        player.x(),
        player.y(),
        player.width(),
        player.height(),
        spriteUv.u0(),
        spriteUv.v0(),
        spriteUv.u1(),
        spriteUv.v1(),
        texture.textureIndex(),
        WHITE,
        WHITE,
        WHITE,
        WHITE));
  }

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
        u0,
        v0,
        u0 + (float) layout.frameWidth() / layout.sheetWidth(),
        v0 + (float) layout.frameHeight() / layout.sheetHeight());
  }

  private void appendEnemyCommand(List<VulkanRenderer.RenderCommand> commands, EnemyVisual enemy) {
    // Render enemies as colored rects until sprite assets are available
    float r, g, b;
    if (enemy.variant == 0) {
      // Slime: green
      r = 0.2f; g = 0.8f; b = 0.2f;
    } else {
      // Other variants: variant-tinted
      r = 0.4f + 0.1f * (enemy.variant % 5);
      g = 0.3f + 0.1f * ((enemy.variant * 3) % 5);
      b = 0.5f + 0.1f * ((enemy.variant * 7) % 5);
    }
    commands.add(VulkanRenderer.RenderCommand.rect(
        enemy.x(), enemy.y(), enemy.width(), enemy.height(), r, g, b, WHITE));
  }

  private static Entity.Direction parseDirection(String raw) {
    try {
      return Entity.Direction.valueOf(raw);
    } catch (Exception ignored) {
      return Entity.Direction.DOWN;
    }
  }

  private static Entity.AnimationState parseAnimation(String raw) {
    try {
      return Entity.AnimationState.valueOf(raw);
    } catch (Exception ignored) {
      return Entity.AnimationState.IDLE;
    }
  }

  private static int parseEnemyVariant(String appearanceId) {
    if (appearanceId == null || !appearanceId.startsWith("enemy-")) {
      return 0;
    }
    try {
      return Integer.parseInt(appearanceId.substring("enemy-".length()));
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }

  private record TileUv(float u0, float v0, float u1, float v1) {
  }

  private record SpriteUv(float u0, float v0, float u1, float v1) {
  }

  private record TextureSelection(Integer textureIndex, SpriteSheetLayout layout) {
  }

  private record PlayerVisual(
      String appearanceId,
      float x,
      float y,
      float width,
      float height,
      Entity.Direction direction,
      Entity.AnimationState animation,
      int frame) {
    static PlayerVisual fromPlayer(Player player) {
      return new PlayerVisual(
          blankSafe(player.getAppearanceId()),
          (float) player.getX(),
          (float) player.getY(),
          player.getSpriteWidth(),
          player.getSpriteHeight(),
          player.getDirection(),
          player.getCurrentAnimation(),
          player.getCurrentFrame());
    }

    static PlayerVisual fromSnapshot(NetPlayerState state) {
      return new PlayerVisual(
          blankSafe(state.appearanceId()),
          (float) state.x(),
          (float) state.y(),
          (float) state.width(),
          (float) state.height(),
          parseDirection(state.direction()),
          parseAnimation(state.animation()),
          state.frame());
    }
  }

  private record EnemyVisual(
      int variant,
      float x,
      float y,
      float width,
      float height,
      Entity.Direction direction,
      Entity.AnimationState animation,
      int frame) {
    static EnemyVisual fromEnemy(Enemy enemy) {
      return new EnemyVisual(
          enemy.getMovementVariant(),
          (float) enemy.getX(),
          (float) enemy.getY(),
          enemy.getSpriteWidth(),
          enemy.getSpriteHeight(),
          enemy.getDirection(),
          enemy.getCurrentAnimation(),
          enemy.getCurrentFrame());
    }

    static EnemyVisual fromSnapshot(NetEnemyState state) {
      return new EnemyVisual(
          parseEnemyVariant(state.appearanceId()),
          (float) state.x(),
          (float) state.y(),
          (float) state.width(),
          (float) state.height(),
          parseDirection(state.direction()),
          parseAnimation(state.animation()),
          state.frame());
    }
  }

  private record SpriteSheetLayout(int cols, int rows, int frameWidth, int frameHeight) {
    int sheetWidth() {
      return cols * frameWidth;
    }

    int sheetHeight() {
      return rows * frameHeight;
    }

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

  private static String blankSafe(String value) {
    return value == null ? "" : value;
  }
}
