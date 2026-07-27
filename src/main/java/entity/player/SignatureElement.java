package entity.player;

public enum SignatureElement {
  FIRE,
  ICE,
  LIGHTNING,
  EARTH,
  WIND,
  SHADOW;

  public SignatureElement next() {
    SignatureElement[] values = values();
    return values[(ordinal() + 1) % values.length];
  }
}
