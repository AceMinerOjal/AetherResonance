package lib;

import java.util.HashMap;
import java.util.Map;

public abstract class Entity {
  protected static final int SPRITE_WIDTH = 32;
  protected static final int SPRITE_HEIGHT = 32;

  protected double x, y;
  protected final Hitbox hitbox;
  protected String appearanceId = "";

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

  public Direction getDirection() {
    return direction;
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

  public String getAppearanceId() {
    return appearanceId;
  }

  public int getSpriteWidth() {
    return SPRITE_WIDTH;
  }

  public int getSpriteHeight() {
    return SPRITE_HEIGHT;
  }

  protected void loadPlayerSprites(String appearanceId) {
    this.appearanceId = appearanceId;
  }
}
