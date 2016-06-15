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
import android.graphics.Rect;
import android.opengl.GLES20;
import android.util.Log;

import com.bingbing.cameratest.record.EglCore;
import com.bingbing.cameratest.record.TextureMovieEncoder2;
import com.bingbing.cameratest.record.VideoEncoderCore;
import com.bingbing.cameratest.record.WindowSurface;

import java.io.File;
import java.io.IOException;
import java.nio.FloatBuffer;

@TargetApi(11)
public class GPUImageRendererWithRecord extends GPUImageRenderer {
    private static final String TAG = "bingbing_render_record";

    private boolean mRecordingEnabled;
    private File mOutputFile;
    private WindowSurface mInputWindowSurface;
    private TextureMovieEncoder2 mVideoEncoder;
    private Rect mVideoRect;

    private EglCore mEglCore;
    private WindowSurface mWindowSurface;

    private RecordFilter mRecordFilter;


    public GPUImageRendererWithRecord(File outputFile) {
        super();
        mOutputFile = outputFile;
        mVideoRect = new Rect();

        mRecordFilter = new RecordFilter();
        mFilters.addFilter(mRecordFilter);
    }

    public void setEnv(EglCore eglCore, WindowSurface windowSurface) {
        mEglCore = eglCore;
        mWindowSurface = windowSurface;
    }

    @Override
    public void setFilter(final GPUImageFilter filter) {
        runOnDraw(new Runnable() {
            @Override
            public void run() {
                final GPUImageFilter oldFilter = mOldFilter;
                mFilters.removeFilter(mRecordFilter);
                if (oldFilter != null) {
                    mFilters.removeFilter(oldFilter);
                    oldFilter.destroy();
                }
                filter.init();
                mFilters.addFilter(filter);
                GLES20.glUseProgram(filter.getProgram());
                mFilters.addFilter(mRecordFilter);
                mFilters.onOutputSizeChanged(mOutputWidth, mOutputHeight);
                mOldFilter = filter;
            }
        });
    }

    /**
     * Updates the recording state.  Stops or starts recording as needed.
     */
    public void setRecordingEnabled(final boolean enabled) {
        runOnDraw(new Runnable() {
            @Override
            public void run() {
                if (enabled == mRecordingEnabled) {
                    return;
                }
                if (enabled) {
                    startEncoder();
                } else {
                    stopEncoder();
                }
                mRecordingEnabled = enabled;
            }
        });

    }

    public boolean getRecordingEnabled() {
        return mRecordingEnabled;
    }

    private void startEncoder() {
        // Record at 1280x720, regardless of the window dimensions.  The encoder may
        // explode if given "strange" dimensions, e.g. a width that is not a multiple
        // of 16.  We can box it as needed to preserve dimensions.
        final int BIT_RATE = 4000000;   // 4Mbps
        final int VIDEO_WIDTH = 720;
        final int VIDEO_HEIGHT = 1280;
        int windowWidth = mWindowSurface.getWidth();
        int windowHeight = mWindowSurface.getHeight();
        float windowAspect = (float) windowHeight / (float) windowWidth;
        int outWidth, outHeight;
        if (VIDEO_HEIGHT > VIDEO_WIDTH * windowAspect) {
            // limited by narrow width; reduce height
            outWidth = VIDEO_WIDTH;
            outHeight = (int) (VIDEO_WIDTH * windowAspect);
        } else {
            // limited by short height; restrict width
            outHeight = VIDEO_HEIGHT;
            outWidth = (int) (VIDEO_HEIGHT / windowAspect);
        }
        int offX = (VIDEO_WIDTH - outWidth) / 2;
        int offY = (VIDEO_HEIGHT - outHeight) / 2;
        mVideoRect.set(offX, offY, offX + outWidth, offY + outHeight);
        Log.d(TAG, "Adjusting window " + windowWidth + "x" + windowHeight +
                " to +" + offX + ",+" + offY + " " +
                mVideoRect.width() + "x" + mVideoRect.height());

        VideoEncoderCore encoderCore;
        try {
            encoderCore = new VideoEncoderCore(VIDEO_WIDTH, VIDEO_HEIGHT,
                    BIT_RATE, mOutputFile);
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
        mInputWindowSurface = new WindowSurface(mEglCore, encoderCore.getInputSurface(), true);
        mVideoEncoder = new TextureMovieEncoder2(encoderCore);
    }

    /**
     * Stops the video encoder if it's running.
     */
    private void stopEncoder() {
        if (mVideoEncoder != null) {
            Log.d(TAG, "stopping recorder, mVideoEncoder=" + mVideoEncoder);
            mVideoEncoder.stopRecording();
            // TODO: wait (briefly) until it finishes shutting down so we know file is
            //       complete, or have a callback that updates the UI
            mVideoEncoder = null;
        }
        if (mInputWindowSurface != null) {
            mInputWindowSurface.release();
            mInputWindowSurface = null;
        }
    }

    private class RecordFilter extends GPUImageFilter {

        @Override
        public void onDraw(int textureId, FloatBuffer cubeBuffer, FloatBuffer textureBuffer, long timeStampNanos) {
            if (mRecordingEnabled) {
                mVideoEncoder.frameAvailableSoon();
                mInputWindowSurface.makeCurrent();
                GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);    // again, only really need to
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);     //  clear pixels outside rect
                GLES20.glViewport(mVideoRect.left, mVideoRect.top,
                        mVideoRect.width(), mVideoRect.height());
                super.onDraw(textureId, cubeBuffer, textureBuffer, timeStampNanos);
                mInputWindowSurface.setPresentationTime(timeStampNanos);
                mInputWindowSurface.swapBuffers();
                GLES20.glViewport(0, 0, mWindowSurface.getWidth(), mWindowSurface.getHeight());
                mWindowSurface.makeCurrent();

            }
            super.onDraw(textureId, cubeBuffer, textureBuffer, timeStampNanos);
        }
    }

}
