#version 450 core

layout(location = 0) uniform int resolution;
layout(location = 1) uniform float clearDepth;
layout(binding = 9, std430) readonly restrict buffer DirtyShadowTiles {
    uint dirtyShadowTiles[];
};

#import <voxy:lod/gl46/shadow_tiles.glsl>

void main() {
    if (dirtyShadowTiles[shadowTileIndex(ivec2(gl_FragCoord.xy), resolution)] == 0u) discard;
    gl_FragDepth = clearDepth;
}
