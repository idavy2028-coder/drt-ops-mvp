package com.idavy.drtops.jtsimulator;

import com.idavy.drtops.jt.protocol.codec.Jt808Frame;
import com.idavy.drtops.jt.protocol.codec.Jt808FrameDecoder;
import com.idavy.drtops.jt.protocol.codec.Jt808FrameEncoder;
import com.idavy.drtops.jt.protocol.codec.Jt808MessageHeader;
import com.idavy.drtops.jt.protocol.codec.ProtocolVersion;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One simulated terminal connection over a real TCP socket. Frames are encoded/decoded with the
 * production jt-protocol codec so scenario results exercise the same wire format as the gateway.
 * The full terminal identity never appears in any report-facing accessor; only the masked alias
 * is exposed.
 */
public final class SimulatedTerminal implements AutoCloseable {
    private static final Duration READ_SLICE = Duration.ofMillis(50);
    private final String identity;
    private final ProtocolVersion protocolVersion;
    private final String plateNumber;
    private final String manufacturerId;
    private final String model;
    private final String terminalCode;
    private final String maskedAlias;
    private final UUID connectionId = UUID.randomUUID();
    private final AtomicInteger serialNumbers = new AtomicInteger();
    private final BlockingQueue<ReplyRecord> replies = new LinkedBlockingQueue<>();
    private final CountDownLatch peerClosed = new CountDownLatch(1);

    private Socket socket;
    private OutputStream output;
    private Thread reader;
    private volatile byte[] registrationToken = new byte[0];
    private ConnectionState connectionState = ConnectionState.NEW;

    public SimulatedTerminal(String identity, ProtocolVersion protocolVersion, String plateNumber) {
        this(identity, protocolVersion, plateNumber, "SIMMF", "SIM-MODEL", "SIM0001");
    }

    public SimulatedTerminal(
            String identity,
            ProtocolVersion protocolVersion,
            String plateNumber,
            String manufacturerId,
            String model,
            String terminalCode) {
        if (identity == null || identity.isBlank()) {
            throw new IllegalArgumentException("identity is required");
        }
        this.identity = identity;
        this.protocolVersion = Objects.requireNonNull(protocolVersion, "protocolVersion");
        this.plateNumber = plateNumber == null ? "SIM-PLATE" : plateNumber;
        this.manufacturerId = Objects.requireNonNull(manufacturerId, "manufacturerId");
        this.model = Objects.requireNonNull(model, "model");
        this.terminalCode = Objects.requireNonNull(terminalCode, "terminalCode");
        int visible = Math.min(4, identity.length());
        this.maskedAlias = "****" + identity.substring(identity.length() - visible);
    }

    public String maskedAlias() {
        return maskedAlias;
    }

    public ProtocolVersion protocolVersion() {
        return protocolVersion;
    }

    /** Synthetic physical-connection identifier; it never exposes terminal identity or credentials. */
    public UUID connectionId() {
        return connectionId;
    }

    public synchronized void connect(InetSocketAddress endpoint) {
        if (connectionState != ConnectionState.NEW) {
            throw new IllegalStateException("terminal connection is single-use and already closed or connected");
        }
        Socket connected = new Socket();
        try {
            connected.connect(endpoint, 2_000);
            socket = connected;
            output = connected.getOutputStream();
            connectionState = ConnectionState.CONNECTED;
        } catch (IOException unreachable) {
            try { connected.close(); } catch (IOException ignored) { }
            connectionState = ConnectionState.CLOSED;
            throw new IllegalStateException("cannot connect to the platform endpoint", unreachable);
        }
        reader = new Thread(() -> readLoop(connected), "jt-sim-reader");
        reader.setDaemon(true);
        reader.start();
    }

