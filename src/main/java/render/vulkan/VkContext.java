package render.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

import java.nio.*;
import java.util.*;

import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

/**
 * Vulkan device bootstrap and low-level utilities.
 * Owns instance, surface, physical device, logical device, command pool,
 * and provides reusable memory/buffer/image/staging helpers.
 */
final class VkContext implements AutoCloseable {

  final VkInstance instance;
  final long surface;
  final VkPhysicalDevice physicalDevice;
  final VkDevice device;
  final int queueFamily;
  final VkQueue queue;
  final long commandPool;

  private final Map<Long, Long> stagingMap = new HashMap<>();

  VkContext(long window, int initW, int initH, boolean enableValidation) {
    this.instance = createInstance(initW, initH, enableValidation);
    this.surface = createSurface(window);
    int[] qfHolder = new int[1];
    this.physicalDevice = pickDevice(qfHolder);
    this.queueFamily = qfHolder[0];
    this.device = createDevice();
    this.queue = getQueue();
    this.commandPool = createCmdPool();
  }

  /* ---- Accessors ---- */

  VkDevice device() { return device; }
  VkPhysicalDevice physicalDevice() { return physicalDevice; }
  long commandPool() { return commandPool; }
  VkQueue queue() { return queue; }
  int queueFamily() { return queueFamily; }

  /* ---- Instance ---- */

  private VkInstance createInstance(int initW, int initH, boolean enableValidation) {
    try (MemoryStack s = stackPush()) {
      VkApplicationInfo app = VkApplicationInfo.calloc(s)
          .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
          .pApplicationName(s.UTF8("AetherResonance"))
          .applicationVersion(VK_MAKE_VERSION(0, 1, 0))
          .apiVersion(VK_API_VERSION_1_0);

      PointerBuffer exts = GLFWVulkan.glfwGetRequiredInstanceExtensions();
      if (exts == null) throw new IllegalStateException("Vulkan not supported");

      VkInstanceCreateInfo ci = VkInstanceCreateInfo.calloc(s)
          .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
          .pApplicationInfo(app)
          .ppEnabledExtensionNames(exts);

      if (enableValidation) {
        PointerBuffer layers = s.mallocPointer(1);
        layers.put(0, s.UTF8("VK_LAYER_KHRONOS_validation"));
        ci.ppEnabledLayerNames(layers);
      }

      PointerBuffer p = s.mallocPointer(1);
      check(vkCreateInstance(ci, null, p), "vkCreateInstance");
      return new VkInstance(p.get(0), ci);
    }
  }

  /* ---- Surface ---- */

  private long createSurface(long window) {
    try (MemoryStack s = stackPush()) {
      LongBuffer p = s.mallocLong(1);
      check(GLFWVulkan.glfwCreateWindowSurface(instance, window, null, p), "glfwCreateWindowSurface");
      return p.get(0);
    }
  }

  /* ---- Physical device ---- */

  private VkPhysicalDevice pickDevice(int[] outQueueFamily) {
    try (MemoryStack s = stackPush()) {
      IntBuffer c = s.ints(0);
      check(vkEnumeratePhysicalDevices(instance, c, null), "vkEnumPhys");
      PointerBuffer devs = s.mallocPointer(c.get(0));
      check(vkEnumeratePhysicalDevices(instance, c, devs), "vkEnumPhys");
      for (int i = 0; i < devs.capacity(); i++) {
        VkPhysicalDevice dev = new VkPhysicalDevice(devs.get(i), instance);
        int qf = findQueue(s, dev);
        if (qf < 0 || !hasSwapExt(s, dev)) continue;
        SwapCaps sc = queryCaps(s, dev, surface);
        if (!sc.ok()) continue;
        outQueueFamily[0] = qf;
        return dev;
      }
    }
    throw new IllegalStateException("No suitable GPU");
  }

  private int findQueue(MemoryStack s, VkPhysicalDevice dev) {
    IntBuffer c = s.ints(0);
    vkGetPhysicalDeviceQueueFamilyProperties(dev, c, null);
    VkQueueFamilyProperties.Buffer qf = VkQueueFamilyProperties.calloc(c.get(0), s);
    vkGetPhysicalDeviceQueueFamilyProperties(dev, c, qf);
    for (int i = 0; i < qf.capacity(); i++) {
      if ((qf.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) == 0) continue;
      IntBuffer sup = s.ints(VK_FALSE);
      vkGetPhysicalDeviceSurfaceSupportKHR(dev, i, surface, sup);
      if (sup.get(0) == VK_TRUE) return i;
    }
    return -1;
  }

