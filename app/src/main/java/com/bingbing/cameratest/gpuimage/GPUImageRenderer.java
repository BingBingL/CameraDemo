/*
 * Copyright (C) 2012 CyberAgent
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bingbing.cameratest.gpuimage;

import android.annotation.TargetApi;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.Size;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;

import com.bingbing.cameratest.RenderThread;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.LinkedList;
import java.util.Queue;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

@TargetApi(11)
public class GPUImageRenderer implements SurfaceTexture.OnFrameAvailableListener{
    public static final int NO_IMAGE = -1;
    static final float CUBE[] = {
            -1.0f, -1.0f,
            1.0f, -1.0f,
            -1.0f, 1.0f,
            1.0f, 1.0f,
    };

    protected GPUImageFilterGroup mFilters;

    public final Object mSurfaceChangedWaiter = new Object();

    protected int mGLTextureId = NO_IMAGE;
    protected SurfaceTexture mSurfaceTexture = null;
    private final FloatBuffer mGLCubeBuffer;
    private final FloatBuffer mGLTextureBuffer;
    private IntBuffer mGLRgbBuffer;

    protected int mOutputWidth;
    protected int mOutputHeight;
    protected int mImageWidth;
    protected int mImageHeight;
    private int mAddedPadding;

    private final Queue<Runnable> mRunOnDraw;
    private final Queue<Runnable> mRunOnDrawEnd;
    private Rotation mRotation;
    private boolean mFlipHorizontal;
    private boolean mFlipVertical;
//    private GPUImage.ScaleType mScaleType = GPUImage.ScaleType.CENTER_CROP;

    private float mBackgroundRed = 0;
    private float mBackgroundGreen = 0;
    private float mBackgroundBlue = 0;

    protected GPUImageFilter mOldFilter;
    protected CameraInputFilter mCameraInputFilter;
//    protected GLSurfaceView mGlSurfaceView;
    protected RenderThread.RenderHandler mRenderHandler;

    public GPUImageRenderer() {
        mRunOnDraw = new LinkedList<Runnable>();
        mRunOnDrawEnd = new LinkedList<Runnable>();

        mGLCubeBuffer = ByteBuffer.allocateDirect(CUBE.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        mGLCubeBuffer.put(CUBE).position(0);

        mGLTextureBuffer = ByteBuffer.allocateDirect(TextureRotationUtil.TEXTURE_NO_ROTATION.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        setRotation(Rotation.NORMAL, false, false);

        mCameraInputFilter = new CameraInputFilter();
        mFilters = new GPUImageFilterGroup();
        mFilters.addFilter(mCameraInputFilter);
    }

//    /**
//     * Sets the GLSurfaceView which will display the preview.
//     *
//     * @param view the GLSurfaceView
//     */
//    public void setGLSurfaceView(final GLSurfaceView view) {
//        mGlSurfaceView = view;
//        mGlSurfaceView.setEGLContextClientVersion(2);
////        mGlSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
////        mGlSurfaceView.getHolder().setFormat(PixelFormat.RGBA_8888);
//        mGlSurfaceView.setRenderer(this);
//        mGlSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
//        mGlSurfaceView.requestRender();
//    }

    public void setRenderHandler(RenderThread.RenderHandler renderHandler) {
        mRenderHandler = renderHandler;
    }

    public void onSurfaceCreated() {
        mRenderHandler.post(new Runnable() {
            @Override
            public void run() {
                GLES20.glClearColor(mBackgroundRed, mBackgroundGreen, mBackgroundBlue, 1);
                GLES20.glDisable(GLES20.GL_DEPTH_TEST);
                mFilters.init();
            }
        });

    }

    public void onSurfaceChanged(final GL10 gl, final int width, final int height) {
        mRenderHandler.post(new Runnable() {
            @Override
            public void run() {
                mOutputWidth = width;
                mOutputHeight = height;
                GLES20.glViewport(0, 0, width, height);
                GLES20.glUseProgram(mFilters.getProgram());
                mFilters.onOutputSizeChanged(width, height);
                adjustImageScaling();
                synchronized (mSurfaceChangedWaiter) {
                    mSurfaceChangedWaiter.notifyAll();
                }
            }
        });

    }

    public void onDrawFrame(long timeStampNanos) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        runAll(mRunOnDraw);
        mFilters.onDraw(mGLTextureId, mGLCubeBuffer, mGLTextureBuffer, timeStampNanos);
        runAll(mRunOnDrawEnd);
    }

    /**
     * Sets the background color
     *
     * @param red   red color value
     * @param green green color value
     * @param blue  red color value
     */
    public void setBackgroundColor(float red, float green, float blue) {
        mBackgroundRed = red;
        mBackgroundGreen = green;
        mBackgroundBlue = blue;
    }

    private void runAll(Queue<Runnable> queue) {
        synchronized (queue) {
            while (!queue.isEmpty()) {
                queue.poll().run();
            }
        }
    }

    /**
     * Sets the up camera to be connected to GPUImage to get a filtered preview.
     *
     * @param camera         the camera
     * @param degrees        by how many degrees the image should be rotated
     * @param flipHorizontal if the image should be flipped horizontally
     * @param flipVertical   if the image should be flipped vertically
     */
    public void setUpCamera(final Camera camera, final int degrees, final boolean flipHorizontal,
                            final boolean flipVertical) {
//        mGlSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        setUpSurfaceTexture(camera);
        Rotation rotation = Rotation.NORMAL;
        switch (degrees) {
            case 90:
                rotation = Rotation.ROTATION_90;
                break;
            case 180:
                rotation = Rotation.ROTATION_180;
                break;
            case 270:
                rotation = Rotation.ROTATION_270;
                break;
        }
        setRotationCamera(rotation, flipHorizontal, flipVertical);
//        mGlSurfaceView.requestRender();
    }

    public void setUpSurfaceTexture(final Camera camera) {
        runOnDraw(new Runnable() {
            @Override
            public void run() {
                Size size = camera.getParameters().getPreviewSize();
                mImageWidth = size.width;
                mImageHeight = size.height;
                adjustImageScaling();

                int[] textures = new int[1];
                GLES20.glGenTextures(1, textures, 0);
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0]);
                GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                        GL10.GL_TEXTURE_MIN_FILTER, GL10.GL_LINEAR);
                GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                        GL10.GL_TEXTURE_MAG_FILTER, GL10.GL_LINEAR);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                        GL10.GL_TEXTURE_WRAP_S, GL10.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                        GL10.GL_TEXTURE_WRAP_T, GL10.GL_CLAMP_TO_EDGE);

                mSurfaceTexture = new SurfaceTexture(textures[0]);
                mGLTextureId = textures[0];
                mCameraInputFilter.setSurfaceTexture(mSurfaceTexture);
                mSurfaceTexture.setOnFrameAvailableListener(GPUImageRenderer.this);
                try {
                    camera.setPreviewTexture(mSurfaceTexture);
                    camera.startPreview();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    public void setFilter(final GPUImageFilter filter) {
        runOnDraw(new Runnable() {

            @Override
            public void run() {
                final GPUImageFilter oldFilter = mOldFilter;
                if (oldFilter != null) {
                    mFilters.removeFilter(oldFilter);
                    oldFilter.destroy();
                }
                mOldFilter = filter;
                mOldFilter.init();
                mFilters.addFilter(filter);
                GLES20.glUseProgram(filter.getProgram());
                mFilters.onOutputSizeChanged(mOutputWidth, mOutputHeight);
            }
        });
    }

    protected int getFrameWidth() {
        return mOutputWidth;
    }

    protected int getFrameHeight() {
        return mOutputHeight;
    }

    protected void adjustImageScaling() {
        float outputWidth = mOutputWidth;
        float outputHeight = mOutputHeight;
        if (mRotation == Rotation.ROTATION_270 || mRotation == Rotation.ROTATION_90) {
            outputWidth = mOutputHeight;
            outputHeight = mOutputWidth;
        }

        float ratio1 = outputWidth / mImageWidth;
        float ratio2 = outputHeight / mImageHeight;
        float ratioMax = Math.max(ratio1, ratio2);
        int imageWidthNew = Math.round(mImageWidth * ratioMax);
        int imageHeightNew = Math.round(mImageHeight * ratioMax);

        float ratioWidth = imageWidthNew / outputWidth;
        float ratioHeight = imageHeightNew / outputHeight;

        float[] cube = CUBE;
        float[] textureCords = TextureRotationUtil.getRotation(mRotation, mFlipHorizontal, mFlipVertical);
        float distHorizontal = (1 - 1 / ratioWidth) / 2;
        float distVertical = (1 - 1 / ratioHeight) / 2;
        textureCords = new float[]{
                addDistance(textureCords[0], distHorizontal), addDistance(textureCords[1], distVertical),
                addDistance(textureCords[2], distHorizontal), addDistance(textureCords[3], distVertical),
                addDistance(textureCords[4], distHorizontal), addDistance(textureCords[5], distVertical),
                addDistance(textureCords[6], distHorizontal), addDistance(textureCords[7], distVertical),
        };

        mGLCubeBuffer.clear();
        mGLCubeBuffer.put(cube).position(0);
        mGLTextureBuffer.clear();
        mGLTextureBuffer.put(textureCords).position(0);
    }

    private float addDistance(float coordinate, float distance) {
        return coordinate == 0.0f ? distance : 1 - distance;
    }

    public void setRotationCamera(final Rotation rotation, final boolean flipHorizontal,
                                  final boolean flipVertical) {
        setRotation(rotation, flipVertical, flipHorizontal);
    }

    public void setRotation(final Rotation rotation) {
        mRotation = rotation;
        adjustImageScaling();
    }

    public void setRotation(final Rotation rotation,
                            final boolean flipHorizontal, final boolean flipVertical) {
        mFlipHorizontal = flipHorizontal;
        mFlipVertical = flipVertical;
        setRotation(rotation);
    }

    public Rotation getRotation() {
        return mRotation;
    }

    public boolean isFlippedHorizontally() {
        return mFlipHorizontal;
    }

    public boolean isFlippedVertically() {
        return mFlipVertical;
    }

    protected void runOnDraw(final Runnable runnable) {
        mRenderHandler.post(runnable);
//        synchronized (mRunOnDraw) {
//            mRunOnDraw.add(runnable);
//        }
    }

    protected void runOnDrawEnd(final Runnable runnable) {
        synchronized (mRunOnDrawEnd) {
            mRunOnDrawEnd.add(runnable);
        }
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        mRenderHandler.sendDoFrame(System.nanoTime());
//        mGlSurfaceView.requestRender();
    }
}
