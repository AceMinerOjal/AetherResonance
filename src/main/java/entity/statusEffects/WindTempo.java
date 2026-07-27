package entity.statusEffects;

public class WindTempo extends StatusEffect {

  private final double attackSpeedDelta;

  public WindTempo(double durationSeconds, double attackSpeedDelta) {
    super((attackSpeedDelta >= 0.0) ? "Haste" : "Slow", durationSeconds, 0.0);
    this.attackSpeedDelta = attackSpeedDelta;
  }

  @Override
  public void apply(EffectTarget t) {
    super.apply(t);
    if (t != null) {
      t.modifyAttackSpeedMultiplier(attackSpeedDelta);
    }
  }

  @Override
  public void onFinish() {
    if (target != null) {
      target.modifyAttackSpeedMultiplier(-attackSpeedDelta);
    }
  }
}
