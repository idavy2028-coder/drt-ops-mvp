package com.idavy.drtops.jtgateway.netty;

import com.idavy.drtops.jt.protocol.codec.Jt808Frame;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

public final class JtFrameOwnershipHandler extends ChannelOutboundHandlerAdapter {
    @Override
    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
        try {
            context.write(message, promise);
        } finally {
            if (message instanceof Jt808Frame frame && frame.body().refCnt() > 0) {
                frame.body().release();
            }
        }
    }
}
