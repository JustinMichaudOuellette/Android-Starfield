package ca.justinmo.starfield

object StarShaders {
    const val VERTEX_SHADER_CODE = """
        uniform mat4 uMVPMatrix;
        attribute vec4 vPosition;
        void main() {
            gl_Position = uMVPMatrix * vPosition;
        }
    """

    const val FRAGMENT_SHADER_CODE = """
        precision mediump float;
        void main() {
            gl_FragColor = vec4(1.0, 1.0, 1.0, 1.0);
        }
    """
}
