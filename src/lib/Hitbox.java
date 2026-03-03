package lib;

public class Hitbox {
  private double x;
  private double y;
  private double width;
  private double height;
  private double offsetX;
  private double offsetY;

  public Hitbox(double width, double height) {
    this(width, height, 0, 0);
  }

  public Hitbox(double width, double height, double offsetX, double offsetY) {
    this.width = width;
    this.height = height;
    this.offsetX = offsetX;
    this.offsetY = offsetY;
  }

  public void sync(double ownerX, double ownerY) {
    x = ownerX + offsetX;
    y = ownerY + offsetY;
  }

  public boolean intersects(Hitbox other) {
    return x < other.x + other.width
        && x + width > other.x
        && y < other.y + other.height
        && y + height > other.y;
  }

  public void setSize(double width, double height) {
    this.width = width;
    this.height = height;
  }

  public void setOffset(double offsetX, double offsetY) {
    this.offsetX = offsetX;
    this.offsetY = offsetY;
  }

  public double getLeft() {
    return x;
  }

  public double getTop() {
    return y;
  }

  public double getRight() {
    return x + width;
  }

  public double getBottom() {
    return y + height;
  }

  public double getWidth() {
    return width;
  }

  public double getHeight() {
    return height;
  }
}
