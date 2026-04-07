package lib;

import java.awt.Graphics2D;

public final class SpriteFrameRenderer {
  private SpriteFrameRenderer() {
  }

  public static void draw(Graphics2D g, SpriteAssets assets, Entity.AnimationState animation,
      Entity.Direction direction, int frame, double x, double y, int width, int height) {
    if (assets == null || !assets.isLoaded()) {
      return;
    }

    int row = resolveRow(animation, direction);
    int sx = frame * width;
    int sy = row * height;

    if (direction == Entity.Direction.LEFT) {
      g.drawImage(assetsFor(animation, assets), (int) x, (int) y, (int) x + width, (int) y + height,
          sx + width, sy, sx, sy + height, null);
      return;
    }

    g.drawImage(assetsFor(animation, assets), (int) x, (int) y, (int) x + width, (int) y + height,
        sx, sy, sx + width, sy + height, null);
  }

  private static java.awt.image.BufferedImage assetsFor(Entity.AnimationState animation, SpriteAssets assets) {
    return animation == Entity.AnimationState.ATTACK || animation == Entity.AnimationState.DIE
        ? assets.actionSheet()
        : assets.walkSheet();
  }

  private static int resolveRow(Entity.AnimationState animation, Entity.Direction direction) {
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
    return baseRow + directionOffset;
  }
}
