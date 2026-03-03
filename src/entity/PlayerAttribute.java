package entity;

public enum PlayerAttribute {
  MAX_HP,
  HP_REGEN,
  MAX_MANA,
  MANA_REGEN,
  AP,
  DEFENCE;

  public PlayerAttribute next() {
    PlayerAttribute[] values = values();
    return values[(ordinal() + 1) % values.length];
  }
}
