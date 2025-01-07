package com.example.mock;

import com.example.account.Account;
import kalix.javasdk.action.Action;
import kalix.javasdk.client.ComponentClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import static com.example.account.Account.DepositResult.*;

@RequestMapping("/clearing")
public class Clearing extends Action {

    /**
     * Clearing is a component that clears a transaction by adjusting the balances of the
     * receiving account(s) involved in the transaction.
     *
     * For the purpose of this demo, clearing will involve depositing funds int the
     * destination account.
     *
     */
    private final ComponentClient client;

    public Clearing(ComponentClient client) {
        this.client = client;
    }

    @PostMapping("/clear")
    public Effect<ClearingResult> clear(@RequestBody Clear.Funds request) {

        var deposit = client.forEventSourcedEntity(request.account)
            .call(Account::deposit)
            .params(request.amount)
            .execute()
            .toCompletableFuture().join();

        return switch(deposit){
            case DepositSucceed __ -> effects().reply(new ClearingResult.Accepted());
            case DepositFailed error -> effects().reply(new ClearingResult.Rejected(error.errorMsg()));
        };

    }

    public sealed interface Clear {

        record Funds(String txId, String account, int amount) implements Clear {}

    }

    public sealed interface ClearingResult  {

        record Rejected(String reason) implements ClearingResult {}

        record Accepted() implements ClearingResult {}

    }


}
