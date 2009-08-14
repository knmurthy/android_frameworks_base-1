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

package android.service.wallpaper;

import com.android.internal.os.HandlerCaller;
import com.android.internal.view.BaseIWindow;
import com.android.internal.view.BaseSurfaceHolder;

import android.app.Service;
import android.app.WallpaperManager;
import android.content.Intent;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.view.Gravity;
import android.view.IWindowSession;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewRoot;
import android.view.WindowManager;
import android.view.WindowManagerImpl;

/**
 * A wallpaper service is responsible for showing a live wallpaper behind
 * applications that would like to sit on top of it.
 * @hide Live Wallpaper
 */
public abstract class WallpaperService extends Service {
    /**
     * The {@link Intent} that must be declared as handled by the service.
     */
    public static final String SERVICE_INTERFACE =
        "android.service.wallpaper.WallpaperService";

    static final String TAG = "WallpaperService";
    static final boolean DEBUG = false;
    
    private static final int DO_ATTACH = 10;
    private static final int DO_DETACH = 20;
    
    private static final int MSG_UPDATE_SURFACE = 10000;
    private static final int MSG_VISIBILITY_CHANGED = 10010;
    private static final int MSG_WALLPAPER_OFFSETS = 10020;
    private static final int MSG_WINDOW_RESIZED = 10030;
    
    /**
     * The actual implementation of a wallpaper.  A wallpaper service may
     * have multiple instances running (for example as a real wallpaper
     * and as a preview), each of which is represented by its own Engine
     * instance.  You must implement {@link WallpaperService#onCreateEngine()}
     * to return your concrete Engine implementation.
     */
    public class Engine {
        IWallpaperEngineWrapper mIWallpaperEngine;
        
        // Copies from mIWallpaperEngine.
        HandlerCaller mCaller;
        IWallpaperConnection mConnection;
        IBinder mWindowToken;
        
        boolean mInitializing = true;
        
        // Current window state.
        boolean mCreated;
        boolean mIsCreating;
        boolean mDrawingAllowed;
        int mWidth;
        int mHeight;
        int mFormat;
        int mType;
        int mCurWidth;
        int mCurHeight;
        boolean mDestroyReportNeeded;
        final Rect mVisibleInsets = new Rect();
        final Rect mWinFrame = new Rect();
        final Rect mContentInsets = new Rect();
        
        final WindowManager.LayoutParams mLayout
                = new WindowManager.LayoutParams();
        IWindowSession mSession;

        final Object mLock = new Object();
        boolean mOffsetMessageEnqueued;
        float mPendingXOffset;
        float mPendingYOffset;
        
        final BaseSurfaceHolder mSurfaceHolder = new BaseSurfaceHolder() {

            @Override
            public boolean onAllowLockCanvas() {
                return mDrawingAllowed;
            }

            @Override
            public void onRelayoutContainer() {
                Message msg = mCaller.obtainMessage(MSG_UPDATE_SURFACE);
                mCaller.sendMessage(msg);
            }

            @Override
            public void onUpdateSurface() {
                Message msg = mCaller.obtainMessage(MSG_UPDATE_SURFACE);
                mCaller.sendMessage(msg);
            }

            public boolean isCreating() {
                return mIsCreating;
            }

            public void setKeepScreenOn(boolean screenOn) {
                // Ignore.
            }
            
        };
        
        final BaseIWindow mWindow = new BaseIWindow() {
            public void resized(int w, int h, Rect coveredInsets,
                    Rect visibleInsets, boolean reportDraw) {
                Message msg = mCaller.obtainMessageI(MSG_WINDOW_RESIZED,
                        reportDraw ? 1 : 0);
                mCaller.sendMessage(msg);
            }
            
            public void dispatchAppVisibility(boolean visible) {
                Message msg = mCaller.obtainMessageI(MSG_VISIBILITY_CHANGED,
                        visible ? 1 : 0);
                mCaller.sendMessage(msg);
            }

            @Override
            public void dispatchWallpaperOffsets(float x, float y) {
                synchronized (mLock) {
                    mPendingXOffset = x;
                    mPendingYOffset = y;
                    if (!mOffsetMessageEnqueued) {
                        mOffsetMessageEnqueued = true;
                        Message msg = mCaller.obtainMessage(MSG_WALLPAPER_OFFSETS);
                        mCaller.sendMessage(msg);
                    }
                }
            }
            
        };
        
