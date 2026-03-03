package lib;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.awt.Graphics2D;

public abstract class Entity {
  protected static final int SPRITE_WIDTH = 32;
  protected static final int SPRITE_HEIGHT = 32;

  protected double x, y;
  protected final double SPEED = 30;
  protected final Hitbox hitbox;

  protected BufferedImage walkSheet;
  protected BufferedImage actionSheet;

  public enum Direction {
    UP, DOWN, LEFT, RIGHT
  }

  protected Direction direction = Direction.DOWN;

  public enum AnimationState {
    IDLE, WALK, ATTACK, DIE
  }

  private AnimationState currentAnimation = AnimationState.IDLE;
  private int currentFrame = 0;
  private float frameTimer = 0f;
  private float frameDuration = 0.2f;

  private final Map<AnimationState, Integer> animationFrames = new HashMap<>();

  public Entity() {
    hitbox = new Hitbox(SPRITE_WIDTH, SPRITE_HEIGHT);
    animationFrames.put(AnimationState.IDLE, 1);
    animationFrames.put(AnimationState.WALK, 4);
    animationFrames.put(AnimationState.ATTACK, 3);
    animationFrames.put(AnimationState.DIE, 5);
  }

  public void updateAnimation(float dt) {
    frameTimer += dt;

    if (frameTimer >= frameDuration) {
      frameTimer = 0f;
      currentFrame++;
      int maxFrames = animationFrames.getOrDefault(currentAnimation, 1);
      if (currentFrame >= maxFrames) {
        currentFrame = 0;
      }
    }
  }

  public int getSpriteRow() {
    int baseRow = switch (getCurrentAnimation()) {
      case IDLE -> 0;
      case WALK -> 3;
      case ATTACK -> 6;
      case DIE -> 9;
    };

    int directionOffset = switch (direction) {
      case DOWN -> 0;
      case UP -> 1;
      case LEFT, RIGHT -> 2;
    };

    return baseRow + directionOffset;
  }

  public void setAnimation(AnimationState state) {
    if (state != currentAnimation) {
      currentAnimation = state;
      currentFrame = 0;
      frameTimer = 0f;
    }
  }

  public AnimationState getCurrentAnimation() {
    return currentAnimation;
  }

  public int getCurrentFrame() {
    return currentFrame;
  }

  public void move(Direction dir, double dt) {
    direction = dir;

    switch (dir) {
      case UP -> setPosition(x, y - SPEED * dt);
      case DOWN -> setPosition(x, y + SPEED * dt);
      case LEFT -> setPosition(x - SPEED * dt, y);
      case RIGHT -> setPosition(x + SPEED * dt, y);
    }

    setAnimation(AnimationState.WALK);
  }

  public void stop() {
    setAnimation(AnimationState.IDLE);
  }

  public void draw(Graphics2D g) {
    BufferedImage currentSheet;
    int rowOffset;

    switch (getCurrentAnimation()) {
      case IDLE -> {
        currentSheet = walkSheet;
        rowOffset = 0; // Assuming Idle is the first set of rows in walkSheet
      }
      case WALK -> {
        currentSheet = walkSheet;
        rowOffset = 3; // Offset if Walk rows are below Idle
      }
      case ATTACK -> {
        currentSheet = actionSheet;
        rowOffset = 0;
      }
      case DIE -> {
        currentSheet = actionSheet;
        rowOffset = 3;
      }
      default -> {
        currentSheet = walkSheet;
        rowOffset = 0;
      }
    }

    int directionOffset = switch (direction) {
      case DOWN -> 0;
      case UP -> 1;
      case LEFT, RIGHT -> 2;
    };

    int row = rowOffset + directionOffset;

    renderFrame(g, currentSheet, row);
  }

  private void renderFrame(Graphics2D g, BufferedImage sheet, int row) {
    int tw = SPRITE_WIDTH;
    int th = SPRITE_HEIGHT;

    int sx = getCurrentFrame() * tw;
    int sy = row * th;

    if (direction == Direction.RIGHT) {
      g.drawImage(sheet, (int) x, (int) y, (int) x + tw, (int) y + th,
          sx + tw, sy, sx, sy + th, null);
    } else {
      g.drawImage(sheet, (int) x, (int) y, (int) x + tw, (int) y + th,
          sx, sy, sx + tw, sy + th, null);
    }
  }

  protected void setPosition(double x, double y) {
    this.x = x;
    this.y = y;
    hitbox.sync(x, y);
  }

  protected void setHitbox(double width, double height, double offsetX, double offsetY) {
    hitbox.setSize(width, height);
    hitbox.setOffset(offsetX, offsetY);
    hitbox.sync(x, y);
  }

  public Hitbox getHitbox() {
    return hitbox;
  }

  public double getX() {
    return x;
  }

  public double getY() {
    return y;
  }
}
