package com.bingbing.cameratest.gpuimage;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.util.Log;

import java.nio.FloatBuffer;

/**
 * Created by Bingbing on 16/6/13.
 */
public class CameraInputFilter extends GPUImageFilter {

    private static final String VERTEX_SHADER =
            "attribute vec4 position;\n" +
                    "attribute vec4 inputTextureCoordinate;\n" +
                    "uniform mat4 textureTransform;\n" +
                    "\n" +
                    "varying highp vec2 textureCoordinate;\n" +
                    "void main()\n" +
                    "{\n" +
                    "    gl_Position = position;\n" +
                    "    textureCoordinate = (textureTransform * inputTextureCoordinate).xy;\n" +
                    "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;\n" +
                    "uniform samplerExternalOES inputImageTexture;\n" +
                    "varying vec2 textureCoordinate;\n" +
                    "\n" +
                    "void main () {\n" +
                    "    vec4 color = texture2D(inputImageTexture, textureCoordinate);\n" +
                    "    gl_FragColor = color;\n" +
                    "}";


    private SurfaceTexture mSurfaceTexture;
    private int mTextureTransformMatrixLocation;

    public CameraInputFilter() {
        super(VERTEX_SHADER, FRAGMENT_SHADER);
    }

    public void setSurfaceTexture(SurfaceTexture surfaceTexture) {
        mSurfaceTexture = surfaceTexture;
    }

    @Override
    public void onInit() {
        super.onInit();
        mTextureTransformMatrixLocation = GLES20.glGetUniformLocation(mGLProgId, "textureTransform");
    }
    private long lastTime;

    @Override
    public void onDraw(int textureId, FloatBuffer cubeBuffer, FloatBuffer textureBuffer, long timeStampNanos) {
        Log.d("bingbing_time", "" + (System.currentTimeMillis() - lastTime));
        lastTime = System.currentTimeMillis();

        GLES20.glUseProgram(mGLProgId);
        runPendingOnDrawTasks();
        if (!isInitialized()) {
            return;
        }

        mSurfaceTexture.updateTexImage();
        float[] matrix = new float[16];
        mSurfaceTexture.getTransformMatrix(matrix);


        cubeBuffer.position(0);
        GLES20.glVertexAttribPointer(mGLAttribPosition, 2, GLES20.GL_FLOAT, false, 0, cubeBuffer);
        GLES20.glEnableVertexAttribArray(mGLAttribPosition);
        textureBuffer.position(0);
        GLES20.glVertexAttribPointer(mGLAttribTextureCoordinate, 2, GLES20.GL_FLOAT, false, 0,
                textureBuffer);
        GLES20.glEnableVertexAttribArray(mGLAttribTextureCoordinate);

        GLES20.glUniformMatrix4fv(mTextureTransformMatrixLocation, 1, false, matrix, 0);


        if (textureId != OpenGlUtils.NO_TEXTURE) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glUniform1i(mGLUniformTexture, 0);
        }
        onDrawArraysPre();
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(mGLAttribPosition);
        GLES20.glDisableVertexAttribArray(mGLAttribTextureCoordinate);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
    }


}