        /**
         * Provides access to the surface in which this wallpaper is drawn.
         */
        public SurfaceHolder getSurfaceHolder() {
            return mSurfaceHolder;
        }
        
        /**
         * Convenience for {@link WallpaperManager#getDesiredMinimumWidth()
         * WallpaperManager.getDesiredMinimumWidth()}, returning the width
         * that the system would like this wallpaper to run in.
         */
        public int getDesiredMinimumWidth() {
            return mIWallpaperEngine.mReqWidth;
        }
        
        /**
         * Convenience for {@link WallpaperManager#getDesiredMinimumHeight()
         * WallpaperManager.getDesiredMinimumHeight()}, returning the height
         * that the system would like this wallpaper to run in.
         */
        public int getDesiredMinimumHeight() {
            return mIWallpaperEngine.mReqHeight;
        }
        
        /**
         * Called once to initialize the engine.  After returning, the
         * engine's surface will be created by the framework.
         */
        public void onCreate(SurfaceHolder surfaceHolder) {
        }
        
        /**
         * Called right before the engine is going away.  After this the
         * surface will be destroyed and this Engine object is no longer
         * valid.
         */
        public void onDestroy() {
        }
        
        /**
         * Called to inform you of the wallpaper becoming visible or
         * hidden.  <em>It is very important that a wallpaper only use
         * CPU while it is visible.</em>.
         */
        public void onVisibilityChanged(boolean visible) {
        }
        
        /**
         * Called to inform you of the wallpaper's offsets changing
         * within its contain, corresponding to the container's
         * call to {@link WallpaperManager#setWallpaperOffsets(IBinder, float, float)
         * WallpaperManager.setWallpaperOffsets()}.
         */
        public void onOffsetsChanged(float xOffset, float yOffset,
                int xPixelOffset, int yPixelOffset) {
        }
        
        /**
         * Convenience for {@link SurfaceHolder.Callback#surfaceChanged
         * SurfaceHolder.Callback.surfaceChanged()}.
         */
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        }

        /**
         * Convenience for {@link SurfaceHolder.Callback#surfaceCreated
         * SurfaceHolder.Callback.surfaceCreated()}.
         */
        public void onSurfaceCreated(SurfaceHolder holder) {
        }

        /**
         * Convenience for {@link SurfaceHolder.Callback#surfaceDestroyed
         * SurfaceHolder.Callback.surfaceDestroyed()}.
         */
        public void onSurfaceDestroyed(SurfaceHolder holder) {
        }

