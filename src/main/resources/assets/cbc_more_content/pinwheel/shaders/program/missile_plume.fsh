#version 150
// Ray-marched rocket exhaust in nozzle space: +X runs downstream from the nozzle exit.
//
// The jet is built the way a real underexpanded rocket plume looks: a blinding nozzle
// exit; a supersonic core whose expansion fans and recompression shocks form a train of
// bright shock diamonds, each with a Mach disk at its node, fading downstream; a
// turbulent mixing layer that widens and cools around it; and a soft glow.
uniform mat4 PlumeView;
uniform mat4 PlumeProjection;
uniform vec3 CameraLocal;
uniform float Time;
uniform float Strength;
uniform int Samples;
uniform vec2 FogRange;
uniform float MinimumRadius;
// 0 solid booster, 1 interceptor motor, 2 turbofan sustainer.
uniform int Kind;
// 0 realistic propellant flame, 1 plasma (violet jet, white diamonds).
uniform float Style;
in vec3 localPosition;
out vec4 fragColor;

const float LENGTH = 9.0;
const float HALF = 1.6;
const float TAU = 6.2831853;

float hash(vec3 p) {
    p = fract(p * .1031);
    p += dot(p, p.yzx + 33.33);
    return fract((p.x + p.y) * p.z);
}

float noise(vec3 p) {
    vec3 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash(i), hash(i + vec3(1, 0, 0)), f.x),
                   mix(hash(i + vec3(0, 1, 0)), hash(i + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(hash(i + vec3(0, 0, 1)), hash(i + vec3(1, 0, 1)), f.x),
                   mix(hash(i + vec3(0, 1, 1)), hash(i + vec3(1, 1, 1)), f.x), f.y), f.z);
}

// Heat 0..1 to colour. Realistic: deep orange edges through yellow to a white core.
// Plasma: blue-violet edges through lavender to white diamonds.
vec3 palette(float heat) {
    vec3 solid = mix(vec3(.80, .14, .02), vec3(1.0, .46, .08), smoothstep(.0, .35, heat));
    solid = mix(solid, vec3(1.0, .80, .38), smoothstep(.35, .7, heat));
    solid = mix(solid, vec3(1.0, .98, .93), smoothstep(.7, .98, heat));
    vec3 plasma = mix(vec3(.40, .18, .95), vec3(.74, .40, 1.0), smoothstep(.0, .35, heat));
    plasma = mix(plasma, vec3(.95, .78, 1.0), smoothstep(.35, .72, heat));
    plasma = mix(plasma, vec3(1.0, .99, 1.0), smoothstep(.72, .98, heat));
    return mix(solid, plasma, Style);
}

