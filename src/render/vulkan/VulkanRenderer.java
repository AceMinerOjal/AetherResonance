package render.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

import java.awt.image.BufferedImage;
import java.nio.*;
import java.util.*;

import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

/**
 * Minimal Vulkan 2D batch renderer.
 * Single pipeline handles both textured quads (push constant = texture slot >= 0)
 * and solid-color rects (push constant = -1, color from vertex data).
 */
public final class VulkanRenderer implements AutoCloseable {

  private static final int MAX_FRAMES = 2;
  private static final int MAX_TEXTURES = 64;
  private static final int MAX_QUADS = 16384;
  private static final int VTX_SIZE = 32;   // 8 floats per vertex: x,y,u,v,r,g,b,a
  private static final int IDX_PER_QUAD = 6;

  private VkInstance instance;
  private long surface;
  private VkPhysicalDevice physicalDevice;
  private VkDevice device;
  private int queueFamily;
  private VkQueue queue;
  private long commandPool;

  private long swapchain;
  private int swapchainFormat;
  private int swapchainW, swapchainH;
  private long[] swapchainFBs;
  private long[] swapchainViews;

  private long pipeline, pipelineLayout, renderPass;
  private long descriptorPool, descriptorSetLayout;
  private final long[] descriptorSets = new long[MAX_FRAMES];

  private VkCommandBuffer[] cmdBufs;
  private long[] semImage, semRender, fences;
  private int frameIdx;

  private final long[] texImages = new long[MAX_TEXTURES];
  private final long[] texMems = new long[MAX_TEXTURES];
  private final long[] texViews = new long[MAX_TEXTURES];
  private final long[] texSamplers = new long[MAX_TEXTURES];
  private int texCount;

  private final long[] vBuf = new long[MAX_FRAMES], vMemHost = new long[MAX_FRAMES];
  private long vBufSize, iBuf, iMem;
  private long[] uboBuf = new long[MAX_FRAMES];
  private long[] uboMemArr = new long[MAX_FRAMES];

  private final boolean enableValidation;

  private final long window;
  private final int initW, initH;

  public VulkanRenderer(long window, int width, int height) {
    this.window = window;
    this.initW = width;
    this.initH = height;
    this.enableValidation = "1".equals(System.getenv("VALIDATION_LAYERS"));
    init();
  }

  private void init() {
    createInstance();
    createSurface();
    pickDevice();
    createDevice();
    createCmdPool();
    createSwapchain();
    createSwapchainViews();
    createRenderPass();
    createDescriptorLayout();
    createPipeline();
    createFramebuffers();
    createDefaultTex();
    createBuffers();
    createDescriptorPool();
    createDescriptorSet();
    createCmdBufs();
    createSync();
  }

