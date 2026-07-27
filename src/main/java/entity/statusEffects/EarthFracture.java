package entity.statusEffects;

public class EarthFracture extends StatusEffect {

  private final double extraDamageTaken;

  public EarthFracture(double durationSeconds, double extraDamageTaken) {
    super("Fracture", durationSeconds, 0.0);
    this.extraDamageTaken = extraDamageTaken;
  }

  @Override
  public void apply(EffectTarget t) {
    super.apply(t);
    if (t != null) {
      t.modifyDamageTakenMultiplier(extraDamageTaken);
    }
  }

  @Override
  public void onFinish() {
    if (target != null) {
      target.modifyDamageTakenMultiplier(-extraDamageTaken);
    }
  }
}