  private boolean hasSwapExt(MemoryStack s, VkPhysicalDevice dev) {
    IntBuffer c = s.ints(0);
    vkEnumerateDeviceExtensionProperties(dev, (ByteBuffer) null, c, null);
    if (c.get(0) == 0) return false;
    VkExtensionProperties.Buffer p = VkExtensionProperties.malloc(c.get(0), s);
    vkEnumerateDeviceExtensionProperties(dev, (ByteBuffer) null, c, p);
    for (int i = 0; i < p.capacity(); i++)
      if (VK_KHR_SWAPCHAIN_EXTENSION_NAME.equals(p.get(i).extensionNameString())) return true;
    return false;
  }

  static SwapCaps queryCaps(MemoryStack s, VkPhysicalDevice dev, long surface) {
    SwapCaps r = new SwapCaps();
    r.cap = VkSurfaceCapabilitiesKHR.malloc(s);
    if (vkGetPhysicalDeviceSurfaceCapabilitiesKHR(dev, surface, r.cap) != VK_SUCCESS) r.cap = null;

    IntBuffer c = s.mallocInt(1);
    vkGetPhysicalDeviceSurfaceFormatsKHR(dev, surface, c, null);
    if (c.get(0) > 0) {
      r.fmts = VkSurfaceFormatKHR.malloc(c.get(0), s);
      vkGetPhysicalDeviceSurfaceFormatsKHR(dev, surface, c, r.fmts);
    }

    vkGetPhysicalDeviceSurfacePresentModesKHR(dev, surface, c, null);
    if (c.get(0) > 0) {
      r.modes = s.mallocInt(c.get(0));
      vkGetPhysicalDeviceSurfacePresentModesKHR(dev, surface, c, r.modes);
    }
    return r;
  }

  SwapCaps queryCaps(MemoryStack s) {
    return queryCaps(s, physicalDevice, surface);
  }

  /* ---- Logical device ---- */

