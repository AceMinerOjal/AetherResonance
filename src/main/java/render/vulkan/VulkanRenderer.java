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
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

/**
 * Vulkan 2D batch renderer.
 * Single pipeline handles both textured quads (push constant = texture slot >= 0)
 * and solid-color rects (push constant = -1, color from vertex data).
 */
public final class VulkanRenderer implements AutoCloseable {

  private static final int MAX_FRAMES = 2;
  private static final int MAX_TEXTURES = 64;
  private static final int MAX_QUADS = 16384;
  private static final int VTX_SIZE = 32;   // 8 floats per vertex: x,y,u,v,r,g,b,a
  private static final int IDX_PER_QUAD = 6;

  private final VkContext ctx;
  private final SwapchainManager swap;

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

  public VulkanRenderer(long window, int width, int height) {
    boolean enableValidation = "1".equals(System.getenv("VALIDATION_LAYERS"));
    this.ctx = new VkContext(window, width, height, enableValidation);
    this.swap = new SwapchainManager(ctx, width, height);
    init();
  }

  private void init() {
    createRenderPass();
    swap.create(renderPass);
    createDescriptorLayout();
    createPipeline();
    createDefaultTex();
    createBuffers();
    createDescriptorPool();
    createDescriptorSet();
    createCmdBufs();
    createSync();
  }

  /* ---- Render pass ---- */

