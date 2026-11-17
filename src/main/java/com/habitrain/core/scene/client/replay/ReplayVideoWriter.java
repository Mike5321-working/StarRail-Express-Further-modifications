package com.habitrain.core.scene.client.replay;

import java.io.IOException;

/** Sequential frame sink for exported replay videos. */
interface ReplayVideoWriter extends AutoCloseable {
    void add(byte[] bottomUpBgr) throws IOException;

    void finish() throws IOException;

    @Override
    void close() throws IOException;
}
