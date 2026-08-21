package com.idavy.drtops.jtgateway.netty;

import com.idavy.drtops.jt.protocol.codec.Jt808Frame;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

public final class MessageRateLimitHandler extends ChannelInboundHandlerAdapter {
    private final ConnectionAdmissionHandler.AdmissionTracker tracker;

    public MessageRateLimitHandler(ConnectionAdmissionHandler.AdmissionTracker tracker) {
        this.tracker = java.util.Objects.requireNonNull(tracker, "tracker");
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
        String remoteIp = context.channel().attr(ConnectionAdmissionHandler.REMOTE_IP).get();
        if (remoteIp == null) {
            remoteIp = ConnectionAdmissionHandler.remoteIp(context.channel().remoteAddress());
        }
        if (tracker.allowMessage(remoteIp)) {
            context.fireChannelRead(message);
            return;
        }
        release(message);
        context.close();
    }

    private static void release(Object message) {
        if (message instanceof Jt808Frame frame) {
            frame.body().release();
        } else {
            io.netty.util.ReferenceCountUtil.release(message);
        }
    }
}