        void updateSurface(boolean force) {
            int myWidth = mSurfaceHolder.getRequestedWidth();
            if (myWidth <= 0) myWidth = ViewGroup.LayoutParams.FILL_PARENT;
            int myHeight = mSurfaceHolder.getRequestedHeight();
            if (myHeight <= 0) myHeight = ViewGroup.LayoutParams.FILL_PARENT;
            
            final boolean creating = !mCreated;
            final boolean formatChanged = mFormat != mSurfaceHolder.getRequestedFormat();
            boolean sizeChanged = mWidth != myWidth || mHeight != myHeight;
            final boolean typeChanged = mType != mSurfaceHolder.getRequestedType();
            if (force || creating || formatChanged || sizeChanged || typeChanged) {

                if (DEBUG) Log.i(TAG, "Changes: creating=" + creating
                        + " format=" + formatChanged + " size=" + sizeChanged);

                try {
                    mWidth = myWidth;
                    mHeight = myHeight;
                    mFormat = mSurfaceHolder.getRequestedFormat();
                    mType = mSurfaceHolder.getRequestedType();

                    // Scaling/Translate window's layout here because mLayout is not used elsewhere.
                    
                    // Places the window relative
                    mLayout.x = 0;
                    mLayout.y = 0;
                    mLayout.width = myWidth;
                    mLayout.height = myHeight;
                    
                    mLayout.format = mFormat;
                    mLayout.flags |=WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                                  | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                  | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                  | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                  ;

                    mLayout.memoryType = mType;
                    mLayout.token = mWindowToken;

                    if (!mCreated) {
                        mLayout.type = WindowManager.LayoutParams.TYPE_WALLPAPER;
                        mLayout.gravity = Gravity.LEFT|Gravity.TOP;
                        mSession.add(mWindow, mLayout, View.VISIBLE, mContentInsets);
                    }
                    
                    mSurfaceHolder.mSurfaceLock.lock();
                    mDrawingAllowed = true;

                    final int relayoutResult = mSession.relayout(
                        mWindow, mLayout, mWidth, mHeight,
                            View.VISIBLE, false, mWinFrame, mContentInsets,
                            mVisibleInsets, mSurfaceHolder.mSurface);

                    if (DEBUG) Log.i(TAG, "New surface: " + mSurfaceHolder.mSurface
                            + ", frame=" + mWinFrame);
                    
                    int w = mWinFrame.width();
                    if (mCurWidth != w) {
                        sizeChanged = true;
                        mCurWidth = w;
                    }
                    int h = mWinFrame.height();
                    if (mCurHeight != h) {
                        sizeChanged = true;
                        mCurHeight = h;
                    }
                    
                    mSurfaceHolder.mSurfaceLock.unlock();

                    try {
                        mDestroyReportNeeded = true;

                        SurfaceHolder.Callback callbacks[] = null;
                        synchronized (mSurfaceHolder.mCallbacks) {
                            final int N = mSurfaceHolder.mCallbacks.size();
                            if (N > 0) {
                                callbacks = new SurfaceHolder.Callback[N];
                                mSurfaceHolder.mCallbacks.toArray(callbacks);
                            }
                        }

                        if (!mCreated) {
                            mIsCreating = true;
                            onSurfaceCreated(mSurfaceHolder);
                            if (callbacks != null) {
                                for (SurfaceHolder.Callback c : callbacks) {
                                    c.surfaceCreated(mSurfaceHolder);
                                }
                            }
                        }
                        if (force || creating || formatChanged || sizeChanged) {
                            onSurfaceChanged(mSurfaceHolder, mFormat,
                                    mCurWidth, mCurHeight);
                            if (callbacks != null) {
                                for (SurfaceHolder.Callback c : callbacks) {
                                    c.surfaceChanged(mSurfaceHolder, mFormat,
                                            mCurWidth, mCurHeight);
                                }
                            }
                        }
                    } finally {
                        mIsCreating = false;
                        mCreated = true;
                        if (creating || (relayoutResult&WindowManagerImpl.RELAYOUT_FIRST_TIME) != 0) {
                            mSession.finishDrawing(mWindow);
                        }
                    }
                } catch (RemoteException ex) {
                }
                if (DEBUG) Log.v(
                    TAG, "Layout: x=" + mLayout.x + " y=" + mLayout.y +
                    " w=" + mLayout.width + " h=" + mLayout.height);
            }
        }
        
        void attach(IWallpaperEngineWrapper wrapper) {
            mIWallpaperEngine = wrapper;
            mCaller = wrapper.mCaller;
            mConnection = wrapper.mConnection;
            mWindowToken = wrapper.mWindowToken;
            // XXX temp -- should run in size from layout (screen) mode.
            mSurfaceHolder.setFixedSize(mIWallpaperEngine.mReqWidth,
                    mIWallpaperEngine.mReqHeight);
            //mSurfaceHolder.setSizeFromLayout();
            mInitializing = true;
            mSession = ViewRoot.getWindowSession(getMainLooper());
            mWindow.setSession(mSession);
            
            onCreate(mSurfaceHolder);
            
            mInitializing = false;
            updateSurface(false);
        }
        
