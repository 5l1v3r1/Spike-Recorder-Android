package com.backyardbrains.data.processing;

import android.support.annotation.CallSuper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.SparseArray;
import com.angrygoat.buffer.CircularByteBuffer;
import com.backyardbrains.audio.Filters;
import com.backyardbrains.filters.Filter;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.backyardbrains.utils.LogUtils.makeLogTag;

/**
 * @author Tihomir Leka <ticapeca at gmail.com.
 */
public abstract class AbstractSampleSource implements SampleSource {

    private static final String TAG = makeLogTag(AbstractSampleSource.class);

    // Additional filters that should be applied to input data
    protected static final Filters FILTERS = new Filters();

    // 16MB for initial buffer size until sample rate is set
    private static final int DEFAULT_BUFFER_SIZE = 16 * 1024;
    // After sample rate is set buffer will change to be 2 seconds long
    private static final int BUFFERED_SECONDS = 2;

    @SuppressWarnings("WeakerAccess") final OnSamplesReceivedListener listener;

    /**
     * Background thread that reads data from the local buffer filled by the derived class and passes it to {@link
     * OnSamplesReceivedListener}.
     */
    protected class ReadThread extends Thread {

        private AtomicBoolean working = new AtomicBoolean(true);
        private AtomicBoolean paused = new AtomicBoolean(false);

        @Override public void run() {
            while (working.get()) {
                if (!paused.get()) {
                    int size = readBuffer.peekSize();
                    if (size > 0) {
                        byte[] data = new byte[size];
                        readBuffer.read(data, data.length, false);

                        // we should process the incoming data even if there is no listener
                        SparseArray<String> events = new SparseArray<>();
                        if (listener == null) {
                            processIncomingData(data, events);
                            return;
                        } else {
                            // forward received samples to OnSamplesReceivedListener
                            synchronized (listener) {
                                listener.onSamplesReceived(processIncomingData(data, events), events);
                            }
                        }
                    }
                }
            }
        }

        void pauseWorking() {
            paused.set(true);
        }

        void resumeWorking() {
            paused.set(false);
        }

        void stopWorking() {
            working.set(false);
        }
    }

    private ReadThread readThread;
    @SuppressWarnings("WeakerAccess") CircularByteBuffer readBuffer = new CircularByteBuffer(DEFAULT_BUFFER_SIZE);

    private int sampleRate;
    private int channelCount;

    public AbstractSampleSource(@Nullable OnSamplesReceivedListener listener) {
        this.listener = listener;
    }

    /**
     * Returns {@link Filter} that is used to additionally filter incoming data.
     */
    @Nullable public Filter getFilter() {
        return FILTERS.getFilter();
    }

    /**
     * Sets {@link Filter} that is used to additionally filter incoming data.
     */
    public void setFilter(@Nullable Filter filter) {
        FILTERS.setFilter(filter);
    }

    /**
     * Returns sample rate for this input source.
     */
    public int getSampleRate() {
        return sampleRate;
    }

    /**
     * Sets sample rate for this input source.
     */
    protected void setSampleRate(int sampleRate) {
        this.sampleRate = sampleRate;
        // recreate buffer to hold 2 seconds of data
        this.readBuffer.setCapacity(sampleRate * BUFFERED_SECONDS);
    }

    /**
     * Returns number of channels for this input source.
     */
    public int getChannelCount() {
        return channelCount;
    }

    /**
     * Sets number of channels for this input source.
     */
    @CallSuper protected void setChannelCount(int channelCount) {
        this.channelCount = channelCount;
    }

    /**
     * Whether this is an audio input source.
     */
    public boolean isAudio() {
        return getType() == Type.AUDIO;
    }

    /**
     * Whether this is an USB input source.
     */
    public boolean isUsb() {
        return getType() == Type.USB;
    }

    /**
     * {@inheritDoc}
     */
    @Override public final void start() {
        // start the read thread
        if (readThread == null) {
            readThread = new ReadThread();
            readThread.start();
        }
        // give chance to subclass to init resources and start writing data to buffer
        onInputStart();
    }

    /**
     * {@inheritDoc}
     */
    @Override public final void pause() {
        // pause read thread
        if (readThread != null) readThread.pauseWorking();
    }

    /**
     * {@inheritDoc}
     */
    @Override public final void resume() {
        // resume read thread
        if (readThread != null) readThread.resumeWorking();
    }

    /**
     * {@inheritDoc}
     */
    @Override public final void stop() {
        // give chance to subclass to clean resources
        onInputStop();
        // stop the read thread
        if (readThread != null) {
            readThread.stopWorking();
            readThread = null;
        }
    }

    /**
     * Returns size of the read buffer.
     *
     * @return Size of the buffer in bytes.
     */
    protected final int getReadBufferSize() {
        return readBuffer.getCapacity();
    }

    /**
     * Sets the size of the read buffer.
     *
     * @param size Size of the buffer in bytes.
     */
    protected final void setReadBufferSize(int size) {
        readBuffer.setCapacity(size);
    }

    /**
     * Subclasses should write any received data to buffer for further processing.
     */
    protected final void writeToBuffer(@NonNull byte[] data) {
        readBuffer.write(data);
    }

    /**
     * Called during data reading initialization from the input stream. Implementation should start the actual reading
     * of data from the concrete source.
     */
    protected abstract void onInputStart();

    /**
     * Called during data reading finalization from the input stream. Implementation should stop the actual reading
     * of data from the concrete source and clear all resources.
     */
    protected abstract void onInputStop();

    /**
     * Called by {@link OnSamplesReceivedListener} before triggering the listener to convert incoming byte data to
     * sample data.
     * <p>
     * This method is called from background thread so implementation should not communicate with UI thread
     * directly.
     */
    @NonNull protected abstract short[] processIncomingData(byte[] data, @NonNull SparseArray<String> events);
}