package com.bingbing.cameratest;

import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.bingbing.cameratest.gpuimage.GPUImageRenderer;
import com.bingbing.cameratest.gpuimage.GPUImageRendererWithRecord;
import com.bingbing.cameratest.record.EglCore;
import com.bingbing.cameratest.record.GlUtil;
import com.bingbing.cameratest.record.TextureMovieEncoder2;
import com.bingbing.cameratest.record.VideoEncoderCore;
import com.bingbing.cameratest.record.WindowSurface;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;

/**
 * Created by Bingbing on 16/6/14.
 */
public class RenderThread extends Thread {
    private static final String TAG = "bingbing_RenderThread";
    // Object must be created on render thread to get correct Looper, but is used from
    // UI thread, so we need to declare it volatile to ensure the UI thread sees a fully
    // constructed object.
    private volatile RenderHandler mHandler;

    // Handler we can send messages to if we want to update the app UI.

    // Used to wait for the thread to start.
    private Object mStartLock = new Object();
    private boolean mReady = false;

    private volatile SurfaceTexture mSurfaceTexture;  // may be updated by UI thread
    private EglCore mEglCore;
    private WindowSurface mWindowSurface;

    // Previous frame time.
    private long mPrevTimeNanos;

    // FPS / drop counter.
    private long mRefreshPeriodNanos;
    private long mFpsCountStartNanos;
    private int mFpsCountFrame;
    private int mDroppedFrames;
    private boolean mPreviousWasDropped;

    private GPUImageRenderer mGPUImageRenderer;


    /**
     * Pass in the SurfaceView's SurfaceHolder.  Note the Surface may not yet exist.
     */
    public RenderThread(long refreshPeriodNs, GPUImageRenderer renderer) {
        mRefreshPeriodNanos = refreshPeriodNs;
        mGPUImageRenderer = renderer;
    }

    public void setSurface(SurfaceTexture surface) {
        mSurfaceTexture = surface;
    }

    public void setRenderer(GPUImageRenderer renderer) {
        mGPUImageRenderer = renderer;
    }
//    public void setSurface(Surface surface) {
//        mSurfaceTexture = surface;
//    }

    /**
     * Thread entry point.
     * <p/>
     * The thread should not be started until the Surface associated with the SurfaceHolder
     * has been created.  That way we don't have to wait for a separate "surface created"
     * message to arrive.
     */
    @Override
    public void run() {
        Looper.prepare();
        mHandler = new RenderHandler(this);
        mEglCore = new EglCore(null, EglCore.FLAG_RECORDABLE | EglCore.FLAG_TRY_GLES3);
        synchronized (mStartLock) {
            mReady = true;
            mStartLock.notify();    // signal waitUntilReady()
        }

        Looper.loop();

        Log.d(TAG, "looper quit");
        releaseGl();
        mEglCore.release();

        synchronized (mStartLock) {
            mReady = false;
        }
    }

    /**
     * Waits until the render thread is ready to receive messages.
     * <p/>
     * Call from the UI thread.
     */
    public void waitUntilReady() {
        synchronized (mStartLock) {
            while (!mReady) {
                try {
                    mStartLock.wait();
                } catch (InterruptedException ie) { /* not expected */ }
            }
        }
    }

    /**
     * Shuts everything down.
     */
    private void shutdown() {
        Log.d(TAG, "shutdown");
        Looper.myLooper().quit();
    }

    /**
     * Returns the render thread's Handler.  This may be called from any thread.
     */
    public RenderHandler getHandler() {
        return mHandler;
    }

    /**
     * Prepares the surface.
     */
    private void surfaceCreated() {
        SurfaceTexture surface = mSurfaceTexture;
        prepareGl(surface);
    }

    /**
     * Prepares window surface and GL state.
     */
    private void prepareGl(Surface surface) {
        Log.d(TAG, "prepareGl");

        mWindowSurface = new WindowSurface(mEglCore, surface);
        mWindowSurface.makeCurrent();

        // Set the background color.
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);

