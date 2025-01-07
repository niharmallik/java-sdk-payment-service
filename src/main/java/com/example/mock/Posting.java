package com.example.mock;

import com.example.account.Account;
import kalix.javasdk.action.Action;
import kalix.javasdk.client.ComponentClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import static com.example.account.Account.WithdrawResult.*;
import static com.example.account.Account.DepositResult.*;

@RequestMapping("/posting")
public class Posting extends Action {

    /**
     * Posting is a component that adjusts the balances of the account(s) involved
     * in the transaction.
     *
     * For the purpose of this demo, posting will involve withdrawing funds from
     * the source account.
     *
     */

    private final ComponentClient client;

    public Posting(ComponentClient client) {
        this.client = client;
    }

    @PostMapping("/post")
    public Effect<PostResult> post(@RequestBody Post.Funds request) {

        var withdraw = client.forEventSourcedEntity(request.account)
            .call(Account::withdraw)
            .params(request.amount)
            .execute()
            .toCompletableFuture().join();

        return switch(withdraw){
            case WithdrawSucceed __ -> effects().reply(new PostResult.Approved());
            case WithdrawFailed error -> effects().reply(new PostResult.Rejected(error.errorMsg()));
        };

    }

    @PostMapping("/reversal")
    public Effect<PostResult> reversal(@RequestBody Post.Reversal request) {

        var reversal = client.forEventSourcedEntity(request.account)
            .call(Account::deposit)
            .params(request.amount)
            .execute()
            .toCompletableFuture().join();

        return switch(reversal){
            case DepositSucceed __ -> effects().reply(new PostResult.Approved());
            case DepositFailed error -> effects().reply(new PostResult.Rejected(error.errorMsg()));
        };

    }

    public sealed interface Post {

        record Funds(String txId, String account, int amount) implements Post {}

        record Reversal(String txId, String account, int amount) implements Post {}

    }

    public sealed interface PostResult  {

        record Rejected(String reason) implements PostResult {}

        record Approved() implements PostResult {}

    }


}
