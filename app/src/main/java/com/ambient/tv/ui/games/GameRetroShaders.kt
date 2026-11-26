package com.ambient.tv.ui.games

import com.ambient.tv.games.GameShader
import com.swordfish.libretrodroid.ShaderConfig

/**
 * Original, compact GLES2-compatible shader presets. Only the chosen GLSL is compiled;
 * no LUTs, assets, shader binaries, or native libraries are added to the APK.
 *
 * These are intentionally simple effects, NOT copies or ports of the RetroArch presets
 * with similar visual goals. Full glslp/slangp requires its own preset runtime.
 */
internal object GameRetroShaders {
    private const val VERTEX = """
        attribute vec4 vPosition;
        attribute vec2 vCoordinate;
        varying highp vec2 coords;
        void main() {
            coords = vCoordinate;
            gl_Position = vPosition;
        }
    """
    private const val HEADER = """
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        precision highp float;
        #else
        precision mediump float;
        #endif
        uniform sampler2D texture;
        uniform vec2 textureSize;
        uniform vec2 OutputSize;
        uniform int FrameCount;
        uniform float ambientFilterIntensity;
        #define INTENSITY ambientFilterIntensity
        varying highp vec2 coords;
        float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
        float noise(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
    """

    private fun pass(body: String, strength: Float, linear: Boolean = false): ShaderConfig.Custom {
        val amount = strength.takeIf { it.isFinite() }?.coerceIn(0.5f, 1.5f) ?: 1f
        return ShaderConfig.Custom(listOf(ShaderConfig.Custom.ShaderPass(
            vertex = VERTEX,
            fragment = HEADER + body,
            linear = linear,
            ambientIntensity = amount,
        )))
    }

    fun isCustom(shader: GameShader): Boolean = when (shader) {
        GameShader.SHARP_BILINEAR, GameShader.PIXEL_AA, GameShader.CRT_LIGHT,
        GameShader.CRT_GEOMETRY, GameShader.CRT_APERTURE, GameShader.CRT_GLOW,
        GameShader.GAMEBOY_DMG, GameShader.GBC_GRID, GameShader.GBA_COLOR,
        GameShader.DS_LCD, GameShader.NTSC_COMPOSITE, GameShader.VHS_SOFT,
        GameShader.FILM_GRAIN, GameShader.DEBAND, GameShader.COLOR_BOOST -> true
        else -> false
    }

    fun config(shader: GameShader, strength: Float): ShaderConfig = when (shader) {
        GameShader.SHARP_BILINEAR -> pass(SHARP_BILINEAR, strength, linear = true)
        GameShader.PIXEL_AA -> pass(PIXEL_AA, strength)
        GameShader.CRT_LIGHT -> pass(CRT_LIGHT, strength, linear = true)
        GameShader.CRT_GEOMETRY -> pass(CRT_GEOMETRY, strength, linear = true)
        GameShader.CRT_APERTURE -> pass(CRT_APERTURE, strength, linear = true)
        GameShader.CRT_GLOW -> pass(CRT_GLOW, strength, linear = true)
        GameShader.GAMEBOY_DMG -> pass(GAMEBOY_DMG, strength)
        GameShader.GBC_GRID -> pass(GBC_GRID, strength)
        GameShader.GBA_COLOR -> pass(GBA_COLOR, strength)
        GameShader.DS_LCD -> pass(DS_LCD, strength)
        GameShader.NTSC_COMPOSITE -> pass(NTSC, strength, linear = true)
        GameShader.VHS_SOFT -> pass(VHS, strength, linear = true)
        GameShader.FILM_GRAIN -> pass(FILM_GRAIN, strength)
        GameShader.DEBAND -> pass(DEBAND, strength)
        GameShader.COLOR_BOOST -> pass(COLOR_BOOST, strength)
        else -> error("Not a compact GLSL shader: " + shader.key)
    }

