uniform sampler2D DiffuseSampler0;
uniform float FlashIntensity;
uniform float LookStrength;
uniform float Proximity;
uniform float SkyGlow;
uniform vec3 FlashColor;
uniform vec2 BlastUV;
uniform vec2 OutSize;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec4 scene = texture(DiffuseSampler0, texCoord);

    vec2 targetUV = clamp(BlastUV, vec2(-0.2), vec2(1.2));
    float aspect = OutSize.x / max(OutSize.y, 1.0);
    vec2 d = (texCoord - targetUV) * vec2(aspect, 1.0);
    float r2 = dot(d, d);

    float core = exp(-r2 * 14.0);
    float mid = exp(-r2 * 3.5);
    float halo = exp(-r2 * 1.5);
    float viewEnergy = clamp(LookStrength, 0.0, 1.9);
    float sky = clamp(SkyGlow, 0.0, 1.9);
    float glare = exp(-r2 * 6.0) * viewEnergy;
    float streak = exp(-abs(d.y) * 32.0) * exp(-d.x * d.x * 2.0) * viewEnergy;
    // At long range the blast reads as a concentrated luminous point, not as a
    // weak full-screen wash. Proximity smoothly hands this over to the close glare.
    float farPoint = exp(-r2 * 180.0) * (1.0 - clamp(Proximity, 0.0, 1.0)) * viewEnergy;
    float farAura = exp(-r2 * 34.0) * (1.0 - clamp(Proximity, 0.0, 1.0)) * viewEnergy;

    float wash = clamp(viewEnergy * (0.14 + mid * 0.34) + FlashIntensity * core * 0.14, 0.0, 0.74);
    wash *= mix(1.0, 1.32, Proximity);
    // Behind a hill the operator should see a glow high in the sky, not a
    // full-screen white flash through the terrain.
    wash *= mix(1.0, 0.22, clamp(sky, 0.0, 1.0));

    vec3 fireball = mix(FlashColor, vec3(1.0, 0.98, 0.90), clamp(core * 1.25, 0.0, 1.0));
    vec3 warmed = scene.rgb * mix(vec3(1.0), vec3(1.12, 1.03, 0.90), wash);
    warmed = mix(warmed, fireball, clamp(wash * core * (0.32 + Proximity * 0.28), 0.0, 0.86));

    float closeExposure = mix(1.0, 1.58, clamp(Proximity, 0.0, 1.0));
    vec3 added = fireball * (glare * 0.48 + streak * 0.15 + mid * 0.32 + halo * 0.14 + core * viewEnergy * 0.26)
        * (FlashIntensity * 0.58 + LookStrength * 0.72)
        * closeExposure;
    added += fireball * (farPoint * 2.15 + farAura * 0.42)
        * (FlashIntensity * 0.82 + LookStrength * 0.76);
    warmed += added;
    float skyPoint = exp(-r2 * 120.0) * sky * 1.8;
    float skyShaft = exp(-abs(d.x) * 18.0) * exp(-abs(d.y) * 3.8) * sky * 0.42;
    warmed += fireball * (skyPoint + skyShaft);

    fragColor = vec4(clamp(warmed, vec3(0.0), vec3(3.6)), scene.a);
}
