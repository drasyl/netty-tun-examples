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
import static org.drasyl.channel.tun.Tun4Packet.INET4_HEADER_CHECKSUM;
import static org.drasyl.channel.tun.Tun4Packet.INET4_SOURCE_ADDRESS;
import static org.drasyl.channel.tun.jna.windows.Wintun.WintunGetAdapterLUID;

@Command(
        name = "reply-ping",
        description = "Creates a tun device that replies to ICMP Echo messages.",
        showDefaultValues = true
)
@SuppressWarnings({ "java:S106", "java:S112", "unused" })
public class ReplyPingCommand implements Runnable {
    @Option(
            names = "--if-name",
            description = "Desired name of the TUN device. If left empty, the OS will pick a name.",
            defaultValue = ""
    )
    private String ifName;
    @Option(
            names = "--address",
            required = true,
            description = "IP address assigned to the TUN device.",
            defaultValue = "10.10.10.10"
    )
    private String address;
    @Option(
            names = "--netmask-prefix",
            required = true,
            description = "Netmask indicated by the number of bits of the prefix.",
            defaultValue = "24"
    )
    private int netmaskPrefix;

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
            System.out.println("TUN device created: " + name);

            if (PlatformDependent.isOsx()) {
                exec("/sbin/ifconfig", name, "add", address, address);
                exec("/sbin/ifconfig", name, "up");
                exec("/sbin/route", "add", "-net", address + '/' + netmaskPrefix, "-iface", name);
            }
            else if (PlatformDependent.isWindows()) {
                // Windows
                final WINTUN_ADAPTER_HANDLE adapter = ((WindowsTunDevice) ((TunChannel) ch).device()).adapter();

                final Pointer interfaceLuid = new Memory(8);
                WintunGetAdapterLUID(adapter, interfaceLuid);
                AddressAndNetmaskHelper.setIPv4AndNetmask(interfaceLuid, address, netmaskPrefix);
            }
            else {
                // Linux
                exec("/sbin/ip", "addr", "add", address + '/' + netmaskPrefix, "dev", name);
                exec("/sbin/ip", "link", "set", "dev", name, "up");
            }

            System.out.println("Address and netmask assigned: " + address + '/' + netmaskPrefix);
            System.out.println("All pings addressed to this subnet should now be replied.");

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
                    final ByteBuf buf = packet.content().retain();
                    buf.setBytes(INET4_SOURCE_ADDRESS, destination.getAddress());
                    buf.setBytes(INET4_DESTINATION_ADDRESS, source.getAddress());
                    buf.setByte(TYPE, ECHO_REPLY);
                    buf.setShort(CHECKSUM, checksum + 0x0800);

                    System.out.println("Reply echo addressed to " + destination.getHostAddress() + " and received from " + source.getHostAddress());
                    final Tun4Packet response = new Tun4Packet(buf);
                    ctx.writeAndFlush(response).addListener(future -> {
                        if (!future.isSuccess()) {
                            future.cause().printStackTrace(System.err);
                        }
                    });
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
