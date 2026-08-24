#version 300 es
precision highp float;
in vec2 vTexCoord;
out vec4 FragColor;

uniform sampler2D screenTexture;
// Always declared and uploaded at the limit, whatever the live radius is.
uniform float uKernel[124];
uniform int   uBlurRadius;
uniform vec2  uBlurOffset;

// No coordinate clamp: every texture this samples is CLAMP_TO_EDGE (AtmosphereGl.createFboTexture),
// so the sampler already smears the edge outward and clamping in ALU would repeat that on every tap
// of the hottest shader in the pipeline.
void main() {
    vec4 sourceColor = texture(screenTexture, vTexCoord);
    if (uBlurRadius <= 1) { FragColor = sourceColor; return; }
    vec3 finalColor = sourceColor.rgb * uKernel[0];
    for (int i = 1; i < uBlurRadius; i++) {
        float weight = uKernel[i];
        vec2 tap = uBlurOffset * float(i);
        finalColor += texture(screenTexture, vTexCoord - tap).rgb * weight;
        finalColor += texture(screenTexture, vTexCoord + tap).rgb * weight;
    }
    FragColor = vec4(finalColor, sourceColor.a);
}
