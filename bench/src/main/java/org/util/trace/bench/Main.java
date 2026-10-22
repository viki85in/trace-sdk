package org.util.trace.bench;

public final class Main {

    private Main() {
    }

    /** sink --port=N | run --strategy=.. --workload=.. --rate=.. | suite --profile=smoke|full --out=DIR */
    public static void main(String[] argv) throws Exception {
        if (argv.length == 0) {
            System.err.println("usage: sink | run | suite  (--key=value ...)");
            System.exit(2);
        }
        Args a = new Args(argv, 1);
        switch (argv[0]) {
            case "sink" -> Sink.serve(a.num("port", 4318));
            case "run" -> {
                LoadRun.run(a);
                System.exit(0);
            }
            case "suite" -> Suite.run(a);
            default -> {
                System.err.println("unknown command " + argv[0]);
                System.exit(2);
            }
        }
    }
}
