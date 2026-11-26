package com.habitrain.core.scene.client.replay;

import java.io.IOException;

/** Sequential frame sink for exported replay videos. */
interface ReplayVideoWriter extends AutoCloseable {
    void add(byte[] bottomUpBgr) throws IOException;

    default void addAudio(short[] interleavedStereo48k, int stereoFrameOffset, int stereoFrames) throws IOException {}

    default void addAudioBytes(byte[] pcm16LeStereo, int byteOffset, int byteLength) throws IOException {}

    void finish() throws IOException;

    /** Best-effort asynchronous abort to unblock a stalled writer thread on cancellation. */
    default void abort() {}

    @Override
    void close() throws IOException;
}
