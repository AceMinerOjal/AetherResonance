package render.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compilation_status_success;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_into_spv;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_initialize;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_release;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_fragment_shader;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_bytes;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_compilation_status;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_error_message;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_release;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_vertex_shader;
import static org.lwjgl.vulkan.VK10.VK_API_VERSION_1_0;
import static org.lwjgl.vulkan.VK10.VK_MAKE_VERSION;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_GRAPHICS_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_APPLICATION_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.vkCreateDevice;
import static org.lwjgl.vulkan.VK10.vkCreateShaderModule;
import static org.lwjgl.vulkan.VK10.vkDestroyDevice;
import static org.lwjgl.vulkan.VK10.vkDestroyInstance;
import static org.lwjgl.vulkan.VK10.vkDestroyShaderModule;
import static org.lwjgl.vulkan.VK10.vkEnumeratePhysicalDevices;
import static org.lwjgl.vulkan.VK10.vkGetDeviceQueue;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceQueueFamilyProperties;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

public final class VulkanShaderInitializer implements AutoCloseable {

  private static final String VERTEX_SHADER_SOURCE = """
      #version 450
      layout(location = 0) out vec3 fragColor;
      vec2 positions[3] = vec2[](
          vec2(0.0, -0.5),
          vec2(0.5, 0.5),
          vec2(-0.5, 0.5)
      );
      vec3 colors[3] = vec3[](
          vec3(0.9, 0.2, 0.2),
          vec3(0.2, 0.9, 0.2),
          vec3(0.2, 0.3, 1.0)
      );
      void main() {
        gl_Position = vec4(positions[gl_VertexIndex], 0.0, 1.0);
        fragColor = colors[gl_VertexIndex];
      }
      """;

  private static final String FRAGMENT_SHADER_SOURCE = """
      #version 450
      layout(location = 0) in vec3 fragColor;
      layout(location = 0) out vec4 outColor;
      void main() {
        outColor = vec4(fragColor, 1.0);
      }
      """;

  private VkInstance instance;
  private VkDevice device;
  private long vertexShaderModule = VK_NULL_HANDLE;
  private long fragmentShaderModule = VK_NULL_HANDLE;

  private VulkanShaderInitializer() {
  }

  public static VulkanShaderInitializer initialize() {
    VulkanShaderInitializer initializer = new VulkanShaderInitializer();
    initializer.init();
    return initializer;
  }

  private void init() {
    if (VK.getFunctionProvider() == null) {
      throw new IllegalStateException("Vulkan loader not available on this system.");
    }

    int supportedVersion = VK.getInstanceVersionSupported();
    if (supportedVersion == 0) {
      throw new IllegalStateException("No Vulkan instance version is reported as supported.");
    }

    try (MemoryStack stack = stackPush()) {
      VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
          .pApplicationName(stack.UTF8("AetherResonance"))
          .applicationVersion(VK_MAKE_VERSION(0, 1, 0))
          .pEngineName(stack.UTF8("AetherResonanceEngine"))
          .engineVersion(VK_MAKE_VERSION(0, 1, 0))
          .apiVersion(Math.min(supportedVersion, VK_API_VERSION_1_0));

      VkInstanceCreateInfo instanceInfo = VkInstanceCreateInfo.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
          .pApplicationInfo(appInfo);

      PointerBuffer pInstance = stack.mallocPointer(1);
      check(VK10.vkCreateInstance(instanceInfo, null, pInstance), "vkCreateInstance");
      instance = new VkInstance(pInstance.get(0), instanceInfo);

      PickedPhysicalDevice picked = pickPhysicalDevice(stack);
      createLogicalDevice(stack, picked.device(), picked.graphicsQueueFamilyIndex());
      createShaderModules(stack);
    } catch (RuntimeException ex) {
      close();
      throw ex;
    }
  }

  private PickedPhysicalDevice pickPhysicalDevice(MemoryStack stack) {
    IntBuffer count = stack.ints(0);
    check(vkEnumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices(count)");
    if (count.get(0) == 0) {
      throw new IllegalStateException("No Vulkan physical devices found.");
    }

    PointerBuffer physicalDevices = stack.mallocPointer(count.get(0));
    check(vkEnumeratePhysicalDevices(instance, count, physicalDevices), "vkEnumeratePhysicalDevices(list)");

    for (int i = 0; i < physicalDevices.capacity(); i++) {
      VkPhysicalDevice device = new VkPhysicalDevice(physicalDevices.get(i), instance);
      int graphicsFamily = findGraphicsQueueFamily(stack, device);
      if (graphicsFamily >= 0) {
        return new PickedPhysicalDevice(device, graphicsFamily);
      }
    }

    throw new IllegalStateException("No suitable physical device with a graphics queue family was found.");
  }

  private int findGraphicsQueueFamily(MemoryStack stack, VkPhysicalDevice physicalDevice) {
    IntBuffer count = stack.ints(0);
    vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, null);

    VkQueueFamilyProperties.Buffer queueFamilies =
        VkQueueFamilyProperties.calloc(count.get(0), stack);
    vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, queueFamilies);

