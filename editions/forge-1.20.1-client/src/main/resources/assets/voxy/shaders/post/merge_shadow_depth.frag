#version 450 core

layout(binding = 0) uniform sampler2D casterDepth;

void main() {
    gl_FragDepth = texelFetch(casterDepth, ivec2(gl_FragCoord.xy), 0).r;
}
