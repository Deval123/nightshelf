package io.github.deval123.nightshelf;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        System.out.println(Config.fromEnv());
    }
}
