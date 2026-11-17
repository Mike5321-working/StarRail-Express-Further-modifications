#version 150

uniform vec4 ColorModulator;
uniform float Time;
uniform float SignalNoise;
uniform float DangerPulse;
uniform float BootProgress;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

void main() {
    vec2 uv = texCoord0;
    vec2 centered = uv * 2.0 - 1.0;
    float r2 = dot(centered, centered);

    // Clear central piloting field (0..0.55) with curved aerospace visor vignette at edges
    float vignette = smoothstep(0.52, 1.82, r2);

    // Subtle corner optical calibration frame lines in visor periphery
    vec2 absC = abs(centered);
    float cornerBand = step(0.86, max(absC.x, absC.y)) * (1.0 - step(0.875, max(absC.x, absC.y)));
    float cornerMask = cornerBand * step(0.72, min(absC.x, absC.y)) * 0.35;

    // Ultra-fine OLED telemetry scanlines (intensify with RF SignalNoise)
    float scanline = 0.5 + 0.5 * sin(gl_FragCoord.y * 2.2 - Time * 16.0);
    float scanAlpha = scanline * (0.028 + 0.085 * SignalNoise);

    // Weak-signal / low-battery / signal-lost RF band tear & digital snow
    float frame = floor(Time * 30.0);
    float band = floor(uv.y * 46.0);
    float bandHit = step(1.0 - SignalNoise * 0.68, hash12(vec2(band, frame)));
    float snow = hash12(floor(gl_FragCoord.xy * 0.5) + vec2(frame * 13.7, frame * 5.3));
    float staticAlpha = SignalNoise * (0.16 * snow + 0.36 * bandHit * snow);

    // Boot / recall horizontal laser-iris shutter
    float openHalf = max(0.002, BootProgress * 0.52);
    float distY = abs(uv.y - 0.5);
    float shutterMask = smoothstep(openHalf - 0.018, openHalf + 0.004, distY) * (1.0 - step(0.995, BootProgress));
    float beamLine = exp(-abs(distY - openHalf) * 110.0) * (1.0 - step(0.995, BootProgress));

    vec3 cyanTint = vec3(0.22, 0.74, 0.97);
    vec3 iceTint = vec3(0.73, 0.92, 1.0);
    vec3 dangerTint = vec3(0.98, 0.16, 0.26);
    vec3 baseCol = mix(vec3(0.015, 0.055, 0.095), dangerTint * 0.48, DangerPulse * vignette);

    vec3 rgb = baseCol * vignette * 0.58;
    rgb += cyanTint * (scanAlpha * 0.42 + cornerMask + beamLine * 0.92);
    rgb += iceTint * staticAlpha;
    rgb += dangerTint * (DangerPulse * smoothstep(0.60, 1.62, r2) * 0.46);
    rgb = mix(rgb, vec3(0.008, 0.022, 0.042), shutterMask * 0.94);

    float alpha = clamp(
            vignette * 0.46 + scanAlpha + staticAlpha + cornerMask * 0.55
            + shutterMask * 0.94 + beamLine * 0.72 + DangerPulse * vignette * 0.36,
            0.0, 0.96
    );
    fragColor = vec4(rgb, alpha * vertexColor.a) * ColorModulator;
}
