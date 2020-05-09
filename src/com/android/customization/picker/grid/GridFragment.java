/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.customization.picker.grid;

import static android.app.Activity.RESULT_OK;

import static com.android.customization.picker.ViewOnlyFullPreviewActivity.SECTION_GRID;
import static com.android.customization.picker.grid.GridFullPreviewFragment.EXTRA_GRID_OPTION;
import static com.android.customization.picker.grid.GridFullPreviewFragment.EXTRA_GRID_USES_SURFACE_VIEW;
import static com.android.customization.picker.grid.GridFullPreviewFragment.EXTRA_WALLPAPER_INFO;
import static com.android.wallpaper.widget.BottomActionBar.BottomAction.APPLY;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.os.RemoteException;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnLayoutChangeListener;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.core.widget.ContentLoadingProgressBar;
import androidx.recyclerview.widget.RecyclerView;

import com.android.customization.model.CustomizationManager.Callback;
import com.android.customization.model.CustomizationManager.OptionsFetchedListener;
import com.android.customization.model.grid.GridOption;
import com.android.customization.model.grid.GridOptionsManager;
import com.android.customization.module.ThemesUserEventLogger;
import com.android.customization.picker.BasePreviewAdapter;
import com.android.customization.picker.BasePreviewAdapter.PreviewPage;
import com.android.customization.picker.ViewOnlyFullPreviewActivity;
import com.android.customization.widget.OptionSelectorController;
import com.android.wallpaper.R;
import com.android.wallpaper.asset.Asset;
import com.android.wallpaper.asset.ContentUriAsset;
import com.android.wallpaper.model.WallpaperInfo;
import com.android.wallpaper.module.CurrentWallpaperInfoFactory;
import com.android.wallpaper.module.InjectorProvider;
import com.android.wallpaper.picker.AppbarFragment;
import com.android.wallpaper.util.SurfaceViewUtils;
import com.android.wallpaper.widget.BottomActionBar;
import com.android.wallpaper.widget.PreviewPager;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;

import java.util.List;

/**
 * Fragment that contains the UI for selecting and applying a GridOption.
 */
public class GridFragment extends AppbarFragment {

    static final int PREVIEW_FADE_DURATION_MS = 100;

    private static final int FULL_PREVIEW_REQUEST_CODE = 1000;

    private static final String TAG = "GridFragment";

    /**
     * Interface to be implemented by an Activity hosting a {@link GridFragment}
     */
    public interface GridFragmentHost {
        GridOptionsManager getGridOptionsManager();
    }

    public static GridFragment newInstance(CharSequence title) {
        GridFragment fragment = new GridFragment();
        fragment.setArguments(AppbarFragment.createArguments(title));
        return fragment;
    }

    private WallpaperInfo mHomeWallpaper;
    private float mScreenAspectRatio;
    private int mCardHeight;
    private int mCardWidth;
    private BitmapDrawable mCardBackground;
    private GridPreviewAdapter mAdapter;
    private RecyclerView mOptionsContainer;
    private OptionSelectorController<GridOption> mOptionsController;
    private GridOptionsManager mGridManager;
    private GridOption mSelectedOption;
    private PreviewPager mPreviewPager;
    private ContentLoadingProgressBar mLoading;
    private View mContent;
    private View mError;
    private BottomActionBar mBottomActionBar;
    private ThemesUserEventLogger mEventLogger;
    private boolean mReloadOptionsAfterApplying;

    private final Callback mApplyGridCallback = new Callback() {
        @Override
        public void onSuccess() {
            mGridManager.fetchOptions(new OptionsFetchedListener<GridOption>() {
                @Override
                public void onOptionsLoaded(List<GridOption> options) {
                    mOptionsController.resetOptions(options);
                    mSelectedOption = getSelectedOption(options);
                    mReloadOptionsAfterApplying = true;
                    // It will trigger OptionSelectedListener#onOptionSelected.
                    mOptionsController.setSelectedOption(mSelectedOption);
                    Toast.makeText(getContext(), R.string.applied_grid_msg, Toast.LENGTH_SHORT)
                            .show();
                    // Since we disabled it when clicked apply button.
                    mBottomActionBar.enableActions();
                    mBottomActionBar.hide();
                }

                @Override
                public void onError(@Nullable Throwable throwable) {
                    if (throwable != null) {
                        Log.e(TAG, "Error loading grid options", throwable);
                    }
                    showError();
                }
            }, true);
        }

        @Override
        public void onError(@Nullable Throwable throwable) {
            //TODO(chihhangchuang): handle
        }
    };

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mGridManager = ((GridFragmentHost) context).getGridOptionsManager();
        mEventLogger = (ThemesUserEventLogger)
                InjectorProvider.getInjector().getUserEventLogger(context);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(
                R.layout.fragment_grid_picker, container, /* attachToRoot */ false);
        setUpToolbar(view);
        mContent = view.findViewById(R.id.content_section);
        mPreviewPager = view.findViewById(R.id.grid_preview_pager);
        mOptionsContainer = view.findViewById(R.id.options_container);
        mLoading = view.findViewById(R.id.loading_indicator);
        mError = view.findViewById(R.id.error_section);
        final Resources res = getResources();
        DisplayMetrics dm = res.getDisplayMetrics();
        mScreenAspectRatio = (float) dm.heightPixels / dm.widthPixels;

