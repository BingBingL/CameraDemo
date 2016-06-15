package com.bingbing.cameratest;

import android.app.Activity;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.TextureView;
import android.view.View;

import com.bingbing.cameratest.gpuimage.CameraHelper;
import com.bingbing.cameratest.gpuimage.GPUImageFilter;
import com.bingbing.cameratest.gpuimage.GPUImageFilterGroup;
import com.bingbing.cameratest.gpuimage.GPUImageRenderer;
import com.bingbing.cameratest.gpuimage.GPUImageRendererWithRecord;
import com.bingbing.cameratest.gpuimage.GPUImageTwoInputFilter;

import java.io.File;


public class MainActivity extends Activity implements View.OnClickListener {

    //    GLSurfaceView mGLSurface;
    TextureView mTextureView;

    private boolean mRecording;

    private GPUImageRendererWithRecord mGPUImageRender;
    private CameraHelper mCameraHelper;
    private CameraLoader mCamera;
    private RenderThread mRenderThread;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

//        mGLSurface = (GLSurfaceView) findViewById(R.id.surface_view);
        mTextureView = (TextureView) findViewById(R.id.surface_view);

        mRenderThread = new RenderThread(1000000000 / 60, mGPUImageRender);
        mRenderThread.setName("GL render");
        mRenderThread.start();
        mRenderThread.waitUntilReady();

        File outFile = new File("/sdcard/record_test.mp4");
        mGPUImageRender = new GPUImageRendererWithRecord(outFile);
        mRenderThread.setRenderer(mGPUImageRender);
        mGPUImageRender.setRenderHandler(mRenderThread.getHandler());
//        mGPUImageRender.setGLSurfaceView(mGLSurface);

        mCameraHelper = new CameraHelper(this);
        mCamera = new CameraLoader();


        mTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                RenderThread.RenderHandler rh = mRenderThread.getHandler();
                if (rh != null) {
                    rh.sendSurfaceCreated();
                }
                mRenderThread.setSurface(surface);
                mGPUImageRender.onSurfaceCreated();

