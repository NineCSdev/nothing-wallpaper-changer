#version 300 es
precision highp float;
in vec2 vTexCoord;
out vec4 outColor;

uniform sampler2D s_TextureMap;
uniform vec3  uBgColor;
uniform float uMixBlend;

// Centre-crop factors for surfaces whose aspect is not the panel's (picker preview, freeform
// windows, rotation); identity otherwise.
uniform vec2 uCoverScale;

void main() {
    vec2 uv = (vTexCoord - 0.5) * uCoverScale + 0.5;
    // The only vertical flip in the pipeline: the photo is the sole Bitmap texture (row 0 = top).
    uv.y = 1.0 - uv.y;
    vec4 sourceColor = texture(s_TextureMap, uv);
    outColor = vec4(mix(sourceColor.rgb, uBgColor, uMixBlend), 1.0);
}