  /* ---- Instance ---- */
  private void createInstance() {
    try (MemoryStack s = stackPush()) {
      VkApplicationInfo app = VkApplicationInfo.calloc(s).sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
          .pApplicationName(s.UTF8("AetherResonance")).applicationVersion(VK_MAKE_VERSION(0, 1, 0)).apiVersion(VK_API_VERSION_1_0);
      PointerBuffer exts = GLFWVulkan.glfwGetRequiredInstanceExtensions();
      if (exts == null) throw new IllegalStateException("Vulkan not supported");
      VkInstanceCreateInfo ci = VkInstanceCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO).pApplicationInfo(app).ppEnabledExtensionNames(exts);
      if (enableValidation) {
        PointerBuffer layers = s.mallocPointer(1);
        layers.put(0, s.UTF8("VK_LAYER_KHRONOS_validation"));
        ci.ppEnabledLayerNames(layers);
      }
      PointerBuffer p = s.mallocPointer(1);
      check(vkCreateInstance(ci, null, p), "vkCreateInstance");
      instance = new VkInstance(p.get(0), ci);
    }
  }

  /* ---- Surface ---- */
  private void createSurface() {
    try (MemoryStack s = stackPush()) {
      LongBuffer p = s.mallocLong(1);
      check(GLFWVulkan.glfwCreateWindowSurface(instance, window, null, p), "glfwCreateWindowSurface");
      surface = p.get(0);
    }
  }

  /* ---- Physical device ---- */
  private void pickDevice() {
    try (MemoryStack s = stackPush()) {
      IntBuffer c = s.ints(0);
      check(vkEnumeratePhysicalDevices(instance, c, null), "vkEnumPhys");
      PointerBuffer devs = s.mallocPointer(c.get(0));
      check(vkEnumeratePhysicalDevices(instance, c, devs), "vkEnumPhys");
      for (int i = 0; i < devs.capacity(); i++) {
        VkPhysicalDevice dev = new VkPhysicalDevice(devs.get(i), instance);
        int qf = findQueue(s, dev);
        if (qf < 0 || !hasSwapExt(s, dev)) continue;
        SwapCaps sc = queryCaps(s, dev);
        if (!sc.ok()) continue;
        physicalDevice = dev;
        queueFamily = qf;
        return;
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

  private SwapCaps queryCaps(MemoryStack s, VkPhysicalDevice dev) {
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

  /* ---- Logical device ---- */
  private void createDevice() {
    try (MemoryStack s = stackPush()) {
      VkDeviceQueueCreateInfo.Buffer qc = VkDeviceQueueCreateInfo.calloc(1, s).sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO).queueFamilyIndex(queueFamily).pQueuePriorities(s.floats(1.0f));
      PointerBuffer exts = s.mallocPointer(1); exts.put(0, s.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME));
      VkDeviceCreateInfo ci = VkDeviceCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO).pQueueCreateInfos(qc).ppEnabledExtensionNames(exts);
      PointerBuffer p = s.mallocPointer(1);
      check(vkCreateDevice(physicalDevice, ci, null, p), "vkCreateDevice");
      device = new VkDevice(p.get(0), physicalDevice, ci);
      PointerBuffer qp = s.mallocPointer(1);
      vkGetDeviceQueue(device, queueFamily, 0, qp);
      queue = new VkQueue(qp.get(0), device);
    }
  }

  /* ---- Command pool ---- */
  private void createCmdPool() {
    try (MemoryStack s = stackPush()) {
      VkCommandPoolCreateInfo ci = VkCommandPoolCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO).flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(queueFamily);
      LongBuffer p = s.mallocLong(1);
      check(vkCreateCommandPool(device, ci, null, p), "mkCmdPool");
      commandPool = p.get(0);
    }
  }

  /* ---- Swapchain ---- */
  private void createSwapchain() {
    try (MemoryStack s = stackPush()) {
      SwapCaps sc = queryCaps(s, physicalDevice);
      VkSurfaceFormatKHR sf = pickFmt(sc.fmts);
      int pm = pickMode(sc.modes);
      VkExtent2D ext = pickExtent(s, sc.cap);
      int n = sc.cap.minImageCount() + 1;
      if (sc.cap.maxImageCount() > 0 && n > sc.cap.maxImageCount()) n = sc.cap.maxImageCount();
      VkSwapchainCreateInfoKHR ci = VkSwapchainCreateInfoKHR.calloc(s).sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
          .surface(surface).minImageCount(n).imageFormat(sf.format()).imageColorSpace(sf.colorSpace())
          .imageExtent(ext).imageArrayLayers(1).imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
          .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE).preTransform(sc.cap.currentTransform())
          .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR).presentMode(pm).clipped(true).oldSwapchain(VK_NULL_HANDLE);
      LongBuffer p = s.mallocLong(1);
      check(vkCreateSwapchainKHR(device, ci, null, p), "mkSwapchain");
      swapchain = p.get(0);
      swapchainFormat = sf.format();
      swapchainW = ext.width();
      swapchainH = ext.height();
    }
  }

  private VkSurfaceFormatKHR pickFmt(VkSurfaceFormatKHR.Buffer f) {
    if (f == null) throw new IllegalStateException("No formats");
    for (int i = 0; i < f.capacity(); i++)
      if (f.get(i).format() == VK_FORMAT_B8G8R8A8_SRGB && f.get(i).colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) return f.get(i);
    return f.get(0);
  }

  private int pickMode(IntBuffer m) {
    if (m == null) return VK_PRESENT_MODE_FIFO_KHR;
    for (int i = 0; i < m.capacity(); i++) if (m.get(i) == VK_PRESENT_MODE_MAILBOX_KHR) return m.get(i);
    return VK_PRESENT_MODE_FIFO_KHR;
  }

  private VkExtent2D pickExtent(MemoryStack s, VkSurfaceCapabilitiesKHR cap) {
    if (cap.currentExtent().width() != 0xFFFFFFFF) return VkExtent2D.calloc(s).set(cap.currentExtent());
    int w = Math.clamp(initW, cap.minImageExtent().width(), cap.maxImageExtent().width());
    int h = Math.clamp(initH, cap.minImageExtent().height(), cap.maxImageExtent().height());
    return VkExtent2D.calloc(s).width(w).height(h);
  }

  private void createSwapchainViews() {
    try (MemoryStack s = stackPush()) {
      IntBuffer c = s.ints(0);
      vkGetSwapchainImagesKHR(device, swapchain, c, null);
      int n = c.get(0);
      LongBuffer imgs = s.mallocLong(n);
      vkGetSwapchainImagesKHR(device, swapchain, c, imgs);
      swapchainViews = new long[n];
      swapchainFBs = new long[n];
      for (int i = 0; i < n; i++) swapchainViews[i] = mkView(imgs.get(i), swapchainFormat);
    }
  }

  private long mkView(long img, int fmt) {
    try (MemoryStack s = stackPush()) {
      VkImageViewCreateInfo ci = VkImageViewCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO).image(img).viewType(VK_IMAGE_VIEW_TYPE_2D).format(fmt)
          .subresourceRange(VkImageSubresourceRange.calloc(s).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
      LongBuffer p = s.mallocLong(1); check(vkCreateImageView(device, ci, null, p), "mkView"); return p.get(0);
    }
  }

  /* ---- Render pass ---- */
  private void createRenderPass() {
    try (MemoryStack s = stackPush()) {
      VkAttachmentDescription.Buffer att = VkAttachmentDescription.calloc(1, s).format(swapchainFormat).samples(VK_SAMPLE_COUNT_1_BIT)
          .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE).stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
          .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
      VkAttachmentReference.Buffer ref = VkAttachmentReference.calloc(1, s).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
      VkSubpassDescription.Buffer sp = VkSubpassDescription.calloc(1, s).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).pColorAttachments(ref);
      VkSubpassDependency.Buffer dep = VkSubpassDependency.calloc(1, s).srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0)
          .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT).srcAccessMask(0)
          .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT).dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
      VkRenderPassCreateInfo ci = VkRenderPassCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO).pAttachments(att).pSubpasses(sp).pDependencies(dep);
      LongBuffer p = s.mallocLong(1); check(vkCreateRenderPass(device, ci, null, p), "mkRP"); renderPass = p.get(0);
    }
  }

  /* ---- Descriptor set layout ---- */
  private void createDescriptorLayout() {
    try (MemoryStack s = stackPush()) {
      VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(2, s);
      b.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_VERTEX_BIT);
      b.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(MAX_TEXTURES).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
      VkDescriptorSetLayoutCreateInfo ci = VkDescriptorSetLayoutCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO).pBindings(b);
      LongBuffer p = s.mallocLong(1); check(vkCreateDescriptorSetLayout(device, ci, null, p), "mkDSL"); descriptorSetLayout = p.get(0);
    }
  }

  /* ---- Pipeline ---- */
  private void createPipeline() {
    try (MemoryStack s = stackPush()) {
      long vs = mkShader(s, compile("vs", shaderc_vertex_shader, """
          #version 450
          layout(location=0) in vec2 iP; layout(location=1) in vec2 iU; layout(location=2) in vec4 iC;
          layout(binding=0) uniform UBO{mat4 proj;}ubo;
          layout(push_constant) uniform PC{int ti; int z;}pc;
          layout(location=0) out vec2 oU; layout(location=1) out vec4 oC;
          void main(){gl_Position=ubo.proj*vec4(iP,pc.z*0.001,1);oU=iU;oC=iC;}"""));
      long fs = mkShader(s, compile("fs", shaderc_fragment_shader, """
          #version 450
          layout(location=0) in vec2 oU; layout(location=1) in vec4 oC;
          layout(binding=1) uniform sampler2D ts[MAX_TEX];
          layout(push_constant) uniform PC{int ti; int z;}pc;
          layout(location=0) out vec4 fC;
          void main(){fC=pc.ti<0?oC:texture(ts[pc.ti],oU)*oC;}""".replace("MAX_TEX", "" + MAX_TEXTURES)));

      VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, s);
      stages.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_VERTEX_BIT).module(vs).pName(s.UTF8("main"));
      stages.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fs).pName(s.UTF8("main"));

      VkVertexInputBindingDescription.Buffer vb = VkVertexInputBindingDescription.calloc(1, s).binding(0).stride(VTX_SIZE).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
      VkVertexInputAttributeDescription.Buffer va = VkVertexInputAttributeDescription.calloc(3, s);
      va.get(0).binding(0).location(0).format(VK_FORMAT_R32G32_SFLOAT).offset(0);
      va.get(1).binding(0).location(1).format(VK_FORMAT_R32G32_SFLOAT).offset(8);
      va.get(2).binding(0).location(2).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(16);

      VkPipelineVertexInputStateCreateInfo vi = VkPipelineVertexInputStateCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO).pVertexBindingDescriptions(vb).pVertexAttributeDescriptions(va);
      VkPipelineInputAssemblyStateCreateInfo ia = VkPipelineInputAssemblyStateCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO).topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST).primitiveRestartEnable(false);
      VkPipelineViewportStateCreateInfo vp = VkPipelineViewportStateCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO).viewportCount(1).scissorCount(1);
      VkPipelineRasterizationStateCreateInfo rs = VkPipelineRasterizationStateCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO).polygonMode(VK_POLYGON_MODE_FILL).lineWidth(1f).cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE);
      VkPipelineMultisampleStateCreateInfo ms = VkPipelineMultisampleStateCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO).rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
      VkPipelineColorBlendAttachmentState.Buffer ba = VkPipelineColorBlendAttachmentState.calloc(1, s).colorWriteMask(0xF).blendEnable(true)
          .srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA).dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA).colorBlendOp(VK_BLEND_OP_ADD)
          .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE).dstAlphaBlendFactor(VK_BLEND_FACTOR_ZERO).alphaBlendOp(VK_BLEND_OP_ADD);
      VkPipelineColorBlendStateCreateInfo cb = VkPipelineColorBlendStateCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO).pAttachments(ba);
      IntBuffer dyn = s.mallocInt(2); dyn.put(0, VK_DYNAMIC_STATE_VIEWPORT).put(1, VK_DYNAMIC_STATE_SCISSOR);
      VkPipelineDynamicStateCreateInfo ds = VkPipelineDynamicStateCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO).pDynamicStates(dyn);
      VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, s).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT | VK_SHADER_STAGE_VERTEX_BIT).offset(0).size(8);
      LongBuffer dsl = s.mallocLong(1); dsl.put(0, descriptorSetLayout);
      VkPipelineLayoutCreateInfo pl = VkPipelineLayoutCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO).pSetLayouts(dsl).pPushConstantRanges(pcr);
      LongBuffer ppl = s.mallocLong(1); check(vkCreatePipelineLayout(device, pl, null, ppl), "mkPL"); pipelineLayout = ppl.get(0);

      VkGraphicsPipelineCreateInfo.Buffer gp = VkGraphicsPipelineCreateInfo.calloc(1, s).sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
          .pStages(stages).pVertexInputState(vi).pInputAssemblyState(ia).pViewportState(vp)
          .pRasterizationState(rs).pMultisampleState(ms).pColorBlendState(cb).pDynamicState(ds)
          .layout(pipelineLayout).renderPass(renderPass).subpass(0);
      LongBuffer pp = s.mallocLong(1); check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, gp, null, pp), "mkPipe"); pipeline = pp.get(0);

      vkDestroyShaderModule(device, fs, null);
      vkDestroyShaderModule(device, vs, null);
    }
  }

  private ByteBuffer compile(String tag, int kind, String src) {
    long c = shaderc_compiler_initialize(), o = shaderc_compile_options_initialize();
    long r = shaderc_compile_into_spv(c, src, kind, tag, "main", o);
    if (shaderc_result_get_compilation_status(r) != 0) throw new RuntimeException(tag + ": " + shaderc_result_get_error_message(r));
    long len = shaderc_result_get_length(r);
    ByteBuffer out = BufferUtils.createByteBuffer((int) len);
    out.put(shaderc_result_get_bytes(r)).rewind();
    shaderc_result_release(r); shaderc_compile_options_release(o); shaderc_compiler_release(c);
    return out;
  }

  private long mkShader(MemoryStack s, ByteBuffer code) {
    VkShaderModuleCreateInfo ci = VkShaderModuleCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO).pCode(code);
    LongBuffer p = s.mallocLong(1); check(vkCreateShaderModule(device, ci, null, p), "mkShd"); return p.get(0);
  }

  /* ---- Framebuffers ---- */
  private void createFramebuffers() {
    try (MemoryStack s = stackPush()) {
      int n = swapchainViews.length;
      for (int i = 0; i < n; i++) {
        LongBuffer att = s.mallocLong(1); att.put(0, swapchainViews[i]);
        VkFramebufferCreateInfo ci = VkFramebufferCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO).renderPass(renderPass).pAttachments(att).width(swapchainW).height(swapchainH).layers(1);
        LongBuffer p = s.mallocLong(1); check(vkCreateFramebuffer(device, ci, null, p), "mkFB"); swapchainFBs[i] = p.get(0);
      }
    }
  }

  /* ---- Default texture ---- */
  private void createDefaultTex() {
    Arrays.fill(texImages, VK_NULL_HANDLE);
    Arrays.fill(texMems, VK_NULL_HANDLE);
    Arrays.fill(texViews, VK_NULL_HANDLE);
    Arrays.fill(texSamplers, VK_NULL_HANDLE);
    try (MemoryStack s = stackPush()) {
      long sb = mkStagingBuf(s, 4);
      { PointerBuffer mp = s.mallocPointer(1); vkMapMemory(device, sbMem(sb), 0L, 4L, 0, mp); MemoryUtil.memPutInt(mp.get(0), 0xFFFFFFFF); vkUnmapMemory(device, sbMem(sb)); }
      long img = mkImg(s, 1, 1); long mem = mkImgMem(s, img);
      vkBindImageMemory(device, img, mem, 0);
      transLayout(s, img, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
      copyStagingImg(s, sb, img, 1, 1);
      transLayout(s, img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
      destroyStagingBuf(s, sb);
      texImages[0] = img; texMems[0] = mem;
      texViews[0] = mkView(img, VK_FORMAT_R8G8B8A8_SRGB);
      texSamplers[0] = mkSampler();
      texCount = 1;
    }
  }

  /* ---- Vertex/Index/UBO buffers ---- */
  private void createBuffers() {
    try (MemoryStack s = stackPush()) {
      long vSize = (long) MAX_QUADS * 4 * VTX_SIZE;
      long iSize = (long) MAX_QUADS * IDX_PER_QUAD * 4;
      vBufSize = vSize;

      for (int i = 0; i < MAX_FRAMES; i++) {
        vBuf[i] = mkBuf(s, vSize, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
        VkMemoryRequirements vmr = VkMemoryRequirements.malloc(s); vkGetBufferMemoryRequirements(device, vBuf[i], vmr);
        vMemHost[i] = mkHostMem(s, vmr.size(), vmr.memoryTypeBits()); vkBindBufferMemory(device, vBuf[i], vMemHost[i], 0);
      }

      iBuf = mkBuf(s, iSize, VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
      iMem = mkDevMem(s, iBuf); vkBindBufferMemory(device, iBuf, iMem, 0);

      // Upload indices once via staging
      long sb = mkStagingBuf(s, iSize);
      { PointerBuffer mp = s.mallocPointer(1); vkMapMemory(device, sbMem(sb), 0L, iSize, 0, mp);
        ByteBuffer mapped = mp.getByteBuffer(0, (int) iSize); IntBuffer idx = mapped.asIntBuffer();
        int vo = 0; for (int j = 0; j < MAX_QUADS; j++) { idx.put(vo).put(vo+1).put(vo+2).put(vo).put(vo+2).put(vo+3); vo += 4; }
        vkUnmapMemory(device, sbMem(sb)); }
      copyBufDev(s, sb, iBuf, iSize);
      destroyStagingBuf(s, sb);

      for (int i = 0; i < MAX_FRAMES; i++) {
        uboBuf[i] = mkBuf(s, 64, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
        VkMemoryRequirements umr = VkMemoryRequirements.malloc(s); vkGetBufferMemoryRequirements(device, uboBuf[i], umr);
        uboMemArr[i] = mkHostMem(s, umr.size(), umr.memoryTypeBits()); vkBindBufferMemory(device, uboBuf[i], uboMemArr[i], 0);
      }
    }
  }

  private long mkBuf(MemoryStack s, long size, int usage) {
    VkBufferCreateInfo ci = VkBufferCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO).size(size).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
    LongBuffer p = s.mallocLong(1); vkCreateBuffer(device, ci, null, p); return p.get(0);
  }
  private long mkDevMem(MemoryStack s, long buf) {
    VkMemoryRequirements mr = VkMemoryRequirements.malloc(s); vkGetBufferMemoryRequirements(device, buf, mr);
    VkMemoryAllocateInfo ai = VkMemoryAllocateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO).allocationSize(mr.size()).memoryTypeIndex(findMT(s, mr.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
    LongBuffer p = s.mallocLong(1); vkAllocateMemory(device, ai, null, p); return p.get(0);
  }
  private long mkHostMem(MemoryStack s, long size, int mask) {
    VkMemoryAllocateInfo ai = VkMemoryAllocateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO).allocationSize(size).memoryTypeIndex(findMT(s, mask, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
    LongBuffer p = s.mallocLong(1); check(vkAllocateMemory(device, ai, null, p), "mkHostMem"); return p.get(0);
  }

  private int findMT(MemoryStack s, int mask, int flags) {
    VkPhysicalDeviceMemoryProperties mp = VkPhysicalDeviceMemoryProperties.malloc(s);
    vkGetPhysicalDeviceMemoryProperties(physicalDevice, mp);
    for (int i = 0; i < mp.memoryTypeCount(); i++)
      if ((mask & (1 << i)) != 0 && (mp.memoryTypes(i).propertyFlags() & flags) == flags) return i;
    throw new IllegalStateException("No mem type");
  }

  /* ---- Staging buffer ---- */
  private final Map<Long, Long> stagingMap = new HashMap<>();
  private long sbMem(long b) { return stagingMap.get(b); }

  private long mkStagingBuf(MemoryStack s, long size) {
    VkBufferCreateInfo ci = VkBufferCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO).size(size).usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
    LongBuffer pb = s.mallocLong(1); vkCreateBuffer(device, ci, null, pb); long b = pb.get(0);
    VkMemoryRequirements mr = VkMemoryRequirements.malloc(s); vkGetBufferMemoryRequirements(device, b, mr);
    VkMemoryAllocateInfo ai = VkMemoryAllocateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO).allocationSize(mr.size()).memoryTypeIndex(findMT(s, mr.memoryTypeBits(), VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
    LongBuffer pm = s.mallocLong(1); vkAllocateMemory(device, ai, null, pm); long m = pm.get(0);
    vkBindBufferMemory(device, b, m, 0);
    stagingMap.put(b, m);
    return b;
  }
  private void destroyStagingBuf(MemoryStack s, long b) {
    Long m = stagingMap.remove(b);
    if (m != null) vkFreeMemory(device, m, null);
    vkDestroyBuffer(device, b, null);
  }

  private void copyBufDev(MemoryStack s, long src, long dst, long size) {
    VkCommandBuffer cb = oneTimeCmd(s);
    VkBufferCopy.Buffer r = VkBufferCopy.calloc(1, s).size(size);
    vkCmdCopyBuffer(cb, src, dst, r);
    submitAndWait(cb);
  }

  private VkCommandBuffer oneTimeCmd(MemoryStack s) {
    VkCommandBufferAllocateInfo ai = VkCommandBufferAllocateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO).commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
    PointerBuffer p = s.mallocPointer(1); vkAllocateCommandBuffers(device, ai, p);
    VkCommandBuffer cb = new VkCommandBuffer(p.get(0), device);
    VkCommandBufferBeginInfo bi = VkCommandBufferBeginInfo.calloc(s).sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO).flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
    vkBeginCommandBuffer(cb, bi);
    return cb;
  }

  private void submitAndWait(VkCommandBuffer cb) {
    try (MemoryStack s = stackPush()) {
      vkEndCommandBuffer(cb);
      VkSubmitInfo si = VkSubmitInfo.calloc(s).sType(VK_STRUCTURE_TYPE_SUBMIT_INFO).pCommandBuffers(s.pointers(cb.address()));
      vkQueueSubmit(queue, si, VK_NULL_HANDLE);
      vkQueueWaitIdle(queue);
      vkFreeCommandBuffers(device, commandPool, cb);
    }
  }

  /* ---- Image helpers ---- */
  private long mkImg(MemoryStack s, int w, int h) {
    VkExtent3D ext = VkExtent3D.calloc(s).width(w).height(h).depth(1);
    VkImageCreateInfo ci = VkImageCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO).imageType(VK_IMAGE_TYPE_2D).extent(ext).mipLevels(1).arrayLayers(1)
        .format(VK_FORMAT_R8G8B8A8_SRGB).tiling(VK_IMAGE_TILING_OPTIMAL).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
        .sharingMode(VK_SHARING_MODE_EXCLUSIVE).samples(VK_SAMPLE_COUNT_1_BIT).usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
    LongBuffer p = s.mallocLong(1); check(vkCreateImage(device, ci, null, p), "mkImg"); return p.get(0);
  }
  private long mkImgMem(MemoryStack s, long img) {
    VkMemoryRequirements mr = VkMemoryRequirements.malloc(s); vkGetImageMemoryRequirements(device, img, mr);
    VkMemoryAllocateInfo ai = VkMemoryAllocateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO).allocationSize(mr.size()).memoryTypeIndex(findMT(s, mr.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
    LongBuffer p = s.mallocLong(1); check(vkAllocateMemory(device, ai, null, p), "mkImgMem"); return p.get(0);
  }

  private void transLayout(MemoryStack s, long img, int oldL, int newL) {
    VkCommandBuffer cb = oneTimeCmd(s);
    VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, s).sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
        .oldLayout(oldL).newLayout(newL).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
        .image(img).subresourceRange(VkImageSubresourceRange.calloc(s).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
    int srcS = oldL == VK_IMAGE_LAYOUT_UNDEFINED ? VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT : VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    int dstS = newL == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL ? VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT : VK_PIPELINE_STAGE_TRANSFER_BIT;
    if (newL == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) b.dstAccessMask(VK_ACCESS_SHADER_READ_BIT); else b.dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
    vkCmdPipelineBarrier(cb, srcS, dstS, VK_FALSE, null, null, b);
    submitAndWait(cb);
  }

  private void copyStagingImg(MemoryStack s, long sb, long img, int w, int h) {
    VkCommandBuffer cb = oneTimeCmd(s);
    VkBufferImageCopy.Buffer r = VkBufferImageCopy.calloc(1, s)
        .bufferOffset(0).bufferRowLength(0).bufferImageHeight(0)
        .imageSubresource(VkImageSubresourceLayers.calloc(s).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1))
        .imageOffset(VkOffset3D.calloc(s)).imageExtent(VkExtent3D.calloc(s).width(w).height(h).depth(1));
    vkCmdCopyBufferToImage(cb, sb, img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, r);
    submitAndWait(cb);
  }

  private long mkSampler() {
    try (MemoryStack s = stackPush()) {
      VkSamplerCreateInfo ci = VkSamplerCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
          .magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
          .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
          .anisotropyEnable(false).borderColor(VK_BORDER_COLOR_INT_OPAQUE_BLACK).unnormalizedCoordinates(false).compareEnable(false)
          .mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR).minLod(0f).maxLod(0f);
      LongBuffer p = s.mallocLong(1); check(vkCreateSampler(device, ci, null, p), "mkSam"); return p.get(0);
    }
  }

  /* ---- Load texture from BufferedImage ---- */
  public int loadTexture(BufferedImage image) {
    if (image == null || texCount >= MAX_TEXTURES) return -1;
    try (MemoryStack s = stackPush()) {
      int w = image.getWidth(), h = image.getHeight();
      int[] px = new int[w * h]; image.getRGB(0, 0, w, h, px, 0, w);
      int size = w * h * 4;
      ByteBuffer buf = BufferUtils.createByteBuffer(size);
      for (int p : px) { buf.put((byte)((p>>16)&0xFF)); buf.put((byte)((p>>8)&0xFF)); buf.put((byte)(p&0xFF)); buf.put((byte)((p>>24)&0xFF)); }
      buf.rewind();

      long img = mkImg(s, w, h), mem = mkImgMem(s, img);
      vkBindImageMemory(device, img, mem, 0);
      transLayout(s, img, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

      long sb = mkStagingBuf(s, size);
      try {
        { PointerBuffer mp = s.mallocPointer(1); vkMapMemory(device, sbMem(sb), 0L, (long) size, 0, mp); MemoryUtil.memCopy(buf, mp.getByteBuffer(0, size)); vkUnmapMemory(device, sbMem(sb)); }
        copyStagingImg(s, sb, img, w, h);
      } finally {
        destroyStagingBuf(s, sb);
      }

      transLayout(s, img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
      long view = mkView(img, VK_FORMAT_R8G8B8A8_SRGB);
      long sam = mkSampler();

      int slot = texCount;
      texImages[slot] = img; texMems[slot] = mem; texViews[slot] = view; texSamplers[slot] = sam; texCount++;
      updateTexDS(slot, sam, view);
      return slot;
    }
  }

  /* ---- Descriptor pool + set ---- */
  private void createDescriptorPool() {
    try (MemoryStack s = stackPush()) {
      VkDescriptorPoolSize.Buffer ps = VkDescriptorPoolSize.calloc(2, s);
      ps.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(MAX_FRAMES);
      ps.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(MAX_TEXTURES * MAX_FRAMES);
      VkDescriptorPoolCreateInfo ci = VkDescriptorPoolCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO).pPoolSizes(ps).maxSets(MAX_FRAMES);
      LongBuffer p = mkLong(s); check(vkCreateDescriptorPool(device, ci, null, p), "mkDP"); descriptorPool = p.get(0);
    }
  }

  private void createDescriptorSet() {
    try (MemoryStack s = stackPush()) {
      LongBuffer layouts = s.mallocLong(MAX_FRAMES); for (int i = 0; i < MAX_FRAMES; i++) layouts.put(i, descriptorSetLayout);
      VkDescriptorSetAllocateInfo ai = VkDescriptorSetAllocateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO).descriptorPool(descriptorPool).pSetLayouts(layouts);
      LongBuffer p = s.mallocLong(MAX_FRAMES); check(vkAllocateDescriptorSets(device, ai, p), "mkDS");
      for (int i = 0; i < MAX_FRAMES; i++) descriptorSets[i] = p.get(i);

      for (int i = 0; i < MAX_FRAMES; i++) {
        VkDescriptorBufferInfo.Buffer bi = VkDescriptorBufferInfo.calloc(1, s).buffer(uboBuf[i]).offset(0).range(64);
        VkDescriptorImageInfo.Buffer ii = VkDescriptorImageInfo.calloc(MAX_TEXTURES, s);
        for (int j = 0; j < MAX_TEXTURES; j++) ii.get(j).sampler(texSamplers[0]).imageView(texViews[0]).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

        VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(2, s);
        w.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(descriptorSets[i]).dstBinding(0).dstArrayElement(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).pBufferInfo(bi);
        w.get(1).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(descriptorSets[i]).dstBinding(1).dstArrayElement(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(MAX_TEXTURES).pImageInfo(ii);
        vkUpdateDescriptorSets(device, w, null);
      }
    }
  }

  private void updateTexDS(int slot, long sam, long view) {
    vkDeviceWaitIdle(device);
    try (MemoryStack s = stackPush()) {
      VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, s); info.get(0).sampler(sam).imageView(view).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
      for (int i = 0; i < MAX_FRAMES; i++) {
        VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(1, s);
        w.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(descriptorSets[i]).dstBinding(1).dstArrayElement(slot).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(info);
        vkUpdateDescriptorSets(device, w, null);
      }
    }
  }

  /* ---- Command buffers ---- */
  private void createCmdBufs() {
    try (MemoryStack s = stackPush()) {
      VkCommandBufferAllocateInfo ai = VkCommandBufferAllocateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO).commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(MAX_FRAMES);
      PointerBuffer p = s.mallocPointer(MAX_FRAMES); check(vkAllocateCommandBuffers(device, ai, p), "mkCBs");
      cmdBufs = new VkCommandBuffer[MAX_FRAMES];
      for (int i = 0; i < MAX_FRAMES; i++) cmdBufs[i] = new VkCommandBuffer(p.get(i), device);
    }
  }

  /* ---- Sync ---- */
  private void createSync() {
    try (MemoryStack s = stackPush()) {
      VkSemaphoreCreateInfo si = VkSemaphoreCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
      VkFenceCreateInfo fi = VkFenceCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO).flags(VK_FENCE_CREATE_SIGNALED_BIT);
      LongBuffer p = s.mallocLong(1);
      semImage = new long[MAX_FRAMES]; semRender = new long[MAX_FRAMES]; fences = new long[MAX_FRAMES];
      for (int i = 0; i < MAX_FRAMES; i++) {
        check(vkCreateSemaphore(device, si, null, p), "mkS"); semImage[i] = p.get(0);
        check(vkCreateSemaphore(device, si, null, p), "mkS"); semRender[i] = p.get(0);
        check(vkCreateFence(device, fi, null, p), "mkF"); fences[i] = p.get(0);
      }
    }
  }

  /* ---- Frame control ---- */
  public void beginFrame() {
    try { check(vkWaitForFences(device, new long[]{fences[frameIdx]}, true, -1L), "vkWaitFences"); }
    catch (Exception ex) { System.err.println("[VulkanRenderer] Fence wait failed: " + ex.getMessage()); }
  }

  public int acquireNextImage() {
    try (MemoryStack s = stackPush()) {
      IntBuffer p = s.mallocInt(1);
      int r = vkAcquireNextImageKHR(device, swapchain, -1L, semImage[frameIdx], VK_NULL_HANDLE, p);
      if (r == VK_ERROR_OUT_OF_DATE_KHR || r == VK_SUBOPTIMAL_KHR) { recreateSwapchain(); return -1; }
      if (r > 0) return -1;
      check(r, "vkAcquire"); return p.get(0);
    }
  }

  public void recordCommandBuffer(int imgIdx, float[] proj, List<RenderCommand> cmds) {
    vkResetFences(device, new long[]{fences[frameIdx]});
    vkResetCommandBuffer(cmdBufs[frameIdx], 0);
    cmds.sort(Comparator.naturalOrder());
    try (MemoryStack s = stackPush()) {
      check(vkBeginCommandBuffer(cmdBufs[frameIdx], VkCommandBufferBeginInfo.calloc(s).sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)), "vkBeg");

      VkRenderPassBeginInfo rbi = VkRenderPassBeginInfo.calloc(s).sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
          .renderPass(renderPass).framebuffer(swapchainFBs[imgIdx])
          .renderArea(VkRect2D.calloc(s).offset(VkOffset2D.calloc(s)).extent(VkExtent2D.calloc(s).width(swapchainW).height(swapchainH)))
          .pClearValues(VkClearValue.calloc(1, s).color(VkClearColorValue.calloc(s).float32(s.floats(0.05f, 0.05f, 0.08f, 1.0f))));
      vkCmdBeginRenderPass(cmdBufs[frameIdx], rbi, VK_SUBPASS_CONTENTS_INLINE);
      vkCmdBindPipeline(cmdBufs[frameIdx], VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);

      VkViewport.Buffer vp = VkViewport.calloc(1, s).x(0).y(0).width(swapchainW).height(swapchainH).minDepth(0).maxDepth(1);
      vkCmdSetViewport(cmdBufs[frameIdx], 0, vp);
      VkRect2D.Buffer sc = VkRect2D.calloc(1, s).offset(VkOffset2D.calloc(s)).extent(VkExtent2D.calloc(s).width(swapchainW).height(swapchainH));
      vkCmdSetScissor(cmdBufs[frameIdx], 0, sc);

      // UBO
      PointerBuffer mp = s.mallocPointer(1);
      vkMapMemory(device, uboMemArr[frameIdx], 0L, 64L, 0, mp);
      FloatBuffer uboFb = mp.getByteBuffer(0, 64).asFloatBuffer();
      uboFb.put(proj).position(0);
      vkUnmapMemory(device, uboMemArr[frameIdx]);

      vkCmdBindDescriptorSets(cmdBufs[frameIdx], VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, s.longs(descriptorSets[frameIdx]), null);

      // Direct write to host-visible vertex buffer (no staging / no vkQueueWaitIdle)
      int totalVtx = 0, totalIdx = 0;
      for (RenderCommand c : cmds) { totalVtx += c.vertexCount; totalIdx += c.indexCount; }
      int maxVtx = (int)(vBufSize / VTX_SIZE);
      int actualVtx = Math.min(totalVtx, maxVtx);
      int cmdCount = 0;
      if (actualVtx < totalVtx) {
        int v = 0;
        for (RenderCommand c : cmds) {
          if (v + c.vertexCount > maxVtx) break;
          v += c.vertexCount;
          cmdCount++;
        }
        if (cmdCount < cmds.size()) {
          System.err.println("[VulkanRenderer] Vertex buffer overflow: " + (totalVtx * VTX_SIZE) + " > " + vBufSize
              + ", dropping " + (cmds.size() - cmdCount) + "/" + cmds.size() + " commands");
        }
        actualVtx = v;
      } else {
        cmdCount = cmds.size();
      }
      int vtxBytes = actualVtx * VTX_SIZE;

      if (vtxBytes > 0) {
        PointerBuffer vmp = s.mallocPointer(1);
        vkMapMemory(device, vMemHost[frameIdx], 0L, (long) vtxBytes, 0, vmp);
        FloatBuffer fb = vmp.getByteBuffer(0, vtxBytes).asFloatBuffer();
        for (int ci = 0; ci < cmdCount; ci++) {
          RenderCommand c = cmds.get(ci);
          for (float v : c.vertices) fb.put(v);
        }
        vkUnmapMemory(device, vMemHost[frameIdx]);
      }

      LongBuffer offsets = s.longs(0), bufs = s.longs(vBuf[frameIdx]);
      vkCmdBindVertexBuffers(cmdBufs[frameIdx], 0, bufs, offsets);
      vkCmdBindIndexBuffer(cmdBufs[frameIdx], iBuf, 0, VK_INDEX_TYPE_UINT32);

      IntBuffer pc = s.mallocInt(2);
      int vOff = 0, iOff = 0;
      for (int ci = 0; ci < cmdCount; ci++) {
        RenderCommand c = cmds.get(ci);
        pc.put(0, c.textureIndex);
        pc.put(1, c.z);
        vkCmdPushConstants(cmdBufs[frameIdx], pipelineLayout, VK_SHADER_STAGE_FRAGMENT_BIT, 0, pc);
        vkCmdDrawIndexed(cmdBufs[frameIdx], c.indexCount, 1, iOff, vOff, 0);
        vOff += c.vertexCount; iOff += c.indexCount;
      }

      vkCmdEndRenderPass(cmdBufs[frameIdx]);
      check(vkEndCommandBuffer(cmdBufs[frameIdx]), "vkEnd");
    }
  }

  public void submitCommandBuffer(int imgIdx) {
    try (MemoryStack s = stackPush()) {
      VkSubmitInfo si = VkSubmitInfo.calloc(s).sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
          .pWaitSemaphores(s.longs(semImage[frameIdx])).pWaitDstStageMask(s.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
          .pCommandBuffers(s.pointers(cmdBufs[frameIdx].address()))
          .pSignalSemaphores(s.longs(semRender[frameIdx]));
      check(vkQueueSubmit(queue, si, fences[frameIdx]), "vkSubmit");

      VkPresentInfoKHR pi = VkPresentInfoKHR.calloc(s).sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
          .pWaitSemaphores(s.longs(semRender[frameIdx])).pSwapchains(s.longs(swapchain)).pImageIndices(s.ints(imgIdx));
      int r = vkQueuePresentKHR(queue, pi);
      if (r == VK_ERROR_OUT_OF_DATE_KHR || r == VK_SUBOPTIMAL_KHR) recreateSwapchain();
      else check(r, "vkPresent");

      frameIdx = (frameIdx + 1) % MAX_FRAMES;
    }
  }

  /* ---- Recreate swapchain ---- */
  private void recreateSwapchain() {
    try { vkDeviceWaitIdle(device); } catch (Exception ignored) {}
    try (MemoryStack s = stackPush()) {
      long oldSwapchain = this.swapchain;
      // Destroy swapchain FIRST, then dependent views and framebuffers
      if (oldSwapchain != VK_NULL_HANDLE) vkDestroySwapchainKHR(device, oldSwapchain, null);
      for (long v : swapchainViews) if (v != VK_NULL_HANDLE) vkDestroyImageView(device, v, null);
      for (long f : swapchainFBs) if (f != VK_NULL_HANDLE) vkDestroyFramebuffer(device, f, null);

      SwapCaps sc = queryCaps(s, physicalDevice);
      VkSurfaceFormatKHR sf = pickFmt(sc.fmts);
      int pm = pickMode(sc.modes);
      VkExtent2D ext = pickExtent(s, sc.cap);
      int n = sc.cap.minImageCount() + 1;
      if (sc.cap.maxImageCount() > 0 && n > sc.cap.maxImageCount()) n = sc.cap.maxImageCount();

      VkSwapchainCreateInfoKHR ci = VkSwapchainCreateInfoKHR.calloc(s).sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
          .surface(surface).minImageCount(n).imageFormat(sf.format()).imageColorSpace(sf.colorSpace())
          .imageExtent(ext).imageArrayLayers(1).imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
          .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE).preTransform(sc.cap.currentTransform())
          .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR).presentMode(pm).clipped(true).oldSwapchain(oldSwapchain);
      LongBuffer p = s.mallocLong(1); check(vkCreateSwapchainKHR(device, ci, null, p), "reSwap");
      swapchain = p.get(0); swapchainFormat = sf.format();
      swapchainW = ext.width(); swapchainH = ext.height();

      IntBuffer c = s.ints(0);
      vkGetSwapchainImagesKHR(device, swapchain, c, null);
      int imgN = c.get(0);
      LongBuffer imgs = s.mallocLong(imgN);
      vkGetSwapchainImagesKHR(device, swapchain, c, imgs);
      swapchainViews = new long[imgN]; swapchainFBs = new long[imgN];
      for (int i = 0; i < imgN; i++) {
        swapchainViews[i] = mkView(imgs.get(i), swapchainFormat);
        LongBuffer att = s.mallocLong(1); att.put(0, swapchainViews[i]);
        VkFramebufferCreateInfo fbi = VkFramebufferCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO).renderPass(renderPass).pAttachments(att).width(swapchainW).height(swapchainH).layers(1);
        LongBuffer pf = s.mallocLong(1); check(vkCreateFramebuffer(device, fbi, null, pf), "reFB"); swapchainFBs[i] = pf.get(0);
      }
    }
  }

  /* ---- Cleanup ---- */
  @Override
  public void close() {
    try { vkDeviceWaitIdle(device); } catch (Exception ignored) {}
    for (int i = 0; i < texCount; i++) {
      if (texSamplers[i] != VK_NULL_HANDLE) vkDestroySampler(device, texSamplers[i], null);
      if (texViews[i] != VK_NULL_HANDLE) vkDestroyImageView(device, texViews[i], null);
      if (texMems[i] != VK_NULL_HANDLE) vkFreeMemory(device, texMems[i], null);
      if (texImages[i] != VK_NULL_HANDLE) vkDestroyImage(device, texImages[i], null);
    }
    if (pipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, pipeline, null);
    if (pipelineLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(device, pipelineLayout, null);
    if (renderPass != VK_NULL_HANDLE) vkDestroyRenderPass(device, renderPass, null);
    if (swapchain != VK_NULL_HANDLE) vkDestroySwapchainKHR(device, swapchain, null);
    if (swapchainFBs != null) for (long f : swapchainFBs) if (f != VK_NULL_HANDLE) vkDestroyFramebuffer(device, f, null);
    if (swapchainViews != null) for (long v : swapchainViews) if (v != VK_NULL_HANDLE) vkDestroyImageView(device, v, null);
    for (Map.Entry<Long, Long> e : stagingMap.entrySet()) { vkFreeMemory(device, e.getValue(), null); vkDestroyBuffer(device, e.getKey(), null); }
    stagingMap.clear();
    if (descriptorPool != VK_NULL_HANDLE) vkDestroyDescriptorPool(device, descriptorPool, null);
    if (descriptorSetLayout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null);
    if (commandPool != VK_NULL_HANDLE) vkDestroyCommandPool(device, commandPool, null);
    if (vBuf != null) for (long b : vBuf) if (b != VK_NULL_HANDLE) vkDestroyBuffer(device, b, null);
    if (vMemHost != null) for (long m : vMemHost) if (m != VK_NULL_HANDLE) vkFreeMemory(device, m, null);
    if (iBuf != VK_NULL_HANDLE) vkDestroyBuffer(device, iBuf, null);
    if (iMem != VK_NULL_HANDLE) vkFreeMemory(device, iMem, null);
    if (uboBuf != null) { for (long b : uboBuf) if (b != VK_NULL_HANDLE) vkDestroyBuffer(device, b, null); }
    if (uboMemArr != null) { for (long m : uboMemArr) if (m != VK_NULL_HANDLE) vkFreeMemory(device, m, null); }
    if (semImage != null) for (long s : semImage) if (s != VK_NULL_HANDLE) vkDestroySemaphore(device, s, null);
    if (semRender != null) for (long s : semRender) if (s != VK_NULL_HANDLE) vkDestroySemaphore(device, s, null);
    if (fences != null) for (long f : fences) if (f != VK_NULL_HANDLE) vkDestroyFence(device, f, null);
    if (device != null) vkDestroyDevice(device, null);
    if (surface != VK_NULL_HANDLE) vkDestroySurfaceKHR(instance, surface, null);
    if (instance != null) vkDestroyInstance(instance, null);
  }

  private static void check(int r, String msg) { if (r < 0) throw new RuntimeException(msg + ": " + r); }
  private static LongBuffer mkLong(MemoryStack s) { return s.mallocLong(1); }

  private static class SwapCaps { VkSurfaceCapabilitiesKHR cap; VkSurfaceFormatKHR.Buffer fmts; IntBuffer modes; boolean ok() { return cap != null && fmts != null && fmts.remaining() > 0 && modes != null && modes.remaining() > 0; } }

  public static final class RenderCommand implements Comparable<RenderCommand> {
    public final float[] vertices;
    public final int vertexCount;
    public final int indexCount;
    public final int textureIndex;
    public final int z;
    public RenderCommand(float[] v, int ti, int z) {
      this.vertices = v; this.vertexCount = v.length / 8; this.indexCount = (vertexCount / 4) * 6; this.textureIndex = ti; this.z = z;
      if (vertexCount < 1 || vertexCount % 4 != 0) throw new IllegalArgumentException("vertexCount must be a multiple of 4, got " + vertexCount);
    }
    public static RenderCommand texQuad(float x, float y, float w, float h, float u0, float v0, float u1, float v1, int ti, float r, float g, float b, float a, int z) {
      return new RenderCommand(new float[]{x,y+h,u0,v1,r,g,b,a, x+w,y+h,u1,v1,r,g,b,a, x+w,y,u1,v0,r,g,b,a, x,y,u0,v0,r,g,b,a}, ti, z);
    }
    public static RenderCommand rect(float x, float y, float w, float h, float r, float g, float b, float a, int z) {
      return new RenderCommand(new float[]{x,y,0,0,r,g,b,a, x+w,y,0,0,r,g,b,a, x+w,y+h,0,0,r,g,b,a, x,y+h,0,0,r,g,b,a}, -1, z);
    }
    @Override public int compareTo(RenderCommand o) { return Integer.compare(this.z, o.z); }
  }
}
