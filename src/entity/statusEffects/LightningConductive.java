package entity.statusEffects;

import java.util.List;
import lib.Entity;

public class LightningConductive extends StatusEffect {

  private final double damagePerTick;
  private final double chainDamageMultiplier;
  private final double chainRadius;

  public LightningConductive(double durationSeconds, double damagePerTick, double tickInterval,
      double chainDamageMultiplier, double chainRadius) {
    super("Conductive", durationSeconds, tickInterval);
    this.damagePerTick = damagePerTick;
    this.chainDamageMultiplier = chainDamageMultiplier;
    this.chainRadius = chainRadius;
  }

  @Override
  protected void onTick() {
    if (target == null) {
      return;
    }
    target.applyDamage(damagePerTick);

    List<Entity> nearby = target.getNearbyEntities(chainRadius);
    if (nearby == null || nearby.isEmpty()) {
      return;
    }

    for (Entity e : nearby) {
      if (e == null || e == target) {
        continue;
      }
      if (e instanceof EffectTarget et) {
        double chained = damagePerTick * chainDamageMultiplier;
        et.applyDamage(chained);
      }
    }
  }

  @Override
  public void onFinish() {
  }
}
