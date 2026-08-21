package com.idavy.drtops.jtgateway.netty;

import com.idavy.drtops.jt.protocol.codec.Jt808Frame;
import com.idavy.drtops.jt.protocol.codec.Jt808MessageHeader;
import com.idavy.drtops.jt.protocol.codec.ProtocolVersion;
import com.idavy.drtops.jtgateway.dispatch.ProtocolModuleRegistry;
import com.idavy.drtops.jtgateway.session.RegistrationAuthenticationHandler;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** Dispatches post-authentication frames and acknowledges only durable ingress outcomes. */
final class ProtocolDispatchHandler extends ChannelInboundHandlerAdapter {
    private static final AtomicInteger PLATFORM_SERIAL = new AtomicInteger();

    private final RegistrationAuthenticationHandler registrationAuthentication;
    private final ProtocolModuleRegistry protocolModules;

    ProtocolDispatchHandler(
            RegistrationAuthenticationHandler registrationAuthentication,
            ProtocolModuleRegistry protocolModules) {
        this.registrationAuthentication = Objects.requireNonNull(
                registrationAuthentication, "registrationAuthentication");
        this.protocolModules = Objects.requireNonNull(protocolModules, "protocolModules");
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
        if (!(message instanceof Jt808Frame frame)) {
            context.fireChannelRead(message);
            return;
        }
        ProtocolModuleRegistry.DispatchResult result = protocolModules.dispatch(
                registrationAuthentication.session(), frame);
        if (result.mayAcknowledgeSuccess()) {
            context.writeAndFlush(successReply(frame.header()));
        }
    }

    private static Jt808Frame successReply(Jt808MessageHeader request) {
        ByteBuf body = Unpooled.buffer(5)
                .writeShort(request.serialNumber())
                .writeShort(request.messageId())
                .writeByte(0);
        int properties = body.readableBytes();
        if (request.protocolVersion() == ProtocolVersion.JT808_2019) {
            properties |= 0x4000;
        }
        Jt808MessageHeader header = new Jt808MessageHeader(
                0x8001,
                properties,
                body.readableBytes(),
                0,
                false,
                request.protocolVersion(),
                request.protocolVersionByte(),
                request.terminalIdentity(),
                PLATFORM_SERIAL.updateAndGet(current -> current == 0xffff ? 1 : current + 1),
                null,
                null);
        return new Jt808Frame(header, body, (byte) 0);
    }
}