        // Disable depth testing -- we're 2D only.
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);

        // Don't need backface culling.  (If you're feeling pedantic, you can turn it on to
        // make sure we're defining our shapes correctly.)
        GLES20.glDisable(GLES20.GL_CULL_FACE);

    }

    /**
     * Prepares window surface and GL state.
     */
    private void prepareGl(SurfaceTexture surface) {
        Log.d(TAG, "prepareGl");

        mWindowSurface = new WindowSurface(mEglCore, surface);
        mWindowSurface.makeCurrent();

        // Set the background color.
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);

        // Disable depth testing -- we're 2D only.
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);

        // Don't need backface culling.  (If you're feeling pedantic, you can turn it on to
        // make sure we're defining our shapes correctly.)
        GLES20.glDisable(GLES20.GL_CULL_FACE);

        if (mGPUImageRenderer instanceof GPUImageRendererWithRecord) {
            ((GPUImageRendererWithRecord) mGPUImageRenderer).setEnv(mEglCore, mWindowSurface);
        }
    }

    /**
     * Handles changes to the size of the underlying surface.  Adjusts viewport as needed.
     * Must be called before we start drawing.
     * (Called from RenderHandler.)
     */
    private void surfaceChanged(int width, int height) {
        Log.d(TAG, "surfaceChanged " + width + "x" + height);

        // Use full window.
        GLES20.glViewport(0, 0, width, height);

    }


    /**
     * Releases most of the GL resources we currently hold.
     * <p/>
     * Does not release EglCore.
     */
    private void releaseGl() {
        GlUtil.checkGlError("releaseGl start");

        if (mWindowSurface != null) {
            mWindowSurface.release();
            mWindowSurface = null;
        }

        GlUtil.checkGlError("releaseGl done");

        mEglCore.makeNothingCurrent();
    }



    /**
     * Advance state and draw frame in response to a vsync event.
     */
    private void doFrame(long timeStampNanos) {
        // If we're not keeping up 60fps -- maybe something in the system is busy, maybe
        // recording is too expensive, maybe the CPU frequency governor thinks we're
        // not doing and wants to drop the clock frequencies -- we need to drop frames
        // to catch up.  The "timeStampNanos" value is based on the system monotonic
        // clock, as is System.nanoTime(), so we can compare the values directly.
        //
        // Our clumsy collision detection isn't sophisticated enough to deal with large
        // time gaps, but it's nearly cost-free, so we go ahead and do the computation
        // either way.
        //
        // We can reduce the overhead of recording, as well as the size of the movie,
        // by recording at ~30fps instead of the display refresh rate.  As a quick hack
        // we just record every-other frame, using a "recorded previous" flag.

        update(timeStampNanos);

        long diff = System.nanoTime() - timeStampNanos;
        long max = mRefreshPeriodNanos - 2000000;   // if we're within 2ms, don't bother
        if (diff > max) {
            // too much, drop a frame
            Log.d(TAG, "diff is " + (diff / 1000000.0) + " ms, max " + (max / 1000000.0) +
                    ", skipping render");
            mDroppedFrames++;
            return;
        }

        boolean swapResult;

//        if (!mRecordingEnabled) {
        // Render the scene, swap back to front.
//            draw();
        mGPUImageRenderer.onDrawFrame(timeStampNanos);
        swapResult = mWindowSurface.swapBuffers();
//        } else {

        // recording
//            if (mRecordMethod == RECMETHOD_DRAW_TWICE) {
//                //Log.d(TAG, "MODE: draw 2x");
//
//                // Draw for display, swap.
//                draw();
//                swapResult = mWindowSurface.swapBuffers();
//
//                // Draw for recording, swap.
//                mVideoEncoder.frameAvailableSoon();
//                mInputWindowSurface.makeCurrent();
//                // If we don't set the scissor rect, the glClear() we use to draw the
//                // light-grey background will draw outside the viewport and muck up our
//                // letterboxing.  Might be better if we disabled the test immediately after
//                // the glClear().  Of course, if we were clearing the frame background to
//                // black it wouldn't matter.
//                //
//                // We do still need to clear the pixels outside the scissor rect, of course,
//                // or we'll get garbage at the edges of the recording.  We can either clear
//                // the whole thing and accept that there will be a lot of overdraw, or we
//                // can issue multiple scissor/clear calls.  Some GPUs may have a special
//                // optimization for zeroing out the color buffer.
//                //
//                // For now, be lazy and zero the whole thing.  At some point we need to
//                // examine the performance here.
//                GLES20.glClearColor(0f, 0f, 0f, 1f);
//                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
//
//                GLES20.glViewport(mVideoRect.left, mVideoRect.top,
//                        mVideoRect.width(), mVideoRect.height());
//                GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
//                GLES20.glScissor(mVideoRect.left, mVideoRect.top,
//                        mVideoRect.width(), mVideoRect.height());
//                draw();
//                GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
//                mInputWindowSurface.setPresentationTime(timeStampNanos);
//                mInputWindowSurface.swapBuffers();
//
//                // Restore.
//                GLES20.glViewport(0, 0, mWindowSurface.getWidth(), mWindowSurface.getHeight());
//                mWindowSurface.makeCurrent();
//
//            } else if (mEglCore.getGlVersion() >= 3 &&
//                    mRecordMethod == RECMETHOD_BLIT_FRAMEBUFFER) {
//                //Log.d(TAG, "MODE: blitFramebuffer");
//                // Draw the frame, but don't swap it yet.
//                draw();
//
//                mVideoEncoder.frameAvailableSoon();
//                mInputWindowSurface.makeCurrentReadFrom(mWindowSurface);
//                // Clear the pixels we're not going to overwrite with the blit.  Once again,
//                // this is excessive -- we don't need to clear the entire screen.
//                GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
//                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
//                GlUtil.checkGlError("before glBlitFramebuffer");
//                Log.v(TAG, "glBlitFramebuffer: 0,0," + mWindowSurface.getWidth() + "," +
//                        mWindowSurface.getHeight() + "  " + mVideoRect.left + "," +
//                        mVideoRect.top + "," + mVideoRect.right + "," + mVideoRect.bottom +
//                        "  COLOR_BUFFER GL_NEAREST");
//                GLES30.glBlitFramebuffer(
//                        0, 0, mWindowSurface.getWidth(), mWindowSurface.getHeight(),
//                        mVideoRect.left, mVideoRect.top, mVideoRect.right, mVideoRect.bottom,
//                        GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST);
//                int err;
//                if ((err = GLES30.glGetError()) != GLES30.GL_NO_ERROR) {
//                    Log.w(TAG, "ERROR: glBlitFramebuffer failed: 0x" +
//                            Integer.toHexString(err));
//                }
//                mInputWindowSurface.setPresentationTime(timeStampNanos);
//                mInputWindowSurface.swapBuffers();
//
//                // Now swap the display buffer.
//                mWindowSurface.makeCurrent();
//                swapResult = mWindowSurface.swapBuffers();
//
//            } else {
        //Log.d(TAG, "MODE: offscreen + blit 2x");
        // Render offscreen.
//                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFramebuffer);
//                GlUtil.checkGlError("glBindFramebuffer");
//                draw();
//
//                // Blit to display.
//                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
//                GlUtil.checkGlError("glBindFramebuffer");
//                mFullScreen.drawFrame(mOffscreenTexture, mIdentityMatrix);
//                swapResult = mWindowSurface.swapBuffers();
//
//                // Blit to encoder.
//                mVideoEncoder.frameAvailableSoon();
//                mInputWindowSurface.makeCurrent();
//                GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);    // again, only really need to
//                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);     //  clear pixels outside rect
//                GLES20.glViewport(mVideoRect.left, mVideoRect.top,
//                        mVideoRect.width(), mVideoRect.height());
//                mFullScreen.drawFrame(mOffscreenTexture, mIdentityMatrix);
//                mInputWindowSurface.setPresentationTime(timeStampNanos);
//                mInputWindowSurface.swapBuffers();
//
//                // Restore previous values.
//                GLES20.glViewport(0, 0, mWindowSurface.getWidth(), mWindowSurface.getHeight());
//                mWindowSurface.makeCurrent();
//            }
//        }


        if (!swapResult) {
            // This can happen if the Activity stops without waiting for us to halt.
            Log.w(TAG, "swapBuffers failed, killing renderer thread");
            shutdown();
            return;
        }

        // Update the FPS counter.
        //
        // Ideally we'd generate something approximate quickly to make the UI look
        // reasonable, then ease into longer sampling periods.
        final int NUM_FRAMES = 120;
        final long ONE_TRILLION = 1000000000000L;
        if (mFpsCountStartNanos == 0) {
            mFpsCountStartNanos = timeStampNanos;
            mFpsCountFrame = 0;
        } else {
            mFpsCountFrame++;
            if (mFpsCountFrame == NUM_FRAMES) {
                // compute thousands of frames per second
                long elapsed = timeStampNanos - mFpsCountStartNanos;
//                mActivityHandler.sendFpsUpdate((int)(NUM_FRAMES * ONE_TRILLION / elapsed),
//                        mDroppedFrames);

                // reset
                mFpsCountStartNanos = timeStampNanos;
                mFpsCountFrame = 0;
            }
        }

    }

    /**
     * We use the time delta from the previous event to determine how far everything
     * moves.  Ideally this will yield identical animation sequences regardless of
     * the device's actual refresh rate.
     */
    private void update(long timeStampNanos) {
        // Compute time from previous frame.
        long intervalNanos;
        if (mPrevTimeNanos == 0) {
            intervalNanos = 0;
        } else {
            intervalNanos = timeStampNanos - mPrevTimeNanos;

            final long ONE_SECOND_NANOS = 1000000000L;
            if (intervalNanos > ONE_SECOND_NANOS) {
                // A gap this big should only happen if something paused us.  We can
                // either cap the delta at one second, or just pretend like this is
                // the first frame and not advance at all.
                Log.d(TAG, "Time delta too large: " +
                        (double) intervalNanos / ONE_SECOND_NANOS + " sec");
                intervalNanos = 0;
            }
        }
        mPrevTimeNanos = timeStampNanos;
    }

    /**
     * Draws the scene.
     */
    private void draw() {
        GlUtil.checkGlError("draw start");

        // Clear to a non-black color to make the content easily differentiable from
        // the pillar-/letter-boxing.
        GLES20.glClearColor(0.2f, 0.2f, 0.2f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);


        GlUtil.checkGlError("draw done");
    }


    /**
     * Handler for RenderThread.  Used for messages sent from the UI thread to the render thread.
     * <p/>
     * The object is created on the render thread, and the various "send" methods are called
     * from the UI thread.
     */

    public static class RenderHandler extends Handler {
        private static final int MSG_SURFACE_CREATED = 0;
        private static final int MSG_SURFACE_CHANGED = 1;
        private static final int MSG_DO_FRAME = 2;
        private static final int MSG_RECORDING_ENABLED = 3;
        private static final int MSG_SHUTDOWN = 5;

        // This shouldn't need to be a weak ref, since we'll go away when the Looper quits,
        // but no real harm in it.
        private WeakReference<RenderThread> mWeakRenderThread;

        /**
         * Call from render thread.
         */
        public RenderHandler(RenderThread rt) {
            mWeakRenderThread = new WeakReference<RenderThread>(rt);
        }

        /**
         * Sends the "surface created" message.
         * <p/>
         * Call from UI thread.
         */
        public void sendSurfaceCreated() {
            sendMessage(obtainMessage(RenderHandler.MSG_SURFACE_CREATED));
        }

        /**
         * Sends the "surface changed" message, forwarding what we got from the SurfaceHolder.
         * <p/>
         * Call from UI thread.
         */
        public void sendSurfaceChanged(@SuppressWarnings("unused") int format,
                                       int width, int height) {
            // ignore format
            sendMessage(obtainMessage(RenderHandler.MSG_SURFACE_CHANGED, width, height));
        }

        /**
         * Sends the "do frame" message, forwarding the Choreographer event.
         * <p/>
         * Call from UI thread.
         */
        public void sendDoFrame(long frameTimeNanos) {
            sendMessage(obtainMessage(RenderHandler.MSG_DO_FRAME,
                    (int) (frameTimeNanos >> 32), (int) frameTimeNanos));
        }


        /**
         * Sends the "shutdown" message, which tells the render thread to halt.
         * <p/>
         * Call from UI thread.
         */
        public void sendShutdown() {
            sendMessage(obtainMessage(RenderHandler.MSG_SHUTDOWN));
        }

        @Override  // runs on RenderThread
        public void handleMessage(Message msg) {
            int what = msg.what;
            //Log.d(TAG, "RenderHandler [" + this + "]: what=" + what);

            RenderThread renderThread = mWeakRenderThread.get();
            if (renderThread == null) {
                Log.w(TAG, "RenderHandler.handleMessage: weak ref is null");
                return;
            }

            switch (what) {
                case MSG_SURFACE_CREATED:
                    renderThread.surfaceCreated();
                    break;
                case MSG_SURFACE_CHANGED:
                    renderThread.surfaceChanged(msg.arg1, msg.arg2);
                    break;
                case MSG_DO_FRAME:
                    long timestamp = (((long) msg.arg1) << 32) |
                            (((long) msg.arg2) & 0xffffffffL);
                    renderThread.doFrame(timestamp);
                    break;
                case MSG_SHUTDOWN:
                    renderThread.shutdown();
                    break;
                default:
                    throw new RuntimeException("unknown message " + what);
            }
        }
    }
}


