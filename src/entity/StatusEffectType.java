package entity;

public enum StatusEffectType {
  BURN,
  FREEZE,
  CONDUCTIVE,
  FRACTURE;

  public StatusEffectType next() {
    StatusEffectType[] values = values();
    return values[(ordinal() + 1) % values.length];
  }
}