    for (int i = 0; i < queueFamilies.capacity(); i++) {
      if ((queueFamilies.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) {
        return i;
      }
    }
    return -1;
  }

  private void createLogicalDevice(MemoryStack stack, VkPhysicalDevice physicalDevice, int queueFamilyIndex) {
    FloatBuffer priorities = stack.floats(1.0f);

    VkDeviceQueueCreateInfo.Buffer queueInfo = VkDeviceQueueCreateInfo.calloc(1, stack)
        .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
        .queueFamilyIndex(queueFamilyIndex)
        .pQueuePriorities(priorities);

    VkDeviceCreateInfo deviceInfo = VkDeviceCreateInfo.calloc(stack)
        .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
        .pQueueCreateInfos(queueInfo);

    PointerBuffer pDevice = stack.mallocPointer(1);
    check(vkCreateDevice(physicalDevice, deviceInfo, null, pDevice), "vkCreateDevice");
    device = new VkDevice(pDevice.get(0), physicalDevice, deviceInfo);

    PointerBuffer pQueue = stack.mallocPointer(1);
    vkGetDeviceQueue(device, queueFamilyIndex, 0, pQueue);
    if (pQueue.get(0) == NULL) {
      throw new IllegalStateException("Failed to obtain graphics queue.");
    }
  }

  private void createShaderModules(MemoryStack stack) {
    ByteBuffer vertexSpirv = compileShader(VERTEX_SHADER_SOURCE, shaderc_vertex_shader, "default.vert");
    ByteBuffer fragmentSpirv = compileShader(FRAGMENT_SHADER_SOURCE, shaderc_fragment_shader, "default.frag");

    vertexShaderModule = createShaderModule(stack, vertexSpirv, VK_SHADER_STAGE_VERTEX_BIT);
    fragmentShaderModule = createShaderModule(stack, fragmentSpirv, VK_SHADER_STAGE_FRAGMENT_BIT);
  }

  private long createShaderModule(MemoryStack stack, ByteBuffer spirv, int stageFlag) {
    VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
        .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
        .pCode(spirv);

    LongBuffer pModule = stack.mallocLong(1);
    check(vkCreateShaderModule(device, createInfo, null, pModule),
        stageFlag == VK_SHADER_STAGE_VERTEX_BIT ? "vkCreateShaderModule(vertex)" : "vkCreateShaderModule(fragment)");
    return pModule.get(0);
  }

  private ByteBuffer compileShader(String source, int kind, String name) {
    long compiler = shaderc_compiler_initialize();
    if (compiler == NULL) {
      throw new IllegalStateException("Failed to initialize shader compiler.");
    }

    long result = NULL;
    try {
      result = shaderc_compile_into_spv(compiler, source, kind, name, "main", NULL);
      if (result == NULL) {
        throw new IllegalStateException("Shader compilation returned no result for " + name + ".");
      }

      int status = shaderc_result_get_compilation_status(result);
      if (status != shaderc_compilation_status_success) {
        throw new IllegalStateException("Shader compilation failed for " + name + ": "
            + shaderc_result_get_error_message(result));
      }

      ByteBuffer bytes = shaderc_result_get_bytes(result);
      if (bytes == null || !bytes.hasRemaining()) {
        throw new IllegalStateException("Compiled shader bytecode is empty for " + name + ".");
      }
      ByteBuffer copy = ByteBuffer.allocateDirect(bytes.remaining());
      copy.put(bytes);
      copy.flip();
      return copy;
    } finally {
      if (result != NULL) {
        shaderc_result_release(result);
      }
      shaderc_compiler_release(compiler);
    }
  }

  private static void check(int result, String operation) {
    if (result != VK10.VK_SUCCESS) {
      throw new IllegalStateException(operation + " failed with Vulkan error code " + result + ".");
    }
  }

  @Override
  public void close() {
    if (device != null) {
      if (vertexShaderModule != VK_NULL_HANDLE) {
        vkDestroyShaderModule(device, vertexShaderModule, null);
        vertexShaderModule = VK_NULL_HANDLE;
      }
      if (fragmentShaderModule != VK_NULL_HANDLE) {
        vkDestroyShaderModule(device, fragmentShaderModule, null);
        fragmentShaderModule = VK_NULL_HANDLE;
      }
      vkDestroyDevice(device, null);
      device = null;
    }
    if (instance != null) {
      vkDestroyInstance(instance, null);
      instance = null;
    }
  }

  private record PickedPhysicalDevice(VkPhysicalDevice device, int graphicsQueueFamilyIndex) {
  }
}
