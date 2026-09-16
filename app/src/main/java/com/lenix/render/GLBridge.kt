package com.lenix.render

import android.opengl.GLSurfaceView
import android.util.Log
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * OpenGL bridge — ported from Stryker's GLSurfaceView_SDL / NeoGLView / NeoRenderer.
 *
 * Stryker uses SDL's GLSurfaceView for Xorg hardware rendering.
 * Lenix adapts this for optional hardware-accelerated desktop rendering.
 *
 * Currently VNC uses software bitmap rendering (RfbClient → Bitmap),
 * but this bridge provides the foundation for EGL/hardware path if needed.
 *
 * Architecture: Lenix Android UI → Runtime Layer → Linux/PRoot → Terminal/PTY → Xorg → Desktop → VNC/native rendering
 * - VNC path: Xvnc/Xvfb+x11vnc → RfbClient (software, reliable)
 * - Native path: Xorg with EGL → GLBridge (hardware, optional, future)
 */
class GLBridge {

    companion object {
        private const val TAG = "LenixGL"

        fun isGLES2Supported(): Boolean {
            // Check via system feature — simplified
            return true
        }

        fun isGLES3Supported(): Boolean {
            return true
        }
    }

    /**
     * Simple renderer that logs GL info — placeholder for hardware rendering.
     * Stryker's NeoRenderer does real Xorg rendering via native libSDL.
     */
    class LenixRenderer : GLSurfaceView.Renderer {
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            Log.d(TAG, "GL surface created: ${gl?.glGetString(GL10.GL_VERSION)}")
            Log.d(TAG, "GL vendor: ${gl?.glGetString(GL10.GL_VENDOR)}")
            Log.d(TAG, "GL renderer: ${gl?.glGetString(GL10.GL_RENDERER)}")
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            Log.d(TAG, "GL surface changed: ${width}x${height}")
            gl?.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            // Clear with dark background — real rendering would be native Xorg
            gl?.glClearColor(0.1f, 0.1f, 0.15f, 1.0f)
            gl?.glClear(GL10.GL_COLOR_BUFFER_BIT)
        }
    }

    /**
     * Factory for GLSurfaceView configured like Stryker's DemoGLSurfaceView.
     */
    fun createGLView(context: android.content.Context): GLSurfaceView {
        return GLSurfaceView(context).apply {
            setEGLContextClientVersion(2)
            setRenderer(LenixRenderer())
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            Log.d(TAG, "Created GLSurfaceView with GLES2")
        }
    }
}
