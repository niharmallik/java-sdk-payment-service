package com.example.transaction;

public interface Transaction {

    interface Response extends Transaction {

        record Received(String txId, String status, Long received) implements Response {}

        record Processing(String txId, String status, String message) implements Response {}

    }

}
