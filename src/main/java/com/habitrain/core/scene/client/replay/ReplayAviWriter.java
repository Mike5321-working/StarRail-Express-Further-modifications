package com.habitrain.core.scene.client.replay;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Indexed AVI 1.0 with Motion JPEG video and optional 48 kHz 16-bit stereo PCM audio; uses the JDK JPEG encoder. */
final class ReplayAviWriter implements ReplayVideoWriter {
    private static final int AUDIO_SAMPLE_RATE = 48_000;
    private static final int AUDIO_CHANNELS = 2;
    private static final int AUDIO_BLOCK_ALIGN = AUDIO_CHANNELS * 2;

    private final RandomAccessFile out;
    private final ImageWriter jpeg;
    private final ImageWriteParam compression;
    private final BufferedImage image;
    private final byte[] topDown;
    private final ByteArrayOutputStream scratch = new ByteArrayOutputStream(128 * 1024);
    private final List<Index> index = new ArrayList<>();
    private final long moviSize, moviStart, mainFrames, streamFrames, audioStreamFrames;
    private final int rowBytes;
    private final boolean includeAudio;
    private byte[] pcmScratch = new byte[0];
    private int videoFrameCount;
    private int audioStereoFrames;

    private record Index(String fourcc, int offset, int length) {}

    ReplayAviWriter(Path path, int width, int height, int fps) throws IOException {
        this(path, width, height, fps, false);
    }

