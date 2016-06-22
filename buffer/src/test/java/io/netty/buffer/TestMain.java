package io.netty.buffer;

/**
 * Created by allan on 16/6/14.
 */
public class TestMain {
    public static void main(String[] args) {
        System.out.println(test(1024));
    }

    static int test(int normCapacity) {
        int tableIdx = 0;
        int i = normCapacity >>> 10;
        while (i != 0) {
            i >>>= 1;
            tableIdx ++;
        }
        return tableIdx;
    }
}
