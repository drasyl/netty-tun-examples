package org.drasyl.example;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;

@Command(
        name = "netty-tun-examples",
        subcommands = {
                HelpCommand.class,
                EchoCommand.class,
                ReplyPingCommand.class
        }
)
public class Main {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("Main.main: 10s ab jetzt!!!");
        Thread.sleep(10_000);
        System.out.println("Main.main: geht los");
        new CommandLine(new Main()).execute(args);
    }
}
