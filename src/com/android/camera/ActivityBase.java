/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.camera;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.hardware.Camera.Parameters;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.DecelerateInterpolator;

import com.android.camera.ui.CameraPicker;
import com.android.camera.ui.LayoutChangeNotifier;
import com.android.camera.ui.PopupManager;
import com.android.camera.ui.RotateImageView;
import com.android.gallery3d.app.AbstractGalleryActivity;
import com.android.gallery3d.app.AppBridge;
import com.android.gallery3d.app.GalleryActionBar;
import com.android.gallery3d.app.PhotoPage;
import com.android.gallery3d.common.ApiHelper;
import com.android.gallery3d.ui.BitmapScreenNail;
import com.android.gallery3d.ui.ScreenNail;
import com.android.gallery3d.util.MediaSetUtils;

import java.io.File;

/**
 * Superclass of Camera and VideoCamera activities.
 */
public abstract class ActivityBase extends AbstractGalleryActivity
        implements LayoutChangeNotifier.Listener {

    private static final String TAG = "ActivityBase";
    private static final boolean LOGV = false;
    private static final int CAMERA_APP_VIEW_TOGGLE_TIME = 100;  // milliseconds
    private static final String ACTION_DELETE_PICTURE =
            "com.android.gallery3d.action.DELETE_PICTURE";

    private int mResultCodeForTesting;
    private Intent mResultDataForTesting;
    private OnScreenHint mStorageHint;
    private View mSingleTapArea;

    // The bitmap of the last captured picture thumbnail and the URI of the
    // original picture.
    protected Thumbnail mThumbnail;
    protected int mThumbnailViewWidth; // layout width of the thumbnail
    protected AsyncTask<Void, Void, Thumbnail> mLoadThumbnailTask;
    // An imageview showing the last captured picture thumbnail.
    protected RotateImageView mThumbnailView;
    protected CameraPicker mCameraPicker;

    protected boolean mOpenCameraFail;
    protected boolean mCameraDisabled;
    protected CameraManager.CameraProxy mCameraDevice;
    protected Parameters mParameters;
    // The activity is paused. The classes that extend this class should set
    // mPaused the first thing in onResume/onPause.
    protected boolean mPaused;
    protected GalleryActionBar mActionBar;

    // multiple cameras support
    protected int mNumberOfCameras;
    protected int mCameraId;
    // The activity is going to switch to the specified camera id. This is
    // needed because texture copy is done in GL thread. -1 means camera is not
    // switching.
    protected int mPendingSwitchCameraId = -1;

    protected MyAppBridge mAppBridge;
    protected ScreenNail mCameraScreenNail; // This shows camera preview.
    // The view containing only camera related widgets like control panel,
    // indicator bar, focus indicator and etc.
    protected View mCameraAppView;
    protected boolean mShowCameraAppView = true;
    private Animation mCameraAppViewFadeIn;
    private Animation mCameraAppViewFadeOut;

    private long mStorageSpace = Storage.LOW_STORAGE_THRESHOLD;
    private static final int UPDATE_STORAGE_HINT = 0;
    private final Handler mHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case UPDATE_STORAGE_HINT:
                        updateStorageHint();
                        return;
                }
            }
    };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_MEDIA_MOUNTED)
                    || action.equals(Intent.ACTION_MEDIA_UNMOUNTED)
                    || action.equals(Intent.ACTION_MEDIA_CHECKING)
                    || action.equals(Intent.ACTION_MEDIA_SCANNER_FINISHED)) {
                updateStorageSpaceAndHint();
            }
        }
    };

    private boolean mUpdateThumbnailDelayed;
    private IntentFilter mDeletePictureFilter =
            new IntentFilter(ACTION_DELETE_PICTURE);
    private BroadcastReceiver mDeletePictureReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (mShowCameraAppView) {
                        getLastThumbnailUncached();
                    } else {
                        mUpdateThumbnailDelayed = true;
                    }
                }
            };

    protected class CameraOpenThread extends Thread {
        @Override
        public void run() {
            try {
                mCameraDevice = Util.openCamera(ActivityBase.this, mCameraId);
                mParameters = mCameraDevice.getParameters();
            } catch (CameraHardwareException e) {
                mOpenCameraFail = true;
            } catch (CameraDisabledException e) {
                mCameraDisabled = true;
            }
        }
    }

    @Override
    public void onCreate(Bundle icicle) {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        super.disableToggleStatusBar();
        // Set a theme with action bar. It is not specified in manifest because
        // we want to hide it by default. setTheme must happen before
        // setContentView.
        //
        // This must be set before we call super.onCreate(), where the window's
        // background is removed.
        setTheme(R.style.Theme_Gallery);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        requestWindowFeature(Window.FEATURE_ACTION_BAR_OVERLAY);

        super.onCreate(icicle);
    }

    public boolean isPanoramaActivity() {
        return false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        LocalBroadcastManager manager = LocalBroadcastManager.getInstance(this);
        manager.registerReceiver(mDeletePictureReceiver, mDeletePictureFilter);

        installIntentFilter();
        if(updateStorageHintOnResume()) {
            updateStorageSpace();
            mHandler.sendEmptyMessageDelayed(UPDATE_STORAGE_HINT, 200);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager manager = LocalBroadcastManager.getInstance(this);
        manager.unregisterReceiver(mDeletePictureReceiver);

        if (LOGV) Log.v(TAG, "onPause");
        saveThumbnailToFile();

        if (mLoadThumbnailTask != null) {
            mLoadThumbnailTask.cancel(true);
            mLoadThumbnailTask = null;
        }

        if (mStorageHint != null) {
            mStorageHint.cancel();
            mStorageHint = null;
        }

        unregisterReceiver(mReceiver);
    }

    @Override
    public void setContentView(int layoutResID) {
        super.setContentView(layoutResID);
        // getActionBar() should be after setContentView
        mActionBar = new GalleryActionBar(this);
        mActionBar.hide();
    }

    @Override
    public boolean onSearchRequested() {
        return false;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Prevent software keyboard or voice search from showing up.
        if (keyCode == KeyEvent.KEYCODE_SEARCH
                || keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.isLongPress()) return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU && mShowCameraAppView) {
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU && mShowCameraAppView) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    protected void setResultEx(int resultCode) {
        mResultCodeForTesting = resultCode;
        setResult(resultCode);
    }

    protected void setResultEx(int resultCode, Intent data) {
        mResultCodeForTesting = resultCode;
        mResultDataForTesting = data;
        setResult(resultCode, data);
    }

    public int getResultCode() {
        return mResultCodeForTesting;
    }

    public Intent getResultData() {
        return mResultDataForTesting;
    }

    @Override
    protected void onDestroy() {
        PopupManager.removeInstance(this);
        super.onDestroy();
    }

    protected void installIntentFilter() {
        // install an intent filter to receive SD card related events.
        IntentFilter intentFilter =
                new IntentFilter(Intent.ACTION_MEDIA_MOUNTED);
        intentFilter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        intentFilter.addAction(Intent.ACTION_MEDIA_SCANNER_FINISHED);
        intentFilter.addAction(Intent.ACTION_MEDIA_CHECKING);
        intentFilter.addDataScheme("file");
        registerReceiver(mReceiver, intentFilter);
    }

    protected void updateStorageSpace() {
        mStorageSpace = Storage.getAvailableSpace();
    }

    protected long getStorageSpace() {
        return mStorageSpace;
    }

    protected void updateStorageSpaceAndHint() {
        updateStorageSpace();
        updateStorageHint(mStorageSpace);
    }

    protected void updateStorageHint() {
        updateStorageHint(mStorageSpace);
    }

    protected boolean updateStorageHintOnResume() {
        return true;
    }

    protected void updateStorageHint(long storageSpace) {
        String message = null;
        if (storageSpace == Storage.UNAVAILABLE) {
            message = getString(R.string.no_storage);
        } else if (storageSpace == Storage.PREPARING) {
            message = getString(R.string.preparing_sd);
        } else if (storageSpace == Storage.UNKNOWN_SIZE) {
            message = getString(R.string.access_sd_fail);
        } else if (storageSpace <= Storage.LOW_STORAGE_THRESHOLD) {
            message = getString(R.string.spaceIsLow_content);
        }

        if (message != null) {
            if (mStorageHint == null) {
                mStorageHint = OnScreenHint.makeText(this, message);
            } else {
                mStorageHint.setText(message);
            }
            mStorageHint.show();
        } else if (mStorageHint != null) {
            mStorageHint.cancel();
            mStorageHint = null;
        }
    }

    protected void updateThumbnailView() {
        if (mThumbnail != null) {
            mThumbnailView.setBitmap(mThumbnail.getBitmap());
            mThumbnailView.setVisibility(View.VISIBLE);
        } else {
            mThumbnailView.setBitmap(null);
            mThumbnailView.setVisibility(View.GONE);
        }
    }

    protected void getLastThumbnail() {
        mThumbnail = ThumbnailHolder.getLastThumbnail(getContentResolver());
        // Suppose users tap the thumbnail view, go to the gallery, delete the
        // image, and coming back to the camera. Thumbnail file will be invalid.
        // Since the new thumbnail will be loaded in another thread later, the
        // view should be set to gone to prevent from opening the invalid image.
        updateThumbnailView();
        if (mThumbnail == null) {
            mLoadThumbnailTask = new LoadThumbnailTask(true).execute();
        }
    }

    protected void getLastThumbnailUncached() {
        if (mLoadThumbnailTask != null) mLoadThumbnailTask.cancel(true);
        mLoadThumbnailTask = new LoadThumbnailTask(false).execute();
    }

    private class LoadThumbnailTask extends AsyncTask<Void, Void, Thumbnail> {
        private boolean mLookAtCache;

        public LoadThumbnailTask(boolean lookAtCache) {
            mLookAtCache = lookAtCache;
        }

        @Override
        protected Thumbnail doInBackground(Void... params) {
            // Load the thumbnail from the file.
            ContentResolver resolver = getContentResolver();
            Thumbnail t = null;
            if (mLookAtCache) {
                t = Thumbnail.getLastThumbnailFromFile(getFilesDir(), resolver);
            }

            if (isCancelled()) return null;

            if (t == null) {
                Thumbnail result[] = new Thumbnail[1];
                // Load the thumbnail from the media provider.
                int code = Thumbnail.getLastThumbnailFromContentResolver(
                        resolver, result);
                switch (code) {
                    case Thumbnail.THUMBNAIL_FOUND:
                        return result[0];
                    case Thumbnail.THUMBNAIL_NOT_FOUND:
                        return null;
                    case Thumbnail.THUMBNAIL_DELETED:
                        cancel(true);
                        return null;
                }
            }
            return t;
        }

        @Override
        protected void onPostExecute(Thumbnail thumbnail) {
            if (isCancelled()) return;
            mThumbnail = thumbnail;
            updateThumbnailView();
        }
    }

    protected void gotoGallery() {
        // Move the next picture with capture animation. "1" means next.
        mAppBridge.switchWithCaptureAnimation(1);
    }

    protected void saveThumbnailToFile() {
        if (mThumbnail != null && !mThumbnail.fromFile()) {
            new SaveThumbnailTask().execute(mThumbnail);
        }
    }

    private class SaveThumbnailTask extends AsyncTask<Thumbnail, Void, Void> {
        @Override
        protected Void doInBackground(Thumbnail... params) {
            final int n = params.length;
            final File filesDir = getFilesDir();
            for (int i = 0; i < n; i++) {
                params[i].saveLastThumbnailToFile(filesDir);
            }
            return null;
        }
    }

    // Call this after setContentView.
    protected void createCameraScreenNail(boolean getPictures) {
        mCameraAppView = findViewById(R.id.camera_app_root);
        Bundle data = new Bundle();
        String path = "/local/all/";
        // Intent mode does not show camera roll. Use 0 as a work around for
        // invalid bucket id.
        // TODO: add support of empty media set in gallery.
        path += (getPictures ? MediaSetUtils.CAMERA_BUCKET_ID : "0");
        data.putString(PhotoPage.KEY_MEDIA_SET_PATH, path);
        data.putString(PhotoPage.KEY_MEDIA_ITEM_PATH, path);

        // Send an AppBridge to gallery to enable the camera preview.
        mAppBridge = new MyAppBridge();
        data.putParcelable(PhotoPage.KEY_APP_BRIDGE, mAppBridge);
        getStateManager().startState(PhotoPage.class, data);
        mCameraScreenNail = mAppBridge.getCameraScreenNail();
    }

    private class HideCameraAppView implements Animation.AnimationListener {
        @Override
        public void onAnimationEnd(Animation animation) {
            // We cannot set this as GONE because we want to receive the
            // onLayoutChange() callback even when we are invisible.
            mCameraAppView.setVisibility(View.INVISIBLE);
        }

        @Override
        public void onAnimationRepeat(Animation animation) {
        }

        @Override
        public void onAnimationStart(Animation animation) {
        }
    }

    protected void updateCameraAppView() {
        // Initialize the animation.
        if (mCameraAppViewFadeIn == null) {
            mCameraAppViewFadeIn = new AlphaAnimation(0f, 1f);
            mCameraAppViewFadeIn.setDuration(CAMERA_APP_VIEW_TOGGLE_TIME);
            mCameraAppViewFadeIn.setInterpolator(new DecelerateInterpolator());

            mCameraAppViewFadeOut = new AlphaAnimation(1f, 0f);
            mCameraAppViewFadeOut.setDuration(CAMERA_APP_VIEW_TOGGLE_TIME);
            mCameraAppViewFadeOut.setInterpolator(new DecelerateInterpolator());
            mCameraAppViewFadeOut.setAnimationListener(new HideCameraAppView());
        }

        if (mShowCameraAppView) {
            mCameraAppView.setVisibility(View.VISIBLE);
            // The "transparent region" is not recomputed when a sibling of
            // SurfaceView changes visibility (unless it involves GONE). It's
            // been broken since 1.0. Call requestLayout to work around it.
            mCameraAppView.requestLayout();
            mCameraAppView.startAnimation(mCameraAppViewFadeIn);
        } else {
            mCameraAppView.startAnimation(mCameraAppViewFadeOut);
        }
    }

    protected void onFullScreenChanged(boolean full) {
        if (mShowCameraAppView == full) return;
        mShowCameraAppView = full;
        if (mPaused || isFinishing()) return;
        updateCameraAppView();

        // If we received DELETE_PICTURE broadcasts while the Camera UI is
        // hidden, we update the thumbnail now.
        if (full && mUpdateThumbnailDelayed) {
            getLastThumbnailUncached();
            mUpdateThumbnailDelayed = false;
        }
    }

    @Override
    public GalleryActionBar getGalleryActionBar() {
        return mActionBar;
    }

    // Preview frame layout has changed.
    @Override
    public void onLayoutChange(View v, int left, int top, int right, int bottom) {
        if (mAppBridge == null) return;

        int width = right - left;
        int height = bottom - top;
        if (ApiHelper.HAS_SURFACE_TEXTURE) {
            CameraScreenNail screenNail = (CameraScreenNail) mCameraScreenNail;
            if (Util.getDisplayRotation(this) % 180 == 0) {
                screenNail.setPreviewFrameLayoutSize(width, height);
            } else {
                // Swap the width and height. Camera screen nail draw() is based on
                // natural orientation, not the view system orientation.
                screenNail.setPreviewFrameLayoutSize(height, width);
            }
        }

        // Find out the coordinates of the preview frame relative to GL
        // root view.
        View root = (View) getGLRoot();
        int[] rootLocation = new int[2];
        int[] viewLocation = new int[2];
        root.getLocationInWindow(rootLocation);
        v.getLocationInWindow(viewLocation);

        int l = viewLocation[0] - rootLocation[0];
        int t = viewLocation[1] - rootLocation[1];
        int r = l + width;
        int b = t + height;
        Rect frame = new Rect(l, t, r, b);
        Log.d(TAG, "set CameraRelativeFrame as " + frame);
        mAppBridge.setCameraRelativeFrame(frame);
    }

    protected void setSingleTapUpListener(View singleTapArea) {
        mSingleTapArea = singleTapArea;
    }

    private boolean onSingleTapUp(int x, int y) {
        // Ignore if listener is null or the camera control is invisible.
        if (mSingleTapArea == null || !mShowCameraAppView) return false;

        int[] relativeLocation = Util.getRelativeLocation((View) getGLRoot(),
                mSingleTapArea);
        x -= relativeLocation[0];
        y -= relativeLocation[1];
        if (x >= 0 && x < mSingleTapArea.getWidth() && y >= 0
                && y < mSingleTapArea.getHeight()) {
            onSingleTapUp(mSingleTapArea, x, y);
            return true;
        }
        return false;
    }

    protected void onSingleTapUp(View view, int x, int y) {
    }

    protected void setSwipingEnabled(boolean enabled) {
        mAppBridge.setSwipingEnabled(enabled);
    }

    protected void notifyScreenNailChanged() {
        mAppBridge.notifyScreenNailChanged();
    }

    protected void onPreviewTextureCopied() {
    }

    //////////////////////////////////////////////////////////////////////////
    //  The is the communication interface between the Camera Application and
    //  the Gallery PhotoPage.
    //////////////////////////////////////////////////////////////////////////

    class MyAppBridge extends AppBridge implements CameraScreenNail.Listener {
        @SuppressWarnings("hiding")
        private ScreenNail mCameraScreenNail;
        private Server mServer;

        @Override
        public ScreenNail attachScreenNail() {
            if (mCameraScreenNail == null) {
                if (ApiHelper.HAS_SURFACE_TEXTURE) {
                    mCameraScreenNail = new CameraScreenNail(this);
                } else {
                    Bitmap b = BitmapFactory.decodeResource(getResources(),
                            R.drawable.wallpaper_picker_preview);
                    mCameraScreenNail = new StaticBitmapScreenNail(b);
                }
            }
            return mCameraScreenNail;
        }

        @Override
        public void detachScreenNail() {
            mCameraScreenNail = null;
        }

        public ScreenNail getCameraScreenNail() {
            return mCameraScreenNail;
        }

        // Return true if the tap is consumed.
        @Override
        public boolean onSingleTapUp(int x, int y) {
            return ActivityBase.this.onSingleTapUp(x, y);
        }

        // This is used to notify that the screen nail will be drawn in full screen
        // or not in next draw() call.
        @Override
        public void onFullScreenChanged(boolean full) {
            ActivityBase.this.onFullScreenChanged(full);
        }

        @Override
        public void requestRender() {
            getGLRoot().requestRender();
        }

        @Override
        public void onPreviewTextureCopied() {
            ActivityBase.this.onPreviewTextureCopied();
        }

        @Override
        public void setServer(Server s) {
            mServer = s;
        }

        @Override
        public boolean isPanorama() {
            return ActivityBase.this.isPanoramaActivity();
        }

        private void setCameraRelativeFrame(Rect frame) {
            if (mServer != null) mServer.setCameraRelativeFrame(frame);
        }

        private void switchWithCaptureAnimation(int offset) {
            if (mServer != null) mServer.switchWithCaptureAnimation(offset);
        }

        private void setSwipingEnabled(boolean enabled) {
            if (mServer != null) mServer.setSwipingEnabled(enabled);
        }

        private void notifyScreenNailChanged() {
            if (mServer != null) mServer.notifyScreenNailChanged();
        }
    }
}
