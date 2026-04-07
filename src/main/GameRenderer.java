package main;

import java.awt.Color;
import java.awt.Graphics2D;
import java.util.List;

import entity.enemy.Enemy;
import entity.player.Player;
import lib.Entity;
import lib.Hitbox;
import lib.SpriteAssets;
import lib.SpriteFrameRenderer;
import net.NetEnemyState;
import net.NetPlayerState;
import net.NetSnapshot;
import tile.TiledMap;

public class GameRenderer {
  private static final Color[] ENEMY_PALETTE = {
      new Color(198, 74, 74),
      new Color(206, 117, 74),
      new Color(162, 82, 140),
      new Color(112, 172, 102),
      new Color(72, 148, 178)
  };
  private static final Color ENEMY_OUTLINE = new Color(34, 20, 28);
  private static final Color ENEMY_SHADOW = new Color(0, 0, 0, 58);

  public void renderLocal(Graphics2D g2, TiledMap map, List<Player> players, List<Enemy> enemies) {
    if (map != null) {
      map.draw(g2);
    }

    for (Player player : players) {
      if (player.hasSpriteSheets()) {
        player.draw(g2);
      } else {
        drawHitbox(g2, player.getHitbox(), new Color(75, 200, 255));
      }
    }

    for (Enemy enemy : enemies) {
      drawEnemy(g2, enemy.getX(), enemy.getY(), enemy.getDirection(), enemy.getCurrentAnimation(),
          enemy.getCurrentFrame(), enemy.getMovementVariant());
    }
  }

  public void renderRemote(Graphics2D g2, TiledMap map, NetSnapshot snapshot) {
    if (map != null) {
      map.draw(g2);
    }

    for (NetPlayerState state : snapshot.players()) {
      SpriteAssets assets = SpriteAssets.loadPlayer(state.appearanceId());
      if (assets.isLoaded()) {
        SpriteFrameRenderer.draw(g2, assets, parseAnimation(state.animation()), parseDirection(state.direction()),
            state.frame(), state.x(), state.y(), (int) state.width(), (int) state.height());
      } else {
        drawFallbackPlayer(g2, state);
      }
    }

    for (NetEnemyState state : snapshot.enemies()) {
      drawEnemy(g2, state.x(), state.y(), parseDirection(state.direction()), parseAnimation(state.animation()),
          state.frame(), parseEnemyVariant(state.appearanceId()));
    }
  }

  private void drawFallbackPlayer(Graphics2D g2, NetPlayerState state) {
    g2.setColor(new Color(80, 170, 255));
    g2.fillRect((int) state.x(), (int) state.y(), (int) state.width(), (int) state.height());
    g2.setColor(new Color(20, 20, 24));
    g2.drawRect((int) state.x(), (int) state.y(), (int) state.width() - 1, (int) state.height() - 1);
  }

  private void drawHitbox(Graphics2D g2, Hitbox hitbox, Color color) {
    g2.setColor(color);
    g2.fillRect((int) hitbox.getLeft(), (int) hitbox.getTop(), (int) hitbox.getWidth(), (int) hitbox.getHeight());
  }

  private void drawEnemy(Graphics2D g2, double x, double y, Entity.Direction direction,
      Entity.AnimationState animation, int frame, int variant) {
    Color body = ENEMY_PALETTE[Math.floorMod(variant, ENEMY_PALETTE.length)];
    Color accent = body.brighter();
    int bobY = animation == Entity.AnimationState.WALK && (frame % 2 == 1) ? -1 : 0;
    int eyeX = switch (direction) {
      case LEFT -> 10;
      case RIGHT -> 18;
      case UP, DOWN -> 14;
    };

    g2.setColor(ENEMY_SHADOW);
    g2.fillOval((int) x + 7, (int) y + 24, 18, 5);
    g2.setColor(body.darker());
    g2.fillOval((int) x + 8, (int) y + 9 + bobY, 16, 14);
    g2.setColor(body);
    g2.fillOval((int) x + 6, (int) y + 7 + bobY, 20, 16);
    g2.setColor(accent);
    g2.fillOval((int) x + 10, (int) y + 11 + bobY, 12, 6);
    g2.setColor(Color.WHITE);
    g2.fillRect((int) x + eyeX, (int) y + 12 + bobY, 2, 2);
    g2.setColor(ENEMY_OUTLINE);
    g2.drawOval((int) x + 6, (int) y + 7 + bobY, 19, 15);
    g2.drawLine((int) x + 12, (int) y + 22, (int) x + 10 + (frame % 2 == 0 ? -1 : 1), (int) y + 28);
    g2.drawLine((int) x + 20, (int) y + 22, (int) x + 22 + (frame % 2 == 0 ? 1 : -1), (int) y + 28);
  }

  private Entity.Direction parseDirection(String raw) {
    try {
      return Entity.Direction.valueOf(raw);
    } catch (Exception ex) {
      return Entity.Direction.DOWN;
    }
  }

  private Entity.AnimationState parseAnimation(String raw) {
    try {
      return Entity.AnimationState.valueOf(raw);
    } catch (Exception ex) {
      return Entity.AnimationState.IDLE;
    }
  }

  private int parseEnemyVariant(String appearanceId) {
    if (appearanceId == null || !appearanceId.startsWith("enemy-")) {
      return 0;
    }
    try {
      return Integer.parseInt(appearanceId.substring("enemy-".length()));
    } catch (NumberFormatException ex) {
      return 0;
    }
  }
}
