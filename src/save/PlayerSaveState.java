package save;

public record PlayerSaveState(
    String playerClassName,
    String signatureElement,
    String statusEffectType,
    double x,
    double y,
    double hp,
    double mana,
    double ap,
    double defence,
    int level,
    int exp) {
}
