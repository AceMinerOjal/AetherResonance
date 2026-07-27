package entity.statusEffects;

import java.util.List;
import lib.Entity;
import entity.DamageCalculator;

public class LightningConductive extends StatusEffect {

  private final double damagePerTick;
  private final double chainDamageMultiplier;
  private final double chainRadius;
  private final double durationSeconds;

  public LightningConductive(double durationSeconds, double damagePerTick, double tickInterval,
      double chainDamageMultiplier, double chainRadius) {
    super("Conductive", durationSeconds, tickInterval);
    this.damagePerTick = damagePerTick;
    this.chainDamageMultiplier = chainDamageMultiplier;
    this.chainRadius = chainRadius;
    this.durationSeconds = durationSeconds;
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
        et.addStatusEffect(new LightningConductive(
            durationSeconds, chained, tickInterval, chainDamageMultiplier, chainRadius));
      }
    }
  }

  @Override
  public void onFinish() {
  }
}