    /** Sends the registration message and returns its serial number. */
    public int sendRegistration() {
        boolean versioned = protocolVersion.versionedHeader();
        ByteBuf body = Unpooled.buffer();
        body.writeShort(32).writeShort(1);
        writeFixed(body, manufacturerId, versioned ? 11 : 5, StandardCharsets.US_ASCII);
        writeFixed(body, model, versioned ? 30 : 20, StandardCharsets.US_ASCII);
        writeFixed(body, terminalCode, versioned ? 30 : 7, StandardCharsets.US_ASCII);
        body.writeByte(1);
        body.writeCharSequence(plateNumber, Charset.forName("GBK"));
        byte[] bytes = new byte[body.readableBytes()];
        body.readBytes(bytes);
        body.release();
        return sendFrame(0x0100, bytes);
    }

    /** Sends the authentication message using the token captured from the registration reply. */
    public int sendAuthentication() {
        byte[] token = registrationToken;
        ByteBuf body = Unpooled.buffer();
        if (protocolVersion.versionedHeader()) {
            body.writeByte(token.length);
        }
        body.writeBytes(token);
        byte[] bytes = new byte[body.readableBytes()];
        body.readBytes(bytes);
        body.release();
        return sendFrame(0x0102, bytes);
    }

    public int sendHeartbeat() {
        return sendFrame(0x0002, new byte[0]);
    }

    /** Sends a plain 0x0200 position without additional items (synthetic fixed coordinates). */
    public int sendPosition() {
        ByteBuf body = Unpooled.buffer(28);
        body.writeInt(0);
        body.writeInt(2);
        body.writeInt(32_000_000);
        body.writeInt(118_000_000);
        body.writeShort(90);
        body.writeShort(600);
        body.writeShort(20);
        body.writeBytes(HexFormat.of().parseHex("260815120000"));
        byte[] bytes = new byte[body.readableBytes()];
        body.readBytes(bytes);
        body.release();
        return sendFrame(0x0200, bytes);
    }

    public int sendFrame(int messageId, byte[] body) {
        int serial = serialNumbers.updateAndGet(current -> current == 0xffff ? 1 : current + 1);
        int properties = body.length;
        int versionByte = 0;
        if (protocolVersion.versionedHeader()) {
            properties |= 0x4000;
            versionByte = 1;
        }
        Jt808Frame frame = new Jt808Frame(new Jt808MessageHeader(
                messageId, properties, body.length, 0, false,
                protocolVersion, versionByte, identity, serial, null, null),
                Unpooled.wrappedBuffer(body), (byte) 0);
        EmbeddedChannel encoder = new EmbeddedChannel(new Jt808FrameEncoder());
        try {
            if (!encoder.writeOutbound(frame)) {
                throw new IllegalStateException("frame encoder refused the outbound frame");
            }
            ByteBuf encoded = encoder.readOutbound();
            try {
                byte[] bytes = new byte[encoded.readableBytes()];
                encoded.readBytes(bytes);
                write(bytes);
            } finally {
                encoded.release();
            }
        } finally {
            if (frame.body().refCnt() > 0) {
                frame.body().release();
            }
            encoder.finishAndReleaseAll();
        }
        return serial;
    }

    /** Writes raw bytes verbatim; used by half-packet, sticky-packet and checksum scenarios. */
    public void sendRaw(byte[] bytes) {
        write(bytes);
    }

