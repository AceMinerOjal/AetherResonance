package entity.statusEffects;

public class FireBurn extends StatusEffect {

  private final double damagePerTick;

  public FireBurn(double durationSeconds, double damagePerTick, double tickInterval) {
    super("Burn", durationSeconds, tickInterval);
    this.damagePerTick = damagePerTick;
  }

  @Override
  protected void onTick() {
    if (target == null)
      return;
    target.applyDamage(damagePerTick);
  }

  @Override
  public void onFinish() {
  }
}
