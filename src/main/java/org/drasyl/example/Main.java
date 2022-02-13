package org.drasyl.example;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = "netty-tun-examples",
        subcommands = {
                PingCommand.class
        }
)
public class Main {
    public static void main(String[] args) {
        new CommandLine(new Main()).execute(args);
    }
}
