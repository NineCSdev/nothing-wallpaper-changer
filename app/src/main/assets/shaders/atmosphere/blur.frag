#version 300 es
precision highp float;
in vec2 vTexCoord;
out vec4 FragColor;

uniform sampler2D screenTexture;
// Always declared and uploaded at the limit, whatever the live radius is.
uniform float uKernel[124];
uniform int   uBlurRadius;
uniform vec2  uBlurOffset;

float getWeight(int i) { return uKernel[i]; }

// Matches the CLAMP_TO_EDGE wrap on every texture here: edges smear outward
// rather than wrapping.
vec2 clampCoordinate(vec2 c) { return clamp(c, vec2(0.0), vec2(1.0)); }

void main() {
    vec4 sourceColor = texture(screenTexture, vTexCoord);
    if (uBlurRadius <= 1) { FragColor = sourceColor; return; }
    float weight = getWeight(0);
    vec3 finalColor = sourceColor.rgb * weight;
    for (int i = 1; i < uBlurRadius; i++) {
        weight = getWeight(i);
        finalColor += texture(screenTexture, clampCoordinate(vTexCoord - uBlurOffset * float(i))).rgb * weight;
        finalColor += texture(screenTexture, clampCoordinate(vTexCoord + uBlurOffset * float(i))).rgb * weight;
    }
    FragColor = vec4(finalColor, sourceColor.a);
}
