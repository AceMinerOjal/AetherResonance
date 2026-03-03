package entity.statusEffects;

import lib.Timer;

public abstract class StatusEffect extends Timer {
  protected final String name;
  protected final double tickInterval;
  private double tickAccumulator = 0.0;
  protected EffectTarget target;

  public StatusEffect(String name, double duration, double tickInterval) {
    super(duration);
    this.name = name;
    this.tickInterval = Math.max(0.0, tickInterval);
  }

  public void apply(EffectTarget t) {
    this.target = t;
    start();
  }

  public String getName() {
    return name;
  }

  protected void onTick() {
  }

  @Override
  public abstract void onFinish();

  @Override
  public void onActive() {
  }

  public void update(double dt) {
    if (!isActive()) {
      super.update(dt);
      return;
    }

    if (tickInterval > 0) {
      tickAccumulator += dt;
      while (tickAccumulator >= tickInterval) {
        tickAccumulator -= tickInterval;
        try {
          onTick();
        } catch (Exception ex) {
          System.err.println("Status effect tick failed for " + name + ": " + ex.getMessage());
        }
      }
    }

    super.update(dt);
  }
}