void main() {
    if (gl_FrontFacing) discard;
    vec3 ray = normalize(localPosition - CameraLocal);
    vec3 safeRay = mix(vec3(.00001), ray, greaterThan(abs(ray), vec3(.00001)));
    vec3 a = (vec3(0, -HALF, -HALF) - CameraLocal) / safeRay;
    vec3 b = (vec3(LENGTH, HALF, HALF) - CameraLocal) / safeRay;
    vec3 lo = min(a, b), hi = max(a, b);
    float start = max(max(max(lo.x, lo.y), lo.z), 0.0);
    float end = min(min(hi.x, hi.y), hi.z);
    if (end <= start) discard;

    float interceptor = Kind == 1 ? 1.0 : 0.0;
    float turbofan = Kind == 2 ? 1.0 : 0.0;
    // Jet geometry for this motor.
    float exitRadius = mix(.17, .11, interceptor);
    float coreLength = mix(mix(4.2, 2.8, interceptor), .9, turbofan);
    float spacing = mix(.66, .40, interceptor);
    float plumeLength = mix(mix(LENGTH, 6.0, interceptor), 3.2, turbofan);
    float flowSpeed = mix(14.0, 20.0, interceptor);
    float flowTime = Time * flowSpeed;
    // Combustion is never steady: a fast, small flicker in brightness.
    float flicker = .92 + .08 * noise(vec3(Time * 31.0, Time * 7.0, 0.0));

    float stepSize = (end - start) / float(Samples);
    // Per-pixel jitter hides the step pattern the cells would otherwise alias into.
    float jitter = hash(vec3(gl_FragCoord.xy, floor(Time * 60.0)));
    vec3 radiance = vec3(0);
    float first = -1.0;
    float transmission = 1.0;
    for (int i = 0; i < 40; i++) {
        if (i >= Samples) break;
        float d = start + (float(i) + jitter) * stepSize;
        vec3 p = CameraLocal + ray * d;
        float x = p.x;
        if (x < 0.0 || x > plumeLength) continue;

        // Streaky turbulence stretched along the jet and swept downstream: the flame's
        // tongues visibly race away from the nozzle.
        float turbulence = noise(vec3(x * 1.7 - flowTime * .55, p.yz * 6.0)) * .6
                + noise(vec3(x * 3.9 - flowTime * 1.1, p.yz * 13.0 + 5.0)) * .4;
        float breakup = smoothstep(coreLength * .6, plumeLength * .85, x);
        // The tail flaps; the core near the nozzle barely moves.
        float sway = .02 * x + .22 * breakup;
        vec2 wobble = vec2(sin(x * 2.3 - Time * 9.0), cos(x * 2.9 - Time * 7.3)) * sway;
        float r = length(p.yz - wobble);

        // One continuous core, slowly widening, breathing slightly with each shock cell.
        float phase = x / spacing;
        float inCore = (1.0 - smoothstep(coreLength * .75, coreLength * 1.15, x)) * (1.0 - turbofan);
        float coreRadius = exitRadius * (1.25 + .18 * x / coreLength) * (1.0 + .1 * cos(phase * TAU)) + MinimumRadius;
        float rc = r / coreRadius;
        float core = exp(-rc * rc * rc * rc) * inCore;

        // The flame around it: tongues of burning gas, widening and tearing apart downstream.
        float flameRadius = exitRadius * 1.6 + .1 * x + .35 * breakup;
        float tail = 1.0 - smoothstep(plumeLength * .45, plumeLength, x + (turbulence - .5) * 2.5);
        float flame = exp(-r * r / (flameRadius * flameRadius * (.55 + .8 * turbulence)))
                * smoothstep(.25, .85, turbulence + .25 * (1.0 - breakup)) * tail;

        // Waves of brightness running down the jet: combustion pulsing, not a painted texture.
        float pulse = .8 + .4 * noise(vec3(x * 1.3 - Time * 22.0, Time * 3.0, 0.0));

        // Shock diamonds as brighter, whiter bands inside the core, not separate blobs.
        float fade = exp(-x / max(.5, coreLength) * 1.1);
        float cell = fract(phase + .5) - .5;
        float diamond = (1.0 - smoothstep(.6, 1.0, abs(cell) * 2.0 + r / (coreRadius * .9))) * fade * inCore;
        float machDisk = exp(-cell * cell / .004) * exp(-r * r / (coreRadius * coreRadius * .5)) * fade * inCore;

        // The exit plane itself: the hottest, brightest point of the whole plume.
        float exitGlow = exp(-x * x / .03) * exp(-r * r / (exitRadius * exitRadius * 1.6 + MinimumRadius * MinimumRadius))
                * (1.0 + turbofan * .4);

        float density = flame * mix(.9, .5, turbofan) + core * 1.25 * pulse + diamond * .3 + machDisk * .35
                + exitGlow * 2.0;
        if (density < .02) continue;
        if (first < 0.0) first = d;

        float heat = clamp(
                .16 + .35 * (1.0 - x / plumeLength) - r / flameRadius * .4 + core * .34 * pulse
                + diamond * .22 + machDisk * .3 + exitGlow + (turbulence - .5) * .15,
                0.0, 1.0);
        heat = mix(heat, heat * .75, turbofan);
        vec3 color = palette(heat);
        // A turbofan shows a hot orange nozzle and a short tongue of glowing gas.
        color = mix(color, vec3(1.0, .52, .2) * (.55 + heat * .6), turbofan);
        float opacity = 1.0 - exp(-density * stepSize * 1.8);
        radiance += transmission * color * opacity * .85;
        transmission *= 1.0 - opacity * .45;
    }
    if (first < 0.0) discard;

    // Glow around the core: the light the jet throws into the air around it.
    vec3 closest = CameraLocal + ray * max(start, dot(vec3(coreLength * .4, 0, 0) - CameraLocal, ray));
    float haloR = length(closest.yz);
    float halo = exp(-haloR * haloR / (.55 + .1 * coreLength)) * (1.0 - turbofan)
            * (1.0 - smoothstep(0.0, coreLength * 1.4, abs(closest.x - coreLength * .4)));
    radiance += palette(.55) * halo * .12;

    vec4 viewPoint = PlumeView * vec4(CameraLocal + ray * first, 1.0);
    vec4 clip = PlumeProjection * viewPoint;
    gl_FragDepth = clip.z / clip.w * .5 + .5;
    // setupNoFog uses Float.MAX_VALUE. Equal endpoints make smoothstep undefined on some drivers.
    // Fire is a light source: haze dims it, but it still shows well beyond where the
    // terrain behind it has faded out.
    float fog = FogRange.y > FogRange.x ? 1.0 - .65 * smoothstep(FogRange.x, FogRange.y, length(viewPoint.xyz)) : 1.0;
    // Premultiplied: the flame both emits and, where it is dense, hides what is behind it,
    // so its colour survives against a bright sky instead of washing out to white.
    float cover = (1.0 - transmission) * .9 * fog * clamp(Strength, 0.0, 1.0) * (1.0 - turbofan * .5);
    fragColor = vec4(radiance * Strength * flicker * fog, cover);
}
