package render.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

import java.nio.*;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

/**
 * Manages swapchain lifecycle: creation, image views, framebuffers, and recreation.
 */
final class SwapchainManager implements AutoCloseable {

  private long swapchain;
  private int swapchainFormat;
  private int swapchainW, swapchainH;
  private long[] swapchainViews;
  private long[] swapchainFBs;

  private final VkContext ctx;
  private final int initW, initH;

  SwapchainManager(VkContext ctx, int initW, int initH) {
    this.ctx = ctx;
    this.initW = initW;
    this.initH = initH;
  }

  /* ---- Accessors ---- */

  long getSwapchain() { return swapchain; }
  int format() { return swapchainFormat; }
  int width() { return swapchainW; }
  int height() { return swapchainH; }
  long framebuffer(int index) { return swapchainFBs[index]; }
  int imageCount() { return swapchainViews.length; }

  /* ---- Create ---- */

  void create(long renderPass) {
    try (MemoryStack s = stackPush()) {
      VkContext.SwapCaps sc = ctx.queryCaps(s);
      VkSurfaceFormatKHR sf = pickFmt(sc.fmts);
      int pm = pickMode(sc.modes);
      VkExtent2D ext = pickExtent(s, sc.cap);
      int n = sc.cap.minImageCount() + 1;
      if (sc.cap.maxImageCount() > 0 && n > sc.cap.maxImageCount()) n = sc.cap.maxImageCount();

      VkSwapchainCreateInfoKHR ci = VkSwapchainCreateInfoKHR.calloc(s)
          .sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
          .surface(ctx.surface)
          .minImageCount(n)
          .imageFormat(sf.format()).imageColorSpace(sf.colorSpace())
          .imageExtent(ext).imageArrayLayers(1)
          .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
          .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
          .preTransform(sc.cap.currentTransform())
          .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
          .presentMode(pm).clipped(true)
          .oldSwapchain(VK_NULL_HANDLE);

      LongBuffer p = s.mallocLong(1);
      VkContext.check(vkCreateSwapchainKHR(ctx.device, ci, null, p), "mkSwapchain");
      swapchain = p.get(0);
      swapchainFormat = sf.format();
      swapchainW = ext.width();
      swapchainH = ext.height();
    }
    createViews();
    createFramebuffers(renderPass);
  }

  /* ---- Create views + framebuffers ---- */

  private void createViews() {
    try (MemoryStack s = stackPush()) {
      IntBuffer c = s.ints(0);
      vkGetSwapchainImagesKHR(ctx.device, swapchain, c, null);
      int n = c.get(0);
      LongBuffer imgs = s.mallocLong(n);
      vkGetSwapchainImagesKHR(ctx.device, swapchain, c, imgs);
      swapchainViews = new long[n];
      swapchainFBs = new long[n];
      for (int i = 0; i < n; i++)
        swapchainViews[i] = ctx.mkView(imgs.get(i), swapchainFormat);
    }
  }

  private void createFramebuffers(long renderPass) {
    try (MemoryStack s = stackPush()) {
      for (int i = 0; i < swapchainViews.length; i++) {
        LongBuffer att = s.mallocLong(1);
        att.put(0, swapchainViews[i]);
        VkFramebufferCreateInfo ci = VkFramebufferCreateInfo.calloc(s)
            .sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
            .renderPass(renderPass)
            .pAttachments(att)
            .width(swapchainW).height(swapchainH).layers(1);
        LongBuffer p = s.mallocLong(1);
        VkContext.check(vkCreateFramebuffer(ctx.device, ci, null, p), "mkFB");
        swapchainFBs[i] = p.get(0);
      }
    }
  }

  /* ---- Recreate ---- */

