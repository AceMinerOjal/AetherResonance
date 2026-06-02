package entity.player;

import java.util.EnumMap;
import java.util.Map;

public final class ElementMatrix {
  private static final Map<SignatureElement, Map<SignatureElement, Double>> MATRIX = new EnumMap<>(SignatureElement.class);

  static {
    for (SignatureElement attacker : SignatureElement.values()) {
      Map<SignatureElement, Double> row = new EnumMap<>(SignatureElement.class);
      for (SignatureElement target : SignatureElement.values()) {
        row.put(target, 1.0); // Neutral by default
      }
      MATRIX.put(attacker, row);
    }

    // FIRE: Strong vs ICE, Weak vs LIGHTNING
    setMultiplier(SignatureElement.FIRE, SignatureElement.ICE, 2.0);
    setMultiplier(SignatureElement.FIRE, SignatureElement.LIGHTNING, 0.5);

    // ICE: Strong vs WIND, Weak vs FIRE
    setMultiplier(SignatureElement.ICE, SignatureElement.WIND, 2.0);
    setMultiplier(SignatureElement.ICE, SignatureElement.FIRE, 0.5);

    // LIGHTNING: Strong vs FIRE, Weak vs EARTH
    setMultiplier(SignatureElement.LIGHTNING, SignatureElement.FIRE, 2.0);
    setMultiplier(SignatureElement.LIGHTNING, SignatureElement.EARTH, 0.5);

    // EARTH: Strong vs LIGHTNING, Weak vs WIND
    setMultiplier(SignatureElement.EARTH, SignatureElement.LIGHTNING, 2.0);
    setMultiplier(SignatureElement.EARTH, SignatureElement.WIND, 0.5);

    // WIND: Strong vs EARTH, Weak vs ICE
    setMultiplier(SignatureElement.WIND, SignatureElement.EARTH, 2.0);
    setMultiplier(SignatureElement.WIND, SignatureElement.ICE, 0.5);

    // SHADOW: Strong vs SHADOW (Double-edged), Neutral to others
    setMultiplier(SignatureElement.SHADOW, SignatureElement.SHADOW, 1.5);
  }

  private static void setMultiplier(SignatureElement attacker, SignatureElement target, double value) {
    MATRIX.get(attacker).put(target, value);
  }

  public static double getMultiplier(SignatureElement attacker, SignatureElement target) {
    if (attacker == null || target == null) return 1.0;
    return MATRIX.get(attacker).get(target);
  }

  private ElementMatrix() {}
}
