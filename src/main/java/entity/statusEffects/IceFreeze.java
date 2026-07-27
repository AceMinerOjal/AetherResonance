package entity.statusEffects;

public class IceFreeze extends StatusEffect {

  public IceFreeze(double durationSeconds) {
    super("Freeze", durationSeconds, 0.0);
  }

  @Override
  public void apply(EffectTarget t) {
    super.apply(t);
    if (t != null) {
      t.setFrozen(true);
    }
  }

  @Override
  public void onFinish() {
    if (target != null) {
      target.setFrozen(false);
    }
  }
}
