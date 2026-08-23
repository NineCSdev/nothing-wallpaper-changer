package com.ninecsdev.wallpaperchanger.service.atmosphere

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * The GL plumbing shared by every pass: program building, texture and FBO allocation, and the
 * full-screen quad.
 *
 * Nothing here decides what the effect looks like. The one place a decision is embedded is
 * [createFboTexture] / [uploadPhoto]'s filter and wrap state, which is load-bearing and is
 * documented there.
 */
internal object AtmosphereGl {

    private const val TAG = "AtmosphereGl"

    /**
     * NDC position + texture coordinate, six vertices, two triangles.
     *
     * The v axis maps to NDC y as an **identity** (y = -1 -> v = 0). That makes every
     * render-to-texture round-trip orientation-neutral, which is what keeps a two-pass blur from
     * silently mirroring its input. The photo, the only texture that arrives from a Bitmap, is
     * flipped once inside the composite shader instead.
     */
    private val QUAD = floatArrayOf(
        // x, y, u, v
        -1f, -1f, 0f, 0f,
        1f, -1f, 1f, 0f,
        -1f, 1f, 0f, 1f,
        -1f, 1f, 0f, 1f,
        1f, -1f, 1f, 0f,
        1f, 1f, 1f, 1f
    )

    private const val FLOATS_PER_VERTEX = 4
    private const val QUAD_VERTEX_COUNT = 6
    private const val BYTES_PER_FLOAT = 4

    // Programs

    fun readAsset(context: Context, path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }

    /** Returns 0 on any compile or link failure, having logged the driver's own message. */
    fun buildProgram(vertexSource: String, fragmentSource: String, label: String): Int {
        val vertex = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource, "$label.vert")
        if (vertex == 0) return 0
        val fragment = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource, "$label.frag")
        if (fragment == 0) {
            GLES30.glDeleteShader(vertex)
            return 0
        }

        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertex)
        GLES30.glAttachShader(program, fragment)
        GLES30.glLinkProgram(program)

        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)

        // Attached shaders are reference-counted by the program; deleting them here is correct
        // and frees the compiler's copies immediately.
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)

        if (status[0] == 0) {
            Log.e(TAG, "Link failed for $label: ${GLES30.glGetProgramInfoLog(program)}")
            GLES30.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun compileShader(type: Int, source: String, label: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)

        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Compile failed for $label: ${GLES30.glGetShaderInfoLog(shader)}")
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    // Textures and framebuffers

    /**
     * A color attachment for one of the offscreen passes.
     *
     * Plain `GL_LINEAR`, no mipmaps. The magnify from the 72px raster to the panel is bilinear,
     * and that upscale is a real part of how five flat polygons end up reading as gradients --
     * not an implementation detail to optimize.
     */
    fun createFboTexture(width: Int, height: Int): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA,
            width, height, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    fun createFramebuffer(): Int {
        val ids = IntArray(1)
        GLES30.glGenFramebuffers(1, ids, 0)
        return ids[0]
    }

    fun bindFramebuffer(fbo: Int, texture: Int, width: Int, height: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, texture, 0
        )
        GLES30.glViewport(0, 0, width, height)
    }

    /**
     * Uploads the source photo and builds its mip chain.
     *
     * The mipmaps are **not** optional. The composite pass renders into a 72-pixel-wide target,
     * so a trilinear sample lands several mip levels down; without them the photo is point-sampled
     * to a fraction of its size and aliases in a way the effect never should.
     */
    fun uploadPhoto(bitmap: Bitmap): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    fun deleteTexture(id: Int) {
        if (id != 0) GLES30.glDeleteTextures(1, intArrayOf(id), 0)
    }

    fun bindTextureUnit0(texture: Int, samplerLocation: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(samplerLocation, 0)
    }

    // The full-screen quad

    /**
     * The quad every full-screen pass draws.
     *
     * An owned object rather than a shared one, because GL names are only meaningful inside the
     * context that produced them and there can be more than one context alive at a time: the
     * live-wallpaper picker runs a preview engine alongside the real one. A single shared name
     * would have each engine drawing with the other's buffer.
     */
    class FullScreenQuad {
        private var vbo = 0

        fun create() {
            val buffer: FloatBuffer = ByteBuffer
                .allocateDirect(QUAD.size * BYTES_PER_FLOAT)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(QUAD)
            buffer.position(0)

            val ids = IntArray(1)
            GLES30.glGenBuffers(1, ids, 0)
            vbo = ids[0]
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            GLES30.glBufferData(
                GLES30.GL_ARRAY_BUFFER, QUAD.size * BYTES_PER_FLOAT, buffer, GLES30.GL_STATIC_DRAW
            )
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        }

        fun draw() {
            if (vbo == 0) return
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            val stride = FLOATS_PER_VERTEX * BYTES_PER_FLOAT
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, stride, 0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, 2 * BYTES_PER_FLOAT)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, QUAD_VERTEX_COUNT)
            GLES30.glDisableVertexAttribArray(0)
            GLES30.glDisableVertexAttribArray(1)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        }

        fun release() {
            if (vbo != 0) {
                GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
                vbo = 0
            }
        }
    }

    fun newFloatBuffer(capacity: Int): FloatBuffer = ByteBuffer
        .allocateDirect(capacity * BYTES_PER_FLOAT)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
}
