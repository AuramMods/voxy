#version 450 core

layout(binding = 0) uniform sampler2D depthTex;
layout(location = 1) uniform vec2 scaleFactor;
layout(location = 2) uniform bool matchNearTerrain;
layout(location = 3) uniform mat4 nearProjectionInverse;
layout(location = 4) uniform mat4 lodProjection;

#import <voxy:util/depthutils.glsl>

in vec2 UV;

void main() {
    float vanillaDepth = texture(depthTex, UV * scaleFactor).r;
    if (abs(vanillaDepth - FAR) < 1e-5) discard;
    gl_FragDepth = NEAR;
    if (matchNearTerrain) {
        // Keep near geometry in Hi-Z while admitting LoD surfaces within the handoff tolerance.
        vec4 nearView = nearProjectionInverse * vec4(UV * 2.0 - 1.0, vanillaDepth * 2.0 - 1.0, 1.0);
        nearView.z -= 0.75 * nearView.w;
        vec4 lodClip = lodProjection * nearView;
        gl_FragDepth = clamp(lodClip.z / lodClip.w * 0.5 + 0.5, 0.0, 1.0);
    }
}
