package org.drasyl.example;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.internal.PlatformDependent;
import org.drasyl.channel.tun.InetProtocol;
import org.drasyl.channel.tun.Tun4Packet;
import org.drasyl.channel.tun.Tun6Packet;
import org.drasyl.channel.tun.TunAddress;
import org.drasyl.channel.tun.TunChannel;
import org.drasyl.channel.tun.jna.windows.WindowsTunDevice;
import org.drasyl.channel.tun.jna.windows.Wintun.WINTUN_ADAPTER_HANDLE;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.net.InetAddress;

import static org.drasyl.channel.tun.Tun4Packet.INET4_DESTINATION_ADDRESS;
import static org.drasyl.channel.tun.Tun4Packet.INET4_SOURCE_ADDRESS;
import static org.drasyl.channel.tun.jna.windows.Wintun.WintunGetAdapterLUID;

@Command(
        name = "ping",
        description = "Creates a tun device that replies to ICMP Echo messages."
)
public class PingCommand implements Runnable {
    @Option(
            names = "--if-name",
            defaultValue = ""
    )
    private String ifName;
    @Option(
            names = "--address",
            defaultValue = "10.10.10.10"
    )
    private String address;

    @Override
    public void run() {
        EventLoopGroup group = new NioEventLoopGroup(1);
        try {
            final Bootstrap b = new Bootstrap()
                    .group(group)
                    .channel(TunChannel.class)
                    .handler(new ChannelInitializer<>() {
                        @Override
                        protected void initChannel(final Channel ch) {
                            final ChannelPipeline p = ch.pipeline();

                            p.addLast(new Ping4Handler());
                            p.addLast(new Ping6Handler());
                        }
                    });
            final Channel ch = b.bind(new TunAddress(ifName.isEmpty() ? null : ifName)).syncUninterruptibly().channel();

            final String name = ch.localAddress().toString();
            System.out.println("Interface created: " + name);

            if (PlatformDependent.isOsx()) {
                exec("/sbin/ifconfig", name, "add", address, address);
                exec("/sbin/ifconfig", name, "up");
                exec("/sbin/route", "add", "-net", address + "/24", "-iface", name);
            }
            else if (PlatformDependent.isWindows()) {
                // Windows
                final WINTUN_ADAPTER_HANDLE adapter = ((WindowsTunDevice) ((TunChannel) ch).device()).adapter();

                final Pointer interfaceLuid = new Memory(8);
                WintunGetAdapterLUID(adapter, interfaceLuid);
                AddressAndNetmaskHelper.setIPv4AndNetmask(interfaceLuid, address, 24);
            }
            else {
                // Linux
                exec("/sbin/ip", "addr", "add", address + "/24", "dev", name);
                exec("/sbin/ip", "link", "set", "dev", name, "up");
            }

            System.out.println("Address assigned: " + address);

            ch.closeFuture().syncUninterruptibly();
        }
        catch (final IOException e) {
            throw new RuntimeException(e);
        }
        finally {
            group.shutdownGracefully();
        }
    }

    private static void exec(final String... command) throws IOException {
        try {
            final int exitCode = Runtime.getRuntime().exec(command).waitFor();
            if (exitCode != 0) {
                throw new IOException("Executing `" + String.join(" ", command) + "` returned non-zero exit code (" + exitCode + ").");
            }
        }
        catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static class Ping4Handler extends SimpleChannelInboundHandler<Tun4Packet> {
        // https://datatracker.ietf.org/doc/html/rfc792
        public static final int TYPE = 20;
        public static final int CHECKSUM = 22;
        public static final int ECHO = 8;
        public static final int ECHO_REPLY = 0;

        @Override
        protected void channelRead0(final ChannelHandlerContext ctx,
                                    final Tun4Packet packet) {
            if (packet.protocol() == InetProtocol.ICMP.decimal) {
                final short icmpType = packet.content().getUnsignedByte(TYPE);
                if (icmpType == ECHO) {
                    final InetAddress source = packet.sourceAddress();
                    final InetAddress destination = packet.destinationAddress();
                    final int checksum = packet.content().getUnsignedShort(CHECKSUM);

                    // create response
                    final ByteBuf buf = packet.content().retainedDuplicate();
                    buf.setBytes(INET4_SOURCE_ADDRESS, destination.getAddress());
                    buf.setBytes(INET4_DESTINATION_ADDRESS, source.getAddress());
                    buf.setByte(TYPE, ECHO_REPLY);
                    buf.setShort(CHECKSUM, checksum + 0x0800);

                    System.out.println("Reply to echo from " + source.getHostAddress());
                    final Tun4Packet response = new Tun4Packet(buf);
                    ctx.writeAndFlush(response);
                }
            }
            else {
                ctx.fireChannelRead(packet.retain());
            }
        }
    }

    private static class Ping6Handler extends SimpleChannelInboundHandler<Tun6Packet> {
        // https://datatracker.ietf.org/doc/html/rfc8200

        @Override
        protected void channelRead0(final ChannelHandlerContext ctx,
                                    final Tun6Packet packet) {
            System.out.println("IPv6 not supported yet.");
        }
    }
}