        void detach() {
            onDestroy();
            if (mDestroyReportNeeded) {
                mDestroyReportNeeded = false;
                SurfaceHolder.Callback callbacks[];
                synchronized (mSurfaceHolder.mCallbacks) {
                    callbacks = new SurfaceHolder.Callback[
                            mSurfaceHolder.mCallbacks.size()];
                    mSurfaceHolder.mCallbacks.toArray(callbacks);
                }
                for (SurfaceHolder.Callback c : callbacks) {
                    c.surfaceDestroyed(mSurfaceHolder);
                }
            }
            if (mCreated) {
                try {
                    mSession.remove(mWindow);
                } catch (RemoteException e) {
                }
                mSurfaceHolder.mSurface.clear();
                mCreated = false;
            }
        }
    }
    
    class IWallpaperEngineWrapper extends IWallpaperEngine.Stub
            implements HandlerCaller.Callback {
        private final HandlerCaller mCaller;

        final IWallpaperConnection mConnection;
        final IBinder mWindowToken;
        int mReqWidth;
        int mReqHeight;
        
        Engine mEngine;
        
        IWallpaperEngineWrapper(WallpaperService context,
                IWallpaperConnection conn, IBinder windowToken,
                int reqWidth, int reqHeight) {
            mCaller = new HandlerCaller(context, this);
            mConnection = conn;
            mWindowToken = windowToken;
            mReqWidth = reqWidth;
            mReqHeight = reqHeight;
            
            try {
                conn.attachEngine(this);
            } catch (RemoteException e) {
                destroy();
            }
            
            Message msg = mCaller.obtainMessage(DO_ATTACH);
            mCaller.sendMessage(msg);
        }
        
        public void destroy() {
            Message msg = mCaller.obtainMessage(DO_DETACH);
            mCaller.sendMessage(msg);
        }

        public void executeMessage(Message message) {
            switch (message.what) {
                case DO_ATTACH: {
                    Engine engine = onCreateEngine();
                    mEngine = engine;
                    engine.attach(this);
                    return;
                }
                case DO_DETACH: {
                    mEngine.detach();
                    return;
                }
                case MSG_UPDATE_SURFACE:
                    mEngine.updateSurface(false);
                    break;
                case MSG_VISIBILITY_CHANGED:
                    if (DEBUG) Log.v(TAG, "Visibility change in " + mEngine
                            + ": " + message.arg1);
                    mEngine.onVisibilityChanged(message.arg1 != 0);
                    break;
                case MSG_WALLPAPER_OFFSETS: {
                    float xOffset;
                    float yOffset;
                    synchronized (mEngine.mLock) {
                        xOffset = mEngine.mPendingXOffset;
                        yOffset = mEngine.mPendingYOffset;
                        mEngine.mOffsetMessageEnqueued = false;
                    }
                    if (DEBUG) Log.v(TAG, "Offsets change in " + mEngine
                            + ": " + xOffset + "," + yOffset);
                    final int availw = mReqWidth-mEngine.mCurWidth;
                    final int xPixels = availw > 0 ? -(int)(availw*xOffset+.5f) : 0;
                    final int availh = mReqHeight-mEngine.mCurHeight;
                    final int yPixels = availh > 0 ? -(int)(availh*yOffset+.5f) : 0;
                    mEngine.onOffsetsChanged(xOffset, yOffset, xPixels, yPixels);
                } break;
                case MSG_WINDOW_RESIZED: {
                    final boolean reportDraw = message.arg1 != 0;
                    mEngine.updateSurface(true);
                    if (reportDraw) {
                        try {
                            mEngine.mSession.finishDrawing(mEngine.mWindow);
                        } catch (RemoteException e) {
                        }
                    }
                } break;
                default :
                    Log.w(TAG, "Unknown message type " + message.what);
            }
        }
    }

    /**
     * Implements the internal {@link IWallpaperService} interface to convert
     * incoming calls to it back to calls on an {@link WallpaperService}.
     */
    class IWallpaperServiceWrapper extends IWallpaperService.Stub {
        private final WallpaperService mTarget;

        public IWallpaperServiceWrapper(WallpaperService context) {
            mTarget = context;
        }

        public void attach(IWallpaperConnection conn,
                IBinder windowToken, int reqWidth, int reqHeight) {
            new IWallpaperEngineWrapper(
                    mTarget, conn, windowToken, reqWidth, reqHeight);
        }
    }
    
    /**
     * Implement to return the implementation of the internal accessibility
     * service interface.  Subclasses should not override.
     */
    @Override
    public final IBinder onBind(Intent intent) {
        return new IWallpaperServiceWrapper(this);
    }
    
    public abstract Engine onCreateEngine();
}
