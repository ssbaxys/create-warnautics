uniform vec2 BlastUV;
uniform float FlashIntensity;
uniform float LookStrength;
uniform float Proximity;
uniform float SkyGlow;
uniform vec3 FlashColor;
uniform vec2 OutSize;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    float aspect = OutSize.x / max(OutSize.y, 1.0);
    vec2 d = (texCoord - BlastUV) * vec2(aspect, 1.0);
    float r2 = dot(d, d);

    float core = exp(-r2 * 42.0);
    float mid = exp(-r2 * 9.0);
    float halo = exp(-r2 * 2.2);
    float farPoint = exp(-r2 * 210.0) * (1.0 - clamp(Proximity, 0.0, 1.0));
    float farAura = exp(-r2 * 38.0) * (1.0 - clamp(Proximity, 0.0, 1.0));
    float sky = clamp(SkyGlow, 0.0, 1.9);

    float energy = (FlashIntensity * 0.42 + LookStrength * 0.86) * (1.0 + Proximity * 0.72);
    float h = (core * 1.55 + mid * 0.9 + halo * 0.42
        + farPoint * 3.2 + farAura * 0.58) * energy;
    h += exp(-r2 * 130.0) * sky * 1.25;
    h = clamp(h, 0.0, 4.2);

    vec3 col = mix(FlashColor, vec3(1.0, 0.97, 0.86), clamp(core * 1.15, 0.0, 1.0));
    fragColor = vec4(col * h, clamp(h * 0.75, 0.0, 1.0));
}