  void recreate(long renderPass) {
    try { vkDeviceWaitIdle(ctx.device); } catch (Exception ignored) {}
    try (MemoryStack s = stackPush()) {
      for (long f : swapchainFBs)
        if (f != VK_NULL_HANDLE) vkDestroyFramebuffer(ctx.device, f, null);
      for (long v : swapchainViews)
        if (v != VK_NULL_HANDLE) vkDestroyImageView(ctx.device, v, null);

      VkContext.SwapCaps sc = ctx.queryCaps(s);
      VkSurfaceFormatKHR sf = pickFmt(sc.fmts);
      int pm = pickMode(sc.modes);
      VkExtent2D ext = pickExtent(s, sc.cap);
      int n = sc.cap.minImageCount() + 1;
      if (sc.cap.maxImageCount() > 0 && n > sc.cap.maxImageCount()) n = sc.cap.maxImageCount();

      VkSwapchainCreateInfoKHR ci = VkSwapchainCreateInfoKHR.calloc(s)
          .sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
          .surface(ctx.surface)
          .minImageCount(n)
          .imageFormat(sf.format()).imageColorSpace(sf.colorSpace())
          .imageExtent(ext).imageArrayLayers(1)
          .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
          .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
          .preTransform(sc.cap.currentTransform())
          .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
          .presentMode(pm).clipped(true)
          .oldSwapchain(this.swapchain);

      LongBuffer p = s.mallocLong(1);
      VkContext.check(vkCreateSwapchainKHR(ctx.device, ci, null, p), "reSwap");
      long newSwapchain = p.get(0);

      if (this.swapchain != VK_NULL_HANDLE) vkDestroySwapchainKHR(ctx.device, this.swapchain, null);

      swapchain = newSwapchain;
      swapchainFormat = sf.format();
      swapchainW = ext.width();
      swapchainH = ext.height();

      IntBuffer c = s.ints(0);
      vkGetSwapchainImagesKHR(ctx.device, swapchain, c, null);
      int imgN = c.get(0);
      LongBuffer imgs = s.mallocLong(imgN);
      vkGetSwapchainImagesKHR(ctx.device, swapchain, c, imgs);
      swapchainViews = new long[imgN];
      swapchainFBs = new long[imgN];
      for (int i = 0; i < imgN; i++) {
        swapchainViews[i] = ctx.mkView(imgs.get(i), swapchainFormat);
        LongBuffer att = s.mallocLong(1);
        att.put(0, swapchainViews[i]);
        VkFramebufferCreateInfo fbi = VkFramebufferCreateInfo.calloc(s)
            .sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
            .renderPass(renderPass)
            .pAttachments(att)
            .width(swapchainW).height(swapchainH).layers(1);
        LongBuffer pf = s.mallocLong(1);
        VkContext.check(vkCreateFramebuffer(ctx.device, fbi, null, pf), "reFB");
        swapchainFBs[i] = pf.get(0);
      }
    }
  }

  /* ---- Format/mode/extent selection ---- */

  private static VkSurfaceFormatKHR pickFmt(VkSurfaceFormatKHR.Buffer f) {
    if (f == null) throw new IllegalStateException("No formats");
    for (int i = 0; i < f.capacity(); i++)
      if (f.get(i).format() == VK_FORMAT_B8G8R8A8_SRGB
          && f.get(i).colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) return f.get(i);
    return f.get(0);
  }

  private static int pickMode(IntBuffer m) {
    if (m == null) return VK_PRESENT_MODE_FIFO_KHR;
    for (int i = 0; i < m.capacity(); i++)
      if (m.get(i) == VK_PRESENT_MODE_MAILBOX_KHR) return m.get(i);
    return VK_PRESENT_MODE_FIFO_KHR;
  }

  private VkExtent2D pickExtent(MemoryStack s, VkSurfaceCapabilitiesKHR cap) {
    if (cap.currentExtent().width() != 0xFFFFFFFF)
      return VkExtent2D.calloc(s).set(cap.currentExtent());
    int w = Math.clamp(initW, cap.minImageExtent().width(), cap.maxImageExtent().width());
    int h = Math.clamp(initH, cap.minImageExtent().height(), cap.maxImageExtent().height());
    return VkExtent2D.calloc(s).width(w).height(h);
  }

  /* ---- Cleanup ---- */

  @Override
  public void close() {
    if (swapchainFBs != null)
      for (long f : swapchainFBs)
        if (f != VK_NULL_HANDLE) vkDestroyFramebuffer(ctx.device, f, null);
    if (swapchainViews != null)
      for (long v : swapchainViews)
        if (v != VK_NULL_HANDLE) vkDestroyImageView(ctx.device, v, null);
    if (swapchain != VK_NULL_HANDLE) vkDestroySwapchainKHR(ctx.device, swapchain, null);
  }
}
