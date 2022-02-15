package org.drasyl.example;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;

@Command(
        name = "netty-tun-examples",
        subcommands = {
                HelpCommand.class,
                ReplyPingCommand.class
        }
)
public class Main {
    public static void main(String[] args) {
        new CommandLine(new Main()).execute(args);
    }
}
