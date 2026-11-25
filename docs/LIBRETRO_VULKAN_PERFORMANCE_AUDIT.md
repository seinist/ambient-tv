# Vulkan gaming performance: verified first pass

## Shipped changes
* Native SDR filtered->filtered preset switches retain the same compatible
  color-attachment Vulkan swapchain, images and semaphores. The device still
  becomes idle before shader history is freed. The previous presentation
  success is invalidated until the new filter successfully presents.
* Raw<->filtered, device/surface/transfer changes and error paths retain
  the previous safe swapchain re-creation rules.
* The final Slang render pass already clears the whole target. The Vulkan
  transfer clear is skipped only for native filtered frames. Raw output still
  clears its letterbox bars to opaque black. Failed shader output is not
  presented; the established GLES fallback remains available.

## Pinned Slang runtime resource reuse
The Slang Vulkan runtime previously created an extra attachment ImageView for
each intermediate output on each frame. An intermediate OwnedImage with exactly
one mip level already has a matching 2D view. Such passes now borrow the owned
view, skipping the redundant Vulkan allocation and subsequent destruction.
Multi-mip images retain the separate one-level view, preserving Vulkan
framebuffer compatibility. The existing OwnedImage lifetime is unchanged and
the final swapchain image view is still frame-scoped. This is a bounded
allocation optimization, not an unsafe cache of stale image handles.

## Audit requirements
Production Vulkan methods are compiled and executed in the driver-fault
harness. Tests verify filtered swapchain reuse, queue-idle before shader
release, invalidation of native frame success, transition to raw with fresh
image usage, and absence of redundant filtered clear. All existing GLES/HDR,
native Vulkan, Slang GPU pixels, forced allocation failures and teardown
validation must remain green before merging.

## Important boundaries
This does **not** promise faster FPS on a particular device. Full GLES/Vulkan
frame handoff elimination, glFinish removal, async frame slots, Slang
ImageView/Framebuffer caching and replacing the GLSurfaceView EGL swap still
require owner-tracked Android AHardwareBuffer and SurfaceFlinger synchronization,
TV timing traces and device testing. An unproven fence completion must never
cause an in-flight Vulkan resource to be reused or freed.
