package render.vulkan;

public final class RenderCommand implements Comparable<RenderCommand> {
  public final float[] vertices;
  public final int vertexCount;
  public final int indexCount;
  public final int textureIndex;
  public final int z;

  public RenderCommand(float[] v, int ti, int z) {
    this.vertices = v;
    this.vertexCount = v.length / 8;
    this.indexCount = (vertexCount / 4) * 6;
    this.textureIndex = ti;
    this.z = z;
    if (vertexCount < 1 || vertexCount % 4 != 0)
      throw new IllegalArgumentException("vertexCount must be a multiple of 4, got " + vertexCount);
  }

  public static RenderCommand texQuad(float x, float y, float w, float h,
      float u0, float v0, float u1, float v1, int ti,
      float r, float g, float b, float a, int z) {
    return new RenderCommand(new float[]{
        x, y + h, u0, v1, r, g, b, a,
        x + w, y + h, u1, v1, r, g, b, a,
        x + w, y, u1, v0, r, g, b, a,
        x, y, u0, v0, r, g, b, a}, ti, z);
  }

  public static RenderCommand rect(float x, float y, float w, float h,
      float r, float g, float b, float a, int z) {
    return new RenderCommand(new float[]{
        x, y, 0, 0, r, g, b, a,
        x + w, y, 0, 0, r, g, b, a,
        x + w, y + h, 0, 0, r, g, b, a,
        x, y + h, 0, 0, r, g, b, a}, -1, z);
  }

  @Override
  public int compareTo(RenderCommand o) {
    return Integer.compare(this.z, o.z);
  }
}
