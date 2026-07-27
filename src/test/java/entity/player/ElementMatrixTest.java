package entity.player;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ElementMatrixTest {

  @Test
  void fireIsStrongVsIce() {
    assertEquals(2.0, ElementMatrix.getMultiplier(SignatureElement.FIRE, SignatureElement.ICE));
  }

  @Test
  void fireIsWeakVsLightning() {
    assertEquals(0.5, ElementMatrix.getMultiplier(SignatureElement.FIRE, SignatureElement.LIGHTNING));
  }

  @Test
  void iceIsStrongVsWind() {
    assertEquals(2.0, ElementMatrix.getMultiplier(SignatureElement.ICE, SignatureElement.WIND));
  }

  @Test
  void lightningIsStrongVsFire() {
    assertEquals(2.0, ElementMatrix.getMultiplier(SignatureElement.LIGHTNING, SignatureElement.FIRE));
  }

  @Test
  void earthIsStrongVsLightning() {
    assertEquals(2.0, ElementMatrix.getMultiplier(SignatureElement.EARTH, SignatureElement.LIGHTNING));
  }

  @Test
  void windIsStrongVsEarth() {
    assertEquals(2.0, ElementMatrix.getMultiplier(SignatureElement.WIND, SignatureElement.EARTH));
  }

  @Test
  void shadowIsStrongVsShadow() {
    assertEquals(1.5, ElementMatrix.getMultiplier(SignatureElement.SHADOW, SignatureElement.SHADOW));
  }

  @Test
  void neutralMatchupReturnsOne() {
    assertEquals(1.0, ElementMatrix.getMultiplier(SignatureElement.FIRE, SignatureElement.EARTH));
    assertEquals(1.0, ElementMatrix.getMultiplier(SignatureElement.ICE, SignatureElement.LIGHTNING));
  }

  @Test
  void nullElementsReturnNeutral() {
    assertEquals(1.0, ElementMatrix.getMultiplier(null, SignatureElement.FIRE));
    assertEquals(1.0, ElementMatrix.getMultiplier(SignatureElement.FIRE, null));
  }
}
