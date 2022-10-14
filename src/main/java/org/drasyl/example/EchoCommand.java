package org.drasyl.example;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.util.internal.PlatformDependent;
import org.drasyl.channel.tun.TunAddress;
import org.drasyl.channel.tun.TunChannel;
import org.drasyl.channel.tun.jna.windows.WindowsTunDevice;
import org.drasyl.channel.tun.jna.windows.Wintun.WINTUN_ADAPTER_HANDLE;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.net.InetAddress;

import static org.drasyl.channel.tun.jna.windows.Wintun.WintunGetAdapterLUID;

@Command(
        name = "echo",
        description = "Echoes all received packets.",
        showDefaultValues = true
)
@SuppressWarnings({ "java:S106", "java:S112", "unused" })
public class EchoCommand implements Runnable {
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
    private InetAddress address;

    @Override
    public void run() {
        EventLoopGroup group = new DefaultEventLoopGroup(1);
        try {
            final Bootstrap b = new Bootstrap()
                    .group(group)
                    .channel(TunChannel.class)
                    .handler(new ChannelInitializer<>() {
                        @Override
                        protected void initChannel(final Channel ch) {
                            final ChannelPipeline p = ch.pipeline();

                            p.addLast(new LoopbackHandler());
                        }
                    });
            final Channel ch = b.bind(new TunAddress(ifName.isEmpty() ? null : ifName)).syncUninterruptibly().channel();

            final String name = ch.localAddress().toString();
            System.out.println("TUN device created: " + name);

            if (PlatformDependent.isOsx()) {
                exec("/sbin/ifconfig", name, "add", address.getHostAddress(), address.getHostAddress());
                exec("/sbin/ifconfig", name, "up");
                exec("/sbin/route", "add", "-net", address.getHostAddress() + '/' + 32, "-iface", name);
            }
            else if (PlatformDependent.isWindows()) {
                // Windows
                final WINTUN_ADAPTER_HANDLE adapter = ((WindowsTunDevice) ((TunChannel) ch).device()).adapter();

                final Pointer interfaceLuid = new Memory(8);
                WintunGetAdapterLUID(adapter, interfaceLuid);
                AddressAndNetmaskHelper.setIPv4AndNetmask(interfaceLuid, address.getHostAddress(), 32);
            }
            else {
                // Linux
                exec("/sbin/ip", "addr", "add", address.getHostAddress() + '/' + 32, "dev", name);
                exec("/sbin/ip", "link", "set", "dev", name, "up");
            }

            System.out.println("Address assigned: " + address.getHostAddress());
            System.out.println("All packets addressed to this address will be echoed.");

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

    private static class LoopbackHandler extends ChannelDuplexHandler {
        @Override
        public void channelRead(final ChannelHandlerContext ctx,
                                final Object msg) {
            // loopback
            ctx.write(msg);
        }

        @Override
        public void channelReadComplete(final ChannelHandlerContext ctx) {
            ctx.fireChannelReadComplete();
            ctx.flush();
        }
    }
}
