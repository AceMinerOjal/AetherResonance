package entity;

public enum InventoryItem {
  STATUS_TUNER,
  ELEMENT_TUNER,
  ATTRIBUTE_TUNER;

  public InventoryItem next() {
    InventoryItem[] values = values();
    return values[(ordinal() + 1) % values.length];
  }
}
