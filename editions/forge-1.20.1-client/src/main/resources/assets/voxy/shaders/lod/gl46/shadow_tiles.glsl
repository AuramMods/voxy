// Fixed tile grid shared by invalidation, command generation and the depth pass.
const int SHADOW_TILE_COUNT = 16;

int shadowTileIndex(ivec2 pixel, int resolution) {
    ivec2 tile = min(pixel * SHADOW_TILE_COUNT / resolution, ivec2(SHADOW_TILE_COUNT - 1));
    return tile.y * SHADOW_TILE_COUNT + tile.x;
}

ivec4 shadowTileRect(vec4 bounds, int resolution) {
    vec2 margin = vec2(2.0 / float(resolution));
    vec4 outer = vec4(bounds.xy - margin, bounds.zw + margin);
    return min(ivec4(floor(clamp(outer * 0.5 + 0.5, 0.0, 1.0) * float(SHADOW_TILE_COUNT))),
               ivec4(SHADOW_TILE_COUNT - 1));
}
