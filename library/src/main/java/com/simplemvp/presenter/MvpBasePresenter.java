/*
 * Copyright (c) 2019 Pavel A. Sokolov
 */

package com.simplemvp.presenter;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.res.Resources;
import android.support.annotation.CallSuper;
import android.util.Log;
import android.view.DragEvent;

import com.simplemvp.common.MvpPresenter;
import com.simplemvp.common.MvpState;
import com.simplemvp.common.MvpView;
import com.simplemvp.common.MvpViewHandle;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This is a base class for any MVP presenter. It has basic implementation of interface methods that
 * prints debug information. Also it keeps a list of attached views (handles)
 *
 * @param <S> state type
 */
public abstract class MvpBasePresenter<S extends MvpState> extends ContextWrapper implements MvpPresenter<S> {
    private final static AtomicInteger lastId = new AtomicInteger();
    protected final String tag = getClass().getSimpleName();
    protected final MvpPresenterManager manager;
    protected final Resources resources;
    protected final S state;
    protected final int id;
    private final List<MvpViewHandle<S>> handles = new CopyOnWriteArrayList<>();
    private final ExecutorService executor;

    public MvpBasePresenter(Context context, S state) {
        super(context);
        this.manager = MvpPresenterManager.getInstance(context);
        this.executor = manager.getExecutor();
        this.state = state;
        this.resources = context.getResources();
        this.id = lastId.incrementAndGet();
    }

    /**
     * This method is called by a view to attached oneself to presenter that is instantiated by
     * {@link MvpPresenterManager}
     *
     * @param handle {@link MvpViewHandle MvpViewHandle} to connect to
     */
    public final synchronized void connect(MvpViewHandle<S> handle) {
        boolean isFirst = handles.isEmpty();
        handles.add(handle);
        executor.execute(() -> {
            try {
                synchronized (this) {
                    if (isFirst) {
                        onFirstViewConnected(handle);
                    }
                    onViewConnected(handle);
                }
                // post state after onViewConnected finished (state is initialized)
                handle.post(getStateSnapshot());
            } catch (Exception e) {
                Log.d(tag, "error: ", e);
            }
        });
    }

    /**
     * This method is called by a view to disconnect oneself from presenter when view is about to be
     * destroyed. This method call is mandatory otherwise presenter will not be stopped and acquired
     * resources will not be released.
     *
     * @param handle {@link MvpViewHandle MvpViewHandle} to disconnect from
     */
    public final synchronized void disconnect(MvpViewHandle<S> handle) {
        handles.remove(handle);
        boolean isLast = handles.isEmpty();
        executor.execute(() -> {
            try {
                synchronized (this) {
                    onViewDisconnected(handle);
                    if (isLast) {
                        onLastViewDisconnected();
                    }
                }
            } catch (Exception e) {
                Log.d(tag, "error: ", e);
            }
        });
    }

    /**
     * Predicate that indicates that presenter has no attached views
     *
     * @return true if there is no attached views
     */
    @Override
    public final boolean isDetached() {
        return handles.isEmpty();
    }

    @Override
    public int getId() {
        return id;
    }

    /**
     * This method sends current state to attached views to render changes.
     * Method is synchronized to keep state order otherwise state with less revision number may
     * be delivered to {@link MvpView#onStateChanged(MvpState)} before the another state snapshot with
     * bigger revision number.
     */
    protected synchronized void commit() {
        if (state.isChanged() || state.isInitial()) {
            S snapshot = getStateSnapshot();
            state.clearChanged();
            for (MvpViewHandle<S> handle : handles) {
                handle.post(snapshot);
            }
        }
    }

    @Override
    public void finish() {
        for (MvpViewHandle<S> handle : handles) {
            handle.finish();
        }
    }

    @CallSuper
    @Override
    public void onActivityResult(MvpViewHandle<S> handle, int requestCode, int resultCode, Intent data) {
        Log.d(tag, "onActivityResult(" + requestCode + ", " + resultCode + ", " + data + ")");
    }

    @CallSuper
    @Override
    public void onRequestPermissionsResult(MvpViewHandle<S> handle, int requestCode, String[] permissions, int[] grantResults) {
        Log.d(tag, "onRequestPermissionsResult(" + requestCode + ", " + Arrays.toString(permissions) + ", "
                + Arrays.toString(grantResults));
    }

    @CallSuper
    @Override
    public void onViewClicked(MvpViewHandle<S> handle, int viewId) {
        Log.d(tag, "onViewClicked(" + resources.getResourceName(viewId) + ")");
    }

    @CallSuper
    @Override
    public void onOptionsItemSelected(MvpViewHandle<S> handle, int itemId) {
        Log.d(tag, "onOptionsItemSelected(" + resources.getResourceName(itemId) + ")");
    }

    @CallSuper
    @Override
    public void onItemSelected(MvpViewHandle<S> handle, int viewId, Object item) {
        Log.d(tag, "onItemSelected(" + resources.getResourceName(viewId) + ", " + item + ")");
    }

    @CallSuper
    @Override
    public void onTextChanged(MvpViewHandle<S> handle, int viewId, String text) {
        Log.d(tag, "onTextChanged(" + resources.getResourceName(viewId) + ", " + text + ")");
    }

    @Override
    public void onCheckedChanged(MvpViewHandle<S> handle, int viewId, boolean isChecked) {
        Log.d(tag, "onCheckedChanged(" + resources.getResourceName(viewId) + ", " + isChecked + ")");
    }

    @Override
    public void onRadioCheckedChanged(MvpViewHandle<S> handle, int radioViewId, int viewId) {
        Log.d(tag, "onRadioCheckedChanged(" + resources.getResourceName(radioViewId) + ", " + viewId + ")");
    }

    @Override
    public void onDrag(MvpViewHandle<S> handle, int viewId, DragEvent event) {
        Log.d(tag, "onDrag(" + resources.getResourceName(viewId) + ", " + event + ")");
    }

    /**
     * This method is called when master view is connected to current presenter. This method is to be
     * overridden to place initialization code that fills {@link MvpBasePresenter#state} with initial
     * values and subscribes to necessary events.
     *
     * @param handle {@link MvpViewHandle MvpViewHandle} interface reference
     */
    @CallSuper
    protected void onFirstViewConnected(MvpViewHandle<S> handle) {
        Log.d(tag, "onFirstViewConnected(" + handle.getMvpView() + ")");
    }

    /**
     * This method is called when fresh view is connected to a presenter.
     *
     * @param handle {@link MvpViewHandle MvpViewHandle} interface reference
     */
    @CallSuper
    protected void onViewConnected(MvpViewHandle<S> handle) {
        Log.d(tag, "onViewConnected(" + handle.getMvpView() + ")");
    }

    /**
     * This method is called when view is disconnected from presenter.
     *
     * @param handle {@link MvpViewHandle MvpViewHandle} interface reference
     */
    @CallSuper
    protected void onViewDisconnected(MvpViewHandle<S> handle) {
        Log.d(tag, "onViewDisconnected(" + handle.getMvpView() + ")");
    }

    /**
     * This method is called when presenter has no connected views and it is about to be released
     * by {@link MvpPresenterManager}.
     */
    @CallSuper
    protected void onLastViewDisconnected() {
        Log.d(tag, "onLastViewDisconnected()");
    }

    protected S getStateSnapshot() {
        try {
            return (S) state.clone();
        } catch (CloneNotSupportedException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + " {" +
                "id=" + id +
                ", state=" + state +
                '}';
    }
}
