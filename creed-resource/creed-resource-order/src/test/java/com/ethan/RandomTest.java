package com.ethan;

import org.junit.jupiter.api.Test;

import java.util.Random;

public class RandomTest {
    @Test
    void random() {
        Random random = new Random();
        for (int i = 0; i < 6; i++) {
            System.out.println(random.nextInt(0, 8));
        }
    }
}