                if (rh != null) {
                    rh.sendSurfaceChanged(0, width, height);
                }
                mGPUImageRender.onSurfaceChanged(null, width, height);

            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                RenderThread.RenderHandler rh = mRenderThread.getHandler();
                if (rh != null) {
                    rh.sendSurfaceChanged(0, width, height);
                }
                mGPUImageRender.onSurfaceChanged(null, width, height);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                RenderThread.RenderHandler rh = mRenderThread.getHandler();
                if (rh != null) {
                    rh.sendShutdown();
                    try {
                        mRenderThread.join();
                    } catch (InterruptedException ie) {
                        // not expected
                        throw new RuntimeException("join was interrupted", ie);
                    }
                }
                mRenderThread = null;
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {

            }
        });

        View cameraSwitchView = findViewById(R.id.img_switch_camera);
        cameraSwitchView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mCamera.switchCamera();
            }
        });

        if (!mCameraHelper.hasFrontCamera() || !mCameraHelper.hasBackCamera()) {
            cameraSwitchView.setVisibility(View.GONE);
        }


        findViewById(R.id.button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mGPUImageRender.getRecordingEnabled()) {
                    mGPUImageRender.setRecordingEnabled(false);
                } else {
                    mGPUImageRender.setRecordingEnabled(true);
                }
            }
        });


        GPUImageFilter filter = new GPUImageFilter(GPUImageFilter.NO_FILTER_VERTEX_SHADER, shader4);
        GPUImageTwoInputFilter filter1 = new GPUImageTwoInputFilter(shader1);
        filter1.setBitmap(BitmapFactory.decodeResource(getResources(), R.drawable.nature));
        GPUImageFilterGroup group = new GPUImageFilterGroup();
        group.addFilter(filter);
        group.addFilter(filter1);

        mGPUImageRender.setFilter(group);


    }

    @Override
    protected void onResume() {
        super.onResume();
        mCamera.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mCamera.onPause();
    }

    @Override
    public void onClick(View v) {

    }

    private class CameraLoader {

        private int mCurrentCameraId = 0;
        private Camera mCameraInstance;

        public void onResume() {
            setUpCamera(mCurrentCameraId);
        }

        public void onPause() {
            releaseCamera();
        }

        public void switchCamera() {
            releaseCamera();
            mCurrentCameraId = (mCurrentCameraId + 1) % mCameraHelper.getNumberOfCameras();
            setUpCamera(mCurrentCameraId);
        }

        private void setUpCamera(final int id) {
            mCameraInstance = getCameraInstance(id);
            Camera.Parameters parameters = mCameraInstance.getParameters();
            // TODO adjust by getting supportedPreviewSizes and then choosing
            // the best one for screen size (best fill screen)
            if (parameters.getSupportedFocusModes().contains(
                    Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            }
            parameters.setPreviewSize(1280, 720);
//            parameters.setPreviewFpsRange(60,60);
            mCameraInstance.setParameters(parameters);

            int orientation = mCameraHelper.getCameraDisplayOrientation(
                    MainActivity.this, mCurrentCameraId);
            CameraHelper.CameraInfo2 cameraInfo = new CameraHelper.CameraInfo2();
            mCameraHelper.getCameraInfo(mCurrentCameraId, cameraInfo);
            boolean flipVertical = cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT;
            mGPUImageRender.setUpCamera(mCameraInstance, orientation, false, flipVertical);
        }

        /**
         * A safe way to get an instance of the Camera object.
         */
        private Camera getCameraInstance(final int id) {
            Camera c = null;
            try {
                c = mCameraHelper.openCamera(id);
            } catch (Exception e) {
                e.printStackTrace();
            }
            return c;
        }

        private void releaseCamera() {
            mCameraInstance.setPreviewCallback(null);
            mCameraInstance.release();
            mCameraInstance = null;
        }
    }


    private static final String shader4 = " precision highp float;\n"+
" uniform sampler2D inputImageTexture;\n"+
" varying highp vec2 textureCoordinate;\n"+
"void main(){\n"+
"   vec3 centralColor;\n"+
"   float sampleColor;\n"+
"   vec2 blurCoordinates[12];\n"+
"   float mul = 1.5;\n"+
"   float mul_x = mul / 1280.0; \n"+
"   float mul_y = mul / 720.0; \n"+
"   blurCoordinates[0] = textureCoordinate + vec2(5.0 * mul_x,-8.0 * mul_y); \n"+
"   blurCoordinates[1] = textureCoordinate + vec2(8.0 * mul_x,-5.0 * mul_y); \n"+
"   blurCoordinates[2] = textureCoordinate + vec2(8.0 * mul_x,5.0 * mul_y);  \n"+
"   blurCoordinates[3] = textureCoordinate + vec2(5.0 * mul_x,8.0 * mul_y);  \n"+
"   blurCoordinates[4] = textureCoordinate + vec2(-5.0 * mul_x,8.0 * mul_y); \n"+
"   blurCoordinates[5] = textureCoordinate + vec2(-8.0 * mul_x,5.0 * mul_y); \n"+
"   blurCoordinates[6] = textureCoordinate + vec2(-8.0 * mul_x,-5.0 * mul_y); \n"+
"   blurCoordinates[7] = textureCoordinate + vec2(-5.0 * mul_x,-8.0 * mul_y); \n"+
"   blurCoordinates[8] = textureCoordinate + vec2(0.0 * mul_x,-6.0 * mul_y); \n"+
"   blurCoordinates[9] = textureCoordinate + vec2(-6.0 * mul_x,0.0 * mul_y);  \n"+
"   blurCoordinates[10] = textureCoordinate + vec2(0.0 * mul_x,6.0 * mul_y);  \n"+
"   blurCoordinates[11] = textureCoordinate + vec2(4.0 * mul_x,-4.0 * mul_y); \n"+
"   sampleColor = texture2D(inputImageTexture, textureCoordinate).b * 20.0; \n"+
"   sampleColor += texture2D(inputImageTexture, blurCoordinates[0]).b; \n"+
"   sampleColor += texture2D(inputImageTexture, blurCoordinates[1]).b; \n"+
"   sampleColor += texture2D(inputImageTexture, blurCoordinates[2]).b; \n"+
"   sampleColor += texture2D(inputImageTexture, blurCoordinates[3]).b; \n"+
"   sampleColor += texture2D(inputImageTexture, blurCoordinates[4]).b; \n"+
"   sampleColor += texture2D(inputImageTexture, blurCoordinates[5]).b; \n"+
"   sampleColor += texture2D(inputImageTexture, blurCoordinates[6]).b; \n"+
"   sampleColor += texture2D(inputImageTexture, blurCoordinates[7]).b; \n"+
"   sampleColor += texture2D(inputImageTexture, blurCoordinates[8]).b; \n"+
"   sampleColor += texture2D(inputImageTexture, blurCoordinates[9]).b; \n"+
"   sampleColor += texture2D(inputImageTexture, blurCoordinates[10]).b; \n"+
"   sampleColor += texture2D(inputImageTexture, blurCoordinates[11]).b; \n"+
"   sampleColor = sampleColor/32.0; \n"+
"   centralColor = texture2D(inputImageTexture, textureCoordinate).rgb; \n"+
"   float dis = centralColor.b - sampleColor + 0.5; \n"+
"   if(dis <= 0.5) \n"+
"   { \n"+
"   dis = dis * dis * 2.0; \n"+
"   } \n"+
"   else \n"+
"   { \n"+
"   dis = 1.0 - ((1.0 - dis)*(1.0 - dis) * 2.0); \n"+
"   } \n"+
"   if(dis <= 0.5) \n"+
"   { \n"+
"   dis = dis * dis * 2.0; \n"+
"   } \n"+
"   else \n"+
"   { \n"+
"   dis = 1.0 - ((1.0 - dis)*(1.0 - dis) * 2.0); \n"+
"   } \n"+
"   if(dis <= 0.5) \n"+
"   { \n"+
"   dis = dis * dis * 2.0; \n"+
"   } \n"+
"   else \n"+
"   { \n"+
"   dis = 1.0 - ((1.0 - dis)*(1.0 - dis) * 2.0); \n"+
"   } \n"+
"   if(dis <= 0.5) \n"+
"   { \n"+
"   dis = dis * dis * 2.0; \n"+
"   } \n"+
"   else \n"+
"   { \n"+
"   dis = 1.0 - ((1.0 - dis)*(1.0 - dis) * 2.0); \n"+
"   } \n"+
"   if(dis <= 0.5) \n"+
"   { \n"+
"   dis = dis * dis * 2.0; \n"+
"   } \n"+
"   else \n"+
"   { \n"+
"   dis = 1.0 - ((1.0 - dis)*(1.0 - dis) * 2.0); \n"+
"   } \n"+
"   float aa = 1.065;\n"+
"   gl_FragColor = vec4(centralColor*aa - vec3(dis)*(aa-1.0), 1.0);\n"+
"   float Hue = dot(gl_FragColor.rgb, vec3(0.299,0.587,0.114));\n"+
"   Hue = clamp(Hue - 0.3, 0.0, 1.0);\n"+
"   gl_FragColor.rgb = centralColor * (1.0 - pow(Hue,0.3)) + gl_FragColor.rgb * pow(Hue,0.3);\n"+
"   gl_FragColor = vec4(((gl_FragColor.rgb - vec3(0.8)) * 1.06 + vec3(0.8)), 1.0);\n"+
"}\n"+
"";
    private static final String shader = "//meiyan3\n" +
            "\n" +
            "\n" +
            "precision highp float;\n" +
            "uniform sampler2D inputImageTexture;\n" +
            "varying highp vec2 textureCoordinate;\n" +
            "\n" +
            "\n" +
            "\n" +
            "void main(){\n" +
            "\n" +
            "vec3 centralColor;\n" +
            "float sampleColor;\n" +
            "\n" +
            "\n" +
            "vec2 blurCoordinates[20];\n" +
            "\n" +
            "float mul = 2.0;\n" +
            "\n" +
            "float mul_x = mul / 1280.0;\n" +
            "float mul_y = mul / 720.0;\n" +
            "\n" +
            "\n" +
            "blurCoordinates[0] = textureCoordinate + vec2(0.0 * mul_x,-10.0 * mul_y);\n" +
            "blurCoordinates[1] = textureCoordinate + vec2(5.0 * mul_x,-8.0 * mul_y);\n" +
            "blurCoordinates[2] = textureCoordinate + vec2(8.0 * mul_x,-5.0 * mul_y);\n" +
            "blurCoordinates[3] = textureCoordinate + vec2(10.0 * mul_x,0.0 * mul_y);\n" +
            "blurCoordinates[4] = textureCoordinate + vec2(8.0 * mul_x,5.0 * mul_y);\n" +
            "blurCoordinates[5] = textureCoordinate + vec2(5.0 * mul_x,8.0 * mul_y);\n" +
            "blurCoordinates[6] = textureCoordinate + vec2(0.0 * mul_x,10.0 * mul_y);\n" +
            "blurCoordinates[7] = textureCoordinate + vec2(-5.0 * mul_x,8.0 * mul_y);\n" +
            "blurCoordinates[8] = textureCoordinate + vec2(-8.0 * mul_x,5.0 * mul_y);\n" +
            "blurCoordinates[9] = textureCoordinate + vec2(-10.0 * mul_x,0.0 * mul_y);\n" +
            "blurCoordinates[10] = textureCoordinate + vec2(-8.0 * mul_x,-5.0 * mul_y);\n" +
            "blurCoordinates[11] = textureCoordinate + vec2(-5.0 * mul_x,-8.0 * mul_y);\n" +
            "blurCoordinates[12] = textureCoordinate + vec2(0.0 * mul_x,-6.0 * mul_y);\n" +
            "blurCoordinates[13] = textureCoordinate + vec2(-4.0 * mul_x,-4.0 * mul_y);\n" +
            "blurCoordinates[14] = textureCoordinate + vec2(-6.0 * mul_x,0.0 * mul_y);\n" +
            "blurCoordinates[15] = textureCoordinate + vec2(-4.0 * mul_x,4.0 * mul_y);\n" +
            "blurCoordinates[16] = textureCoordinate + vec2(0.0 * mul_x,6.0 * mul_y);\n" +
            "blurCoordinates[17] = textureCoordinate + vec2(4.0 * mul_x,4.0 * mul_y);\n" +
            "blurCoordinates[18] = textureCoordinate + vec2(6.0 * mul_x,0.0 * mul_y);\n" +
            "blurCoordinates[19] = textureCoordinate + vec2(4.0 * mul_x,-4.0 * mul_y);\n" +
            "\n" +
            "\n" +
            "sampleColor = texture2D(inputImageTexture, textureCoordinate).g * 22.0;\n" +
            "\n" +
            "sampleColor += texture2D(inputImageTexture, blurCoordinates[0]).g;\n" +
            "sampleColor += texture2D(inputImageTexture, blurCoordinates[1]).g;\n" +
            "sampleColor += texture2D(inputImageTexture, blurCoordinates[2]).g;\n" +
            "sampleColor += texture2D(inputImageTexture, blurCoordinates[3]).g;\n" +
            "sampleColor += texture2D(inputImageTexture, blurCoordinates[4]).g;\n" +
            "sampleColor += texture2D(inputImageTexture, blurCoordinates[5]).g;\n" +
            "sampleColor += texture2D(inputImageTexture, blurCoordinates[6]).g;\n" +
            "sampleColor += texture2D(inputImageTexture, blurCoordinates[7]).g;\n" +
            "sampleColor += texture2D(inputImageTexture, blurCoordinates[8]).g;\n" +
            "sampleColor += texture2D(inputImageTexture, blurCoordinates[9]).g;\n" +
            "sampleColor += texture2D(inputImageTexture, blurCoordinates[10]).g;\n" +
            "sampleColor += texture2D(inputImageTexture, blurCoordinates[11]).g;\n" +
            "sampleColor += texture2D(inputImageTexture, blurCoordinates[12]).g * 2.0;\n" +
            "sampleColor += texture2D(inputImageTexture, blurCoordinates[13]).g * 2.0;\n" +
            "sampleColor += texture2D(inputImageTexture, blurCoordinates[14]).g * 2.0;\n" +
            "sampleColor += texture2D(inputImageTexture, blurCoordinates[15]).g * 2.0;\n" +
            "sampleColor += texture2D(inputImageTexture, blurCoordinates[16]).g * 2.0;\n" +
            "sampleColor += texture2D(inputImageTexture, blurCoordinates[17]).g * 2.0;\n" +
            "sampleColor += texture2D(inputImageTexture, blurCoordinates[18]).g * 2.0;\n" +
            "sampleColor += texture2D(inputImageTexture, blurCoordinates[19]).g * 2.0;\n" +
            "\n" +
            "\n" +
            "\n" +
            "sampleColor = sampleColor/50.0;\n" +
            "\n" +
            "\n" +
            "centralColor = texture2D(inputImageTexture, textureCoordinate).rgb;\n" +
            "\n" +
            "float dis = centralColor.g - sampleColor + 0.5;\n" +
            "\n" +
            "\n" +
            "if(dis <= 0.5)\n" +
            "{\n" +
            "dis = dis * dis * 2.0;\n" +
            "}\n" +
            "else\n" +
            "{\n" +
            "dis = 1.0 - ((1.0 - dis)*(1.0 - dis) * 2.0);\n" +
            "}\n" +
            "\n" +
            "if(dis <= 0.5)\n" +
            "{\n" +
            "dis = dis * dis * 2.0;\n" +
            "}\n" +
            "else\n" +
            "{\n" +
            "dis = 1.0 - ((1.0 - dis)*(1.0 - dis) * 2.0);\n" +
            "}\n" +
            "\n" +
            "if(dis <= 0.5)\n" +
            "{\n" +
            "dis = dis * dis * 2.0;\n" +
            "}\n" +
            "else\n" +
            "{\n" +
            "dis = 1.0 - ((1.0 - dis)*(1.0 - dis) * 2.0);\n" +
            "}\n" +
            "\n" +
            "if(dis <= 0.5)\n" +
            "{\n" +
            "dis = dis * dis * 2.0;\n" +
            "}\n" +
            "else\n" +
            "{\n" +
            "dis = 1.0 - ((1.0 - dis)*(1.0 - dis) * 2.0);\n" +
            "}\n" +
            "\n" +
            "if(dis <= 0.5)\n" +
            "{\n" +
            "dis = dis * dis * 2.0;\n" +
            "}\n" +
            "else\n" +
            "{\n" +
            "dis = 1.0 - ((1.0 - dis)*(1.0 - dis) * 2.0);\n" +
            "}\n" +
            "\n" +
            "\n" +
            "float aa= 1.03;\n" +
            "vec3 smoothColor = centralColor*aa - vec3(dis)*(aa-1.0);\n" +
            "\n" +
            "float hue = dot(smoothColor, vec3(0.299,0.587,0.114));\n" +
            "\n" +
            "aa = 1.0 + pow(hue, 0.6)*0.1;\n" +
            "smoothColor = centralColor*aa - vec3(dis)*(aa-1.0);\n" +
            "\n" +
            "smoothColor.r = clamp(pow(smoothColor.r, 0.8),0.0,1.0);\n" +
            "smoothColor.g = clamp(pow(smoothColor.g, 0.8),0.0,1.0);\n" +
            "smoothColor.b = clamp(pow(smoothColor.b, 0.8),0.0,1.0);\n" +
            "\n" +
            "\n" +
            "vec3 lvse = vec3(1.0)-(vec3(1.0)-smoothColor)*(vec3(1.0)-centralColor);\n" +
            "vec3 bianliang = max(smoothColor, centralColor);\n" +
            "vec3 rouguang = 2.0*centralColor*smoothColor + centralColor*centralColor - 2.0*centralColor*centralColor*smoothColor;\n" +
            "\n" +
            "\n" +
            "gl_FragColor = vec4(mix(centralColor, lvse, pow(hue, 0.6)), 1.0);\n" +
            "gl_FragColor.rgb = mix(gl_FragColor.rgb, bianliang, pow(hue, 0.6));\n" +
            "gl_FragColor.rgb = mix(gl_FragColor.rgb, rouguang, 0.25);\n" +
            "\n" +
            "\n" +
            "\n" +
            "mat3 saturateMatrix = mat3(\n" +
            "1.1102,\n" +
            "-0.0598,\n" +
            "-0.061,\n" +
            "-0.0774,\n" +
            "1.0826,\n" +
            "-0.1186,\n" +
            "-0.0228,\n" +
            "-0.0228,\n" +
            "1.1772);\n" +
            "\n" +
            "vec3 satcolor = gl_FragColor.rgb * saturateMatrix;\n" +
            "gl_FragColor.rgb = mix(gl_FragColor.rgb, satcolor, 0.25);\n" +
            "\n" +
            "\n" +
            "}";

    private static final String shader1 = "precision lowp float;\n" +
            "\n" +
            "varying highp vec2 textureCoordinate;\n" +
            "\n" +
            "uniform sampler2D inputImageTexture;\n" +
            "uniform sampler2D inputImageTexture2;\n" +
            "uniform int disableEffect;\n" +
            "\n" +
            "void main()\n" +
            "{\n" +
            "\n" +
            "     if (disableEffect == 2) {\n" +
            "         gl_FragColor = vec4(0, 0, 0, 1.0);\n" +
            "         return;\n" +
            "     }\n" +
            "\n" +
            "     if (disableEffect == 1) {\n" +
            "         gl_FragColor = texture2D(inputImageTexture, textureCoordinate);\n" +
            "         return;\n" +
            "     }\n" +
            "\n" +
            "highp vec4 textureColor = texture2D(inputImageTexture, textureCoordinate);\n" +
            "\n" +
            "highp float blueColor = textureColor.b * 15.0;\n" +
            "\n" +
            "highp vec2 quad1;\n" +
            "quad1.y = floor(floor(blueColor) / 4.0);\n" +
            "quad1.x = floor(blueColor) - (quad1.y * 4.0);\n" +
            "\n" +
            "highp vec2 quad2;\n" +
            "quad2.y = floor(ceil(blueColor) / 4.0);\n" +
            "quad2.x = ceil(blueColor) - (quad2.y * 4.0);\n" +
            "\n" +
            "highp vec2 texPos1;\n" +
            "texPos1.x = (quad1.x * 0.25) + 0.5/64.0 + ((0.25 - 1.0/64.0) * textureColor.r);\n" +
            "texPos1.y = (quad1.y * 0.25) + 0.5/64.0 + ((0.25 - 1.0/64.0) * textureColor.g);\n" +
            "\n" +
            "highp vec2 texPos2;\n" +
            "texPos2.x = (quad2.x * 0.25) + 0.5/64.0 + ((0.25 - 1.0/64.0) * textureColor.r);\n" +
            "texPos2.y = (quad2.y * 0.25) + 0.5/64.0 + ((0.25 - 1.0/64.0) * textureColor.g);\n" +
            "\n" +
            "lowp vec4 newColor1 = texture2D(inputImageTexture2, texPos1);\n" +
            "lowp vec4 newColor2 = texture2D(inputImageTexture2, texPos2);\n" +
            "\n" +
            "lowp vec4 newColor = mix(newColor1, newColor2, fract(blueColor));\n" +
            "gl_FragColor = mix(textureColor, vec4(newColor.rgb, textureColor.w), 1.0);\n" +
            "\n" +
            "}";

}
