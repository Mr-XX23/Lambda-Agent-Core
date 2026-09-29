package demo;

/** A small calculator used by the multi-agent example. It has deliberate bugs for the reviewer to find. */
public class Calculator {

    public int divide(int a, int b) {
        return a / b;
    }

    public double average(int[] values) {
        int sum = 0;
        for (int v : values) sum += v;
        return sum / values.length;
    }

    public int percentOf(int part, int total) {
        return part * 100 / total;
    }
}
