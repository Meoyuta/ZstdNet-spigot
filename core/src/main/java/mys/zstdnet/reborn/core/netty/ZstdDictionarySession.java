package mys.zstdnet.reborn.core.netty;

import mys.zstdnet.reborn.core.dictionary.ZstdDictionary;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.IOException;
import java.util.Objects;

public final class ZstdDictionarySession {
    private static final int OFFER = 1;
    private static final int ACKNOWLEDGE = 2;
    private static final int CONTROL_HEADER_BYTES = 13;

    private final Role role;
    private final ZstdDictionary offeredDictionary;
    private final DictionaryReceiver receiver;
    private final ZstdDictionaryDownloadListener downloadListener;

    private volatile ZstdDictionary activeDictionary;
    private boolean offerSent;
    private long pendingAcknowledgement;
    private boolean announcedDownload;

    private ZstdDictionarySession(
        Role role,
        ZstdDictionary offeredDictionary,
        DictionaryReceiver receiver,
        ZstdDictionaryDownloadListener downloadListener
    ) {
        this.role = role;
        this.offeredDictionary = offeredDictionary;
        this.receiver = receiver;
        this.downloadListener = downloadListener == null ? ZstdDictionaryDownloadListener.NONE : downloadListener;
    }

    public static ZstdDictionarySession client(DictionaryReceiver receiver, ZstdDictionaryDownloadListener listener) {
        return new ZstdDictionarySession(Role.CLIENT, null, Objects.requireNonNull(receiver, "receiver"), listener);
    }

    public static ZstdDictionarySession server(ZstdDictionary dictionary) {
        return new ZstdDictionarySession(Role.SERVER, dictionary, null, ZstdDictionaryDownloadListener.NONE);
    }

    public static ZstdDictionarySession withoutDictionary() {
        return new ZstdDictionarySession(Role.NONE, null, null, ZstdDictionaryDownloadListener.NONE);
    }

    public boolean expectsHeader() {
        return role == Role.CLIENT;
    }

    public ZstdDictionary activeDictionary() {
        return activeDictionary;
    }

    public synchronized byte[] pollOutboundControl() {
        if (role == Role.SERVER && offeredDictionary != null && !offerSent) {
            offerSent = true;
            return offer(offeredDictionary);
        }
        if (role == Role.CLIENT && pendingAcknowledgement != 0L) {
            long id = pendingAcknowledgement;
            pendingAcknowledgement = 0L;
            return acknowledgement(id);
        }
        return null;
    }

    public void observeControlProgress(ByteBuf in, int payloadStart, int payloadLength) {
        if (role != Role.CLIENT || in.writerIndex() <= payloadStart) {
            return;
        }
        int type = in.getUnsignedByte(payloadStart);
        if (type != OFFER || payloadLength < CONTROL_HEADER_BYTES || in.writerIndex() - payloadStart < CONTROL_HEADER_BYTES) {
            return;
        }
        long id = in.getLong(payloadStart + 1);
        int dictionaryBytes = in.getInt(payloadStart + 9);
        if (dictionaryBytes < ZstdDictionary.MIN_BYTES || dictionaryBytes > ZstdDictionary.MAX_BYTES) {
            return;
        }
        int downloaded = Math.max(0, Math.min(dictionaryBytes, in.writerIndex() - payloadStart - CONTROL_HEADER_BYTES));
        synchronized (this) {
            if (!announcedDownload) {
                announcedDownload = true;
                downloadListener.started(id, dictionaryBytes);
            }
        }
        downloadListener.progress(downloaded, dictionaryBytes);
    }

    public void receiveControl(byte[] payload) throws IOException {
        if (payload.length == 0) {
            throw new IOException("empty ZstdNet control payload");
        }
        int type = payload[0] & 0xFF;
        if (type == OFFER) {
            receiveOffer(payload);
            return;
        }
        if (type == ACKNOWLEDGE) {
            receiveAcknowledgement(payload);
            return;
        }
        throw new IOException("unknown ZstdNet control record: " + type);
    }

    private void receiveOffer(byte[] payload) throws IOException {
        if (role != Role.CLIENT || activeDictionary != null || payload.length < CONTROL_HEADER_BYTES) {
            throw new IOException("unexpected ZstdNet dictionary offer");
        }
        ByteBuf in = Unpooled.wrappedBuffer(payload);
        try {
            in.readUnsignedByte();
            long id = in.readLong();
            int size = in.readInt();
            if (size < ZstdDictionary.MIN_BYTES || size > ZstdDictionary.MAX_BYTES || in.readableBytes() != size) {
                throw new IOException("invalid ZstdNet dictionary offer size");
            }
            if (!announcedDownload) {
                announcedDownload = true;
                downloadListener.started(id, size);
            }
            downloadListener.progress(size, size);
            byte[] bytes = new byte[size];
            in.readBytes(bytes);
            ZstdDictionary dictionary = receiver.receive(id, bytes);
            if (dictionary.id() != id) {
                throw new IOException("received dictionary id does not match offer");
            }
            synchronized (this) {
                activeDictionary = dictionary;
                pendingAcknowledgement = dictionary.id();
            }
            downloadListener.completed(id);
        } catch (IOException e) {
            downloadListener.failed(e.getMessage());
            throw e;
        } finally {
            in.release();
        }
    }

    private synchronized void receiveAcknowledgement(byte[] payload) throws IOException {
        if (role != Role.SERVER || !offerSent || activeDictionary != null || payload.length != 9 || offeredDictionary == null) {
            throw new IOException("unexpected ZstdNet dictionary acknowledgement");
        }
        ByteBuf in = Unpooled.wrappedBuffer(payload);
        try {
            in.readUnsignedByte();
            long id = in.readLong();
            if (id != offeredDictionary.id()) {
                throw new IOException("ZstdNet dictionary acknowledgement does not match server dictionary");
            }
            activeDictionary = offeredDictionary;
        } finally {
            in.release();
        }
    }

    private static byte[] offer(ZstdDictionary dictionary) {
        byte[] bytes = dictionary.bytes();
        ByteBuf out = Unpooled.buffer(CONTROL_HEADER_BYTES + bytes.length);
        try {
            out.writeByte(OFFER);
            out.writeLong(dictionary.id());
            out.writeInt(bytes.length);
            out.writeBytes(bytes);
            byte[] result = new byte[out.readableBytes()];
            out.readBytes(result);
            return result;
        } finally {
            out.release();
        }
    }

    private static byte[] acknowledgement(long id) {
        ByteBuf out = Unpooled.buffer(9);
        try {
            out.writeByte(ACKNOWLEDGE);
            out.writeLong(id);
            byte[] result = new byte[out.readableBytes()];
            out.readBytes(result);
            return result;
        } finally {
            out.release();
        }
    }

    @FunctionalInterface
    public interface DictionaryReceiver {
        ZstdDictionary receive(long expectedId, byte[] bytes) throws IOException;
    }

    public synchronized boolean hasPendingControl() {
        return role == Role.CLIENT && pendingAcknowledgement != 0L;
    }

    public void disconnected() {
        if (role == Role.CLIENT && announcedDownload && activeDictionary == null) {
            downloadListener.failed("Dictionary download interrupted");
        }
    }

    private enum Role {
        NONE,
        CLIENT,
        SERVER
    }
}
