package ui

import androidx.compose.ui.graphics.Color
import org.jetbrains.skia.Color4f
import org.jetbrains.skia.Data
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GrainGradient shader reproduced identically from @componentry/grain-gradient
 */
object GrainGradientShader {
    private const val SKSL_SRC = """
        uniform float2 u_resolution;
        uniform float u_ratio;
        uniform float u_angle;
        uniform float u_position;
        uniform float u_curve;
        uniform float u_softness;
        uniform float u_scale;
        uniform float u_grain;
        uniform float u_grainSize;
        uniform float u_seed;
        uniform float u_time;
        uniform float3 u_light;
        uniform float3 u_mid;
        uniform float3 u_dark;

        float hash(float2 p) {
            float3 p3 = fract(float3(p.xyx) * 0.1031);
            p3 += dot(p3, p3.yzx + 33.33);
            return fract((p3.x + p3.y) * p3.z);
        }

        half4 main(float2 fragCoord) {
            float2 uv = fragCoord / u_resolution;
            // SkSL coordinate system origin is top-left, matching the React WebGL logic (where uv.y = 1.0 - uv.y)
            float2 p = uv - 0.5;
            float aspect = u_resolution.x / u_resolution.y;
            p.x *= aspect;
            float c = cos(u_angle);
            float s = sin(u_angle);
            // 2D rotation matrix: [c, -s; s, c] * p
            p = float2(c * p.x - s * p.y, s * p.x + c * p.y);
            p.x /= aspect;
            p = p / u_scale + 0.5;

            // Breathing cycle:
            float breath = sin(u_time * 0.52);
            float undertow = sin(u_time * 0.31);
            float bend = u_curve + sin(u_time * 0.52 - 0.7) * 0.12;
            float edge = 0.32 + bend * pow(1.0 - p.y, 2.0) + u_position
                + breath * 0.115 + undertow * 0.035;
            float distanceToEdge = p.x - edge + sin(p.y * 4.0 + u_time * 0.38) * 0.035;
            float diffusion = u_softness * (1.0 + breath * 0.28);
            float shadow = smoothstep(-diffusion, diffusion * 0.45, distanceToEdge);

            float2 lightCenter = float2(0.04 + breath * 0.13, 0.04 + undertow * 0.16);
            float2 lightUV = (p - lightCenter) * float2(1.9, 1.7);
            float mint = exp(-dot(lightUV, lightUV));
            float3 light = mix(u_light, u_mid, mint * (0.66 + breath * 0.1));
            float3 col = mix(light, u_mid * (0.68 + breath * 0.035),
                smoothstep(-diffusion * 1.8, 0.02, distanceToEdge));
            col = mix(col, u_dark, shadow);

            float2 grainUV = floor(fragCoord / (u_ratio * u_grainSize));
            float noise = hash(grainUV + mod(u_seed, 1000.0) * 13.7) - 0.5;
            col += noise * u_grain * mix(0.48, 0.32, shadow);

            return half4(clamp(col, 0.0, 1.0), 1.0);
        }
    """

    private val runtimeEffect: RuntimeEffect by lazy {
        RuntimeEffect.makeForShader(SKSL_SRC)
    }

    fun makeShader(
        width: Float,
        height: Float,
        time: Float,
        ratio: Float = 1f,
        colorLight: Color = Color(0xFFDCE5DF),
        colorMid: Color = Color(0xFF83B9AD),
        colorDark: Color = Color(0xFF031419),
        angle: Float = 0f,
        position: Float = 0f,
        curve: Float = 0.48f,
        softness: Float = 0.13f,
        scale: Float = 1f,
        grain: Float = 0.32f,
        grainSize: Float = 1f,
        seed: Float = 1f
    ): org.jetbrains.skia.Shader {
        val builder = RuntimeShaderBuilder(runtimeEffect)
        builder.uniform("u_resolution", width, height)
        builder.uniform("u_ratio", ratio)
        builder.uniform("u_angle", (angle.coerceIn(-360f, 360f) * Math.PI.toFloat()) / 180f)
        builder.uniform("u_position", position.coerceIn(-1f, 1f))
        builder.uniform("u_curve", curve.coerceIn(-1f, 1f))
        builder.uniform("u_softness", softness.coerceIn(0.01f, 1f))
        builder.uniform("u_scale", scale.coerceIn(0.25f, 3f))
        builder.uniform("u_grain", grain.coerceIn(0f, 1f))
        builder.uniform("u_grainSize", grainSize.coerceIn(0.5f, 4f))
        builder.uniform("u_seed", seed.coerceIn(0f, 100000f))
        builder.uniform("u_time", time)
        builder.uniform("u_light", colorLight.red, colorLight.green, colorLight.blue)
        builder.uniform("u_mid", colorMid.red, colorMid.green, colorMid.blue)
        builder.uniform("u_dark", colorDark.red, colorDark.green, colorDark.blue)
        return builder.makeShader()
    }
}
