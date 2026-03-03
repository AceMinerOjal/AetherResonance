package entity;

public enum SignatureElement {
  FIRE,
  ICE,
  LIGHTNING,
  EARTH;

  public SignatureElement next() {
    SignatureElement[] values = values();
    return values[(ordinal() + 1) % values.length];
  }
}
