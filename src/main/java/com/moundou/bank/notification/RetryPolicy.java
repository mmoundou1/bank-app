package com.moundou.bank.notification;

import java.time.Duration;

public final class RetryPolicy {

    public sealed interface Next {

        record RetryAfter(Duration delay) implements Next{}
        record GiveUp() implements Next{}

    }

    private static final int MAX_NUMBER_OF_ATTEMPTS = 5;

    private RetryPolicy() {}

    public static Next afterFailedAttempt(int attempts) {

        if(attempts < MAX_NUMBER_OF_ATTEMPTS) {

            switch (attempts) {
                case 1 -> {return new Next.RetryAfter(Duration.ofMinutes(1));}
                case 2 -> {return new Next.RetryAfter(Duration.ofMinutes(5));}
                case 3 -> {return new Next.RetryAfter(Duration.ofMinutes(30));}
                case 4 -> {return new Next.RetryAfter(Duration.ofHours(2));}
            }

        }

        return new Next.GiveUp();

    }
}