  private void createRenderPass() {
    try (MemoryStack s = stackPush()) {
      VkAttachmentDescription.Buffer att = VkAttachmentDescription.calloc(1, s)
          .format(swap.format()).samples(VK_SAMPLE_COUNT_1_BIT)
          .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
          .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
          .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
      VkAttachmentReference.Buffer ref = VkAttachmentReference.calloc(1, s)
          .attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
      VkSubpassDescription.Buffer sp = VkSubpassDescription.calloc(1, s)
          .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).pColorAttachments(ref);
      VkSubpassDependency.Buffer dep = VkSubpassDependency.calloc(1, s)
          .srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0)
          .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT).srcAccessMask(0)
          .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
          .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
      VkRenderPassCreateInfo ci = VkRenderPassCreateInfo.calloc(s)
          .sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
          .pAttachments(att).pSubpasses(sp).pDependencies(dep);
      LongBuffer p = s.mallocLong(1);
      VkContext.check(vkCreateRenderPass(ctx.device, ci, null, p), "mkRP");
      renderPass = p.get(0);
    }
  }

  /* ---- Descriptor set layout ---- */

  private void createDescriptorLayout() {
    try (MemoryStack s = stackPush()) {
      VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(2, s);
      b.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
          .descriptorCount(1).stageFlags(VK_SHADER_STAGE_VERTEX_BIT);
      b.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
          .descriptorCount(MAX_TEXTURES).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
      VkDescriptorSetLayoutCreateInfo ci = VkDescriptorSetLayoutCreateInfo.calloc(s)
          .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO).pBindings(b);
      LongBuffer p = s.mallocLong(1);
      VkContext.check(vkCreateDescriptorSetLayout(ctx.device, ci, null, p), "mkDSL");
      descriptorSetLayout = p.get(0);
    }
  }

  /* ---- Pipeline ---- */

  private void createPipeline() {
    try (MemoryStack s = stackPush()) {
      String vsSrc = loadResource("shaders/sprite.vert");
      String fsSrc = loadResource("shaders/sprite.frag");
      long vs = mkShader(s, compile("vs", shaderc_vertex_shader, vsSrc));
      long fs = mkShader(s, compile("fs", shaderc_fragment_shader, fsSrc));

      VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, s);
      stages.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
          .stage(VK_SHADER_STAGE_VERTEX_BIT).module(vs).pName(s.UTF8("main"));
      stages.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
          .stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fs).pName(s.UTF8("main"));

      VkVertexInputBindingDescription.Buffer vb = VkVertexInputBindingDescription.calloc(1, s)
          .binding(0).stride(VTX_SIZE).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
      VkVertexInputAttributeDescription.Buffer va = VkVertexInputAttributeDescription.calloc(3, s);
      va.get(0).binding(0).location(0).format(VK_FORMAT_R32G32_SFLOAT).offset(0);
      va.get(1).binding(0).location(1).format(VK_FORMAT_R32G32_SFLOAT).offset(8);
      va.get(2).binding(0).location(2).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(16);

      VkPipelineVertexInputStateCreateInfo vi = VkPipelineVertexInputStateCreateInfo.calloc(s)
          .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
          .pVertexBindingDescriptions(vb).pVertexAttributeDescriptions(va);
      VkPipelineInputAssemblyStateCreateInfo ia = VkPipelineInputAssemblyStateCreateInfo.calloc(s)
          .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
          .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST).primitiveRestartEnable(false);
      VkPipelineViewportStateCreateInfo vp = VkPipelineViewportStateCreateInfo.calloc(s)
          .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
          .viewportCount(1).scissorCount(1);
      VkPipelineRasterizationStateCreateInfo rs = VkPipelineRasterizationStateCreateInfo.calloc(s)
          .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
          .polygonMode(VK_POLYGON_MODE_FILL).lineWidth(1f)
          .cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE);
      VkPipelineMultisampleStateCreateInfo ms = VkPipelineMultisampleStateCreateInfo.calloc(s)
          .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
          .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
      VkPipelineColorBlendAttachmentState.Buffer ba = VkPipelineColorBlendAttachmentState.calloc(1, s)
          .colorWriteMask(0xF).blendEnable(true)
          .srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA)
          .dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA).colorBlendOp(VK_BLEND_OP_ADD)
          .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE).dstAlphaBlendFactor(VK_BLEND_FACTOR_ZERO)
          .alphaBlendOp(VK_BLEND_OP_ADD);
      VkPipelineColorBlendStateCreateInfo cb = VkPipelineColorBlendStateCreateInfo.calloc(s)
          .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO).pAttachments(ba);
      IntBuffer dyn = s.mallocInt(2);
      dyn.put(0, VK_DYNAMIC_STATE_VIEWPORT).put(1, VK_DYNAMIC_STATE_SCISSOR);
      VkPipelineDynamicStateCreateInfo ds = VkPipelineDynamicStateCreateInfo.calloc(s)
          .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO).pDynamicStates(dyn);
      VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, s)
          .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT | VK_SHADER_STAGE_VERTEX_BIT)
          .offset(0).size(8);
      LongBuffer dsl = s.mallocLong(1);
      dsl.put(0, descriptorSetLayout);
      VkPipelineLayoutCreateInfo pl = VkPipelineLayoutCreateInfo.calloc(s)
          .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
          .pSetLayouts(dsl).pPushConstantRanges(pcr);
      LongBuffer ppl = s.mallocLong(1);
      VkContext.check(vkCreatePipelineLayout(ctx.device, pl, null, ppl), "mkPL");
      pipelineLayout = ppl.get(0);

      VkGraphicsPipelineCreateInfo.Buffer gp = VkGraphicsPipelineCreateInfo.calloc(1, s)
          .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
          .pStages(stages).pVertexInputState(vi).pInputAssemblyState(ia).pViewportState(vp)
          .pRasterizationState(rs).pMultisampleState(ms).pColorBlendState(cb).pDynamicState(ds)
          .layout(pipelineLayout).renderPass(renderPass).subpass(0);
      LongBuffer pp = s.mallocLong(1);
      VkContext.check(vkCreateGraphicsPipelines(ctx.device, VK_NULL_HANDLE, gp, null, pp), "mkPipe");
      pipeline = pp.get(0);

      vkDestroyShaderModule(ctx.device, fs, null);
      vkDestroyShaderModule(ctx.device, vs, null);
    }
  }

  private ByteBuffer compile(String tag, int kind, String src) {
    long c = shaderc_compiler_initialize(), o = shaderc_compile_options_initialize();
    long r = shaderc_compile_into_spv(c, src, kind, tag, "main", o);
    if (shaderc_result_get_compilation_status(r) != 0)
      throw new RuntimeException(tag + ": " + shaderc_result_get_error_message(r));
    long len = shaderc_result_get_length(r);
    ByteBuffer out = BufferUtils.createByteBuffer((int) len);
    out.put(shaderc_result_get_bytes(r)).rewind();
    shaderc_result_release(r);
    shaderc_compile_options_release(o);
    shaderc_compiler_release(c);
    return out;
  }

  static String loadResource(String path) {
    try (var in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
      if (in == null) throw new RuntimeException("Shader resource not found: " + path);
      return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new RuntimeException("Failed to load shader: " + path, e);
    }
  }

  private long mkShader(MemoryStack s, ByteBuffer code) {
    VkShaderModuleCreateInfo ci = VkShaderModuleCreateInfo.calloc(s)
        .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO).pCode(code);
    LongBuffer p = s.mallocLong(1);
    VkContext.check(vkCreateShaderModule(ctx.device, ci, null, p), "mkShd");
    return p.get(0);
  }

  /* ---- Default texture ---- */

  private void createDefaultTex() {
    Arrays.fill(texImages, VK_NULL_HANDLE);
    Arrays.fill(texMems, VK_NULL_HANDLE);
    Arrays.fill(texViews, VK_NULL_HANDLE);
    Arrays.fill(texSamplers, VK_NULL_HANDLE);
    try (MemoryStack s = stackPush()) {
      long sb = ctx.mkStagingBuf(s, 4);
      { PointerBuffer mp = s.mallocPointer(1); vkMapMemory(ctx.device, ctx.sbMem(sb), 0L, 4L, 0, mp); MemoryUtil.memPutInt(mp.get(0), 0xFFFFFFFF); vkUnmapMemory(ctx.device, ctx.sbMem(sb)); }
      long img = ctx.mkImg(s, 1, 1); long mem = ctx.mkImgMem(s, img);
      vkBindImageMemory(ctx.device, img, mem, 0);
      ctx.transLayout(s, img, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
      ctx.copyStagingImg(s, sb, img, 1, 1);
      ctx.transLayout(s, img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
      ctx.destroyStagingBuf(s, sb);
      texImages[0] = img; texMems[0] = mem;
      texViews[0] = ctx.mkView(img, VK_FORMAT_R8G8B8A8_SRGB);
      texSamplers[0] = ctx.mkSampler();
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
        vBuf[i] = ctx.mkBuf(s, vSize, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
        VkMemoryRequirements vmr = VkMemoryRequirements.malloc(s); vkGetBufferMemoryRequirements(ctx.device, vBuf[i], vmr);
        vMemHost[i] = ctx.mkHostMem(s, vmr.size(), vmr.memoryTypeBits()); vkBindBufferMemory(ctx.device, vBuf[i], vMemHost[i], 0);
      }

      iBuf = ctx.mkBuf(s, iSize, VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
      iMem = ctx.mkDevMem(s, iBuf); vkBindBufferMemory(ctx.device, iBuf, iMem, 0);

      // Upload indices once via staging
      long sb = ctx.mkStagingBuf(s, iSize);
      { PointerBuffer mp = s.mallocPointer(1); vkMapMemory(ctx.device, ctx.sbMem(sb), 0L, iSize, 0, mp);
        ByteBuffer mapped = mp.getByteBuffer(0, (int) iSize); IntBuffer idx = mapped.asIntBuffer();
        int vo = 0; for (int j = 0; j < MAX_QUADS; j++) { idx.put(vo).put(vo+1).put(vo+2).put(vo).put(vo+2).put(vo+3); vo += 4; }
        vkUnmapMemory(ctx.device, ctx.sbMem(sb)); }
      ctx.copyBufDev(s, sb, iBuf, iSize);
      ctx.destroyStagingBuf(s, sb);

      for (int i = 0; i < MAX_FRAMES; i++) {
        uboBuf[i] = ctx.mkBuf(s, 64, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
        VkMemoryRequirements umr = VkMemoryRequirements.malloc(s); vkGetBufferMemoryRequirements(ctx.device, uboBuf[i], umr);
        uboMemArr[i] = ctx.mkHostMem(s, umr.size(), umr.memoryTypeBits()); vkBindBufferMemory(ctx.device, uboBuf[i], uboMemArr[i], 0);
      }
    }
  }

  /* ---- Descriptor pool + set ---- */

  private void createDescriptorPool() {
    try (MemoryStack s = stackPush()) {
      VkDescriptorPoolSize.Buffer ps = VkDescriptorPoolSize.calloc(2, s);
      ps.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(MAX_FRAMES);
      ps.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(MAX_TEXTURES * MAX_FRAMES);
      VkDescriptorPoolCreateInfo ci = VkDescriptorPoolCreateInfo.calloc(s)
          .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO).pPoolSizes(ps).maxSets(MAX_FRAMES);
      LongBuffer p = VkContext.mkLong(s);
      VkContext.check(vkCreateDescriptorPool(ctx.device, ci, null, p), "mkDP");
      descriptorPool = p.get(0);
    }
  }

  private void createDescriptorSet() {
    try (MemoryStack s = stackPush()) {
      LongBuffer layouts = s.mallocLong(MAX_FRAMES);
      for (int i = 0; i < MAX_FRAMES; i++) layouts.put(i, descriptorSetLayout);
      VkDescriptorSetAllocateInfo ai = VkDescriptorSetAllocateInfo.calloc(s)
          .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
          .descriptorPool(descriptorPool).pSetLayouts(layouts);
      LongBuffer p = s.mallocLong(MAX_FRAMES);
      VkContext.check(vkAllocateDescriptorSets(ctx.device, ai, p), "mkDS");
      for (int i = 0; i < MAX_FRAMES; i++) descriptorSets[i] = p.get(i);

      for (int i = 0; i < MAX_FRAMES; i++) {
        VkDescriptorBufferInfo.Buffer bi = VkDescriptorBufferInfo.calloc(1, s)
            .buffer(uboBuf[i]).offset(0).range(64);
        VkDescriptorImageInfo.Buffer ii = VkDescriptorImageInfo.calloc(MAX_TEXTURES, s);
        for (int j = 0; j < MAX_TEXTURES; j++)
          ii.get(j).sampler(texSamplers[0]).imageView(texViews[0])
              .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

        VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(2, s);
        w.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
            .dstSet(descriptorSets[i]).dstBinding(0).dstArrayElement(0)
            .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).pBufferInfo(bi);
        w.get(1).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
            .dstSet(descriptorSets[i]).dstBinding(1).dstArrayElement(0)
            .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            .descriptorCount(MAX_TEXTURES).pImageInfo(ii);
        vkUpdateDescriptorSets(ctx.device, w, null);
      }
    }
  }

  private void updateTexDS(int slot, long sam, long view) {
    vkDeviceWaitIdle(ctx.device);
    try (MemoryStack s = stackPush()) {
      VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, s);
      info.get(0).sampler(sam).imageView(view).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
      for (int i = 0; i < MAX_FRAMES; i++) {
        VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(1, s);
        w.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
            .dstSet(descriptorSets[i]).dstBinding(1).dstArrayElement(slot)
            .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            .descriptorCount(1).pImageInfo(info);
        vkUpdateDescriptorSets(ctx.device, w, null);
      }
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
      for (int p : px) {
        buf.put((byte)((p>>16)&0xFF)); buf.put((byte)((p>>8)&0xFF));
        buf.put((byte)(p&0xFF)); buf.put((byte)((p>>24)&0xFF));
      }
      buf.rewind();

      long img = ctx.mkImg(s, w, h), mem = ctx.mkImgMem(s, img);
      vkBindImageMemory(ctx.device, img, mem, 0);
      ctx.transLayout(s, img, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

      long sb = ctx.mkStagingBuf(s, size);
      try {
        { PointerBuffer mp = s.mallocPointer(1); vkMapMemory(ctx.device, ctx.sbMem(sb), 0L, (long) size, 0, mp); MemoryUtil.memCopy(buf, mp.getByteBuffer(0, size)); vkUnmapMemory(ctx.device, ctx.sbMem(sb)); }
        ctx.copyStagingImg(s, sb, img, w, h);
      } finally {
        ctx.destroyStagingBuf(s, sb);
      }

      ctx.transLayout(s, img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
      long view = ctx.mkView(img, VK_FORMAT_R8G8B8A8_SRGB);
      long sam = ctx.mkSampler();

      int slot = texCount;
      texImages[slot] = img; texMems[slot] = mem; texViews[slot] = view; texSamplers[slot] = sam;
      texCount++;
      updateTexDS(slot, sam, view);
      return slot;
    }
  }

  /* ---- Command buffers ---- */

  private void createCmdBufs() {
    try (MemoryStack s = stackPush()) {
      VkCommandBufferAllocateInfo ai = VkCommandBufferAllocateInfo.calloc(s)
          .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
          .commandPool(ctx.commandPool)
          .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
          .commandBufferCount(MAX_FRAMES);
      PointerBuffer p = s.mallocPointer(MAX_FRAMES);
      VkContext.check(vkAllocateCommandBuffers(ctx.device, ai, p), "mkCBs");
      cmdBufs = new VkCommandBuffer[MAX_FRAMES];
      for (int i = 0; i < MAX_FRAMES; i++)
        cmdBufs[i] = new VkCommandBuffer(p.get(i), ctx.device);
    }
  }

  /* ---- Sync ---- */

  private void createSync() {
    try (MemoryStack s = stackPush()) {
      VkSemaphoreCreateInfo si = VkSemaphoreCreateInfo.calloc(s)
          .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
      VkFenceCreateInfo fi = VkFenceCreateInfo.calloc(s)
          .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO).flags(VK_FENCE_CREATE_SIGNALED_BIT);
      LongBuffer p = s.mallocLong(1);
      semImage = new long[MAX_FRAMES]; semRender = new long[MAX_FRAMES]; fences = new long[MAX_FRAMES];
      for (int i = 0; i < MAX_FRAMES; i++) {
        VkContext.check(vkCreateSemaphore(ctx.device, si, null, p), "mkS"); semImage[i] = p.get(0);
        VkContext.check(vkCreateSemaphore(ctx.device, si, null, p), "mkS"); semRender[i] = p.get(0);
        VkContext.check(vkCreateFence(ctx.device, fi, null, p), "mkF"); fences[i] = p.get(0);
      }
    }
  }

  /* ---- Frame control ---- */

  public void beginFrame() {
    VkContext.check(vkWaitForFences(ctx.device, new long[]{fences[frameIdx]}, true, -1L), "vkWaitFences");
  }

  public int acquireNextImage() {
    try (MemoryStack s = stackPush()) {
      IntBuffer p = s.mallocInt(1);
      int r = vkAcquireNextImageKHR(ctx.device, swap.getSwapchain(), -1L, semImage[frameIdx], VK_NULL_HANDLE, p);
      if (r == VK_ERROR_OUT_OF_DATE_KHR || r == VK_SUBOPTIMAL_KHR) {
        swap.recreate(renderPass);
        return -1;
      }
      if (r > 0) return -1;
      VkContext.check(r, "vkAcquire");
      return p.get(0);
    }
  }

  public void recordCommandBuffer(int imgIdx, float[] proj, List<RenderCommand> cmds) {
    vkResetFences(ctx.device, new long[]{fences[frameIdx]});
    vkResetCommandBuffer(cmdBufs[frameIdx], 0);
    cmds.sort(Comparator.naturalOrder());
    try (MemoryStack s = stackPush()) {
      VkContext.check(vkBeginCommandBuffer(cmdBufs[frameIdx],
          VkCommandBufferBeginInfo.calloc(s).sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)), "vkBeg");

      VkRenderPassBeginInfo rbi = VkRenderPassBeginInfo.calloc(s)
          .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
          .renderPass(renderPass).framebuffer(swap.framebuffer(imgIdx))
          .renderArea(VkRect2D.calloc(s).offset(VkOffset2D.calloc(s))
              .extent(VkExtent2D.calloc(s).width(swap.width()).height(swap.height())))
          .pClearValues(VkClearValue.calloc(1, s)
              .color(VkClearColorValue.calloc(s).float32(s.floats(0.05f, 0.05f, 0.08f, 1.0f))));
      vkCmdBeginRenderPass(cmdBufs[frameIdx], rbi, VK_SUBPASS_CONTENTS_INLINE);
      vkCmdBindPipeline(cmdBufs[frameIdx], VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);

      VkViewport.Buffer vp = VkViewport.calloc(1, s)
          .x(0).y(0).width(swap.width()).height(swap.height()).minDepth(0).maxDepth(1);
      vkCmdSetViewport(cmdBufs[frameIdx], 0, vp);
      VkRect2D.Buffer sc = VkRect2D.calloc(1, s)
          .offset(VkOffset2D.calloc(s))
          .extent(VkExtent2D.calloc(s).width(swap.width()).height(swap.height()));
      vkCmdSetScissor(cmdBufs[frameIdx], 0, sc);

      // UBO
      PointerBuffer mp = s.mallocPointer(1);
      vkMapMemory(ctx.device, uboMemArr[frameIdx], 0L, 64L, 0, mp);
      FloatBuffer uboFb = mp.getByteBuffer(0, 64).asFloatBuffer();
      uboFb.put(proj).position(0);
      vkUnmapMemory(ctx.device, uboMemArr[frameIdx]);

      vkCmdBindDescriptorSets(cmdBufs[frameIdx], VK_PIPELINE_BIND_POINT_GRAPHICS,
          pipelineLayout, 0, s.longs(descriptorSets[frameIdx]), null);

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
        vkMapMemory(ctx.device, vMemHost[frameIdx], 0L, (long) vtxBytes, 0, vmp);
        FloatBuffer fb = vmp.getByteBuffer(0, vtxBytes).asFloatBuffer();
        for (int ci = 0; ci < cmdCount; ci++) {
          RenderCommand c = cmds.get(ci);
          for (float v : c.vertices) fb.put(v);
        }
        vkUnmapMemory(ctx.device, vMemHost[frameIdx]);
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
      VkContext.check(vkEndCommandBuffer(cmdBufs[frameIdx]), "vkEnd");
    }
  }

  public void submitCommandBuffer(int imgIdx) {
    try (MemoryStack s = stackPush()) {
      VkSubmitInfo si = VkSubmitInfo.calloc(s)
          .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
          .pWaitSemaphores(s.longs(semImage[frameIdx]))
          .pWaitDstStageMask(s.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
          .pCommandBuffers(s.pointers(cmdBufs[frameIdx].address()))
          .pSignalSemaphores(s.longs(semRender[frameIdx]));
      VkContext.check(vkQueueSubmit(ctx.queue, si, fences[frameIdx]), "vkSubmit");

      VkPresentInfoKHR pi = VkPresentInfoKHR.calloc(s)
          .sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
          .pWaitSemaphores(s.longs(semRender[frameIdx]))
          .pSwapchains(s.longs(swap.getSwapchain()))
          .pImageIndices(s.ints(imgIdx));
      int r = vkQueuePresentKHR(ctx.queue, pi);
      if (r == VK_ERROR_OUT_OF_DATE_KHR || r == VK_SUBOPTIMAL_KHR) swap.recreate(renderPass);
      else VkContext.check(r, "vkPresent");

      frameIdx = (frameIdx + 1) % MAX_FRAMES;
    }
  }

  /* ---- Cleanup ---- */

  @Override
  public void close() {
    try { vkDeviceWaitIdle(ctx.device); } catch (Exception ignored) {}
    for (int i = 0; i < texCount; i++) {
      if (texSamplers[i] != VK_NULL_HANDLE) vkDestroySampler(ctx.device, texSamplers[i], null);
      if (texViews[i] != VK_NULL_HANDLE) vkDestroyImageView(ctx.device, texViews[i], null);
      if (texMems[i] != VK_NULL_HANDLE) vkFreeMemory(ctx.device, texMems[i], null);
      if (texImages[i] != VK_NULL_HANDLE) vkDestroyImage(ctx.device, texImages[i], null);
    }
    if (pipeline != VK_NULL_HANDLE) vkDestroyPipeline(ctx.device, pipeline, null);
    if (pipelineLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(ctx.device, pipelineLayout, null);
    if (renderPass != VK_NULL_HANDLE) vkDestroyRenderPass(ctx.device, renderPass, null);
    if (descriptorPool != VK_NULL_HANDLE) vkDestroyDescriptorPool(ctx.device, descriptorPool, null);
    if (descriptorSetLayout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(ctx.device, descriptorSetLayout, null);
    if (vBuf != null) for (long b : vBuf) if (b != VK_NULL_HANDLE) vkDestroyBuffer(ctx.device, b, null);
    if (vMemHost != null) for (long m : vMemHost) if (m != VK_NULL_HANDLE) vkFreeMemory(ctx.device, m, null);
    if (iBuf != VK_NULL_HANDLE) vkDestroyBuffer(ctx.device, iBuf, null);
    if (iMem != VK_NULL_HANDLE) vkFreeMemory(ctx.device, iMem, null);
    if (uboBuf != null) { for (long b : uboBuf) if (b != VK_NULL_HANDLE) vkDestroyBuffer(ctx.device, b, null); }
    if (uboMemArr != null) { for (long m : uboMemArr) if (m != VK_NULL_HANDLE) vkFreeMemory(ctx.device, m, null); }
    if (semImage != null) for (long s : semImage) if (s != VK_NULL_HANDLE) vkDestroySemaphore(ctx.device, s, null);
    if (semRender != null) for (long s : semRender) if (s != VK_NULL_HANDLE) vkDestroySemaphore(ctx.device, s, null);
    if (fences != null) for (long f : fences) if (f != VK_NULL_HANDLE) vkDestroyFence(ctx.device, f, null);
    swap.close();
    ctx.close();
  }
}
