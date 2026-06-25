uniform mat4 uMVPMatrix;
uniform mat3 uNormalMatrix;
uniform vec3 uLightDirection; // normalized, in world space
uniform float uPointSize;     // only affects rendering when drawn as GL_POINTS

attribute vec4 aPosition;
attribute vec3 aNormal;
attribute vec4 aColor;

varying vec4 vColor;

void main() {
    gl_Position = uMVPMatrix * aPosition;
    gl_PointSize = uPointSize;

    vec3 worldNormal = normalize(uNormalMatrix * aNormal);
    float diffuse = max(dot(worldNormal, -uLightDirection), 0.0);
    // Ambient + diffuse so unlit faces aren't fully black, matching the
    // soft "draped cloth" look of a thin-plate surface plot rather than
    // harsh per-facet shading.
    float lighting = 0.45 + 0.55 * diffuse;

    vColor = vec4(aColor.rgb * lighting, aColor.a);
}