    ReplayAviWriter(Path path, int width, int height, int fps, boolean includeAudio) throws IOException {
        this.includeAudio = includeAudio;
        rowBytes = Math.multiplyExact(width, 3);
        image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        topDown = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        var writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IOException("JPEG encoder unavailable");
        jpeg = writers.next();
        compression = jpeg.getDefaultWriteParam();
        compression.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        compression.setCompressionQuality(0.87f);
        try {
            out = new RandomAccessFile(path.toFile(), "rw");
        } catch (IOException | RuntimeException failure) {
            jpeg.dispose();
            throw failure;
        }
        try {
            four("RIFF"); le(0); four("AVI ");
            long header = list("hdrl");
            four("avih"); le(56);
            le(1_000_000 / fps); le(0); le(0); le(0x10);
            mainFrames = out.getFilePointer(); le(0);
            le(0); le(includeAudio ? 2 : 1); le(0); le(width); le(height);
            le(0); le(0); le(0); le(0);
            long stream = list("strl");
            four("strh"); le(56);
            four("vids"); four("MJPG"); le(0); le(0); le(0);
            le(1); le(fps); le(0);
            streamFrames = out.getFilePointer(); le(0);
            le(0); le(-1); le(0);
            shortLe(0); shortLe(0); shortLe(width); shortLe(height);
            four("strf"); le(40);
            le(40); le(width); le(height); shortLe(1); shortLe(24); four("MJPG");
            le(topDown.length); le(0); le(0); le(0); le(0);
            finishList(stream);
            if (includeAudio) {
                long audioStream = list("strl");
                four("strh"); le(56);
                four("auds"); le(0); le(0); le(0); le(0);
                le(AUDIO_BLOCK_ALIGN); le(AUDIO_SAMPLE_RATE * AUDIO_BLOCK_ALIGN); le(0);
                audioStreamFrames = out.getFilePointer(); le(0);
                le(16384); le(-1); le(AUDIO_BLOCK_ALIGN);
                shortLe(0); shortLe(0); shortLe(0); shortLe(0);
                four("strf"); le(18);
                shortLe(1); shortLe(AUDIO_CHANNELS); le(AUDIO_SAMPLE_RATE);
                le(AUDIO_SAMPLE_RATE * AUDIO_BLOCK_ALIGN);
                shortLe(AUDIO_BLOCK_ALIGN); shortLe(16); shortLe(0);
                finishList(audioStream);
            } else {
                audioStreamFrames = -1L;
            }
            finishList(header);
            moviSize = list("movi");
            moviStart = moviSize + 4;
        } catch (IOException | RuntimeException failure) {
            try { close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    @Override
    public void add(byte[] bottomUp) throws IOException {
        if (bottomUp.length != topDown.length) throw new IOException("Unexpected video frame size");
        for (int i = 0; i < topDown.length; i += rowBytes) {
            System.arraycopy(bottomUp, topDown.length - i - rowBytes, topDown, i, rowBytes);
        }
        scratch.reset();
        try (var output = new MemoryCacheImageOutputStream(scratch)) {
            jpeg.setOutput(output);
            jpeg.write(null, new IIOImage(image, null, null), compression);
        } finally {
            jpeg.setOutput(null);
        }
        int size = scratch.size();
        int offset = uint32(out.getFilePointer() - moviStart);
        index.add(new Index("00dc", offset, size));
        videoFrameCount++;
        four("00dc"); le(size);
        scratch.writeTo(new java.io.OutputStream() {
            @Override public void write(int value) throws IOException { out.write(value); }
            @Override public void write(byte[] bytes, int start, int count) throws IOException { out.write(bytes, start, count); }
        });
        if ((size & 1) != 0) out.write(0);
    }

    @Override
    public void addAudio(short[] stereo, int stereoFrameOffset, int stereoFrames) throws IOException {
        if (!includeAudio || stereoFrames <= 0) return;
        int byteLen = Math.multiplyExact(stereoFrames, AUDIO_BLOCK_ALIGN);
        if (pcmScratch.length < byteLen) pcmScratch = new byte[byteLen];
        int totalSrcFrames = stereo == null ? 0 : stereo.length / AUDIO_CHANNELS;
        for (int i = 0; i < stereoFrames; i++) {
            int srcFrame = stereoFrameOffset + i;
            short l = 0;
            short r = 0;
            if (srcFrame >= 0 && srcFrame < totalSrcFrames) {
                l = stereo[srcFrame * 2];
                r = stereo[srcFrame * 2 + 1];
            }
            int p = i * 4;
            pcmScratch[p] = (byte) (l & 0xFF);
            pcmScratch[p + 1] = (byte) ((l >>> 8) & 0xFF);
            pcmScratch[p + 2] = (byte) (r & 0xFF);
            pcmScratch[p + 3] = (byte) ((r >>> 8) & 0xFF);
        }
        int offset = uint32(out.getFilePointer() - moviStart);
        index.add(new Index("01wb", offset, byteLen));
        audioStereoFrames += stereoFrames;
        four("01wb"); le(byteLen);
        out.write(pcmScratch, 0, byteLen);
    }

    @Override
    public void addAudioBytes(byte[] pcm16LeStereo, int byteOffset, int byteLength) throws IOException {
        if (!includeAudio || pcm16LeStereo == null || byteLength <= 0) return;
        int aligned = byteLength & ~3;
        if (aligned <= 0) return;
        int offset = uint32(out.getFilePointer() - moviStart);
        index.add(new Index("01wb", offset, aligned));
        audioStereoFrames += aligned / AUDIO_BLOCK_ALIGN;
        four("01wb"); le(aligned);
        out.write(pcm16LeStereo, byteOffset, aligned);
    }

    @Override
    public void finish() throws IOException {
        finishList(moviSize);
        four("idx1"); le(index.size() * 16);
        for (Index entry : index) {
            four(entry.fourcc); le(0x10); le(entry.offset); le(entry.length);
        }
        patch(4, uint32(out.getFilePointer() - 8));
        patch(mainFrames, videoFrameCount);
        patch(streamFrames, videoFrameCount);
        if (includeAudio && audioStreamFrames >= 0) {
            patch(audioStreamFrames, audioStereoFrames);
        }
    }

    private long list(String type) throws IOException {
        four("LIST"); long offset = out.getFilePointer(); le(0); four(type); return offset;
    }

    private void finishList(long offset) throws IOException {
        patch(offset, uint32(out.getFilePointer() - offset - 4));
    }

    static final class AviSizeLimitException extends IOException {
        AviSizeLimitException(String message) { super(message); }
    }

    private static int uint32(long value) throws IOException {
        if (value < 0 || value > 0x7FFF_FFFFL) {
            throw new AviSizeLimitException("AVI 1.0 chunk exceeds 2 GiB compatibility limit (" + value
                    + "); enable compression (MP4/H.264) for long high-resolution exports");
        }
        return (int) value;
    }

    private void patch(long offset, int value) throws IOException {
        long position = out.getFilePointer();
        out.seek(offset); le(value); out.seek(position);
    }

    private void four(String s) throws IOException { out.writeBytes(s); }
    private void le(int value) throws IOException { out.writeInt(Integer.reverseBytes(value)); }
    private void shortLe(int value) throws IOException { out.writeShort(Short.reverseBytes((short) value)); }

    @Override public void close() throws IOException {
        try { jpeg.dispose(); } finally { out.close(); }
    }
}
