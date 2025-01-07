package com.example.mock;

import com.example.util.Validator;
import com.example.account.Account;
import kalix.javasdk.action.Action;
import kalix.javasdk.client.ComponentClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

@RequestMapping("/liquidity")
public class Liquidity extends Action {

    /**
     * Liquidity is a component that checks if a transaction source account has liquidity.
     *
     * Check balance for the accounts involved in the transaction
     * - Ensure enough funds are available for the source of funds.
     *
     */

    private final ComponentClient client;

    public Liquidity(ComponentClient client) {
        this.client = client;
    }

    @PostMapping("/verify")
    public Effect<LiquidityResult> verify(@RequestBody Verify.Funds request) {
        return Validator
            .validate(
                Validator.isLtEqZero(request.amount, "Amount must be greater than 0")
            )
            .resolve(
                //TODO: Implement alternative to entityExists method in the Validator class
                //TODO: Would like to make a way to validate against various call results
                Validator.entityExists(
                    client
                        .forEventSourcedEntity(request.account)
                        .call(Account::verifyFunds)
                        .params(request.amount),
                    "Source Account Funds Not Available"
                )
            )
            .handle((result, err) -> switch(result){
                case SUCCESS -> effects().reply(new LiquidityResult.Approved());
                case ERROR -> effects().reply(new LiquidityResult.Rejected(err));
            });
    }

    public sealed interface Verify {

        record Funds(String txId, String account, int amount) implements Verify {}

    }

    public sealed interface LiquidityResult  {

        record Rejected(String reason) implements LiquidityResult {}

        record Approved() implements LiquidityResult {}

    }

}
