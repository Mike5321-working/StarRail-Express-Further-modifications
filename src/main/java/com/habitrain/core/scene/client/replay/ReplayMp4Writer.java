package com.habitrain.core.scene.client.replay;

import org.jcodec.codecs.h264.H264Encoder;
import org.jcodec.codecs.h264.encode.RateControl;
import org.jcodec.codecs.h264.io.model.SliceType;
import org.jcodec.common.Codec;
import org.jcodec.common.MuxerTrack;
import org.jcodec.common.VideoCodecMeta;
import org.jcodec.common.VideoEncoder;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Packet;
import org.jcodec.common.model.Picture;
import org.jcodec.common.model.Size;
import org.jcodec.containers.mp4.muxer.MP4Muxer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

/**
 * Pure-Java H.264 / MP4 writer backed by JCodec. Converts OpenGL's bottom-up BGR24 frames
 * directly into {@link ColorSpace#YUV420J} in a single allocation-free pass and encodes with
 * constant quantization so dark starfield and flat UI regions compress cleanly without bitrate bloat.
 */
final class ReplayMp4Writer implements ReplayVideoWriter {
    private static final int KEY_INTERVAL = 60;
    private static final int QP_INTRA = 22;
    private static final int QP_INTER = 25;

    private final SeekableByteChannel channel;
    private final MP4Muxer muxer;
    private final MuxerTrack track;
    private final H264Encoder encoder;
    private final Picture yuv;
    private final ByteBuffer packetScratch;
    private final int width;
    private final int height;
    private final int fps;
    private final int expectedBytes;
    private int frameNo;

    ReplayMp4Writer(Path path, int width, int height, int fps) throws IOException {
        if (width <= 0 || height <= 0 || (width & 1) != 0 || (height & 1) != 0) {
            throw new IllegalArgumentException("MP4 dimensions must be positive even integers: " + width + "x" + height);
        }
        if (fps <= 0) {
            throw new IllegalArgumentException("FPS must be positive: " + fps);
        }
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.expectedBytes = Math.multiplyExact(Math.multiplyExact(width, height), 3);
        this.yuv = Picture.create(width, height, ColorSpace.YUV420J);
        this.encoder = new H264Encoder(new ConstantQpRateControl(QP_INTRA, QP_INTER));
        this.encoder.setKeyInterval(KEY_INTERVAL);
        this.encoder.setMotionSearchRange(width >= 2560 ? 8 : 12);
        this.packetScratch = ByteBuffer.allocate(encoder.estimateBufferSize(yuv));

        SeekableByteChannel opened = NIOUtils.writableChannel(path.toFile());
        try {
            this.muxer = MP4Muxer.createMP4MuxerToChannel(opened);
            this.track = muxer.addVideoTrack(
                    Codec.H264,
                    VideoCodecMeta.createSimpleVideoCodecMeta(new Size(width, height), ColorSpace.YUV420J));
            this.channel = opened;
        } catch (IOException | RuntimeException failure) {
            try {
                opened.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    @Override
    public void add(byte[] bottomUpBgr) throws IOException {
        if (bottomUpBgr.length != expectedBytes) {
            throw new IOException("Unexpected video frame size: " + bottomUpBgr.length + " != " + expectedBytes);
        }
        bgrBottomUpToYuv420j(bottomUpBgr, width, height,
                yuv.getPlaneData(0), yuv.getPlaneData(1), yuv.getPlaneData(2));

        packetScratch.clear();
        VideoEncoder.EncodedFrame encoded = encoder.encodeFrame(yuv, packetScratch);
        ByteBuffer raw = encoded.getData();
        // Copy out of packetScratch so SPS/PPS slices retained by the muxer track cannot be aliased by later frames.
        ByteBuffer packetData = ByteBuffer.allocate(raw.remaining());
        packetData.put(raw.duplicate());
        packetData.flip();

        Packet packet = Packet.createPacket(
                packetData,
                frameNo,
                fps,
                1,
                frameNo,
                encoded.isKeyFrame() ? Packet.FrameType.KEY : Packet.FrameType.INTER,
                null);
        track.addFrame(packet);
        frameNo++;
    }

    @Override
    public void finish() throws IOException {
        muxer.finish();
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    /**
     * Converts bottom-up 24-bit BGR rows directly into JCodec's {@code -128}-biased JFIF YCbCr 4:2:0 planes.
     */
    static void bgrBottomUpToYuv420j(byte[] bottomUpBgr, int width, int height,
                                     byte[] yPlane, byte[] uPlane, byte[] vPlane) {
        int rowBytes = width * 3;
        int halfWidth = width >> 1;
        for (int y = 0; y < height; y += 2) {
            int srcRow0 = (height - 1 - y) * rowBytes;
            int srcRow1 = srcRow0 - rowBytes;
            int dstY0 = y * width;
            int dstY1 = dstY0 + width;
            int uvRow = (y >> 1) * halfWidth;

            for (int x = 0, srcOff = 0, uv = uvRow; x < width; x += 2, srcOff += 6, uv++) {
                int i00 = srcRow0 + srcOff;
                int b00 = bottomUpBgr[i00] & 0xFF;
                int g00 = bottomUpBgr[i00 + 1] & 0xFF;
                int r00 = bottomUpBgr[i00 + 2] & 0xFF;

                int i01 = i00 + 3;
                int b01 = bottomUpBgr[i01] & 0xFF;
                int g01 = bottomUpBgr[i01 + 1] & 0xFF;
                int r01 = bottomUpBgr[i01 + 2] & 0xFF;

                int i10 = srcRow1 + srcOff;
                int b10 = bottomUpBgr[i10] & 0xFF;
                int g10 = bottomUpBgr[i10 + 1] & 0xFF;
                int r10 = bottomUpBgr[i10 + 2] & 0xFF;

                int i11 = i10 + 3;
                int b11 = bottomUpBgr[i11] & 0xFF;
                int g11 = bottomUpBgr[i11 + 1] & 0xFF;
                int r11 = bottomUpBgr[i11 + 2] & 0xFF;

                yPlane[dstY0 + x] = (byte) (((77 * r00 + 150 * g00 + 29 * b00 + 128) >> 8) - 128);
                yPlane[dstY0 + x + 1] = (byte) (((77 * r01 + 150 * g01 + 29 * b01 + 128) >> 8) - 128);
                yPlane[dstY1 + x] = (byte) (((77 * r10 + 150 * g10 + 29 * b10 + 128) >> 8) - 128);
                yPlane[dstY1 + x + 1] = (byte) (((77 * r11 + 150 * g11 + 29 * b11 + 128) >> 8) - 128);

                int rSum = r00 + r01 + r10 + r11;
                int gSum = g00 + g01 + g10 + g11;
                int bSum = b00 + b01 + b10 + b11;

                uPlane[uv] = (byte) Math.clamp((-43 * rSum - 85 * gSum + 128 * bSum + 512) >> 10, -128, 127);
                vPlane[uv] = (byte) Math.clamp((128 * rSum - 107 * gSum - 21 * bSum + 512) >> 10, -128, 127);
            }
        }
    }

    private record ConstantQpRateControl(int qpIntra, int qpInter) implements RateControl {
        @Override
        public int startPicture(Size sz, int maxSize, SliceType sliceType) {
            return sliceType == SliceType.I ? qpIntra : qpInter;
        }

        @Override
        public int initialQpDelta() {
            return 0;
        }

        @Override
        public int accept(int bits) {
            return 0;
        }
    }
}
