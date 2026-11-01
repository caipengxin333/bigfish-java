/** The R4 clipping manager does not explicitly delete its mask texture/framebuffer.
 * Track allocations on this renderer's private context, without patching SDK source
 * or the browser prototype, and release any leftovers after SDK/model cleanup. */
export function trackGraphicsResources(gl: WebGLRenderingContext): () => void {
  const textures = new Set<WebGLTexture>(), buffers = new Set<WebGLFramebuffer>()
  const createTexture = gl.createTexture, deleteTexture = gl.deleteTexture
  const createFramebuffer = gl.createFramebuffer, deleteFramebuffer = gl.deleteFramebuffer
  gl.createTexture = () => { const value = createTexture.call(gl); if (value) textures.add(value); return value }
  gl.deleteTexture = value => { if (value) textures.delete(value); deleteTexture.call(gl, value) }
  gl.createFramebuffer = () => { const value = createFramebuffer.call(gl); if (value) buffers.add(value); return value }
  gl.deleteFramebuffer = value => { if (value) buffers.delete(value); deleteFramebuffer.call(gl, value) }
  let released = false
  return () => {
    if (released) return
    released = true
    textures.forEach(value => deleteTexture.call(gl, value)); textures.clear()
    buffers.forEach(value => deleteFramebuffer.call(gl, value)); buffers.clear()
    gl.createTexture = createTexture; gl.deleteTexture = deleteTexture
    gl.createFramebuffer = createFramebuffer; gl.deleteFramebuffer = deleteFramebuffer
  }
}