        // Clear memory cache whenever grid fragment view is being loaded.
        Glide.get(getContext()).clearMemory();
        setUpOptions();

        CurrentWallpaperInfoFactory factory = InjectorProvider.getInjector()
                .getCurrentWallpaperFactory(getContext().getApplicationContext());

        factory.createCurrentWallpaperInfos((homeWallpaper, lockWallpaper, presentationMode) -> {
            mHomeWallpaper = homeWallpaper;
            loadWallpaperBackground();

        }, false);
        view.addOnLayoutChangeListener(new OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                mCardHeight = mPreviewPager.getHeight() - mPreviewPager.getPaddingTop() -
                        res.getDimensionPixelSize(R.dimen.indicator_container_height);
                mCardWidth = (int) (mCardHeight / mScreenAspectRatio);
                view.removeOnLayoutChangeListener(this);
                loadWallpaperBackground();
            }
        });
        return view;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FULL_PREVIEW_REQUEST_CODE && resultCode == RESULT_OK) {
            applyGridOption(data.getParcelableExtra(EXTRA_GRID_OPTION));
        }
    }


    @Override
    protected void onBottomActionBarReady(BottomActionBar bottomActionBar) {
        mBottomActionBar = bottomActionBar;
        mBottomActionBar.showActionsOnly(APPLY);
        mBottomActionBar.setActionClickListener(APPLY, unused -> applyGridOption(mSelectedOption));
    }

    private void applyGridOption(GridOption gridOption) {
        mBottomActionBar.disableActions();
        mGridManager.apply(gridOption, mApplyGridCallback);
    }

    private void loadWallpaperBackground() {
        if (mHomeWallpaper != null && mCardHeight > 0 && mCardWidth > 0) {
            mHomeWallpaper.getThumbAsset(getContext()).decodeBitmap(mCardWidth,
                    mCardHeight,
                    bitmap -> {
                        mCardBackground =
                                new BitmapDrawable(getResources(), bitmap);
                        if (mAdapter != null) {
                            mAdapter.onWallpaperInfoLoaded();
                        }
                    });
        }
    }

    private void createAdapter() {
        mAdapter = new GridPreviewAdapter(mSelectedOption);
        mPreviewPager.setAdapter(mAdapter);
    }

    private void setUpOptions() {
        hideError();
        mLoading.show();
        mGridManager.fetchOptions(new OptionsFetchedListener<GridOption>() {
            @Override
            public void onOptionsLoaded(List<GridOption> options) {
                mLoading.hide();
                mOptionsController = new OptionSelectorController<>(mOptionsContainer, options);

                mOptionsController.addListener(selected -> {
                    mSelectedOption = (GridOption) selected;
                    if (mReloadOptionsAfterApplying) {
                        mReloadOptionsAfterApplying = false;
                        return;
                    }
                    mBottomActionBar.show();
                    mEventLogger.logGridSelected(mSelectedOption);
                    createAdapter();
                });
                mOptionsController.initOptions(mGridManager);
                mSelectedOption = getSelectedOption(options);
                createAdapter();
            }

            @Override
            public void onError(@Nullable Throwable throwable) {
                if (throwable != null) {
                    Log.e(TAG, "Error loading grid options", throwable);
                }
                showError();
            }
        }, false);
    }

    private GridOption getSelectedOption(List<GridOption> options) {
        return options.stream()
                .filter(option -> option.isActive(mGridManager))
                .findAny()
                // For development only, as there should always be a grid set.
                .orElse(options.get(0));
    }

    private void hideError() {
        mContent.setVisibility(View.VISIBLE);
        mError.setVisibility(View.GONE);
    }

    private void showError() {
        mLoading.hide();
        mContent.setVisibility(View.GONE);
        mError.setVisibility(View.VISIBLE);
    }

    private void showFullPreview() {
        Bundle bundle = new Bundle();
        bundle.putParcelable(EXTRA_WALLPAPER_INFO, mHomeWallpaper);
        bundle.putParcelable(EXTRA_GRID_OPTION, mSelectedOption);
        bundle.putBoolean(EXTRA_GRID_USES_SURFACE_VIEW, mGridManager.usesSurfaceView());
        Intent intent = ViewOnlyFullPreviewActivity.newIntent(getContext(), SECTION_GRID, bundle);
        startActivityForResult(intent, FULL_PREVIEW_REQUEST_CODE);
    }

    private class GridPreviewPage extends PreviewPage {
        private final int mPageId;
        private final Asset mPreviewAsset;
        private final int mCols;
        private final int mRows;
        private final Activity mActivity;

        private final String mName;

        private ImageView mPreview;
        private SurfaceView mPreviewSurface;

        private final SurfaceHolder.Callback mSurfaceCallback = new SurfaceHolder.Callback() {

            private Surface mLastSurface;
            private Message mCallback;

            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                if (mLastSurface != holder.getSurface()) {
                    mLastSurface = holder.getSurface();
                    Bundle result = mGridManager.renderPreview(
                            SurfaceViewUtils.createSurfaceViewRequest(mPreviewSurface), mName);
                    if (result != null) {
                        mPreviewSurface.setChildSurfacePackage(
                                SurfaceViewUtils.getSurfacePackage(result));
                        mCallback = SurfaceViewUtils.getCallback(result);
                    }
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width,
                    int height) {}

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                if (mCallback != null) {
                    try {
                        mCallback.replyTo.send(mCallback);
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    } finally {
                        mCallback = null;
                    }
                }
            }
        };

        private GridPreviewPage(Activity activity, int id, Uri previewUri, String name, int rows,
                int cols) {
            super(null, activity);
            mPageId = id;
            mPreviewAsset = new ContentUriAsset(activity, previewUri,
                    RequestOptions.fitCenterTransform());
            mName = name;
            mRows = rows;
            mCols = cols;
            mActivity = activity;
        }

        @Override
        public void setCard(CardView card) {
            super.setCard(card);
            mPreview = card.findViewById(R.id.grid_preview_image);
            mPreviewSurface = card.findViewById(R.id.grid_preview_surface);
            // PreviewSurface is the top of its window(card view), due to #setZOrderOnTop(true).
            mPreviewSurface.setOnClickListener(view -> showFullPreview());
        }

        public void bindPreviewContent() {
            Resources resources = card.getResources();
            bindWallpaperIfAvailable();
            final boolean usesSurfaceViewForPreview = mGridManager.usesSurfaceView();
            mPreview.setVisibility(usesSurfaceViewForPreview ? View.GONE : View.VISIBLE);
            mPreviewSurface.setVisibility(usesSurfaceViewForPreview ? View.VISIBLE : View.GONE);
            if (usesSurfaceViewForPreview) {
                mPreviewSurface.setZOrderOnTop(true);
                mPreviewSurface.getHolder().addCallback(mSurfaceCallback);
            } else {
                mPreviewAsset.loadDrawableWithTransition(mActivity,
                        mPreview /* imageView */,
                        PREVIEW_FADE_DURATION_MS /* duration */,
                        null /* drawableLoadedListener */,
                        resources.getColor(android.R.color.transparent,
                                null) /* placeHolderColorJ */);
            }
        }

        void bindWallpaperIfAvailable() {
            if (card != null && mCardBackground != null) {
                mPreview.setBackground(mCardBackground);
                mPreviewSurface.setBackground(mCardBackground);
            }
        }
    }
    /**
     * Adapter class for mPreviewPager.
     * This is a ViewPager as it allows for a nice pagination effect (ie, pages snap on swipe,
     * we don't want to just scroll)
     */
    class GridPreviewAdapter extends BasePreviewAdapter<GridPreviewPage> {

        GridPreviewAdapter(GridOption gridOption) {
            super(getContext(), R.layout.grid_preview_card);
            for (int i = 0; i < gridOption.previewPagesCount; i++) {
                addPage(new GridPreviewPage(getActivity(), i,
                        gridOption.previewImageUri.buildUpon().appendPath("" + i).build(),
                        gridOption.name, gridOption.rows, gridOption.cols));
            }
        }

        void onWallpaperInfoLoaded() {
            for (GridPreviewPage page : mPages) {
                page.bindWallpaperIfAvailable();
            }
        }
    }
}
