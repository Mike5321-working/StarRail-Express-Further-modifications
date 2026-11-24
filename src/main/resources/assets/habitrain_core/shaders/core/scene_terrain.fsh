#version 150
#moj_import <fog.glsl>
uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform float AlphaCutoff;
// Plane in mesh-local block units; the default (0,0,0,1) never discards.
uniform vec4 SceneClip;
// Source-local XZ limits of the fixed tray interior. Applies to opaque and translucent geometry.
uniform vec4 SceneBounds;
uniform int ReplayFanCount;
// Columns hold (viewer-forward, foot y, viewer-sideways, radius), up to 128 avatars.
uniform mat4 ReplayFanAnchors[32];
uniform vec4 ReplayFanView;
in vec3 scenePos;
in vec3 sceneNormal;
in float vertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;
float replayCoverage(vec3 cell, vec2 sampleXZ) {
    float edge = ReplayFanView.w;
    float distanceToCut = edge;
    vec2 view = ReplayFanView.xy;
    vec2 point = vec2(dot(sampleXZ, view), dot(sampleXZ, vec2(view.y, -view.x)));
    for (int i = 0; i < ReplayFanCount; ++i) {
        vec4 anchor = ReplayFanAnchors[i / 4][i % 4];
        if (cell.y <= anchor.y + 0.01) continue;
        vec2 delta = point - anchor.xz;
        float distance;
        if (delta.x >= 0.0) {
            distance = abs(delta.y) - anchor.w - delta.x * ReplayFanView.z;
        } else {
            float reach = anchor.w + edge;
            if (-delta.x >= reach || abs(delta.y) >= reach) continue;
            distance = length(delta) - anchor.w;
        }
        if (distance <= -edge) return 0.0;
        distanceToCut = min(distanceToCut, distance);
    }
    return smoothstep(-edge, edge, distanceToCut);
}
// A fixed ordered threshold leaves the terrain in its existing depth-writing pass.
float replayThreshold() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    int x = pixel.x & 3;
    int y = pixel.y & 3;
    int mixed = x ^ y;
    int order = ((mixed & 1) << 3) | ((y & 1) << 2) | (mixed & 2) | ((y & 2) >> 1);
    return (float(order) + 0.5) / 16.0;
}
void main() {
    if (scenePos.x < SceneBounds.x || scenePos.z < SceneBounds.y
            || scenePos.x > SceneBounds.z || scenePos.z > SceneBounds.w) discard;
    if (dot(scenePos, SceneClip.xyz) > SceneClip.w) discard;
    if (ReplayFanCount > 0) {
        // Classify the voxel just inside this face. Opposite faces of neighbouring blocks then
        // disagree at a cut boundary, exposing the retained solid face instead of a hollow shell.
        vec3 n = dot(sceneNormal, sceneNormal) > 0.00001 ? normalize(sceneNormal) : vec3(0.0, 1.0, 0.0);
        vec3 cell = floor(scenePos - n * 0.002) + vec3(0.5);
        float coverage = replayCoverage(cell, mix(scenePos.xz, cell.xz, 0.5));
        if (coverage <= 0.0 || (coverage < 1.0 && coverage < replayThreshold())) discard;
    }
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (color.a < AlphaCutoff) discard;
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