  private VkDevice createDevice() {
    try (MemoryStack s = stackPush()) {
      VkDeviceQueueCreateInfo.Buffer qc = VkDeviceQueueCreateInfo.calloc(1, s)
          .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
          .queueFamilyIndex(queueFamily)
          .pQueuePriorities(s.floats(1.0f));
      PointerBuffer exts = s.mallocPointer(1);
      exts.put(0, s.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME));
      VkDeviceCreateInfo ci = VkDeviceCreateInfo.calloc(s)
          .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
          .pQueueCreateInfos(qc)
          .ppEnabledExtensionNames(exts);
      PointerBuffer p = s.mallocPointer(1);
      check(vkCreateDevice(physicalDevice, ci, null, p), "vkCreateDevice");
      return new VkDevice(p.get(0), physicalDevice, ci);
    }
  }

  private VkQueue getQueue() {
    try (MemoryStack s = stackPush()) {
      PointerBuffer qp = s.mallocPointer(1);
      vkGetDeviceQueue(device, queueFamily, 0, qp);
      return new VkQueue(qp.get(0), device);
    }
  }

  /* ---- Command pool ---- */

  private long createCmdPool() {
    try (MemoryStack s = stackPush()) {
      VkCommandPoolCreateInfo ci = VkCommandPoolCreateInfo.calloc(s)
          .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
          .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
          .queueFamilyIndex(queueFamily);
      LongBuffer p = s.mallocLong(1);
      check(vkCreateCommandPool(device, ci, null, p), "mkCmdPool");
      return p.get(0);
    }
  }

  /* ---- Image view ---- */

  long mkView(long img, int fmt) {
    try (MemoryStack s = stackPush()) {
      VkImageViewCreateInfo ci = VkImageViewCreateInfo.calloc(s)
          .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
          .image(img).viewType(VK_IMAGE_VIEW_TYPE_2D).format(fmt)
          .subresourceRange(VkImageSubresourceRange.calloc(s)
              .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
              .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
      LongBuffer p = s.mallocLong(1);
      check(vkCreateImageView(device, ci, null, p), "mkView");
      return p.get(0);
    }
  }

  /* ---- Sampler ---- */

  long mkSampler() {
    try (MemoryStack s = stackPush()) {
      VkSamplerCreateInfo ci = VkSamplerCreateInfo.calloc(s)
          .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
          .magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
          .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
          .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
          .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
          .anisotropyEnable(false)
          .borderColor(VK_BORDER_COLOR_INT_OPAQUE_BLACK)
          .unnormalizedCoordinates(false).compareEnable(false)
          .mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR).minLod(0f).maxLod(0f);
      LongBuffer p = s.mallocLong(1);
      check(vkCreateSampler(device, ci, null, p), "mkSam");
      return p.get(0);
    }
  }

  /* ---- Image creation ---- */

  long mkImg(MemoryStack s, int w, int h) {
    VkExtent3D ext = VkExtent3D.calloc(s).width(w).height(h).depth(1);
    VkImageCreateInfo ci = VkImageCreateInfo.calloc(s)
        .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
        .imageType(VK_IMAGE_TYPE_2D).extent(ext).mipLevels(1).arrayLayers(1)
        .format(VK_FORMAT_R8G8B8A8_SRGB).tiling(VK_IMAGE_TILING_OPTIMAL)
        .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
        .sharingMode(VK_SHARING_MODE_EXCLUSIVE).samples(VK_SAMPLE_COUNT_1_BIT)
        .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
    LongBuffer p = s.mallocLong(1);
    check(vkCreateImage(device, ci, null, p), "mkImg");
    return p.get(0);
  }

  long mkImgMem(MemoryStack s, long img) {
    VkMemoryRequirements mr = VkMemoryRequirements.malloc(s);
    vkGetImageMemoryRequirements(device, img, mr);
    VkMemoryAllocateInfo ai = VkMemoryAllocateInfo.calloc(s)
        .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
        .allocationSize(mr.size())
        .memoryTypeIndex(findMT(s, mr.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
    LongBuffer p = s.mallocLong(1);
    check(vkAllocateMemory(device, ai, null, p), "mkImgMem");
    return p.get(0);
  }

  /* ---- Layout transition ---- */

  void transLayout(MemoryStack s, long img, int oldL, int newL) {
    VkCommandBuffer cb = oneTimeCmd(s);
    VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, s)
        .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
        .oldLayout(oldL).newLayout(newL)
        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
        .image(img)
        .subresourceRange(VkImageSubresourceRange.calloc(s)
            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
            .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
    int srcS = oldL == VK_IMAGE_LAYOUT_UNDEFINED
        ? VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT : VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    int dstS = newL == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
        ? VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT : VK_PIPELINE_STAGE_TRANSFER_BIT;
    if (newL == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) b.dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
    else b.dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
    vkCmdPipelineBarrier(cb, srcS, dstS, VK_FALSE, null, null, b);
    submitAndWait(cb);
  }

  /* ---- Copy staging → image ---- */

  void copyStagingImg(MemoryStack s, long sb, long img, int w, int h) {
    VkCommandBuffer cb = oneTimeCmd(s);
    VkBufferImageCopy.Buffer r = VkBufferImageCopy.calloc(1, s)
        .bufferOffset(0).bufferRowLength(0).bufferImageHeight(0)
        .imageSubresource(VkImageSubresourceLayers.calloc(s)
            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
            .mipLevel(0).baseArrayLayer(0).layerCount(1))
        .imageOffset(VkOffset3D.calloc(s))
        .imageExtent(VkExtent3D.calloc(s).width(w).height(h).depth(1));
    vkCmdCopyBufferToImage(cb, sb, img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, r);
    submitAndWait(cb);
  }

  /* ---- Buffer creation ---- */

  long mkBuf(MemoryStack s, long size, int usage) {
    VkBufferCreateInfo ci = VkBufferCreateInfo.calloc(s)
        .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
        .size(size).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
    LongBuffer p = s.mallocLong(1);
    check(vkCreateBuffer(device, ci, null, p), "mkBuf");
    return p.get(0);
  }

  long mkDevMem(MemoryStack s, long buf) {
    VkMemoryRequirements mr = VkMemoryRequirements.malloc(s);
    vkGetBufferMemoryRequirements(device, buf, mr);
    VkMemoryAllocateInfo ai = VkMemoryAllocateInfo.calloc(s)
        .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
        .allocationSize(mr.size())
        .memoryTypeIndex(findMT(s, mr.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
    LongBuffer p = s.mallocLong(1);
    check(vkAllocateMemory(device, ai, null, p), "mkDevMem");
    return p.get(0);
  }

  long mkHostMem(MemoryStack s, long size, int mask) {
    VkMemoryAllocateInfo ai = VkMemoryAllocateInfo.calloc(s)
        .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
        .allocationSize(size)
        .memoryTypeIndex(findMT(s, mask,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
    LongBuffer p = s.mallocLong(1);
    check(vkAllocateMemory(device, ai, null, p), "mkHostMem");
    return p.get(0);
  }

  /* ---- Staging buffers ---- */

  long sbMem(long b) { return stagingMap.get(b); }

  long mkStagingBuf(MemoryStack s, long size) {
    VkBufferCreateInfo ci = VkBufferCreateInfo.calloc(s)
        .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
        .size(size).usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
        .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
    LongBuffer pb = s.mallocLong(1);
    vkCreateBuffer(device, ci, null, pb);
    long b = pb.get(0);
    VkMemoryRequirements mr = VkMemoryRequirements.malloc(s);
    vkGetBufferMemoryRequirements(device, b, mr);
    VkMemoryAllocateInfo ai = VkMemoryAllocateInfo.calloc(s)
        .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
        .allocationSize(mr.size())
        .memoryTypeIndex(findMT(s, mr.memoryTypeBits(),
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
    LongBuffer pm = s.mallocLong(1);
    vkAllocateMemory(device, ai, null, pm);
    long m = pm.get(0);
    vkBindBufferMemory(device, b, m, 0);
    stagingMap.put(b, m);
    return b;
  }

  void destroyStagingBuf(MemoryStack s, long b) {
    Long m = stagingMap.remove(b);
    if (m != null) vkFreeMemory(device, m, null);
    vkDestroyBuffer(device, b, null);
  }

  /* ---- Copy buffer device-to-device ---- */

  void copyBufDev(MemoryStack s, long src, long dst, long size) {
    VkCommandBuffer cb = oneTimeCmd(s);
    VkBufferCopy.Buffer r = VkBufferCopy.calloc(1, s).size(size);
    vkCmdCopyBuffer(cb, src, dst, r);
    submitAndWait(cb);
  }

  /* ---- One-time command buffer ---- */

  VkCommandBuffer oneTimeCmd(MemoryStack s) {
    VkCommandBufferAllocateInfo ai = VkCommandBufferAllocateInfo.calloc(s)
        .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
        .commandPool(commandPool)
        .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
        .commandBufferCount(1);
    PointerBuffer p = s.mallocPointer(1);
    vkAllocateCommandBuffers(device, ai, p);
    VkCommandBuffer cb = new VkCommandBuffer(p.get(0), device);
    VkCommandBufferBeginInfo bi = VkCommandBufferBeginInfo.calloc(s)
        .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
        .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
    vkBeginCommandBuffer(cb, bi);
    return cb;
  }

  void submitAndWait(VkCommandBuffer cb) {
    try (MemoryStack s = stackPush()) {
      vkEndCommandBuffer(cb);
      VkSubmitInfo si = VkSubmitInfo.calloc(s)
          .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
          .pCommandBuffers(s.pointers(cb.address()));
      vkQueueSubmit(queue, si, VK_NULL_HANDLE);
      vkQueueWaitIdle(queue);
      vkFreeCommandBuffers(device, commandPool, cb);
    }
  }

  /* ---- Memory type finder ---- */

  int findMT(MemoryStack s, int mask, int flags) {
    VkPhysicalDeviceMemoryProperties mp = VkPhysicalDeviceMemoryProperties.malloc(s);
    vkGetPhysicalDeviceMemoryProperties(physicalDevice, mp);
    for (int i = 0; i < mp.memoryTypeCount(); i++)
      if ((mask & (1 << i)) != 0 && (mp.memoryTypes(i).propertyFlags() & flags) == flags) return i;
    throw new IllegalStateException("No mem type");
  }

  /* ---- Helpers ---- */

  static void check(int r, String msg) {
    if (r < 0) throw new RuntimeException(msg + ": " + r);
  }

  static LongBuffer mkLong(MemoryStack s) { return s.mallocLong(1); }

  static final class SwapCaps {
    VkSurfaceCapabilitiesKHR cap;
    VkSurfaceFormatKHR.Buffer fmts;
    IntBuffer modes;
    boolean ok() { return cap != null && fmts != null && fmts.remaining() > 0 && modes != null && modes.remaining() > 0; }
  }

  /* ---- Cleanup ---- */

  @Override
  public void close() {
    if (commandPool != VK_NULL_HANDLE) vkDestroyCommandPool(device, commandPool, null);
    if (device != null) vkDestroyDevice(device, null);
    if (surface != VK_NULL_HANDLE) vkDestroySurfaceKHR(instance, surface, null);
    if (instance != null) vkDestroyInstance(instance, null);
  }
}
