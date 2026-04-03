package entity.player;

public enum StatusEffectType {
  BURN,
  FREEZE,
  CONDUCTIVE,
  FRACTURE,
  HASTE_SLOW,
  OBSCURE;

  public StatusEffectType next() {
    StatusEffectType[] values = values();
    return values[(ordinal() + 1) % values.length];
  }
}
