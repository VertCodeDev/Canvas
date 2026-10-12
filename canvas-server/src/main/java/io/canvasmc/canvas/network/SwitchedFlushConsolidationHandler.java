package io.canvasmc.canvas.network;

import io.canvasmc.canvas.GlobalConfiguration;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.flush.FlushConsolidationHandler;

/**
 * Consolidates flushes outside of reads too, with a switch that can be flipped while the server runs.
 * <p>
 * Netty's handler decides at construction whether a flush that arrives while nothing is being read is held back and
 * merged with the ones after it. A Minecraft server sends most of what it sends on its own, with nothing coming in, so
 * that is where the flushes are. With the option on, this is that handler. With it off, a flush outside of a read goes
 * out at once, which is what the handler Paper installs does, so the two can be compared on the same connections.
 * <p>
 * One instance belongs to one channel and is only touched by that channel's event loop.
 */
public final class SwitchedFlushConsolidationHandler extends FlushConsolidationHandler {

    private boolean reading;

    public SwitchedFlushConsolidationHandler() {
        super(DEFAULT_EXPLICIT_FLUSH_AFTER_FLUSHES, true);
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        this.reading = true;
        super.channelRead(ctx, msg);
    }

    @Override
    public void channelReadComplete(final ChannelHandlerContext ctx) throws Exception {
        this.reading = false;
        super.channelReadComplete(ctx);
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
        this.reading = false;
        super.exceptionCaught(ctx, cause);
    }

    @Override
    public void flush(final ChannelHandlerContext ctx) throws Exception {
        if (!this.reading && !GlobalConfiguration.getInstance().networking.consolidateFlushesOutsideReads) {
            ctx.flush();
            return;
        }

        super.flush(ctx);
    }
}
