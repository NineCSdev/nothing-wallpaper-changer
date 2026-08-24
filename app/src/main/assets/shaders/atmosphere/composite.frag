#version 300 es
precision mediump float;
in vec2 vTexCoord;
out vec4 outColor;

uniform sampler2D s_TextureMap;
uniform vec3  uBgColor;
uniform float uMixBlend;

// Centre-crop factors for the texcoords, for the surfaces whose aspect is not the panel's: the
// live-wallpaper picker preview, freeform windows, rotation. Identity when the aspects match,
// which is the normal path.
uniform vec2 uCoverScale;

void main() {
    vec2 uv = (vTexCoord - 0.5) * uCoverScale + 0.5;
    // The one and only vertical flip in the pipeline. The quad maps NDC y to v as an
    // identity so every FBO round-trip stays upright; the photo is the sole texture
    // that arrives from a Bitmap (row 0 = top), so it alone needs correcting, once.
    uv.y = 1.0 - uv.y;
    vec4 sourceColor = texture(s_TextureMap, uv);
    outColor = vec4(mix(sourceColor.rgb, uBgColor, uMixBlend), 1.0);
}