    /** Waits for the next platform reply; empty when the deadline passes without one. */
    public ReplyRecord awaitReply(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return null;
                }
                ReplyRecord reply = replies.poll(
                        Math.min(remaining, READ_SLICE.toNanos()), TimeUnit.NANOSECONDS);
                if (reply != null) {
                    return reply;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while awaiting a platform reply", interrupted);
        }
    }

    /** True when the platform closed the connection within the timeout. */
    public boolean awaitPeerClose(Duration timeout) {
        try {
            return peerClosed.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void disconnect() {
        Socket connected;
        Thread readerToJoin;
        synchronized (this) {
            if (connectionState == ConnectionState.CLOSED) {
                return;
            }
            connectionState = ConnectionState.CLOSED;
            connected = socket;
            socket = null;
            output = null;
            readerToJoin = reader;
            reader = null;
            clearSessionState();
        }
        if (connected != null) {
            try {
                connected.close();
            } catch (IOException ignored) {
                // Closing a simulated terminal must never fail the scenario runner.
            }
        }
        if (readerToJoin != null && readerToJoin != Thread.currentThread()) {
            try {
                readerToJoin.join(500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (this) {
            clearSessionState();
        }
    }

    @Override
    public void close() {
        disconnect();
    }

    private void write(byte[] bytes) {
        OutputStream target;
        synchronized (this) {
            target = output;
        }
        if (target == null) {
            throw new IllegalStateException("terminal is not connected");
        }
        try {
            target.write(bytes);
            target.flush();
        } catch (IOException broken) {
            throw new IllegalStateException("the platform connection is broken", broken);
        }
    }

    private void readLoop(Socket connected) {
        EmbeddedChannel decoder = new EmbeddedChannel(new Jt808FrameDecoder());
        try {
            InputStream input = connected.getInputStream();
            byte[] chunk = new byte[1024];
            int read;
            while ((read = input.read(chunk)) >= 0) {
                decoder.writeInbound(Unpooled.wrappedBuffer(chunk, 0, read));
                Object decoded;
                while ((decoded = decoder.readInbound()) != null) {
                    Jt808Frame frame = (Jt808Frame) decoded;
                    try {
                        DecodedReply reply = decodeReply(frame);
                        synchronized (this) {
                            if (connectionState == ConnectionState.CONNECTED && socket == connected) {
                                if (reply.registrationToken() != null) {
                                    Arrays.fill(registrationToken, (byte) 0);
                                    registrationToken = reply.registrationToken();
                                }
                                replies.add(reply.reply());
                            }
                        }
                    } finally {
                        if (frame.body().refCnt() > 0) {
                            frame.body().release();
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException ended) {
            // EOF or a malformed downlink both terminate the reader; awaiters time out honestly.
        } finally {
            decoder.finishAndReleaseAll();
            peerClosed.countDown();
        }
    }

    private DecodedReply decodeReply(Jt808Frame frame) {
        ByteBuf body = frame.body().duplicate();
        byte[] bytes = new byte[body.readableBytes()];
        body.readBytes(bytes);
        int messageId = frame.header().messageId();
        if (messageId == 0x8001 && bytes.length >= 5) {
            int requestSerial = ((bytes[0] & 0xff) << 8) | (bytes[1] & 0xff);
            int requestMessageId = ((bytes[2] & 0xff) << 8) | (bytes[3] & 0xff);
            return new DecodedReply(new ReplyRecord(messageId, requestMessageId, bytes[4] & 0xff, requestSerial,
                    HexFormat.of().formatHex(bytes)), null);
        }
        if (messageId == 0x8100 && bytes.length >= 3) {
            int requestSerial = ((bytes[0] & 0xff) << 8) | (bytes[1] & 0xff);
            int result = bytes[2] & 0xff;
            byte[] token = null;
            if (result == 0 && bytes.length > 3) {
                token = new byte[bytes.length - 3];
                System.arraycopy(bytes, 3, token, 0, token.length);
            }
            return new DecodedReply(new ReplyRecord(
                    messageId, 0x0100, result, requestSerial, HexFormat.of().formatHex(bytes)), token);
        }
        return new DecodedReply(new ReplyRecord(messageId, null, -1, frame.header().serialNumber(),
                HexFormat.of().formatHex(bytes)), null);
    }

    private void clearSessionState() {
        Arrays.fill(registrationToken, (byte) 0);
        registrationToken = new byte[0];
        replies.clear();
    }

    private static void writeFixed(ByteBuf target, String value, int length, Charset charset) {
        byte[] bytes = value.getBytes(charset);
        if (bytes.length > length) {
            throw new IllegalArgumentException("value does not fit the fixed field");
        }
        target.writeBytes(bytes);
        target.writeZero(length - bytes.length);
    }

    /**
     * One platform reply. {@code bodyHex} is retained for protocol assertions but is never
     * rendered into report text.
     */
    public record ReplyRecord(
            int messageId, Integer requestMessageId, int result, int requestSerialNo, String bodyHex) { }

    private record DecodedReply(ReplyRecord reply, byte[] registrationToken) { }

    private enum ConnectionState { NEW, CONNECTED, CLOSED }
}
