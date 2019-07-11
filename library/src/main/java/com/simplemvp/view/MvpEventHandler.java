/*
 * Copyright (c) 2019 Pavel A. Sokolov
 */

package com.simplemvp.view;

import android.arch.lifecycle.Lifecycle;
import android.arch.lifecycle.LifecycleObserver;
import android.arch.lifecycle.OnLifecycleEvent;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.SearchView;
import android.text.TextWatcher;
import android.util.Log;
import android.view.DragEvent;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.CompoundButton;
import android.widget.RadioGroup;
import android.widget.Toast;

import com.simplemvp.common.MvpListener;
import com.simplemvp.common.MvpPresenter;
import com.simplemvp.common.MvpState;
import com.simplemvp.common.MvpView;
import com.simplemvp.common.MvpViewHandle;
import com.simplemvp.functions.Consumer;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

class MvpEventHandler<S extends MvpState, P extends MvpPresenter<S>>
        implements MvpViewHandle<S>, MvpListener, LifecycleObserver {
    private final static int QUEUE_SIZE = 8;
    private final String tag = getClass().getSimpleName();
    private final WeakReference<MvpView<S, P>> reference;
    private final ReferenceQueue<MvpView<S, P>> referenceQueue;
    private final P presenter;
    private final Queue<S> queue = new ConcurrentLinkedQueue<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<TextWatcher> textWatchers = new ArrayList<>();
    private final List<SearchView.OnQueryTextListener> queryTextListeners = new ArrayList<>();
    private final AtomicBoolean isEnabled = new AtomicBoolean();
    private final AtomicBoolean isResumed = new AtomicBoolean();
    private final AtomicBoolean isQueueFlush = new AtomicBoolean();
    private volatile S lastState;

    MvpEventHandler(MvpView<S, P> view, P presenter) {
        this.referenceQueue = new ReferenceQueue<>();
        this.reference = new WeakReference<>(view, referenceQueue);
        this.presenter = presenter;
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
    public void onResumed() {
        MvpView<S, P> view = reference.get();
        isResumed.set(true);
        if (isResumed()) {
            if (queue.isEmpty() && lastState != null) {
                view.onStateChanged(lastState);
            } else {
                Log.d(tag, "flushing event queue");
                flushQueue();
            }
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    public void onPaused() {
        isResumed.set(false);
    }

    @Override
    public void onClick(View v) {
        presenter.onViewClicked(this, v.getId());
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        presenter.onOptionsItemSelected(this, item.getItemId());
        return true;
    }

    @Override
    public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
        presenter.onItemSelected(this, adapterView.getId(), adapterView.getItemAtPosition(i));
    }

    @Override
    public void onNothingSelected(AdapterView<?> adapterView) {
        presenter.onItemSelected(this, adapterView.getId(), null);
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        presenter.onCheckedChanged(this, buttonView.getId(), isChecked);
    }

    @Override
    public void onCheckedChanged(RadioGroup group, int checkedId) {
        presenter.onRadioCheckedChanged(this, group.getId(), checkedId);
    }

    @Override
    public boolean onDrag(View v, DragEvent event) {
        presenter.onDrag(this, v.getId(), event);
        return true;
    }

    TextWatcher newTextWatcher(View view) {
        Log.d(tag, "new text watcher for view: " + view);
        TextWatcher watcher = new MvpTextWatcher<>(handler, this, presenter, view.getId());
        textWatchers.add(watcher);
        return watcher;
    }

    SearchView.OnQueryTextListener newQueryTextListener(SearchView view) {
        Log.d(tag, "new query text listener for view: " + view);
        SearchView.OnQueryTextListener listener = new MvpOnQueryTextListener<>(handler, this, presenter, view.getId());
        queryTextListeners.add(listener);
        return listener;
    }

    @Override
    public MvpView<S, ?> getMvpView() {
        return reference.get();
    }

    @Override
    public Bundle getArguments() {
        MvpView<S, P> view = reference.get();
        return view == null ? new Bundle() : view.getArguments();
    }

    @Override
    public void post(S state) {
        queue.offer(state);
        if (isResumed() && isQueueFlush.compareAndSet(false, true)) {
            handler.post(this::flushQueue);
        }
        expungeStaleEntries();
    }

    @Override
    public void finish() {
        handler.post(new EventRunnable(view -> view.finish()));
    }

    @Override
    public void showToast(String text, int duration) {
        handler.post(new EventRunnable(view ->
                Toast.makeText(view.getContext(), text, duration).show()));
    }

    @Override
    public void showToast(int resId, int duration) {
        handler.post(new EventRunnable(view ->
                Toast.makeText(view.getContext(), resId, duration).show()));
    }

    @Override
    public void startActivity(Intent intent) {
        handler.post(new EventRunnable(view -> view.getContext().startActivity(intent)));
    }

    @Override
    public void startActivityForResult(Intent intent, int requestCode) {
        handler.post(new EventRunnable(view -> {
            if (view instanceof AppCompatActivity) {
                ((AppCompatActivity) view).startActivityForResult(intent, requestCode);
            }
        }));
    }

    /**
     * This method enables or disables queue drain. State queue must not be drained in some cases
     * since view is not ready to handle a state. If menu is not inflated for example.
     *
     * @param value true to enable queue drain, false to stop it
     */
    void setEnabled(boolean value) {
        if (isEnabled.compareAndSet(!value, value) && isResumed()) {
            flushQueue();
        }
    }

    private boolean isResumed() {
        return isEnabled.get() && isResumed.get();
    }

    private void flushQueue() {
        int size = queue.size();
        int n = size / QUEUE_SIZE;
        // flushQueue may be called when View has been paused or it is about to be destroyed
        // so it is better to check this flag before start state processing
        while (!queue.isEmpty() && isResumed()) {
            S state = queue.poll();
            // process every n'th state in case of queue overflow
            if (n == 0 || size % n == 0) {
                lastState = state;
                try {
                    MvpView<S, P> view = reference.get();
                    if (view != null) {
                        view.onStateChanged(state);
                    }
                } catch (Exception e) {
                    Log.e(tag, "state handling error: ", e);
                }
                size = queue.size();
                n = size / QUEUE_SIZE;
            }
            // set flag and then check queue size again to avoid cases when item is left unprocessed
            if (queue.isEmpty()) {
                isQueueFlush.set(false);
            }
        }
    }

    private void expungeStaleEntries() {
        synchronized (referenceQueue) {
            if (referenceQueue.poll() != null) {
                presenter.disconnect(this);
            }
        }
    }

    private class EventRunnable implements Runnable {
        private final Consumer<MvpView<S, ?>> consumer;

        EventRunnable(Consumer<MvpView<S, ?>> consumer) {
            this.consumer = consumer;
        }

        @Override
        public void run() {
            handler.post(() -> {
                MvpView<S, P> view = reference.get();
                if (view != null) {
                    consumer.accept(view);
                }
            });
        }
    }
}
