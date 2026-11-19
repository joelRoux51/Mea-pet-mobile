package com.meapet.mobile.live2d

import android.opengl.GLES20
import android.util.Log

/**
 * OpenGL ES 2.0 3D shader for layered volumetric sprite avatars.
 *
 * Expects premultiplied-alpha PNG textures (`inPremultiplied = true`, blended
 * with `GL_ONE, GL_ONE_MINUS_SRC_ALPHA`) and modulates diffuse shading, subtle
 * specular highlight, and Fresnel rim lighting while preserving premultiplied alpha.
 */
class Sprite3dShader : AutoCloseable {

    val programId: Int
    val positionLoc: Int
    val texCoordLoc: Int
    val normalLoc: Int
    val mvpMatrixLoc: Int
    val modelMatrixLoc: Int
    val textureLoc: Int
    val baseColorLoc: Int
    val lightDirLoc: Int
    val rimStrengthLoc: Int
    val shadowModeLoc: Int

    val isValid: Boolean
        get() = programId != 0 && positionLoc >= 0 && texCoordLoc >= 0 && normalLoc >= 0 && mvpMatrixLoc >= 0

    init {
        programId = createProgram()
        if (programId != 0) {
            positionLoc = GLES20.glGetAttribLocation(programId, "aPosition")
            texCoordLoc = GLES20.glGetAttribLocation(programId, "aTexCoord")
            normalLoc = GLES20.glGetAttribLocation(programId, "aNormal")
            mvpMatrixLoc = GLES20.glGetUniformLocation(programId, "uMvpMatrix")
            modelMatrixLoc = GLES20.glGetUniformLocation(programId, "uModelMatrix")
            textureLoc = GLES20.glGetUniformLocation(programId, "uTexture")
            baseColorLoc = GLES20.glGetUniformLocation(programId, "uBaseColor")
            lightDirLoc = GLES20.glGetUniformLocation(programId, "uLightDir")
            rimStrengthLoc = GLES20.glGetUniformLocation(programId, "uRimStrength")
            shadowModeLoc = GLES20.glGetUniformLocation(programId, "uShadowMode")
        } else {
            positionLoc = -1
            texCoordLoc = -1
            normalLoc = -1
            mvpMatrixLoc = -1
            modelMatrixLoc = -1
            textureLoc = -1
            baseColorLoc = -1
            lightDirLoc = -1
            rimStrengthLoc = -1
            shadowModeLoc = -1
        }
    }

    override fun close() {
        if (programId != 0) {
            GLES20.glDeleteProgram(programId)
        }
    }

    private fun createProgram(): Int {
        val vs = compileShader(VERTEX_SHADER, GLES20.GL_VERTEX_SHADER, "vertex")
        if (vs == 0) return 0
        val fs = compileShader(FRAGMENT_SHADER, GLES20.GL_FRAGMENT_SHADER, "fragment")
        if (fs == 0) {
            GLES20.glDeleteShader(vs)
            return 0
        }
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linked, 0)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        if (linked[0] == 0) {
            Log.e(TAG, "Program link error: ${GLES20.glGetProgramInfoLog(prog)}")
            GLES20.glDeleteProgram(prog)
            return 0
        }
        return prog
    }

    private fun compileShader(source: String, type: Int, label: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, source)
        GLES20.glCompileShader(id)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(TAG, "Shader compile error [$label]: ${GLES20.glGetShaderInfoLog(id)}")
            GLES20.glDeleteShader(id)
            return 0
        }
        return id
    }

    companion object {
        private const val TAG = "Sprite3dShader"

        private val VERTEX_SHADER = """
            #version 100
            attribute vec3 aPosition;
            attribute vec2 aTexCoord;
            attribute vec3 aNormal;
            uniform mat4 uMvpMatrix;
            uniform mat4 uModelMatrix;
            varying vec2 vTexCoord;
            varying vec3 vWorldNormal;
            void main(void) {
                gl_Position = uMvpMatrix * vec4(aPosition, 1.0);
                vTexCoord = aTexCoord;
                vWorldNormal = normalize((uModelMatrix * vec4(aNormal, 0.0)).xyz);
            }
        """.trimIndent()

        private val FRAGMENT_SHADER = """
            #version 100
            precision mediump float;
            varying vec2 vTexCoord;
            varying vec3 vWorldNormal;
            uniform sampler2D uTexture;
            uniform vec4 uBaseColor;
            uniform vec3 uLightDir;
            uniform float uRimStrength;
            uniform float uShadowMode;
            void main(void) {
                vec4 tex = texture2D(uTexture, vTexCoord);
                if (tex.a <= 0.002) {
                    discard;
                }
                if (uShadowMode > 0.5) {
                    vec3 shadowRgb = vec3(0.01, 0.03, 0.06) * tex.a;
                    gl_FragColor = vec4(shadowRgb, tex.a) * uBaseColor;
                    return;
                }
                vec3 N = normalize(vWorldNormal);
                vec3 L = normalize(uLightDir);
                vec3 V = vec3(0.0, 0.0, 1.0);
                float ndotl = max(dot(N, L), 0.0);
                float shade = 0.86 + 0.19 * ndotl;
                vec3 H = normalize(L + V);
                float spec = pow(max(dot(N, H), 0.0), 20.0) * 0.085;
                float fresnel = pow(1.0 - clamp(dot(N, V), 0.0, 1.0), 2.3) * uRimStrength;
                vec3 rimTint = vec3(0.40, 0.88, 1.00) * fresnel;
                vec3 litRgb = tex.rgb * shade + (vec3(spec) + rimTint) * tex.a;
                gl_FragColor = vec4(litRgb, tex.a) * uBaseColor;
            }
        """.trimIndent()
    }
}
