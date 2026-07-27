package entity.statusEffects;

public class ShadowObscure extends StatusEffect {

  private final double accuracyDelta;
  private final double detectionRangeDelta;

  public ShadowObscure(double durationSeconds, double accuracyDelta, double detectionRangeDelta) {
    super("Obscure", durationSeconds, 0.0);
    this.accuracyDelta = accuracyDelta;
    this.detectionRangeDelta = detectionRangeDelta;
  }

  @Override
  public void apply(EffectTarget t) {
    super.apply(t);
    if (t != null) {
      t.modifyAccuracyMultiplier(accuracyDelta);
      t.modifyDetectionRangeMultiplier(detectionRangeDelta);
    }
  }

  @Override
  public void onFinish() {
    if (target != null) {
      target.modifyAccuracyMultiplier(-accuracyDelta);
      target.modifyDetectionRangeMultiplier(-detectionRangeDelta);
    }
  }
}