    private const val SHARP_BILINEAR = """
        void main() {
            vec2 s = max(textureSize, vec2(1.0));
            vec2 p = coords*s - 0.5;
            vec2 f = fract(p);
            vec2 ratio = max(OutputSize / s, vec2(1.0));
            vec2 sharp = clamp((f-0.5)*ratio*INTENSITY+0.5, 0.0, 1.0);
            gl_FragColor = texture2D(texture, clamp((floor(p)+sharp+0.5)/s, 0.0, 1.0));
        }
    """
    private const val PIXEL_AA = """
        void main() {
            vec2 d = 1.0 / max(textureSize, vec2(1.0));
            vec4 c = texture2D(texture, coords);
            vec3 l = texture2D(texture, coords-vec2(d.x, 0.0)).rgb;
            vec3 r = texture2D(texture, coords+vec2(d.x, 0.0)).rgb;
            vec3 u = texture2D(texture, coords-vec2(0.0, d.y)).rgb;
            vec3 b = texture2D(texture, coords+vec2(0.0, d.y)).rgb;
            float flatness = 1.0-smoothstep(0.06, 0.35, length(l-r)+length(u-b));
            gl_FragColor = vec4(mix(c.rgb, (l+r+u+b)*0.25, 0.30*flatness*INTENSITY), c.a);
        }
    """
    private const val CRT_LIGHT = """
        void main() {
            vec4 c = texture2D(texture, coords);
            float line = 0.80+0.20*cos(coords.y*max(textureSize.y, 1.0)*6.2831853);
            gl_FragColor = vec4(c.rgb*mix(1.0, line, INTENSITY), c.a);
        }
    """
    private const val CRT_GEOMETRY = """
        void main() {
            vec2 p = coords*2.0-1.0;
            vec2 uv = p*(1.0+0.09*INTENSITY*dot(p,p))*0.5+0.5;
            if (uv.x<0.0 || uv.y<0.0 || uv.x>1.0 || uv.y>1.0) {
                gl_FragColor = vec4(0.0,0.0,0.0,1.0);
                return;
            }
            vec4 c = texture2D(texture, uv);
            float scan = 0.83+0.17*cos(uv.y*max(textureSize.y,1.0)*6.2831853);
            gl_FragColor = vec4(c.rgb*scan*max(0.0,1.0-0.15*INTENSITY*dot(p,p)),c.a);
        }
    """
    private const val CRT_APERTURE = """
        void main() {
            vec4 c = texture2D(texture, coords);
            float slot = mod(floor(gl_FragCoord.x), 3.0);
            vec3 m = slot<1.0 ? vec3(1.0,0.72,0.72) :
                     (slot<2.0 ? vec3(0.72,1.0,0.72) : vec3(0.72,0.72,1.0));
            float scan = 0.87+0.13*cos(coords.y*max(textureSize.y,1.0)*6.2831853);
            gl_FragColor = vec4(c.rgb*mix(vec3(1.0),m*scan,INTENSITY),c.a);
        }
    """
    private const val CRT_GLOW = """
        void main() {
            vec2 d = 1.0/max(textureSize, vec2(1.0));
            vec4 c = texture2D(texture,coords);
            vec3 glow = (texture2D(texture,coords+vec2(d.x,0.0)).rgb +
                         texture2D(texture,coords-vec2(d.x,0.0)).rgb +
                         texture2D(texture,coords+vec2(0.0,d.y)).rgb +
                         texture2D(texture,coords-vec2(0.0,d.y)).rgb)*0.25;
            float scan = 0.90+0.10*cos(coords.y*max(textureSize.y,1.0)*6.2831853);
            gl_FragColor = vec4(clamp(c.rgb*scan+max(glow-vec3(0.35),0.0)*0.32*INTENSITY,0.0,1.0),c.a);
        }
    """
    private const val GAMEBOY_DMG = """
        void main() {
            vec4 c = texture2D(texture,coords);
            float q = floor(clamp(luma(c.rgb),0.0,1.0)*3.0+0.5);
            vec3 palette = q<0.5 ? vec3(0.06,0.18,0.08) :
                         (q<1.5 ? vec3(0.22,0.37,0.13) :
                         (q<2.5 ? vec3(0.53,0.68,0.25) : vec3(0.75,0.83,0.49)));
            gl_FragColor = vec4(mix(c.rgb,palette,INTENSITY),c.a);
        }
    """
    private const val GBC_GRID = """
        void main() {
            vec4 c = texture2D(texture,coords);
            vec2 p = fract(coords*max(textureSize,vec2(1.0)));
            float edge = smoothstep(0.0,0.15,p.x)*(1.0-smoothstep(0.85,1.0,p.x))*
                         smoothstep(0.0,0.15,p.y)*(1.0-smoothstep(0.85,1.0,p.y));
            vec3 limited = floor(c.rgb*31.0+0.5)/31.0;
            gl_FragColor = vec4(mix(c.rgb,limited*mix(0.7,1.0,edge),INTENSITY),c.a);
        }
    """
    private const val GBA_COLOR = """
        void main() {
            vec4 c = texture2D(texture,coords);
            vec3 warm = pow(max(c.rgb,vec3(0.0)),vec3(0.92,0.99,1.08));
            warm = mix(warm,vec3(luma(warm)),0.10);
            float row = 0.93+0.07*cos(coords.y*max(textureSize.y,1.0)*6.2831853);
            gl_FragColor = vec4(mix(c.rgb,warm*row,INTENSITY),c.a);
        }
    """
    private const val DS_LCD = """
        void main() {
            vec4 c = texture2D(texture,coords);
            vec2 p = fract(coords*max(textureSize,vec2(1.0)));
            float grid = (0.93+0.07*cos(p.x*6.2831853))*(0.93+0.07*cos(p.y*6.2831853));
            gl_FragColor = vec4(c.rgb*mix(1.0,grid,INTENSITY),c.a);
        }
    """
    private const val NTSC = """
        void main() {
            vec2 d = vec2(1.0/max(textureSize.x,1.0),0.0);
            vec3 c = texture2D(texture,coords).rgb;
            vec3 l = texture2D(texture,coords-d).rgb;
            vec3 r = texture2D(texture,coords+d).rgb;
            vec3 soft = c*0.6+(l+r)*0.2;
            vec3 bleed = vec3(soft.r*0.9+r.r*0.1,soft.g,soft.b*0.9+l.b*0.1);
            gl_FragColor = vec4(mix(c,bleed,INTENSITY),1.0);
        }
    """
    private const val VHS = """
        void main() {
            float t = float(FrameCount);
            float jitter = noise(vec2(floor(coords.y*textureSize.y/8.0),floor(t/6.0)))-0.5;
            vec2 p = coords+vec2(jitter*0.003*INTENSITY,0.0);
            vec2 d = vec2(1.0/max(textureSize.x,1.0),0.0);
            vec3 c = texture2D(texture,p).rgb;
            vec3 shifted = vec3(texture2D(texture,p+d*0.65*INTENSITY).r,
                                c.g,texture2D(texture,p-d*0.65*INTENSITY).b);
            float grain = (noise(gl_FragCoord.xy+t)-0.5)*0.035;
            gl_FragColor = vec4(clamp(mix(c,shifted+grain,INTENSITY),0.0,1.0),1.0);
        }
    """
    private const val FILM_GRAIN = """
        void main() {
            vec4 c = texture2D(texture,coords);
            float n = noise(gl_FragCoord.xy+float(FrameCount)*5.31)-0.5;
            gl_FragColor = vec4(clamp(c.rgb+vec3(n*0.05*INTENSITY),0.0,1.0),c.a);
        }
    """
    private const val DEBAND = """
        void main() {
            vec4 c = texture2D(texture,coords);
            float n = noise(gl_FragCoord.xy*0.5)-0.5;
            gl_FragColor = vec4(clamp(c.rgb+vec3(n/255.0*INTENSITY),0.0,1.0),c.a);
        }
    """
    private const val COLOR_BOOST = """
        void main() {
            vec4 c = texture2D(texture,coords);
            float l = luma(c.rgb);
            vec3 boosted = clamp(vec3(l)+(c.rgb-vec3(l))*(1.0+0.16*INTENSITY),0.0,1.0);
            gl_FragColor = vec4(boosted,c.a);
        }
    """
}
