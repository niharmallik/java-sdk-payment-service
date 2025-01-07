package com.example.mock;

import com.example.util.Validator;
import kalix.javasdk.action.Action;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

@RequestMapping("/sanctions")
public class Sanction extends Action {

    /**
     * Sanction is a component that checks if a transaction is sanctioned.
     *
     * Check if the transaction is sanctioned
     * - Check if the source or destination account is sanctioned
     *
     */

    @PostMapping("/check")
    public Effect<SanctionResult> check(@RequestBody Check.Accounts request) {
        return Validator
            .validate(
                //TODO: For now, this is just basic validation, but we could implement some random sanctions
                Validator.isTrue(request.txId.isEmpty(), "Transaction ID is Required"),
                Validator.isTrue(request.source.isEmpty(), "Source Account is Required"),
                Validator.isTrue(request.destination.isEmpty(), "Destination Account is Required")
            )
            .handle((result, err) -> switch(result){
                case SUCCESS -> effects().reply(new SanctionResult.Approved());
                case ERROR -> effects().reply(new SanctionResult.Rejected(err));
            });
    }

    public sealed interface Check {

        record Accounts(String txId, String source, String destination) implements Check {}

    }

    public sealed interface SanctionResult  {

        record Rejected(String reason) implements SanctionResult {}

        record Approved() implements SanctionResult {}

    }

}
