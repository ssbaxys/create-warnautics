uniform sampler2D DiffuseSampler0;
/** Overall strength, 0..1, set from the blast distance. */
uniform float Shock;
/** 0 at the moment of the blast, 1 when hearing and vision are back. */
uniform float Recovery;
/** Free-running clock so the grain and the drift never freeze. */
uniform float Time;
uniform vec2 OutSize;

in vec2 texCoord;

out vec4 fragColor;

// Cheap hash noise. Two independent samples per pixel per frame give the dry,
// high-frequency static of a damaged sensor rather than a crawling pattern.
float hash(vec2 p) {
    p = fract(p * vec2(443.897, 441.423));
    p += dot(p, p.yx + 19.19);
    return fract((p.x + p.y) * p.x);
}

void main() {
    vec2 px = 1.0 / max(OutSize, vec2(1.0));
    float shock = clamp(Shock, 0.0, 1.0);
    float fade = 1.0 - clamp(Recovery, 0.0, 1.0);
    // Loss of focus is worst immediately and eases off. The exponent is gentler than a
    // square so the daze lingers instead of snapping back after half a second.
    float blurAmount = shock * pow(fade, 1.5);

    // The blur widens toward the edges: the centre of vision clears first, which is
    // what makes it read as concussion rather than as a dirty lens.
    vec2 fromCentre = texCoord - 0.5;
    float edge = clamp(dot(fromCentre, fromCentre) * 4.0, 0.0, 1.0);
    float radius = blurAmount * (11.0 + edge * 24.0);

    // Slow swim of the whole image, as if the head is still moving with the wave.
    // texCoord is a fragment input and read-only, so the drift goes on a local copy.
    vec2 swim = vec2(sin(Time * 1.7), cos(Time * 1.3)) * blurAmount * 0.006;
    vec2 uv = texCoord + swim;

    vec3 scene;
    if (radius < 0.35) {
        scene = texture(DiffuseSampler0, uv).rgb;
    } else {
        // Nine-tap separable-ish gaussian, rotated per pixel so the taps do not line
        // up into visible banding at large radii.
        float angle = hash(uv * OutSize) * 6.2831853;
        vec2 dir = vec2(cos(angle), sin(angle));
        vec2 ortho = vec2(-dir.y, dir.x);
        scene = texture(DiffuseSampler0, uv).rgb * 0.227;
        float weights[4] = float[](0.194, 0.121, 0.054, 0.016);
        for (int i = 0; i < 4; i++) {
            float offset = float(i + 1) * radius * 0.5;
            vec2 a = dir * offset * px;
            vec2 b = ortho * offset * px;
            scene += texture(DiffuseSampler0, uv + a).rgb * weights[i] * 0.5;
            scene += texture(DiffuseSampler0, uv - a).rgb * weights[i] * 0.5;
            scene += texture(DiffuseSampler0, uv + b).rgb * weights[i] * 0.5;
            scene += texture(DiffuseSampler0, uv - b).rgb * weights[i] * 0.5;
        }
    }

    // Pressure wave on the eye: the channels separate outward from centre, pulsing
    // rather than holding steady so it reads as a body reacting, not a lens filter.
    float aberration = blurAmount * (6.5 + sin(Time * 5.0) * 1.8);
    if (aberration > 0.05) {
        vec2 shift = fromCentre * aberration * px * 9.0;
        scene.r = texture(DiffuseSampler0, uv + shift).r;
        scene.b = texture(DiffuseSampler0, uv - shift).b;
    }

    // Colour drains out under shock and comes back with focus.
    float luma = dot(scene, vec3(0.2126, 0.7152, 0.0722));
    scene = mix(scene, vec3(luma), blurAmount * 0.8);

    // Overbright bloom smear: the blurred frame is added back on itself so lights
    // bleed the way they do when the eye cannot resolve them.
    scene += scene * blurAmount * 0.35;

    // White noise, strongest at the start, sitting on top of the blurred image.
    float grainAmount = shock * fade * fade;
    if (grainAmount > 0.002) {
        float n = hash(texCoord * OutSize + Time * 91.7);
        float sparkle = step(0.978 - grainAmount * 0.09, n);
        scene += vec3((n - 0.5) * grainAmount * 1.5 + sparkle * grainAmount * 2.2);
    }

    // Bright wash. Snaps in over the first instants and decays fastest of all, so the
    // player is blinded briefly and then merely dazed.
    float whiteout = shock * pow(fade, 3.5);
    scene = mix(scene, vec3(1.0), clamp(whiteout * 0.97, 0.0, 0.985));

    // Vignette closes in while stunned and opens back up as vision returns.
    float vignette = 1.0 - edge * blurAmount * 1.15;
    scene *= max(vignette, 0.0);

    fragColor = vec4(clamp(scene, vec3(0.0), vec3(4.0)), 1.0);
}
